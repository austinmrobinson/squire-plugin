package com.squire;

import com.squire.ChatComponents.HeightForWidth;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Shown until the player turns Squire on: the campfire scene and emblem, what Squire does in three callouts, and
 * Continue, which signs this install up and starts syncing (the first callout says what's sent; the privacy policy
 * has the details). Nothing is sent before that.
 */
class WelcomeView extends JPanel
{
	private static final Color ERROR = Tokens.COLOR_TEXT_ERROR;
	/** The callout icons' colour: Squire blue, lightened to read on the dark slot. */
	static final Color ICON = Tokens.COLOR_ACCENT_LIGHT;

	private final JLabel error = new JLabel();
	private final JButton next = new ChatComponents.AccentButton("Continue");

	/** {@code onTurnOn} gets a callback for errors (or null on success). */
	WelcomeView(String privacyUrl, Consumer<Consumer<String>> onTurnOn)
	{
		super(new BorderLayout());
		setOpaque(false);

		Content content = new Content();
		JScrollPane scroll = new JScrollPane(content);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll);

		// Bottom: Continue, with the privacy line under it
		JPanel bottom = new JPanel(new BorderLayout(0, 6));
		bottom.setOpaque(false);
		bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
		next.addActionListener(e ->
		{
			next.setEnabled(false);
			next.setText("Turning on...");
			error.setText("");
			onTurnOn.accept(err ->
			{
				next.setEnabled(true);
				next.setText("Continue");
				if (err != null)
				{
					error.setText("<html><div style='text-align:center'>" + MarkdownLite.escape(err) + "</div></html>");
				}
			});
		});
		bottom.add(next, BorderLayout.NORTH);

