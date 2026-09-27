package com.osrssync;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.ImageIcon;

/**
 * Renders the plugin's SVG icons (exported from the Figma design) without an SVG library.
 * Supports what those icons use: paths with absolute M/L/H/V/Z commands, circles, fill colours and opacity.
 */
final class SvgIcon
{
	private static final Pattern VIEWBOX = Pattern.compile("viewBox=\"([\\d.\\s-]+)\"");
	private static final Pattern PATH = Pattern.compile("<path[^>]*?\\sd=\"([^\"]+)\"[^>]*?fill=\"(#[0-9A-Fa-f]{6}|white|black)\"[^>]*/?>");
	private static final Pattern CIRCLE = Pattern.compile("<circle([^>]*)/?>");
	private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
	private static final Pattern TOKEN = Pattern.compile("[MLHVZmlhvz]|-?\\d*\\.?\\d+(?:e-?\\d+)?");
	private static final Map<String, String> CACHE = new HashMap<>();

	/**
	 * Load an icon from resources, drawn at `height` pixels tall (width follows the viewBox), optionally recoloured.
	 * Includes a 2x rendering so it stays sharp on HiDPI displays.
	 */
	static ImageIcon load(String name, int height, Color tint)
	{
		String svg = source(name);
		return new ImageIcon(new BaseMultiResolutionImage(render(svg, height, tint), render(svg, height * 2, tint)));
	}

	static String source(String name)
	{
		return CACHE.computeIfAbsent(name, n ->
		{
			try (InputStream in = SvgIcon.class.getResourceAsStream("icons/" + n + ".svg"))
			{
				if (in == null)
				{
					throw new IllegalStateException("Missing icon " + n);
				}
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (IOException e)
			{
				throw new IllegalStateException(e);
			}
		});
	}

	static BufferedImage render(String svg, int height, Color tint)
	{
		Matcher vb = VIEWBOX.matcher(svg);
		double vbW = 12, vbH = 12;
		if (vb.find())
		{
			String[] p = vb.group(1).trim().split("\\s+");
			vbW = Double.parseDouble(p[2]);
			vbH = Double.parseDouble(p[3]);
		}
		double scale = height / vbH;
		int width = Math.max(1, (int) Math.round(vbW * scale));
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(scale, scale);

		Matcher pm = PATH.matcher(svg);
		while (pm.find())
		{
			g.setColor(tint != null ? tint : color(pm.group(2)));
			g.fill(path(pm.group(1)));
		}
		Matcher cm = CIRCLE.matcher(svg);
		while (cm.find())
		{
			Map<String, String> a = attrs(cm.group(1));
			double r = Double.parseDouble(a.getOrDefault("r", "0"));
			double cx = Double.parseDouble(a.getOrDefault("cx", "0"));
			double cy = Double.parseDouble(a.getOrDefault("cy", "0"));
			float opacity = Float.parseFloat(a.getOrDefault("opacity", "1"));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
			g.setColor(tint != null ? tint : color(a.getOrDefault("fill", "#000000")));
			g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
			g.setComposite(AlphaComposite.SrcOver);
		}
		g.dispose();
		return img;
	}

	private static Map<String, String> attrs(String s)
	{
		Map<String, String> out = new HashMap<>();
		Matcher m = ATTR.matcher(s);
		while (m.find())
		{
			out.put(m.group(1), m.group(2));
		}
		return out;
	}

	private static Color color(String c)
	{
		if ("white".equals(c))
		{
			return Color.WHITE;
		}
		if ("black".equals(c))
		{
			return Color.BLACK;
		}
		return Color.decode(c);
	}

	private static Path2D path(String d)
	{
		Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO);
		Matcher m = TOKEN.matcher(d);
		String cmd = "M";
		double x = 0, y = 0;
		java.util.List<String> tokens = new java.util.ArrayList<>();
		while (m.find())
		{
			tokens.add(m.group());
		}
		for (int i = 0; i < tokens.size(); )
		{
			String t = tokens.get(i);
			if (t.matches("[A-Za-z]"))
			{
				cmd = t;
				i++;
				if (cmd.equalsIgnoreCase("Z"))
				{
					p.closePath();
				}
				continue;
			}
			switch (cmd)
			{
				case "M":
					x = Double.parseDouble(tokens.get(i++));
					y = Double.parseDouble(tokens.get(i++));
					p.moveTo(x, y);
					cmd = "L"; // subsequent pairs are line-tos
					break;
				case "L":
					x = Double.parseDouble(tokens.get(i++));
					y = Double.parseDouble(tokens.get(i++));
					p.lineTo(x, y);
					break;
				case "H":
					x = Double.parseDouble(tokens.get(i++));
					p.lineTo(x, y);
					break;
				case "V":
					y = Double.parseDouble(tokens.get(i++));
					p.lineTo(x, y);
					break;
				default:
					i++; // unsupported command in these icons; skip its argument
			}
		}
		return p;
	}

	private SvgIcon()
	{
	}
}
