package com.squire;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders a saved player model at a few framings, side by side, to tune the Home portrait without the game. */
public class PortraitTune
{
	public static void main(String[] args) throws Exception
	{
		PlayerPortrait.Mesh mesh = PlayerPortrait.load(new File(args[0]), new com.google.gson.Gson());
		if (args.length > 1)
		{
			// Redraw the saved portrait with the current framing
			ImageIO.write(PlayerPortrait.draw(mesh, 128, PlayerPortrait.DEFAULT_FRAMING), "png", new File(args[1]));
			System.out.println("wrote " + args[1]);
			return;
		}
		float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
		for (int i = 0; i < mesh.vx.length; i++)
		{
			minY = Math.min(minY, mesh.vy[i]);
			maxY = Math.max(maxY, mesh.vy[i]);
			minX = Math.min(minX, mesh.vx[i]);
			maxX = Math.max(maxX, mesh.vx[i]);
		}
		System.out.printf("vertices %d faces %d  y %.0f..%.0f  x %.0f..%.0f  textures %s%n",
			mesh.vx.length, mesh.faceCount(), minY, maxY, minX, maxX, mesh.texels.keySet());

		float[][] crops = {{-202, -140}, {-204, -134}, {-206, -128}, {-200, -144}};
		double[] yaws = {-22, -30};
		int size = 128, pad = 8;
		BufferedImage sheet = new BufferedImage(crops.length * (size + pad) + pad, yaws.length * (size + pad) + pad, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(0x1e1e1e));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		for (int r = 0; r < yaws.length; r++)
		{
			for (int c = 0; c < crops.length; c++)
			{
				PlayerPortrait.Framing f = new PlayerPortrait.Framing(crops[c][0], crops[c][1], Math.toRadians(yaws[r]));
				BufferedImage img = PlayerPortrait.draw(mesh, size, f);
				g.drawImage(img, pad + c * (size + pad), pad + r * (size + pad), null);
			}
		}
		g.dispose();
		File out = new File("build/preview/portrait-tune.png");
		out.getParentFile().mkdirs();
		ImageIO.write(sheet, "png", out);
		System.out.println("wrote " + out);
	}
}
