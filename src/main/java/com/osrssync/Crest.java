package com.osrssync;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The rank crest: the tier's kiteshield, drawn from the game's own item icon (pixel-doubled so it stays crisp).
 * Falls back to a flat shield in the tier's colour when item icons aren't available (e.g. previews).
 */
final class Crest
{
	private static IntFunction<BufferedImage> icons;
	private static final Map<Integer, BufferedImage> CACHE = new HashMap<>();

	/** Called by the plugin with ItemManager; icons may arrive asynchronously. */
	static void setIconSource(IntFunction<BufferedImage> source)
	{
		icons = source;
		CACHE.clear();
	}

	/**
	 * Paint the crest centred at (cx, cy), at most maxW x maxH. The game icon is scaled by a whole number so its
	 * pixels stay square.
	 */
	static void paint(Graphics2D g, Component owner, int itemId, Color color, int cx, int cy, int maxW, int maxH)
	{
		BufferedImage img = icon(itemId, owner);
		if (img != null && hasPixels(img))
		{
			// Item icons are 36x32 with padding; crop to the drawn pixels so the shield fills the space
			java.awt.Rectangle b = bounds(img);
			int scale = Math.max(1, Math.min(maxW / Math.max(1, b.width), maxH / Math.max(1, b.height)));
			int w = b.width * scale, h = b.height * scale;
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2.drawImage(img, cx - w / 2, cy - h / 2, cx - w / 2 + w, cy - h / 2 + h, b.x, b.y, b.x + b.width, b.y + b.height, null);
			g2.dispose();
			return;
		}
		paintShield(g, color, cx, cy, Math.min(maxW, maxH * 9 / 10));
	}

	/** A simple kite shield in the tier colour, with a lighter rim and a dark outline. */
	private static void paintShield(Graphics2D g, Color color, int cx, int cy, int w)
	{
		int h = w * 11 / 9;
		int top = cy - h / 2;
		Polygon shield = new Polygon(
			new int[]{cx - w / 2, cx + w / 2, cx + w / 2, cx, cx - w / 2},
			new int[]{top, top, top + h * 5 / 11, top + h, top + h * 5 / 11}, 5);
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(color);
		g2.fill(shield);
		g2.setColor(color.brighter());
		g2.setStroke(new java.awt.BasicStroke(Math.max(2f, w / 10f)));
		g2.draw(shield);
		g2.setColor(new Color(0, 0, 0, 160));
		g2.setStroke(new java.awt.BasicStroke(1f));
		g2.draw(shield);
		g2.dispose();
	}

	/** An item's inventory icon (cached; repaints the owner when it finishes loading), or null. */
	static BufferedImage itemImage(int itemId, Component owner)
	{
		return icon(itemId, owner);
	}

	private static BufferedImage icon(int itemId, Component owner)
	{
		if (icons == null || itemId <= 0)
		{
			return null;
		}
		return CACHE.computeIfAbsent(itemId, id ->
		{
			BufferedImage img = icons.apply(id);
			if (img instanceof net.runelite.client.util.AsyncBufferedImage)
			{
				((net.runelite.client.util.AsyncBufferedImage) img).onLoaded(() -> javax.swing.SwingUtilities.invokeLater(owner::repaint));
			}
			return img;
		});
	}

	private static boolean hasPixels(BufferedImage img)
	{
		return bounds(img).width > 0;
	}

	private static java.awt.Rectangle bounds(BufferedImage img)
	{
		int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if ((img.getRGB(x, y) >>> 24) > 0)
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		return maxX < 0 ? new java.awt.Rectangle(0, 0, 0, 0) : new java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}

	private Crest()
	{
	}
}
