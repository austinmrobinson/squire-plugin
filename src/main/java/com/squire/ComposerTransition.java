package com.squire;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * The floating composer settling into the chat's composer after sending: a stand-in box glides and stretches from
 * where the floating one was to where the chat's sits, its colour and shadow easing to match while the sent text
 * fades out. Drawn above everything in the window's layered pane, then removed to reveal the real composer.
 */
final class ComposerTransition extends JComponent
{
	static final int DURATION_MS = 280;

	private final Rectangle from;
	private final Rectangle to;
	private final String text;
	private final long start = System.currentTimeMillis();
	private double t;

	private ComposerTransition(Rectangle from, Rectangle to, String text)
	{
		this.from = from;
		this.to = to;
		this.text = text;
		setOpaque(false);
	}

	/**
	 * Animate from {@code from} to {@code to} (both in {@code within}'s coordinates), then run {@code done}.
	 * Without a window to draw in (previews) it just runs {@code done}.
	 */
	static void play(JComponent within, Rectangle from, Rectangle to, String text, Runnable done)
	{
		JRootPane root = SwingUtilities.getRootPane(within);
		if (root == null || from == null || to == null || to.width <= 0)
		{
			done.run();
			return;
		}
		JLayeredPane layer = root.getLayeredPane();
		Rectangle a = SwingUtilities.convertRectangle(within, from, layer);
		Rectangle b = SwingUtilities.convertRectangle(within, to, layer);
		Rectangle area = a.union(b);
		area.grow(12, 12);
		ComposerTransition ghost = new ComposerTransition(
			new Rectangle(a.x - area.x, a.y - area.y, a.width, a.height),
			new Rectangle(b.x - area.x, b.y - area.y, b.width, b.height), text);
		ghost.setBounds(area);
		layer.add(ghost, JLayeredPane.DRAG_LAYER);
		Timer timer = new Timer(15, null);
		timer.addActionListener(e ->
		{
			double raw = Math.min(1, (System.currentTimeMillis() - ghost.start) / (double) DURATION_MS);
			ghost.t = 1 - Math.pow(1 - raw, 3); // ease out
			ghost.repaint();
			if (raw >= 1)
			{
				timer.stop();
				layer.remove(ghost);
				layer.repaint(area);
				done.run();
			}
		});
		timer.start();
	}

	/** One frame of the animation as a component (previews). */
	static JComponent frame(Rectangle from, Rectangle to, String text, double t)
	{
		ComposerTransition c = new ComposerTransition(from, to, text);
		c.t = t;
		return c;
	}

	private static int lerp(int a, int b, double t)
	{
		return (int) Math.round(a + (b - a) * t);
	}

	private static Color lerp(Color a, Color b, double t)
	{
		return new Color(lerp(a.getRed(), b.getRed(), t), lerp(a.getGreen(), b.getGreen(), t), lerp(a.getBlue(), b.getBlue(), t));
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int x = lerp(from.x, to.x, t), y = lerp(from.y, to.y, t);
		int w = lerp(from.width, to.width, t), h = lerp(from.height, to.height, t);

		// The floating shadow lifts away as it lands
		for (int i = 1; i <= 6; i++)
		{
			g2.setColor(new Color(0, 0, 0, (int) (22 * (1 - t))));
			int grow = i - 2;
			Pixel.fill(g2, x - grow, y + i * 2 - grow, w + grow * 2, h + grow * 2, (16 + grow * 2) / 2.0);
		}
		g2.setColor(lerp(ChatComponents.CARD_BG, ChatComponents.PANEL_BG, t));
		Pixel.fill(g2, x, y, w, h, 8);
		g2.setColor(ChatComponents.BORDER);
		g2.setStroke(new BasicStroke(1f));
		Pixel.draw(g2, x, y, w, h, 8);

		// The sent text fades out over the first half
		float alpha = (float) Math.max(0, 1 - t * 2);
		if (alpha > 0 && text != null)
		{
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			g2.setFont(FontManager.getRunescapeFont());
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			FontMetrics fm = g2.getFontMetrics();
			g2.setColor(Color.WHITE);
			g2.clipRect(x + 12, y, Math.max(0, w - 56), h);
			g2.drawString(text.replace('\n', ' '), x + 12, y + (Math.min(h, 40) + fm.getAscent() - fm.getDescent()) / 2);
		}
		g2.dispose();
	}
}
