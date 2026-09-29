package com.osrssync;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;

/** The Activity page's visuals: a segmented range picker, a proportional bar and a stacked column chart. */
final class ActivityCharts
{
	/** One activity's share: minutes played or XP gained, depending on the metric shown. */
	static final class Part
	{
		final String name;
		final double value;
		final Color color;

		Part(String name, double value, Color color)
		{
			this.name = name;
			this.value = value;
			this.color = color;
		}
	}

	/** One column: a label under it, an optional second line (the date), and its stacked parts. */
	static final class Column
	{
		final String label;
		final String sub;
		final String tooltip;
		final boolean current;
		final boolean future;
		final List<Part> parts;

		Column(String label, String sub, String tooltip, boolean current, boolean future, List<Part> parts)
		{
			this.label = label;
			this.sub = sub;
			this.tooltip = tooltip;
			this.current = current;
			this.future = future;
			this.parts = parts;
		}

		double total()
		{
			return parts.stream().mapToDouble(p -> p.value).sum();
		}
	}

	/** Pill-shaped options in a sunken track, e.g. Day / Week / Month. */
	static final class Segmented extends JComponent
	{
		private final String[] options;
		private final Consumer<Integer> onPick;
		private int selected;
		private int hover = -1;

		private final Dimension size;

		Segmented(String[] options, int selected, Consumer<Integer> onPick)
		{
			this(options, selected, new Dimension(200, 32), onPick);
		}

