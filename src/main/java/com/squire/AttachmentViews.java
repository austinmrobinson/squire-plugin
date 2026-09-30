package com.squire;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/** Attachment tiles: little squares above the composer (removable) and above sent messages. */
final class AttachmentViews
{
	static final int TILE = 48;
	private static final int RADIUS = 8;
	private static final int CLOSE = 16;

	/** A row of tiles. With onRemove, each shows an x on hover that calls it. */
	static JPanel strip(List<Attachment> attachments, boolean alignRight, Consumer<Attachment> onRemove)
	{
		JPanel row = new JPanel(new FlowLayout(alignRight ? FlowLayout.RIGHT : FlowLayout.LEFT, 6, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		// FlowLayout pads the ends by its gap; cancel it so tiles line up with the text
		row.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, -6, 0, -6));
		row.setOpaque(false);
		for (Attachment a : attachments)
		{
			row.add(new Tile(a, onRemove));
		}
		return row;
	}

	static final class Tile extends JComponent
	{
		private final Attachment attachment;
		private final Consumer<Attachment> onRemove;
		private boolean hover;
		private boolean overClose;

		Tile(Attachment attachment, Consumer<Attachment> onRemove)
		{
			this.attachment = attachment;
			this.onRemove = onRemove;
			setPreferredSize(new Dimension(TILE, TILE));
			setToolTipText("<html><b>" + MarkdownLite.escape(attachment.name) + "</b><br>" + attachment.sizeLabel() + "</html>");
			MouseAdapter mouse = new MouseAdapter()
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
					overClose = false;
					setCursor(Cursor.getDefaultCursor());
					repaint();
				}

				@Override
				public void mouseMoved(MouseEvent e)
				{
					boolean over = onRemove != null && closeBounds().contains(e.getPoint());
					if (over != overClose)
					{
						overClose = over;
						setCursor(over ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
						repaint();
					}
				}

				@Override
				public void mouseReleased(MouseEvent e)
				{
					// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
					if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
					{
						return;
					}
					if (onRemove != null && closeBounds().contains(e.getPoint()))
					{
						onRemove.accept(attachment);
					}
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		private Rectangle closeBounds()
		{
			return new Rectangle(getWidth() - CLOSE - 3, 3, CLOSE, CLOSE);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth(), h = getHeight();
			Shape shape = Pixel.shape(0, 0, w, h, RADIUS);

			if (attachment.kind == Attachment.Kind.IMAGE && attachment.thumbnail != null)
			{
				g2.setColor(ChatComponents.BASE_BG);
				g2.fill(shape);
				g2.clip(shape);
				g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				drawCover(g2, attachment.thumbnail, w, h);
				g2.setClip(null);
			}
			else if (attachment.label != null)
			{
				paintContext(g2, w, h, shape);
			}
			else
			{
				paintFile(g2, w, h, shape);
			}

			g2.setColor(ChatComponents.BORDER);
			g2.setStroke(new BasicStroke(1f));
			Pixel.draw(g2, 0, 0, w, h, RADIUS);

			if (hover && onRemove != null)
			{
				Rectangle c = closeBounds();
				g2.setColor(overClose ? ChatComponents.HOVER_BG : ChatComponents.BASE_BG);
				Pixel.fill(g2, c.x, c.y, c.width, c.height, c.width / 2.0);
				g2.setColor(ChatComponents.BORDER);
				Pixel.draw(g2, c.x, c.y, c.width, c.height, c.width / 2.0);
				Image x = SvgIcon.load("close", 12, overClose ? Color.WHITE : ChatComponents.MUTED).getImage();
				g2.drawImage(x, c.x + 2, c.y + 2, 12, 12, null);
			}
			g2.dispose();
		}

		/** Context from a page: its icon (e.g. the plan's current item) and a label ("PLAN") underneath. */
		private void paintContext(Graphics2D g2, int w, int h, Shape shape)
		{
			g2.setColor(ChatComponents.PANEL_BG);
			g2.fill(shape);
			BufferedImage icon = attachment.thumbnail;
			if (icon != null)
			{
				int iw = Math.min(icon.getWidth(), w - 8), ih = Math.min(icon.getHeight(), h - 18);
				double scale = Math.min(iw / (double) icon.getWidth(), ih / (double) icon.getHeight());
				int dw = (int) (icon.getWidth() * scale), dh = (int) (icon.getHeight() * scale);
				g2.drawImage(icon, (w - dw) / 2, 4 + (h - 18 - dh) / 2, dw, dh, null);
			}
			Font font = FontManager.getRunescapeSmallFont();
			g2.setFont(font);
			FontMetrics fm = g2.getFontMetrics();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setColor(ChatComponents.ACCENT.brighter());
			String label = attachment.label.toUpperCase(java.util.Locale.ROOT);
			g2.drawString(label, 8, h - 7 - fm.getDescent() + 2);
		}

		/** A document: faint lines of "text" and the file type underneath. */
		private void paintFile(Graphics2D g2, int w, int h, Shape shape)
		{
			g2.setColor(ChatComponents.PANEL_BG);
			g2.fill(shape);
			g2.setColor(ChatComponents.USER_BG);
			int x = 8, y = 9;
			int[] lengths = {w - 16, w - 22, w - 18};
			for (int len : lengths)
			{
				g2.fillRect(x, y, len, 2);
				y += 5;
			}

			String ext = attachment.extension();
			if (ext.length() > 4)
			{
				ext = ext.substring(0, 4);
			}
			Font font = FontManager.getRunescapeSmallFont();
			g2.setFont(font);
			FontMetrics fm = g2.getFontMetrics();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setColor(ChatComponents.MUTED);
			g2.drawString(ext, x, h - 7 - fm.getDescent() + 2);
		}

		private static void drawCover(Graphics2D g2, BufferedImage img, int w, int h)
		{
			double scale = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight());
			int dw = (int) Math.ceil(img.getWidth() * scale), dh = (int) Math.ceil(img.getHeight() * scale);
			g2.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
		}
	}

	private AttachmentViews()
	{
	}
}
