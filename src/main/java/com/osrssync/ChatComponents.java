package com.osrssync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Building blocks for the Squire panel, following the Figma design:
 * framed surfaces (fill + faint inner border + dark outer outline), a width-tracking message list,
 * a beveled send button, icon buttons and tabs.
 */
final class ChatComponents
{
	// Base matches RuneLite's title bar and sidebar; raised pieces (bubbles, composer, tabs) sit above it
	// RuneLite's title bar and sidebar colour (TitlePane.background = DARKER_GRAY)
	static final Color BASE_BG = ColorScheme.DARKER_GRAY_COLOR; // #1e1e1e
	static final Color PANEL_BG = ColorScheme.DARK_GRAY_COLOR; // #282828, raised surfaces
	static final Color HOVER_BG = new Color(0x303030); // raised surfaces under the pointer
	static final Color CARD_BG = new Color(0x232323); // conversation card: between the base (#1e1e1e) and raised surfaces (#282828)
	static final Color USER_BG = new Color(0x444444);
	static final Color ERROR_BG = new Color(0x4A2020);
	static final Color BORDER = ColorScheme.MEDIUM_GRAY_COLOR; // #4d4d4d
	static final Color ACCENT = new Color(0x4454DA); // Squire blue, a shade lighter than the plume
	static final Color ACCENT_DARK = new Color(0x2F3AA6);
	static final Color MUTED = new Color(0x9A9A9A);
	static final Color OUTLINE = new Color(0, 0, 0, 128);
	static final Color HAIRLINE = new Color(255, 255, 255, 13);

	enum Align
	{
		LEFT, RIGHT, FILL
	}

	private static final String ALIGN = "chat.align";
	private static final String GAP = "chat.gapTop";

	static <T extends JComponent> T place(T c, Align align, int gapTop)
	{
		c.putClientProperty(ALIGN, align);
		c.putClientProperty(GAP, gapTop);
		return c;
	}

	/**
	 * A rounded surface. "Framed" adds the design's dark 1px outline and faint inner highlight border.
	 */
	static class Surface extends JPanel
	{
		private final int radius;
		private final boolean framed;
		private Color fill;
		private Color border;
		private boolean bottomShadow;

		Surface(Color fill, int radius, boolean framed)
		{
			this.fill = fill;
			this.radius = radius;
			this.framed = framed;
			setOpaque(false);
		}

		/** Solid border colour instead of the framed hairline. */
		Surface border(Color c)
		{
			this.border = c;
			return this;
		}

		/** A 1px line in the border colour under the bottom edge (the composer's "lip"). */
		Surface bottomShadow()
		{
			this.bottomShadow = true;
			return this;
		}

		Color fill()
		{
			return fill;
		}

		void setFill(Color c)
		{
			fill = c;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight(), arc = radius * 2;
			int inset = framed ? 1 : 0;
			int lip = bottomShadow ? 1 : 0;
			if (framed)
			{
				g2.setColor(OUTLINE);
				g2.fillRoundRect(0, 0, w, h, arc + 2, arc + 2);
			}
			if (bottomShadow && border != null)
			{
				g2.setColor(border);
				g2.fillRoundRect(inset, inset + 1, w - 2 * inset, h - 2 * inset - 1, arc, arc);
			}
			if (fill != null)
			{
				g2.setColor(fill);
				g2.fillRoundRect(inset, inset, w - 2 * inset, h - 2 * inset - lip, arc, arc);
			}
			Color line = border != null ? border : framed ? HAIRLINE : null;
			if (line != null)
			{
				g2.setColor(line);
				g2.drawRoundRect(inset, inset, w - 2 * inset - 1, h - 2 * inset - 1 - lip, arc, arc);
			}
			g2.dispose();
			super.paintComponent(g);
		}
	}