		Wrapped privacy = new Wrapped("By clicking Continue, you agree to Squire's Privacy Policy.", ChatComponents.MUTED, true);
		privacy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		privacy.setToolTipText("Read what's sent and why");
		privacy.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
				if (SwingUtilities.isLeftMouseButton(e) && e.getComponent().contains(e.getPoint()))
				{
					LinkBrowser.browse(privacyUrl);
				}
			}
		});
		JPanel notes = new JPanel(new BorderLayout(0, 4))
		{
			@Override
			public Dimension getPreferredSize()
			{
				int w = Math.max(40, getParent() == null ? 200 : getParent().getWidth() - 24);
				int h = privacy.heightForWidth(w) + (error.getText().isEmpty() ? 0 : 4 + error.getPreferredSize().height);
				return new Dimension(w, h);
			}
		};
		notes.setOpaque(false);
		notes.add(privacy, BorderLayout.NORTH);
		error.setHorizontalAlignment(SwingConstants.CENTER);
		error.setFont(FontManager.getRunescapeSmallFont());
		error.setForeground(ERROR);
		notes.add(error, BorderLayout.SOUTH);
		bottom.add(notes, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
	}

	/**
	 * The scrolling part, laid out like the design at any sidebar width: the scene full-bleed with the emblem over
	 * its lower edge, the centred title and intro, then the callouts.
	 */
	private static final class Content extends JPanel implements Scrollable
	{
		// The scene is as wide as the panel at the art's own proportions, with ground below it for the emblem
		private static final int SCENE_GROUND = 58;
		private static final int EMBLEM = 56, EMBLEM_BOTTOM = 14, SIDE = 12, CALLOUT_SIDE = 16;

		private final Scene scene = new Scene();
		private final Wrapped title = new Wrapped("Welcome to Squire", Color.WHITE, true);
		private final Wrapped intro = new Wrapped("Answers from your own stats, bank and quests, checked against the wiki "
			+ "and a DPS calculator.", ChatComponents.MUTED, true);
		private final Callout[] callouts = {
			new Callout("sync", "Sync your data",
				"Your stats, bank and quests sync to Squire's server."),
			new Callout("map", "Plan your progression",
				"Checkpoints that tick off as you play."),
			new Callout("sword", "Gear and boss advice",
				"Setups from what you own, checked with a DPS calculator."),
		};

		Content()
		{
			setOpaque(false);
			title.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
			intro.setFont(FontManager.getRunescapeFont());
			add(scene);
			add(title);
			add(intro);
			for (Callout c : callouts)
			{
				add(c);
			}
			setLayout(new LayoutManager()
			{
				@Override
				public void addLayoutComponent(String name, java.awt.Component comp)
				{
				}

				@Override
				public void removeLayoutComponent(java.awt.Component comp)
				{
				}

				@Override
				public Dimension preferredLayoutSize(Container parent)
				{
					return new Dimension(parent.getWidth(), layout(parent.getWidth(), false));
				}

				@Override
				public Dimension minimumLayoutSize(Container parent)
				{
					return new Dimension(0, 0);
				}

				@Override
				public void layoutContainer(Container parent)
				{
					layout(parent.getWidth(), true);
				}
			});
		}

		/** Lays the page out at this width (or only measures it); returns the height. */
		private int layout(int width, boolean place)
		{
			int w = Math.max(120, width);
			int sceneH = Scene.artHeight(w) + SCENE_GROUND;
			if (place)
			{
				scene.setBounds(0, 0, w, sceneH);
			}
			int y = sceneH + 6;
			int tw = w - SIDE * 2;
			int th = title.heightForWidth(tw);
			if (place)
			{
				title.setBounds(SIDE, y, tw, th);
			}
			y += th + 8;
			int ih = intro.heightForWidth(tw);
			if (place)
			{
				intro.setBounds(SIDE, y, tw, ih);
			}
			y += ih + 24;
			int cw = w - CALLOUT_SIDE * 2;
			for (int i = 0; i < callouts.length; i++)
			{
				int ch = callouts[i].heightForWidth(cw);
				if (place)
				{
					callouts[i].setBounds(CALLOUT_SIDE, y, cw, ch);
				}
				y += ch + (i < callouts.length - 1 ? 22 : 12);
			}
			return y;
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
			return visibleRect.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}

		/** The campfire scene, faded into the panel at its edges, with the emblem over its lower edge. */
		private static final class Scene extends JComponent
		{
			private static final BufferedImage ART = trim(load("welcome/hero.png"));
			private static final BufferedImage EMBLEM_ART = load("welcome/emblem.png");

			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				int w = getWidth(), h = getHeight();
				g2.setColor(new Color(0x292D2E));
				g2.fillRect(0, 0, w, h);
				if (ART != null)
				{
					// The whole scene across the width, never stretched; the ground colour continues below it
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(ART, 0, 0, w, artHeight(w), null);
				}
				// The design's vignette: clear in the middle, fading to the panel colour towards the sides and bottom
				float rx = w * 0.84f, ry = h * 0.84f;
				AffineTransform stretch = AffineTransform.getTranslateInstance(w / 2.0, 0);
				stretch.scale(1, ry / rx);
				Color base = ChatComponents.BASE_BG;
				g2.setPaint(new RadialGradientPaint(new Point2D.Float(0, 0), rx, new Point2D.Float(0, 0), new float[]{0.65f, 1f},
					new Color[]{new Color(base.getRed(), base.getGreen(), base.getBlue(), 0), base},
					MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, stretch));
				g2.fillRect(0, 0, w, h);
				if (EMBLEM_ART != null)
				{
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(EMBLEM_ART, (w - EMBLEM) / 2, h - EMBLEM - EMBLEM_BOTTOM, EMBLEM, EMBLEM, null);
				}
				g2.dispose();
			}

			/** The art's height when drawn this wide. */
			static int artHeight(int width)
			{
				return ART == null ? width / 3 : (int) Math.round((double) width * ART.getHeight() / ART.getWidth());
			}

			private static BufferedImage load(String path)
			{
				try (java.io.InputStream in = WelcomeView.class.getResourceAsStream(path))
				{
					return in == null ? null : ImageIO.read(in);
				}
				catch (java.io.IOException e)
				{
					return null;
				}
			}

			/** The art without its transparent margins, so it can cover the box edge to edge. */
			private static BufferedImage trim(BufferedImage img)
			{
				if (img == null)
				{
					return null;
				}
				int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
				for (int y = 0; y < img.getHeight(); y++)
				{
					for (int x = 0; x < img.getWidth(); x++)
					{
						if ((img.getRGB(x, y) >>> 24) > 16)
						{
							minX = Math.min(minX, x);
							maxX = Math.max(maxX, x);
							minY = Math.min(minY, y);
							maxY = Math.max(maxY, y);
						}
					}
				}
				return maxX < 0 ? img : img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
			}
		}
	}

	/** One of the three callouts: an icon in an inventory-style slot, then a bold title and a short description. */
	private static final class Callout extends JPanel implements HeightForWidth
	{
		private static final int SLOT = 48, GAP = 16;
		private final Wrapped title;
		private final Wrapped body;

		Callout(String icon, String titleText, String bodyText)
		{
			setOpaque(false);
			Slot slot = new Slot(icon);
			title = new Wrapped(titleText, Color.WHITE, false);
			title.setFont(FontManager.getRunescapeBoldFont());
			body = new Wrapped(bodyText, ChatComponents.MUTED, false);
			body.setFont(FontManager.getRunescapeFont());
			add(slot);
			add(title);
			add(body);
			setLayout(new LayoutManager()
			{
				@Override
				public void addLayoutComponent(String name, java.awt.Component comp)
				{
				}

				@Override
				public void removeLayoutComponent(java.awt.Component comp)
				{
				}

				@Override
				public Dimension preferredLayoutSize(Container parent)
				{
					return new Dimension(parent.getWidth(), heightForWidth(parent.getWidth()));
				}

				@Override
				public Dimension minimumLayoutSize(Container parent)
				{
					return new Dimension(0, 0);
				}

				@Override
				public void layoutContainer(Container parent)
				{
					int h = parent.getHeight();
					int tx = SLOT + GAP, tw = Math.max(40, parent.getWidth() - tx);
					int text = textHeight(tw);
					// Centred against each other, like the design's rows
					slot.setBounds(0, (h - SLOT) / 2, SLOT, SLOT);
					int y = (h - text) / 2;
					int th = title.heightForWidth(tw);
					title.setBounds(tx, y, tw, th);
					body.setBounds(tx, y + th + 2, tw, body.heightForWidth(tw));
				}
			});
		}

		private int textHeight(int tw)
		{
			return title.heightForWidth(tw) + 2 + body.heightForWidth(tw);
		}

		@Override
		public int heightForWidth(int width)
		{
			return Math.max(SLOT, textHeight(Math.max(40, width - SLOT - GAP)));
		}

		/** A sunken inventory slot with stepped corners, holding the callout's icon. */
		private static final class Slot extends JComponent
		{
			private final ImageIcon icon;

			Slot(String name)
			{
				icon = SvgIcon.load(name, 21, ICON);
			}

			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				int w = getWidth(), h = getHeight();
				g2.setColor(ChatComponents.BASE_BG);
				Pixel.fill(g2, 0, 0, w, h, 4);
				g2.setColor(ChatComponents.BORDER);
				Pixel.draw(g2, 0, 0, w, h, 4);
				Pixel.bevel(g2, 1, 1, w - 2, h - 2, 3, ChatComponents.CARD_DARK, ChatComponents.CARD_LIGHT);
				if (icon != null)
				{
					icon.paintIcon(this, g2, (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
				}
				g2.dispose();
			}
		}
	}

	/** Word-wrapped small text whose height follows the width it's given. */
	static class Wrapped extends JTextArea implements HeightForWidth
	{
		private final boolean centered;

		Wrapped(String text, Color color, boolean centered)
		{
			super(text);
			this.centered = centered;
			setLineWrap(true);
			setWrapStyleWord(true);
			setEditable(false);
			setFocusable(false);
			setOpaque(false);
			setFont(FontManager.getRunescapeSmallFont());
			setForeground(color);
			setBorder(null);
			setMargin(new Insets(0, 0, 0, 0));
		}

		@Override
		public int heightForWidth(int width)
		{
			if (centered)
			{
				return lines(width).length * getFontMetrics(getFont()).getHeight();
			}
			Dimension saved = getSize();
			setSize(Math.max(40, width), Short.MAX_VALUE);
			int h = super.getPreferredSize().height;
			setSize(saved);
			return h;
		}

		/** Greedy word wrap, used to centre each line (JTextArea can only left-align). */
		private String[] lines(int width)
		{
			java.awt.FontMetrics fm = getFontMetrics(getFont());
			java.util.List<String> out = new java.util.ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (String word : getText().split(" "))
			{
				String tryLine = line.length() == 0 ? word : line + " " + word;
				if (line.length() > 0 && fm.stringWidth(tryLine) > width)
				{
					out.add(line.toString());
					line = new StringBuilder(word);
				}
				else
				{
					line = new StringBuilder(tryLine);
				}
			}
			if (line.length() > 0)
			{
				out.add(line.toString());
			}
			return out.toArray(new String[0]);
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			if (!centered)
			{
				super.paintComponent(g);
				return;
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setFont(getFont());
			g2.setColor(getForeground());
			java.awt.FontMetrics fm = g2.getFontMetrics();
			int y = fm.getAscent();
			for (String l : lines(getWidth()))
			{
				g2.drawString(l, (getWidth() - fm.stringWidth(l)) / 2, y);
				y += fm.getHeight();
			}
			g2.dispose();
		}
	}
}
