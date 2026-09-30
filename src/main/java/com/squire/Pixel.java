package com.squire;

import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;

/**
 * Pixel-art shapes: rectangles whose corners are cut in whole-pixel steps (like the game's own interface) instead
 * of smooth curves. Every "rounded" surface in the panel goes through here so the style stays consistent.
 */
final class Pixel
{
	private Pixel()
	{
	}

	/** How far each row is cut in from the side, from the top row down, for a corner of about this radius. */
	static int[] corner(int radius)
	{
		// Chunky 2px "art pixels", so the stair steps are visible next to the game font
		if (radius <= 0)
		{
			return new int[0];
		}
		if (radius <= 2)
		{
			return new int[]{1};
		}
		if (radius <= 5)
		{
			return new int[]{2, 2};
		}
		if (radius <= 9)
		{
			return new int[]{4, 4, 2, 2};
		}
		return new int[]{6, 6, 4, 4, 2, 2};
	}

	/** A w×h rectangle at (x, y) with stepped corners of about {@code radius}. */
	static Shape shape(double x, double y, double w, double h, double radius)
	{
		int ix = (int) Math.round(x), iy = (int) Math.round(y), iw = (int) Math.round(w), ih = (int) Math.round(h);
		int[] cut = corner((int) Math.min(Math.round(radius), Math.min(iw, ih) / 2));
		int n = Math.min(cut.length, ih / 2);
		Polygon p = new Polygon();
		// Down the right side, then back up the left: one vertical run and one horizontal step per row of the corner
		for (int i = 0; i < n; i++)
		{
			p.addPoint(ix + iw - cut[i], iy + i);
			p.addPoint(ix + iw - cut[i], iy + i + 1);
		}
		p.addPoint(ix + iw, iy + n);
		p.addPoint(ix + iw, iy + ih - n);
		for (int i = n - 1; i >= 0; i--)
		{
			p.addPoint(ix + iw - cut[i], iy + ih - i - 1);
			p.addPoint(ix + iw - cut[i], iy + ih - i);
		}
		for (int i = 0; i < n; i++)
		{
			p.addPoint(ix + cut[i], iy + ih - i);
			p.addPoint(ix + cut[i], iy + ih - i - 1);
		}
		p.addPoint(ix, iy + ih - n);
		p.addPoint(ix, iy + n);
		for (int i = n - 1; i >= 0; i--)
		{
			p.addPoint(ix + cut[i], iy + i + 1);
			p.addPoint(ix + cut[i], iy + i);
		}
		return p;
	}

	static void fill(Graphics2D g, double x, double y, double w, double h, double radius)
	{
		g.fill(shape(x, y, w, h, radius));
	}

	/** A 1px outline just inside the w×h box, following the same stepped corners pixel for pixel. */
	static void draw(Graphics2D g, double x, double y, double w, double h, double radius)
	{
		int ix = (int) Math.round(x), iy = (int) Math.round(y), iw = (int) Math.round(w), ih = (int) Math.round(h);
		if (iw <= 0 || ih <= 0)
		{
			return;
		}
		int[] cut = corner((int) Math.min(Math.round(radius), Math.min(iw, ih) / 2));
		int n = Math.min(cut.length, ih / 2);
		for (int r = 0; r < ih; r++)
		{
			int outer = cutAt(cut, n, ih, r);
			if (r == 0 || r == ih - 1)
			{
				g.fillRect(ix + outer, iy + r, iw - 2 * outer, 1);
				continue;
			}
			// The ring is as wide as the step between this row and its neighbours, so the corner stays connected
			int inner = Math.max(outer, Math.max(cutAt(cut, n, ih, r - 1), cutAt(cut, n, ih, r + 1))) + 1;
			int run = Math.min(inner - outer, iw / 2);
			g.fillRect(ix + outer, iy + r, run, 1);
			g.fillRect(ix + iw - outer - run, iy + r, run, 1);
		}
	}

	/**
	 * The game's bevel: a 1px ring inside the shape, light along the top and left and dark along the bottom and
	 * right (swap them for a pressed or sunken look). Corner steps take the colour of the edge they belong to.
	 */
	static void bevel(Graphics2D g, double x, double y, double w, double h, double radius, java.awt.Color light, java.awt.Color dark)
	{
		int ix = (int) Math.round(x), iy = (int) Math.round(y), iw = (int) Math.round(w), ih = (int) Math.round(h);
		if (iw <= 2 || ih <= 2)
		{
			return;
		}
		int[] cut = corner((int) Math.min(Math.round(radius), Math.min(iw, ih) / 2));
		int n = Math.min(cut.length, ih / 2);
		for (int r = 0; r < ih; r++)
		{
			int outer = cutAt(cut, n, ih, r);
			if (r == 0 || r == ih - 1)
			{
				g.setColor(r == 0 ? light : dark);
				g.fillRect(ix + outer, iy + r, iw - 2 * outer, 1);
				continue;
			}
			int inner = Math.max(outer, Math.max(cutAt(cut, n, ih, r - 1), cutAt(cut, n, ih, r + 1))) + 1;
			int run = Math.min(inner - outer, iw / 2);
			boolean topCorner = r < n, bottomCorner = r >= ih - n;
			// Left edge is light except the bottom-left steps (part of the bottom edge); right is dark except top-right
			g.setColor(bottomCorner ? dark : light);
			g.fillRect(ix + outer, iy + r, run, 1);
			g.setColor(topCorner ? light : dark);
			g.fillRect(ix + iw - outer - run, iy + r, run, 1);
		}
	}

	private static int cutAt(int[] cut, int n, int h, int r)
	{
		if (r < n)
		{
			return cut[r];
		}
		if (r >= h - n)
		{
			return cut[h - 1 - r];
		}
		return 0;
	}
}
