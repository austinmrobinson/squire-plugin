package com.squire;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Frames of the Leap spinner across one cycle, enlarged, to check the motion. */
public class LeapStrip
{
	public static void main(String[] args) throws Exception
	{
		int frames = 12, scale = 6, cell = ChatTraceViews.Leap.SIZE + 4;
		BufferedImage img = new BufferedImage(frames * cell * scale, cell * scale, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(0x232323));
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(scale, scale);
		for (int i = 0; i < frames; i++)
		{
			ChatTraceViews.Leap.paint(g, i * cell + 2, 2, (long) (1800.0 * i / frames), new Color(0xDC8A00));
		}
		g.dispose();
		new File("build/preview").mkdirs();
		ImageIO.write(img, "png", new File("build/preview/leap-strip.png"));
	}
}
