package com.osrssync;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Speech-bubble icon for the sidebar button and chat labels, drawn at any size. */
final class BuddyIcon
{
	static BufferedImage create()
	{
		return create(16);
	}

	static BufferedImage create(int size)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(size / 16.0, size / 16.0);
		g.setColor(ChatComponents.ACCENT);
		g.fillRoundRect(1, 2, 14, 10, 6, 6);
		g.fillPolygon(new int[]{4, 8, 4}, new int[]{11, 11, 15}, 3);
		g.setColor(Color.WHITE);
		for (int x : new int[]{5, 8, 11})
		{
			g.fillOval(x - 1, 6, 2, 2);
		}
		g.dispose();
		return img;
	}

	private BuddyIcon()
	{
	}
}