		Segmented(String[] options, int selected, Dimension size, Consumer<Integer> onPick)
		{
			this.size = size;
			this.options = options;
			this.selected = selected;
			this.onPick = onPick;
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter m = new MouseAdapter()
			{
				@Override
				public void mouseReleased(MouseEvent e)
				{
					// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
					if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
					{
						return;
					}
					int i = indexAt(e.getX());
					if (i != Segmented.this.selected)
					{
						Segmented.this.selected = i;
						repaint();
						onPick.accept(i);
					}
				}

				@Override
				public void mouseMoved(MouseEvent e)
				{
					int i = indexAt(e.getX());
					if (i != hover)
					{
						hover = i;
						repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = -1;
					repaint();
				}
			};
			addMouseListener(m);
			addMouseMotionListener(m);
		}

		void setSelected(int i)
		{
			selected = i;
			repaint();
		}

		private int indexAt(int x)
		{
			return Math.max(0, Math.min(options.length - 1, x * options.length / Math.max(1, getWidth())));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return size;
		}

		@Override
		public Dimension getMaximumSize()
		{
			return size;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			g2.setColor(ChatComponents.BASE_BG);
			Pixel.fill(g2, 0, 0, w, h, 5);
			g2.setColor(ChatComponents.OUTLINE);
			Pixel.draw(g2, 0, 0, w, h, 5);

			Font font = h < 28 ? FontManager.getRunescapeSmallFont() : FontManager.getRunescapeFont();
			FontMetrics fm = g2.getFontMetrics(font);
			g2.setFont(font);
			double seg = (w - 4) / (double) options.length;
			for (int i = 0; i < options.length; i++)
			{
				int x = (int) Math.round(2 + seg * i);
				int sw = (int) Math.round(2 + seg * (i + 1)) - x;
				if (i == selected)
				{
					g2.setColor(ChatComponents.OUTLINE);
					Pixel.fill(g2, x, 2, sw, h - 4, 4);
					g2.setColor(ChatComponents.PANEL_BG);
					Pixel.fill(g2, x + 1, 3, sw - 2, h - 6, 3);
					g2.setColor(ChatComponents.HAIRLINE);
					Pixel.draw(g2, x + 1, 3, sw - 2, h - 6, 3);
				}
				g2.setColor(i == selected || i == hover ? Color.WHITE : ChatComponents.MUTED);
				String s = options[i];
				g2.drawString(s, x + (sw - fm.stringWidth(s)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
			}
			g2.dispose();
		}
	}

	/** Horizontal bar split into rounded pills, one per part, sized by share. */
	static final class ShareBar extends JComponent
	{
		private static final int HEIGHT = 8;
		private static final int GAP = 3;
		private final List<Part> parts;

		ShareBar(List<Part> parts)
		{
			this.parts = parts;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(100, HEIGHT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			double total = parts.stream().mapToDouble(p -> p.value).sum();
			int w = getWidth();
			if (total <= 0)
			{
				g2.setColor(ChatComponents.BASE_BG);
				Pixel.fill(g2, 0, 0, w, HEIGHT, HEIGHT / 2.0);
				g2.dispose();
				return;
			}
			double usable = w - GAP * (parts.size() - 1);
			double x = 0;
			for (Part p : parts)
			{
				double pw = Math.max(HEIGHT, usable * p.value / total);
				g2.setColor(p.color);
				g2.fill(Pixel.shape(x, 0, Math.min(pw, w - x), HEIGHT, HEIGHT / 2.0));
				x += pw + GAP;
				if (x >= w)
				{
					break;
				}
			}
			g2.dispose();
		}
	}

	/**
	 * Stacked columns with horizontal gridlines and an axis on the right. The current column (today / this hour)
	 * gets a highlight behind it and its date in an accent circle.
	 */
	static final class ColumnChart extends JComponent
	{
		private static final int PLOT_HEIGHT = 110;
		private static final int AXIS = 26;
		private final List<Column> columns;
		private final double max;
		private final boolean xp;
		private final boolean count;

		/** xp: the columns hold XP rather than minutes (changes the axis scale and labels). */
		ColumnChart(List<Column> columns, boolean xp)
		{
			this(columns, xp, false);
		}

		/** count: the columns hold plain counts (kills, completions), with a whole-number axis. */
		ColumnChart(List<Column> columns, boolean xp, boolean count)
		{
			this.columns = columns;
			this.xp = xp;
			this.count = count;
			double biggest = columns.stream().mapToDouble(Column::total).max().orElse(0);
			this.max = count ? niceCount(biggest) : xp ? niceXp(biggest) : niceMax(biggest);
			setToolTipText("");
		}

		/** An even number at least 2 (so the middle gridline is whole), rounded to 1, 2 or 5 times a power of ten above 10. */
		private static double niceCount(double value)
		{
			double v = Math.max(2, Math.ceil(value));
			if (v <= 10)
			{
				return v % 2 == 0 ? v : v + 1;
			}
			double pow = Math.pow(10, Math.floor(Math.log10(v)));
			for (double m : new double[]{1, 2, 5, 10})
			{
				if (v <= m * pow)
				{
					return m * pow;
				}
			}
			return 10 * pow;
		}

		/** Round up to 1, 2 or 5 times a power of ten (at least 10K). */
		private static double niceXp(double value)
		{
			double v = Math.max(10_000, value);
			double pow = Math.pow(10, Math.floor(Math.log10(v)));
			for (double m : new double[]{1, 1.5, 2, 2.5, 3, 4, 5, 6, 8})
			{
				if (v <= m * pow)
				{
					return m * pow;
				}
			}
			return 10 * pow;
		}

		/** Round the axis up to a whole number of hours (or 30 minutes for short periods). */
		private static double niceMax(double minutes)
		{
			if (minutes <= 30)
			{
				return 30;
			}
			if (minutes <= 60)
			{
				return 60;
			}
			int hours = (int) Math.ceil(minutes / 60);
			int[] steps = {2, 4, 6, 8, 12, 16, 20, 24};
			for (int s : steps)
			{
				if (hours <= s)
				{
					return s * 60;
				}
			}
			return hours * 60;
		}

		private boolean hasSubs()
		{
			return columns.stream().anyMatch(c -> c.sub != null);
		}

		@Override
		public Dimension getPreferredSize()
		{
			int line = getFontMetrics(FontManager.getRunescapeSmallFont()).getHeight();
			return new Dimension(200, PLOT_HEIGHT + 8 + line + (hasSubs() ? line + 6 : 0));
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		private double slot()
		{
			return (getWidth() - AXIS) / (double) Math.max(1, columns.size());
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int i = (int) (e.getX() / slot());
			return i >= 0 && i < columns.size() ? columns.get(i).tooltip : null;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Font small = FontManager.getRunescapeSmallFont();
			FontMetrics fm = g2.getFontMetrics(small);
			g2.setFont(small);
			int plotW = getWidth() - AXIS;
			int top = 6;
			int bottom = top + PLOT_HEIGHT - 6;
			double slot = slot();
			boolean dense = columns.size() > 10;
			double barW = dense ? Math.max(2, slot - 2) : Math.min(26, slot * 0.62);
			double radius = Math.min(6, barW / 2);

			// Current column highlight
			for (int i = 0; i < columns.size(); i++)
			{
				if (columns.get(i).current && !dense)
				{
					g2.setColor(ChatComponents.HOVER_BG);
					double hw = Math.min(slot - 2, barW + 12);
					g2.fill(Pixel.shape(slot * i + (slot - hw) / 2, 0, hw, getHeight(), 4));
				}
			}

			// Gridlines: top, middle, bottom, labelled on the right
			g2.setStroke(new BasicStroke(1f));
			for (int k = 0; k <= 2; k++)
			{
				int y = bottom - (bottom - top) * k / 2;
				g2.setColor(k == 0 ? ChatComponents.BORDER : ChatComponents.OUTLINE);
				g2.drawLine(0, y, plotW, y);
				String label = k == 0 ? "0" : count ? Ui.shortNumber(max * k / 2) : xp ? Ui.shortNumber(max * k / 2) : axisLabel(max * k / 2);
				g2.setColor(ChatComponents.MUTED);
				g2.drawString(label, getWidth() - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
			}

			// Stacked columns, rounded at the top
			for (int i = 0; i < columns.size(); i++)
			{
				Column c = columns.get(i);
				double x = slot * i + (slot - barW) / 2;
				double total = c.total();
				if (total <= 0)
				{
					continue;
				}
				double h = Math.max(2, (bottom - top) * Math.min(1, total / max));
				java.awt.Shape clip = Pixel.shape(x, bottom - h, barW, h + radius, radius);
				Graphics2D bar = (Graphics2D) g2.create();
				bar.clip(new java.awt.Rectangle((int) Math.floor(x) - 1, 0, (int) Math.ceil(barW) + 2, bottom));
				bar.clip(clip);
				double y = bottom;
				for (Part p : c.parts)
				{
					double ph = h * p.value / total;
					bar.setColor(p.color);
					bar.fill(new java.awt.geom.Rectangle2D.Double(x, y - ph, barW, ph + 0.5));
					y -= ph;
				}
				bar.dispose();
			}

			// Labels
			int labelY = bottom + 8 + fm.getAscent();
			for (int i = 0; i < columns.size(); i++)
			{
				Column c = columns.get(i);
				double cx = slot * i + slot / 2;
				if (c.label != null && !c.label.isEmpty())
				{
					g2.setColor(c.current ? Color.WHITE : c.future ? ChatComponents.BORDER : ChatComponents.MUTED);
					int lx = (int) Math.round(cx - fm.stringWidth(c.label) / 2.0);
					g2.drawString(c.label, Math.max(0, Math.min(plotW - fm.stringWidth(c.label), lx)), labelY);
				}
				if (c.sub != null)
				{
					int subY = labelY + fm.getHeight() + 4;
					if (c.current)
					{
						int d = Math.max(fm.getHeight() + 2, fm.stringWidth(c.sub) + 8);
						g2.setColor(ChatComponents.ACCENT);
						Pixel.fill(g2, (int) Math.round(cx - d / 2.0), subY - fm.getAscent() - (d - fm.getHeight()) / 2 - 1, d, d, d / 2.0);
						g2.setColor(Color.WHITE);
					}
					else
					{
						g2.setColor(c.future ? ChatComponents.BORDER : ChatComponents.MUTED);
					}
					g2.drawString(c.sub, (int) Math.round(cx - fm.stringWidth(c.sub) / 2.0), subY);
				}
			}
			g2.dispose();
		}

		private static String axisLabel(double minutes)
		{
			return minutes >= 60 ? (minutes % 60 == 0 ? (int) (minutes / 60) + "h" : String.format("%.1fh", minutes / 60)) : (int) minutes + "m";
		}
	}

	/** Tooltip for a column: its total, then each part. */
	static String tooltip(String title, List<Part> parts, java.util.function.DoubleFunction<String> format)
	{
		double total = parts.stream().mapToDouble(p -> p.value).sum();
		StringBuilder sb = new StringBuilder("<html><b>").append(MarkdownLite.escape(title)).append("</b>: ").append(format.apply(total));
		for (Part p : parts)
		{
			sb.append("<br>").append(MarkdownLite.escape(p.name)).append(": ").append(format.apply(p.value));
		}
		return sb.append("</html>").toString();
	}

	static Map<String, Color> colorMap(List<String> rankedNames)
	{
		Map<String, Color> out = new java.util.LinkedHashMap<>();
		for (int i = 0; i < rankedNames.size(); i++)
		{
			out.put(rankedNames.get(i), Ui.activityColor(i, rankedNames.get(i)));
		}
		return out;
	}

	private ActivityCharts()
	{
	}
}