	/** One message: an HTML text pane, with or without a surface behind it. */
	static class Bubble extends Surface
	{
		static final Insets REPLY_PAD = new Insets(8, 12, 8, 12);
		static final Insets MESSAGE_PAD = new Insets(8, 12, 8, 12);
		// User messages are at least 40px tall
		static final int MESSAGE_MIN_HEIGHT = 40;
		private final Insets pad;
		private int minHeight;
		private final JTextPane text = new JTextPane();
		private final boolean shrinkToFit;
		// Measuring HTML is the costliest part of laying out a chat, and the list asks every bubble on every
		// layout pass, so each bubble remembers its last answers until its content or width changes.
		private int measuredForAvailable = -1;
		private int measuredWidth;
		private int measuredForWidth = -1;
		private int measuredHeight;
		private int laidOutWidth = -1;

		Bubble(Color fill, Color textColor, boolean shrinkToFit, boolean framed)
		{
			this(fill, textColor, shrinkToFit, framed, REPLY_PAD);
		}

		Bubble(Color fill, Color textColor, boolean shrinkToFit, boolean framed, Insets pad)
		{
			super(fill, 4, framed);
			this.shrinkToFit = shrinkToFit;
			this.pad = pad;
			setLayout(null);
			text.setEditable(false);
			text.setOpaque(false);
			text.setBorder(null);
			text.setContentType("text/html");
			HTMLEditorKit kit = new HTMLEditorKit();
			// Our own small style sheet instead of Swing's default one: every style lookup walks the linked
			// sheets, and the default's ~100 rules made parsing and measuring replies several times slower.
			// These are the default rules the chat's markdown relies on.
			StyleSheet css = new StyleSheet();
			css.addRule("b { font-weight: bold; }");
			css.addRule("i { font-style: italic; }");
			css.addRule("ul { list-style-type: disc; }");
			css.addRule("ol { list-style-type: decimal; }");
			css.addRule("tr { text-align: left; }");
			css.addRule("td { padding: 3px; }");
			String hex = String.format("#%06x", textColor.getRGB() & 0xFFFFFF);
			css.addRule("body { color: " + hex + "; margin: 0; }");
			css.addRule("ul { margin-left: 14px; margin-top: 2px; margin-bottom: 2px; }");
			css.addRule("ol { margin-left: 20px; margin-top: 2px; margin-bottom: 2px; }");
			css.addRule("li { margin-bottom: 1px; }");
			css.addRule("b { color: #ffffff; }");
			// Links (wiki references) read as bold text in the bubble's own colour; the hover card and click do the rest
			css.addRule("a { color: " + hex + "; font-weight: bold; text-decoration: none; }");
			kit.setStyleSheet(css);
			text.setEditorKit(kit);
			// Render HTML in the component font (RuneScape)
			text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
			text.setFont(FontManager.getRunescapeFont());
			// Links (wiki citations) open in the browser
			text.addHyperlinkListener(e ->
			{
				if (e.getURL() == null)
				{
					return;
				}
				if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED)
				{
					WikiCards.unhover();
					LinkBrowser.browse(e.getURL().toString());
				}
				else if (e.getEventType() == HyperlinkEvent.EventType.ENTERED)
				{
					WikiCards.hover(e.getURL().toString(), text);
				}
				else if (e.getEventType() == HyperlinkEvent.EventType.EXITED)
				{
					WikiCards.unhover();
				}
			});
			add(text);
		}

		/** The HTML pane, e.g. to listen for clicks. */
		JTextPane pane()
		{
			return text;
		}

		/** Whether a point in the pane is over a link. */
		boolean isLinkAt(java.awt.Point p)
		{
			int pos = text.viewToModel2D(p);
			if (pos < 0 || !(text.getDocument() instanceof javax.swing.text.html.HTMLDocument))
			{
				return false;
			}
			javax.swing.text.Element el = ((javax.swing.text.html.HTMLDocument) text.getDocument()).getCharacterElement(pos);
			return el.getAttributes().getAttribute(javax.swing.text.html.HTML.Tag.A) != null;
		}

		Bubble minHeight(int h)
		{
			minHeight = h;
			return this;
		}

		void setHtml(String html)
		{
			text.setText("<html><body>" + html + "</body></html>");
			forgetMeasurements();
			revalidate();
			repaint();
		}

