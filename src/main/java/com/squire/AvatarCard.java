package com.squire;

import com.google.gson.JsonObject;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;

/**
 * The avatar card: the character on a background, in a frame, with an emblem. Drawn on the same 80x80 pixel grid and
 * from the same data as the website's card (server/app/p/avatar-card.tsx), including the background's specks, which
 * come from the same seeded random sequence, so the plugin and the profile page look the same.
 */
final class AvatarCard
{
	private static final Color OUTLINE = new Color(0x0B0B0B);
	private static final Map<String, BufferedImage> EMBLEMS = new HashMap<>();

	private AvatarCard()
	{
	}

	/**
	 * Paint the card at (x, y), size px square. {@code background} and {@code frame} are the server's option objects;
	 * {@code character} is the avatar render (may be null); {@code icons} gives item icons (may be null).
	 */
	static void paint(Graphics2D g0, int x, int y, int size, JsonObject background, JsonObject frame, String emblem,
		BufferedImage character, IntFunction<BufferedImage> icons)
	{
		Graphics2D g = (Graphics2D) g0.create();
		g.translate(x, y);
		double u = size / 80.0;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

		Color band = color(frame, "band", 0x4454DA), light = color(frame, "light", 0x7A86FF), dark = color(frame, "dark", 0x252E78);
		stepped(g, u, 0, 0, 80, 80, 2, OUTLINE);
		stepped(g, u, 1, 1, 78, 78, 2, band);
		rect(g, u, 2, 1, 76, 1, light);
		rect(g, u, 1, 2, 1, 76, light);
		rect(g, u, 2, 78, 76, 1, dark);
		rect(g, u, 78, 2, 1, 76, dark);
		rect(g, u, 4, 4, 72, 72, OUTLINE);

		// Background: a vertical gradient with its specks
		int ix = px(u, 5), iy = px(u, 5), iw = px(u, 75) - ix, ih = px(u, 75) - iy;
		g.setPaint(new GradientPaint(ix, iy, color(background, "top", 0x10131C), ix, iy + ih, color(background, "bottom", 0x2A2522)));
		g.fillRect(ix, iy, iw, ih);
		Color ink = color(background, "ink", 0xFFFFFF);
		for (double[] s : pattern(Ui.str(background, "id"), Ui.str(background, "pattern")))
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) s[3]));
			rect(g, u, (int) s[0], (int) s[1], (int) s[2], (int) s[2], ink);
		}
		g.setComposite(AlphaComposite.SrcOver);

		if (character != null)
		{
			// Bottom-aligned in the frame's opening, like the web card (x 6, y 7, 68 square)
			Graphics2D c = (Graphics2D) g.create();
			c.clipRect(ix, iy, iw, ih);
			c.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			int cs = px(u, 74) - px(u, 6);
			c.drawImage(character, px(u, 6), px(u, 75) - cs, cs, cs, null);
			c.dispose();
		}

		if (frame != null && frame.has("itemId") && !frame.get("itemId").isJsonNull())
		{
			stepped(g, u, 30, 0, 20, 15, 2, OUTLINE);
			rect(g, u, 31, 1, 18, 13, dark);
			BufferedImage icon = icons == null ? null : icons.apply(frame.get("itemId").getAsInt());
			if (icon != null)
			{
				fit(g, icon, px(u, 32), px(u, 1), px(u, 48) - px(u, 32), px(u, 14) - px(u, 1));
			}
		}
		if (emblem != null)
		{
			stepped(g, u, 64, 64, 15, 15, 2, OUTLINE);
			rect(g, u, 65, 65, 13, 13, new Color(0x1E1E1E));
			BufferedImage e = emblem(emblem);
			if (e != null)
			{
				fit(g, e, px(u, 66), px(u, 66), px(u, 77) - px(u, 66), px(u, 77) - px(u, 66));
			}
		}
		g.dispose();
	}

	/** The background's specks in grid units: x, y, size, opacity. Same sequence as the website's card. */
	static java.util.List<double[]> pattern(String id, String kind)
	{
		long seed = 7;
		for (char ch : id.toCharArray())
		{
			seed = (seed * 31 + ch) % 2147483646L;
		}
		seed += 1;
		int count, y0, y1, size;
		double alpha;
		switch (kind)
		{
			case "stars":
				count = 18; y0 = 5; y1 = 45; size = 1; alpha = 0.85;
				break;
			case "motes":
				count = 14; y0 = 5; y1 = 75; size = 2; alpha = 0.35;
				break;
			case "snow":
				count = 26; y0 = 5; y1 = 75; size = 1; alpha = 0.9;
				break;
			case "embers":
				count = 16; y0 = 40; y1 = 75; size = 1; alpha = 0.9;
				break;
			default:
				return java.util.List.of();
		}
		java.util.List<double[]> out = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			seed = (seed * 48271) % 2147483647L;
			double rx = seed / 2147483647.0;
			seed = (seed * 48271) % 2147483647L;
			double ry = seed / 2147483647.0;
			out.add(new double[]{5 + Math.floor(rx * 70), y0 + Math.floor(ry * (y1 - y0)), size, alpha});
		}
		return out;
	}

	static BufferedImage emblem(String id)
	{
		synchronized (EMBLEMS)
		{
			return EMBLEMS.computeIfAbsent(id, k ->
			{
				if ("squire".equals(k))
				{
					return SquireIcon.create(26);
				}
				try (java.io.InputStream in = AvatarCard.class.getResourceAsStream("emblems/" + k + ".png"))
				{
					return in == null ? null : ImageIO.read(in);
				}
				catch (java.io.IOException e)
				{
					return null;
				}
			});
		}
	}

	/** Draw an image as large as fits the box, in whole-pixel steps when it's small pixel art. */
	private static void fit(Graphics2D g, Image img, int x, int y, int w, int h)
	{
		int iw = img.getWidth(null), ih = img.getHeight(null);
		if (iw <= 0 || ih <= 0)
		{
			return;
		}
		double s = Math.min((double) w / iw, (double) h / ih);
		if (s >= 1)
		{
			s = Math.floor(s);
		}
		int dw = (int) Math.round(iw * s), dh = (int) Math.round(ih * s);
		Graphics2D c = (Graphics2D) g.create();
		c.setRenderingHint(RenderingHints.KEY_INTERPOLATION, s >= 1 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		c.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
		c.dispose();
	}

	private static int px(double u, double v)
	{
		return (int) Math.round(v * u);
	}

	private static void rect(Graphics2D g, double u, int x, int y, int w, int h, Color c)
	{
		g.setColor(c);
		g.fillRect(px(u, x), px(u, y), px(u, x + w) - px(u, x), px(u, y + h) - px(u, y));
	}

	/** A rectangle with corners cut in whole grid pixels, like the web card's stepped path. */
	private static void stepped(Graphics2D g, double u, int x, int y, int w, int h, int cut, Color c)
	{
		rect(g, u, x + cut, y, w - 2 * cut, h, c);
		rect(g, u, x, y + cut, cut, h - 2 * cut, c);
		rect(g, u, x + w - cut, y + cut, cut, h - 2 * cut, c);
	}

	private static Color color(JsonObject o, String key, int fallback)
	{
		String hex = o == null ? "" : Ui.str(o, key);
		try
		{
			return hex.startsWith("#") ? new Color(Integer.parseInt(hex.substring(1), 16)) : new Color(fallback);
		}
		catch (NumberFormatException e)
		{
			return new Color(fallback);
		}
	}
}
