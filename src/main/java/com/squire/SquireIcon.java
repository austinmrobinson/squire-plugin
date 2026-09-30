package com.squire;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Squire, the plugin's mascot: a 13x13 pixel-art helm with a blue plume, drawn at whole-pixel scales so it stays
 * crisp (a size that isn't a multiple of 13 gets the largest whole scale that fits, centred).
 */
final class SquireIcon
{
	private static final String[] SPRITE = {
		".pPPP........",
		"pppPPP.......",
		"ppdpPPP..LM..",
		"pd.dpMLKLKLM.",
		"pd.dMLKMMKLKL",
		"pd.DMKMKMLMLM",
		".PdDKMMMKKKKM",
		".PdDMDKfffffK",
		".PdDKKFkFFFkD",
		"Ppd.DDFFFnFFD",
		"dd..DDfFFFFFD",
		"...DDMDFmmFfD",
		"....DDDfFFFDD",
	};

	private static final Map<Character, Color> PALETTE = Map.ofEntries(
		Map.entry('P', new Color(0x313BBF)), // plume
		Map.entry('p', new Color(0x2D2D84)),
		Map.entry('d', new Color(0x222154)),
		Map.entry('L', new Color(0xB4B4B4)), // steel
		Map.entry('M', new Color(0x848484)),
		Map.entry('D', new Color(0x555555)),
		Map.entry('K', new Color(0x3A3A3A)),
		Map.entry('F', new Color(0xF0C798)), // face
		Map.entry('f', new Color(0xE9A076)),
		Map.entry('k', new Color(0x050505)),
		Map.entry('n', new Color(0xE08E71)),
		Map.entry('m', new Color(0xD19A7D)));

	static final int GRID = SPRITE.length;

	/** The 16px sidebar icon. */
	static BufferedImage create()
	{
		return create(16);
	}

	static BufferedImage create(int size)
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		int scale = Math.max(1, size / GRID);
		int offset = (size - GRID * scale) / 2;
		Graphics2D g = img.createGraphics();
		for (int y = 0; y < GRID; y++)
		{
			for (int x = 0; x < GRID; x++)
			{
				Color c = PALETTE.get(SPRITE[y].charAt(x));
				if (c != null)
				{
					g.setColor(c);
					g.fillRect(offset + x * scale, offset + y * scale, scale, scale);
				}
			}
		}
		g.dispose();
		return img;
	}

	private SquireIcon()
	{
	}
}