		private void forgetMeasurements()
		{
			measuredForAvailable = -1;
			measuredForWidth = -1;
			laidOutWidth = -1;
		}

		int heightForWidth(int width)
		{
			if (width != measuredForWidth)
			{
				int inner = Math.max(20, width - pad.left - pad.right);
				text.setSize(inner, Short.MAX_VALUE);
				measuredHeight = text.getPreferredSize().height;
				measuredForWidth = width;
				laidOutWidth = -1;
			}
			return Math.max(minHeight, measuredHeight + pad.top + pad.bottom);
		}

		int widthFor(int available)
		{
			if (!shrinkToFit)
			{
				return available;
			}
			if (available != measuredForAvailable)
			{
				// Natural (unwrapped) width; measuring it changes the text's size, so the height is stale too
				text.setSize(Short.MAX_VALUE, Short.MAX_VALUE);
				measuredWidth = Math.min(available, text.getPreferredSize().width + pad.left + pad.right + 2);
				measuredForAvailable = available;
				measuredForWidth = -1;
				laidOutWidth = -1;
			}
			return measuredWidth;
		}

		@Override
		public void doLayout()
		{
			// Centre the text vertically when the bubble is taller than its content (min height)
			int w = getWidth() - pad.left - pad.right;
			heightForWidth(getWidth()); // measures the text at this width (cached)
			int h = Math.min(measuredHeight, getHeight() - pad.top - pad.bottom);
			if (laidOutWidth != w || text.getHeight() != h)
			{
				text.setBounds(pad.left, (getHeight() - h) / 2, w, h);
				laidOutWidth = w;
			}
			else
			{
				text.setLocation(pad.left, (getHeight() - h) / 2);
			}
		}
	}

	/** Vertical list that tracks the viewport width, so bubbles wrap instead of scrolling sideways. */
	/** A row whose height depends on its width (wrapping text); the message list asks it at the width it will get. */
	interface HeightForWidth
	{
		int heightForWidth(int width);
	}

	static class MessageList extends JPanel implements Scrollable
	{
		private static final double MAX_BUBBLE = 0.8;
		private final int sidePad;

		MessageList(Color background, int sidePad)
		{
			this.sidePad = sidePad;
			setOpaque(background != null);
			if (background != null)
			{
				setBackground(background);
			}
			setLayout(new ListLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return orientation == SwingConstants.VERTICAL ? visibleRect.height - 32 : visibleRect.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return getParent() != null && getPreferredSize().height < getParent().getHeight();
		}

		private class ListLayout implements LayoutManager
		{
			@Override
			public void addLayoutComponent(String name, Component comp)
			{
			}

			@Override
			public void removeLayoutComponent(Component comp)
			{
			}

			@Override
			public Dimension preferredLayoutSize(Container parent)
			{
				int width = parent.getWidth() > 0 ? parent.getWidth() : 220;
				return new Dimension(width, layoutChildren(parent, width, false));
			}

			@Override
			public Dimension minimumLayoutSize(Container parent)
			{
				return new Dimension(0, 0);
			}

			@Override
			public void layoutContainer(Container parent)
			{
				layoutChildren(parent, parent.getWidth(), true);
			}

			private int layoutChildren(Container parent, int width, boolean apply)
			{
				Insets in = parent.getInsets();
				int y = in.top;
				for (Component c : parent.getComponents())
				{
					if (!c.isVisible())
					{
						continue;
					}
					JComponent jc = (JComponent) c;
					Object gap = jc.getClientProperty(GAP);
					y += gap instanceof Integer ? (Integer) gap : 8;
					Align align = jc.getClientProperty(ALIGN) instanceof Align ? (Align) jc.getClientProperty(ALIGN) : Align.LEFT;
					// Right-aligned bubbles sit inset from the card edge; full-width rows span it
					int pad = align == Align.RIGHT ? sidePad : 0;
					int avail = width - in.left - in.right - pad * 2;

					int w;
					int h;
					if (c instanceof Bubble)
					{
						Bubble b = (Bubble) c;
						int max = align == Align.FILL ? avail : (int) (avail * MAX_BUBBLE);
						w = b.widthFor(max);
						h = b.heightForWidth(w);
					}
					else if (c instanceof HeightForWidth && align == Align.FILL)
					{
						w = avail;
						h = ((HeightForWidth) c).heightForWidth(w);
					}
					else
					{
						Dimension pref = c.getPreferredSize();
						w = align == Align.FILL ? avail : Math.min(pref.width, avail);
						h = pref.height;
					}
					if (apply)
					{
						int x = in.left + pad + (align == Align.RIGHT ? avail - w : 0);
						c.setBounds(x, y, w, h);
					}
					y += h;
				}
				return y + in.bottom;
			}
		}
	}

	/** The design's 24px beveled orange send button; shows a stop square while a reply is streaming. */
	static class SendButton extends JButton
	{
		private final Icon arrow = SvgIcon.load("send-arrow", 16, null);
		private boolean stop;
		private boolean hover;
		/** Grey instead of orange (Home's compact composer). */
		private final boolean neutral;

		SendButton()
		{
			this(false);
		}

		SendButton(boolean neutral)
		{
			this.neutral = neutral;
			setPreferredSize(new Dimension(32, 32));
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
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
			});
			setStop(false);
		}

		void setStop(boolean stop)
		{
			this.stop = stop;
			setToolTipText(stop ? "Stop" : "Send (Enter)");
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, 8, 8);
			if (neutral)
			{
				g2.setColor(hover ? USER_BG.brighter() : USER_BG);
				g2.fill(shape);
			}
			else
			{
				g2.setColor(hover ? ACCENT.brighter() : ACCENT);
				g2.fill(shape);
				// Bevel: light top edge, dark bottom edge
				g2.setClip(shape);
				g2.setColor(new Color(255, 255, 255, 64));
				g2.fillRect(0, 0, w, 2);
				g2.setColor(ACCENT_DARK);
				g2.fillRect(0, h - 2, w, 2);
				g2.setClip(null);
				g2.setColor(ACCENT_DARK);
				g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
			}
			if (stop)
			{
				g2.setColor(Color.WHITE);
				g2.fillRect(w / 2 - 4, h / 2 - 4, 8, 8);
			}
			else
			{
				arrow.paintIcon(this, g2, (w - arrow.getIconWidth()) / 2, (h - arrow.getIconHeight()) / 2);
			}
			g2.dispose();
		}
	}

	/** Full-width beveled accent button with a label, matching the send button (e.g. "Update now"). */
	static class AccentButton extends JButton
	{
		private boolean hover;

		AccentButton(String text)
		{
			super(text);
			setFont(FontManager.getRunescapeBoldFont());
			setForeground(Color.WHITE);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(100, 40));
			addMouseListener(new MouseAdapter()
			{
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
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w, h, 8, 8);
			g2.setColor(getModel().isPressed() ? ACCENT_DARK : hover ? ACCENT.brighter() : ACCENT);
			g2.fill(shape);
			g2.setClip(shape);
			g2.setColor(new Color(255, 255, 255, 64));
			g2.fillRect(0, 0, w, 2);
			g2.setColor(ACCENT_DARK);
			g2.fillRect(0, h - 2, w, 2);
			g2.setClip(null);
			g2.setColor(ACCENT_DARK);
			g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
			g2.dispose();
			super.paintComponent(g);
		}
	}

	/** 32px square button holding a 16px icon from the design; brightens on hover. */
	static JButton iconButton(String icon, String tooltip)
	{
		ImageIcon normal = SvgIcon.load(icon, 16, null);
		JButton b = new JButton(normal);
		b.setRolloverIcon(SvgIcon.load(icon, 16, Color.WHITE));
		b.setToolTipText(tooltip);
		b.setPreferredSize(new Dimension(32, 32));
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setFocusable(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/** Framed tab chip, e.g. the chat title in the header. */
	static class Tab extends Surface
	{
		private static final int MAX_WIDTH = 170;
		private final JLabel label = new JLabel();
		private boolean selected = true;

		Tab(String text)
		{
			super(PANEL_BG, 4, true);
			setLayout(new BorderLayout());
			setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
			label.setFont(FontManager.getRunescapeFont());
			label.setForeground(Color.WHITE);
			add(label, BorderLayout.CENTER);
			setText(text);
		}

		void setText(String text)
		{
			label.setText(text);
			label.setToolTipText(text);
			revalidate();
			repaint();
		}

		/** Selected tabs are a raised chip; unselected ones are just muted text. */
		void setSelected(boolean selected)
		{
			this.selected = selected;
			label.setForeground(selected ? Color.WHITE : MUTED);
			repaint();
		}

		/** Clicking an unselected tab runs this. */
		void onClick(Runnable action)
		{
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter click = new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					action.run();
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					label.setForeground(Color.WHITE);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					label.setForeground(selected ? Color.WHITE : MUTED);
				}
			};
			addMouseListener(click);
			label.addMouseListener(click);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (selected)
			{
				super.paintComponent(g);
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension d = super.getPreferredSize();
			return new Dimension(Math.min(d.width, MAX_WIDTH), 32);
		}
	}

	/** A 32px square tab holding only an icon (e.g. Home): a raised chip when selected, a plain icon button otherwise. */
	static class IconTab extends Surface
	{
		private final JLabel icon = new JLabel();
		private final ImageIcon normal;
		private final ImageIcon bright;
		private boolean selected;
		private boolean hover;

		IconTab(String iconName, String tooltip, Runnable onClick)
		{
			super(PANEL_BG, 4, true);
			normal = SvgIcon.load(iconName, 16, null);
			bright = SvgIcon.load(iconName, 16, Color.WHITE);
			setLayout(new BorderLayout());
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			add(icon, BorderLayout.CENTER);
			setToolTipText(tooltip);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					onClick.run();
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					refresh();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					refresh();
				}
			});
			refresh();
		}

		void setSelected(boolean selected)
		{
			this.selected = selected;
			refresh();
		}

		private void refresh()
		{
			icon.setIcon(selected || hover ? bright : normal);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (selected)
			{
				super.paintComponent(g);
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(32, 32);
		}
	}

	/** Thin rounded progress bar: a sunken track with an accent fill. */
	static class ProgressBar extends JComponent
	{
		private final double fraction;

		ProgressBar(double fraction)
		{
			this.fraction = Math.max(0, Math.min(1, fraction));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(50, 6);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			g2.setColor(OUTLINE);
			g2.fillRoundRect(0, 0, w, h, h, h);
			int fill = (int) Math.round((w - 2) * fraction);
			if (fill > 0)
			{
				g2.setColor(fraction >= 1 ? new Color(0x3FA33F) : ACCENT);
				g2.fillRoundRect(1, 1, Math.max(fill, h - 2), h - 2, h - 2, h - 2);
			}
			g2.dispose();
		}
	}

	/** Clickable framed suggestion, used on the empty state. */
	static JComponent suggestion(String text, Runnable onClick)
	{
		Surface s = new Surface(PANEL_BG, 4, true);
		s.setLayout(new BorderLayout());
		s.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
		JLabel l = new JLabel("<html><div style='width:122px'>" + MarkdownLite.escape(text) + "</div></html>");
		l.setFont(FontManager.getRunescapeFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		s.add(l, BorderLayout.CENTER);
		s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		s.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				l.setForeground(Color.WHITE);
				s.setFill(USER_BG);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				s.setFill(PANEL_BG);
			}
		});
		return s;
	}

	/** A grooved divider (dark line over a lighter line), as around the typing row. */
	static void groove(Graphics g, int y, int width)
	{
		g.setColor(OUTLINE);
		g.fillRect(0, y, width, 1);
		g.setColor(PANEL_BG);
		g.fillRect(0, y + 1, width, 1);
	}

	private ChatComponents()
	{
	}
}
