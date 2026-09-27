package com.osrssync;

import com.osrssync.ChatComponents.Bubble;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** How a reply was reached: a one-line Steps row (the working indicator) and a one-line Sources row. */
final class ChatTraceViews
{
	private static final String MUTED = "#9a9a9a";
	private static final int LINE = 24;
	/** Left edge shared with reply text (the reply bubble's padding), so a turn lines up down one column. */
	static final int INSET = 12;
	/** Space above and below the one-line header (steps line only). */
	private static final int PAD = 8;
	private static final int MAX_BODY = 200;

	/** Shared shape: a one-line header you can click to open a scrollable body underneath. */
	private abstract static class Expandable extends JPanel
	{
		final Bubble body = new Bubble(null, ChatComponents.MUTED, false, false, new Insets(0, 12, 0, 4));
		final JScrollPane scroll = new JScrollPane(body);
		boolean expanded;
		boolean hover;
		/** Space above and below the header line, with a full-width rule at each edge (the steps line). */
		int pad;

		Expandable()
		{
			super(null);
			setOpaque(false);
			scroll.setOpaque(false);
			scroll.getViewport().setOpaque(false);
			scroll.setBorder(null);
			scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
			scroll.setVisible(false);
			add(scroll);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter m = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.getY() <= pad + LINE)
					{
						toggle();
					}
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			};
			addMouseListener(m);
		}

		void toggle()
		{
			expanded = !expanded;
			scroll.setVisible(expanded);
			if (expanded)
			{
				refreshBody();
				scrollToBottom();
			}
			relayoutList();
		}

		abstract void refreshBody();

		/** Open or close as if clicked (previews). */
		void setExpanded(boolean value)
		{
			if (value != expanded)
			{
				toggle();
			}
		}

		void scrollToBottom()
		{
			SwingUtilities.invokeLater(() ->
			{
				JScrollBar bar = scroll.getVerticalScrollBar();
				bar.setValue(bar.getMaximum());
			});
		}

		private int width()
		{
			int w = getWidth();
			if (w <= 0 && getParent() != null)
			{
				Insets in = getParent().getInsets();
				w = getParent().getWidth() - in.left - in.right;
			}
			return Math.max(100, w);
		}

		private int bodyHeight()
		{
			return Math.min(MAX_BODY, body.heightForWidth(width() - INSET - 4 - 4));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(width(), pad * 2 + LINE + (expanded ? bodyHeight() + 6 : 0));
		}

		@Override
		public void doLayout()
		{
			if (expanded)
			{
				int inner = getWidth() - INSET - 4 - 4;
				body.setPreferredSize(new Dimension(inner, body.heightForWidth(inner)));
				scroll.setBounds(INSET + 4, pad + LINE + 4, getWidth() - INSET - 4, bodyHeight());
				scroll.revalidate();
			}
		}

		void relayoutList()
		{
			Container list = getParent();
			if (list != null)
			{
				list.revalidate();
				list.repaint();
			}
			revalidate();
			repaint();
		}

		/** When open, a faint rule down the left of the body, like a quote. */
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (pad > 0)
			{
				// The grooved dividers used between sections (a dark line over a lighter one), full width
				ChatComponents.groove(g, 0, getWidth());
				ChatComponents.groove(g, getHeight() - 2, getWidth());
			}
			if (expanded)
			{
				// A thin rule down the left of the opened body, under the chevron's centre, like a quote
				g.setColor(ChatComponents.BORDER);
				g.fillRect(INSET + 4, pad + LINE + 4, 1, bodyHeight());
			}
		}

		void paintChevron(Graphics2D g2, int x, int y)
		{
			ImageIcon icon = SvgIcon.load(expanded ? "chevron-open" : "chevron-right", 16, hover ? Color.WHITE : null);
			icon.paintIcon(this, g2, x, y);
		}
	}

	/**
	 * The agent's working line. While it works: animated dots and what it's doing ("Reading the wiki...").
	 * When done: "Thought for 12s . 4 steps". Hover turns the dots into a chevron; click opens the thinking and
	 * steps underneath (up to a fixed height, scrolled to the latest); click again to close.
	 */
	static final class StepsLine extends Expandable
	{
		private ChatTrace trace;
		private boolean running = true;
		private String override;
		private final long started = System.currentTimeMillis();
		private final Timer animation = new Timer(16, e -> repaint(0, 0, INSET + 24, pad * 2 + LINE));

		StepsLine()
		{
			pad = PAD;
			animation.start();
		}

		void update(ChatTrace trace, boolean running)
		{
			this.trace = trace;
			if (this.running && !running)
			{
				animation.stop();
			}
			this.running = running;
			if (expanded)
			{
				refreshBody();
				scrollToBottom();
			}
			relayoutList();
		}

		/** Temporary status, e.g. "Stopping". */
		void setStatus(String status)
		{
			override = status;
			repaint();
		}

		boolean hasSteps()
		{
			return trace != null && !trace.entries.isEmpty();
		}

		@Override
		void refreshBody()
		{
			if (trace == null)
			{
				return;
			}
			StringBuilder html = new StringBuilder();
			for (ChatTrace.Entry e : trace.entries)
			{
				html.append("<div style='margin-bottom:6px'>");
				if (e.thought)
				{
					html.append(MarkdownLite.escape(e.text.toString().trim()).replace("\n\n", "<br><br>").replace("\n", " "));
				}
				else
				{
					html.append(MarkdownLite.escape(e.label));
					if (e.detail != null)
					{
						html.append(": ");
						if (e.url != null)
						{
							html.append("<a href='").append(e.url.replace("'", "%27").replace("&", "&amp;")).append("'>")
								.append(MarkdownLite.escape(e.detail)).append("</a>");
						}
						else
						{
							html.append(MarkdownLite.escape(e.detail));
						}
					}
					if (e.failed)
					{
						html.append("<font color='#ffb4b4'> (failed)</font>");
					}
					else if (!e.done && running)
					{
						html.append("<font color='#f0c987'> ...</font>");
					}
				}
				html.append("</div>");
			}
			if (html.length() == 0)
			{
				html.append("<font color='").append(MUTED).append("'>Nothing yet</font>");
			}
			body.setHtml(html.toString());
		}

		private String label()
		{
			if (override != null && running)
			{
				return override + "...";
			}
			if (trace == null || running)
			{
				return (trace == null ? "Thinking" : trace.liveStatus()) + "...";
			}
			long seconds = Math.max(1, Math.round((trace.finishedAt - trace.startedAt) / 1000.0));
			return (trace.hasThoughts() ? "Thought" : "Worked") + " for " + seconds + "s";
		}

		private String counts()
		{
			if (trace == null || running)
			{
				return "";
			}
			int steps = trace.toolCount();
			return steps == 0 ? "" : steps + (steps == 1 ? " step" : " steps");
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.translate(INSET - 7, pad);
			int iconY = (LINE - 16) / 2;
			if (hover || expanded || !running)
			{
				paintChevron(g2, 2, iconY);
			}
			else
			{
				Leap.paint(g2, 2, (LINE - Leap.SIZE) / 2, System.currentTimeMillis() - started, ChatComponents.ACCENT);
			}
			g2.setFont(FontManager.getRunescapeFont());
			FontMetrics fm = g2.getFontMetrics();
			int baseline = (LINE - fm.getHeight()) / 2 + fm.getAscent();
			String label = label();
			g2.setColor(hover ? Color.WHITE : ChatComponents.MUTED);
			g2.drawString(label, 24, baseline);
			String counts = counts();
			if (!counts.isEmpty())
			{
				int x = 24 + fm.stringWidth(label) + 8;
				dot(g2, x, LINE / 2);
				g2.setColor(ChatComponents.MUTED);
				g2.drawString(counts, x + 8, baseline);
			}
			g2.dispose();
		}
	}

	/**
	 * "[icons] 3 sources": where the answer's information came from as overlapping round badges, then the count.
	 * Click to list the sources underneath (wiki pages open in the browser).
	 */
	static final class SourcesRow extends Expandable
	{
		private static final int ICON = 20;
		private final ChatTrace trace;
		private final List<String> origins = new ArrayList<>();
		private BufferedImage wikiLogo;

		SourcesRow(ChatTrace trace)
		{
			this.trace = trace;
			Map<String, Boolean> seen = new LinkedHashMap<>();
			for (ChatTrace.Source s : trace.sources)
			{
				seen.put(originKey(s.origin), true);
			}
			origins.addAll(seen.keySet());
			if (origins.contains("wiki") && WikiCards.images() != null)
			{
				WikiCards.images().load(WikiCards.LOGO, img ->
				{
					wikiLogo = img;
					repaint();
				});
			}
			setToolTipText("Show sources");
		}

		/** One badge per kind of source: the wiki (its logo), OSRS News, Reddit, X, YouTube, or a site's initial. */
		private static String originKey(String origin)
		{
			return origin.toLowerCase().contains("wiki") ? "wiki" : origin;
		}

		@Override
		void refreshBody()
		{
			StringBuilder html = new StringBuilder();
			for (int i = 0; i < trace.sources.size(); i++)
			{
				ChatTrace.Source s = trace.sources.get(i);
				html.append("<div style='margin-bottom:4px'><font color='").append(MUTED).append("'>").append(i + 1).append("&nbsp;&nbsp;</font>");
				if (s.url != null)
				{
					html.append("<a href='").append(s.url.replace("'", "%27").replace("&", "&amp;")).append("'>")
						.append(MarkdownLite.escape(s.title)).append("</a>");
				}
				else
				{
					html.append(MarkdownLite.escape(s.title));
				}
				html.append("<font color='").append(MUTED).append("'>&nbsp;&nbsp;").append(MarkdownLite.escape(s.origin)).append("</font></div>");
			}
			body.setHtml(html.toString());
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			int y = (LINE - ICON) / 2;
			int shown = Math.max(1, Math.min(3, origins.size()));
			// Paint back to front so the first badge sits on top
			for (int i = shown - 1; i >= 0 && i < origins.size(); i--)
			{
				paintOrigin(g2, origins.get(i), INSET + i * (ICON - 6), y);
			}
			int x = INSET + (shown - 1) * (ICON - 6) + ICON + 8;

			g2.setFont(FontManager.getRunescapeFont());
			FontMetrics fm = g2.getFontMetrics();
			int baseline = (LINE - fm.getHeight()) / 2 + fm.getAscent();
			int n = trace.sources.size();
			String count = n + (n == 1 ? " source" : " sources");
			g2.setColor(hover ? Color.WHITE : ChatComponents.MUTED);
			g2.drawString(count, x, baseline);
			g2.dispose();
		}

		/** A round badge for where sources came from: the wiki's logo, RS Buddy's icon, or a letter. */
		private void paintOrigin(Graphics2D g2, String origin, int x, int y)
		{
			Ellipse2D circle = new Ellipse2D.Double(x, y, ICON, ICON);
			g2.setColor(ChatComponents.CARD_BG);
			g2.fill(new Ellipse2D.Double(x - 2, y - 2, ICON + 4, ICON + 4));
			g2.setColor(new Color(0xE9E9E9));
			g2.fill(circle);
			Graphics2D c = (Graphics2D) g2.create();
			c.clip(circle);
			if (origin.equals("wiki") && wikiLogo != null)
			{
				c.drawImage(wikiLogo, x + 2, y + 2, ICON - 4, ICON - 4, null);
			}
			else
			{
				// Brand colour and letter for the sites players will recognise; otherwise the site's initial
				Color bg;
				Color fg = Color.WHITE;
				String letter;
				switch (origin)
				{
					case "OSRS News":
						bg = new Color(0x8C6A1C);
						letter = "J";
						break;
					case "Reddit":
						bg = new Color(0xFF4500);
						letter = "r";
						break;
					case "X":
						bg = new Color(0x111111);
						letter = "X";
						break;
					case "YouTube":
						bg = new Color(0xE62117);
						letter = ">";
						break;
					case "wiki":
						bg = new Color(0xE9E9E9);
						fg = new Color(0x555555);
						letter = "W";
						break;
					default:
						bg = new Color(0x5A5A5A);
						letter = origin.isEmpty() ? "?" : origin.substring(0, 1).toUpperCase();
				}
				c.setColor(bg);
				c.fill(circle);
				c.setColor(fg);
				c.setFont(FontManager.getRunescapeBoldFont());
				FontMetrics fm = c.getFontMetrics();
				c.drawString(letter, x + (ICON - fm.stringWidth(letter)) / 2, y + (ICON - fm.getHeight()) / 2 + fm.getAscent());
			}
			c.dispose();
			g2.setColor(new Color(0, 0, 0, 60));
			g2.setStroke(new BasicStroke(1f));
			g2.draw(circle);
		}
	}

	/**
	 * loading.dev's "Leap" spinner: three dots in a row, the last one leaping over the others to the front.
	 * Each dot sits at one end of an invisible bar that flips 180 degrees (the leap), then slides back one gap,
	 * then another; the three are a third of a cycle apart, each part eased in and out.
	 */
	/** The Leap animation as a small component that runs only while it's on screen (list rows). */
	static JComponent spinner(Color color)
	{
		JComponent c = new JComponent()
		{
			private final long start = System.currentTimeMillis();

			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Leap.paint(g2, (getWidth() - Leap.SIZE) / 2, (getHeight() - Leap.SIZE) / 2, System.currentTimeMillis() - start, color);
				g2.dispose();
			}
		};
		c.setPreferredSize(new Dimension(Leap.SIZE, Leap.SIZE));
		Timer timer = new Timer(33, e -> c.repaint());
		c.addHierarchyListener(e ->
		{
			if (c.isShowing())
			{
				timer.start();
			}
			else
			{
				timer.stop();
			}
		});
		return c;
	}

	static final class Leap
	{
		static final int SIZE = 16;
		private static final double DURATION = 1800;
		private static final double DOT = Math.round(SIZE * 0.22);
		private static final double GAP = Math.floor((SIZE - DOT) / 2);

		static void paint(Graphics2D g2, int x, int y, long elapsedMs, Color color)
		{
			double barWidth = GAP * 2 + DOT;
			double left = x + SIZE - DOT - GAP * 2;
			double cy = y + Math.round((SIZE - DOT) / 2) + DOT / 2;
			double radius = barWidth / 2 - DOT / 2;
			g2.setColor(color);
			for (int step = 1; step <= 3; step++)
			{
				double t = ((elapsedMs + DURATION * (step - 3) / 3.0) % DURATION + DURATION) % DURATION / DURATION;
				double angle;
				double shift;
				if (t < 1 / 3.0)
				{
					angle = Math.PI * ease(t * 3);
					shift = 0;
				}
				else if (t < 2 / 3.0)
				{
					angle = Math.PI;
					shift = -GAP * ease(t * 3 - 1);
				}
				else
				{
					angle = Math.PI;
					shift = -GAP - GAP * ease(t * 3 - 2);
				}
				// The dot starts at the bar's left end; turning the bar clockwise carries it over the top
				double cx = left + barWidth / 2 + shift;
				double dx = -radius * Math.cos(angle);
				double dy = -radius * Math.sin(angle);
				g2.fill(new Ellipse2D.Double(cx + dx - DOT / 2, cy + dy - DOT / 2, DOT, DOT));
			}
		}

		/** CSS ease-in-out, cubic-bezier(0.42, 0, 0.58, 1). */
		private static double ease(double x)
		{
			double t = x;
			for (int i = 0; i < 6; i++)
			{
				double bx = bezier(t, 0.42, 0.58) - x;
				double d = bezierSlope(t, 0.42, 0.58);
				if (Math.abs(d) < 1e-6)
				{
					break;
				}
				t = Math.max(0, Math.min(1, t - bx / d));
			}
			return bezier(t, 0, 1);
		}

		private static double bezier(double t, double p1, double p2)
		{
			double u = 1 - t;
			return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
		}

		private static double bezierSlope(double t, double p1, double p2)
		{
			double u = 1 - t;
			return 3 * u * u * p1 + 6 * u * t * (p2 - p1) + 3 * t * t * (1 - p2);
		}
	}

	/** The small round separator between parts of a line ("3 sources . 12s"). */
	private static void dot(Graphics2D g2, int x, int cy)
	{
		g2.setColor(ChatComponents.BORDER);
		g2.fill(new Ellipse2D.Double(x - 1.5, cy - 1.5, 3, 3));
	}

	private ChatTraceViews()
	{
	}
}
