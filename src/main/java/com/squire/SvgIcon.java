package com.squire;

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
 * Renders the plugin's SVG icons without an SVG library: our own (exported from the Figma design) and Pixelarticons
 * (pixelarticons.com, MIT). Supports what those use: paths with M/L/H/V/Z commands (absolute or relative), circles,
 * fill colours ("currentColor" draws in the default icon grey) and opacity.
 */
final class SvgIcon
{
	private static final Pattern VIEWBOX = Pattern.compile("viewBox=\"([\\d.\\s-]+)\"");
	private static final Pattern PATH = Pattern.compile("<path([^>]*)/?>");
	/** The grey our icons are drawn in when they don't say (Pixelarticons use currentColor). */
	private static final Color DEFAULT = Tokens.COLOR_TEXT_SECONDARY;
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

		java.util.List<Path2D> paths = new java.util.ArrayList<>();
		java.util.List<Color> colors = new java.util.ArrayList<>();
		Matcher pm = PATH.matcher(svg);
		while (pm.find())
		{
			Map<String, String> a = attrs(pm.group(1));
			if (a.containsKey("d"))
			{
				paths.add(path(a.get("d")));
				colors.add(tint != null ? tint : color(a.getOrDefault("fill", "currentColor")));
			}
		}
		// Pixelarticons draw 2-unit pixels on a 24 grid, some starting on odd units: shift those a unit so every
		// pixel lands on the 12-cell grid our 16px icons use, keeping edges as sharp as our own icons.
		if (vbW == 24 && vbH == 24)
		{
			g.translate(snap(paths, true), snap(paths, false));
		}
		for (int i = 0; i < paths.size(); i++)
		{
			g.setColor(colors.get(i));
			g.fill(paths.get(i));
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

	/** -1 or +1 when every vertex sits on an odd coordinate along this axis, else 0. */
	private static int snap(java.util.List<Path2D> paths, boolean horizontal)
	{
		double min = Double.MAX_VALUE;
		for (Path2D p : paths)
		{
			double[] c = new double[6];
			for (java.awt.geom.PathIterator it = p.getPathIterator(null); !it.isDone(); it.next())
			{
				if (it.currentSegment(c) == java.awt.geom.PathIterator.SEG_CLOSE)
				{
					continue;
				}
				double v = horizontal ? c[0] : c[1];
				if (v != Math.floor(v) || ((long) v) % 2 == 0)
				{
					return 0;
				}
				min = Math.min(min, v);
			}
		}
		return min == Double.MAX_VALUE ? 0 : min >= 1 ? -1 : 1;
	}

	private static Color color(String c)
	{
		if (c == null || "currentColor".equals(c) || "none".equals(c))
		{
			return DEFAULT;
		}
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
		double x = 0, y = 0, startX = 0, startY = 0;
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
					x = startX;
					y = startY;
				}
				continue;
			}
			// Lower-case commands are relative to the current point
			boolean rel = Character.isLowerCase(cmd.charAt(0));
			switch (cmd.toUpperCase())
			{
				case "M":
					x = (rel ? x : 0) + Double.parseDouble(tokens.get(i++));
					y = (rel ? y : 0) + Double.parseDouble(tokens.get(i++));
					p.moveTo(x, y);
					startX = x;
					startY = y;
					cmd = rel ? "l" : "L"; // subsequent pairs are line-tos
					break;
				case "L":
					x = (rel ? x : 0) + Double.parseDouble(tokens.get(i++));
					y = (rel ? y : 0) + Double.parseDouble(tokens.get(i++));
					p.lineTo(x, y);
					break;
				case "H":
					x = (rel ? x : 0) + Double.parseDouble(tokens.get(i++));
					p.lineTo(x, y);
					break;
				case "V":
					y = (rel ? y : 0) + Double.parseDouble(tokens.get(i++));
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
