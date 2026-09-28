package com.osrssync;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.List;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;

/** The account score's visuals: a segmented donut and a checkpoint track. */
final class ScoreChart
{
	/** Colours for the score's parts, keyed like the server's SCORE_PARTS. */
	static Color partColor(String key)
	{
		switch (key)
		{
			case "skills":
				return ChatComponents.ACCENT;
			case "combatAchievements":
				return new Color(0xC9483F);
			case "collectionLog":
				return new Color(0x8F6AD8);
			case "quests":
				return new Color(0x3F8FD6);
			case "diaries":
				return new Color(0x3FA33F);
			default:
				return ChatComponents.MUTED;
		}
	}

	static final class Segment
	{
		final double points;
		final Color color;

		Segment(double points, Color color)
		{
			this.points = points;
			this.color = color;
		}
	}

	/** Ring out of 100: one arc per part (sized by the points it earned), ticks at the checkpoints, the score in the middle. */
	static final class Donut extends JComponent
	{
		private final int size;
		private final float thickness;
		private final double score;
		private final List<Segment> segments;
		private final List<Integer> ticks;
		private int crestItemId;
		private Color crestColor;

		Donut(double score, List<Segment> segments, List<Integer> ticks)
		{
			this(112, score, segments, ticks);
		}

		Donut(int size, double score, List<Segment> segments, List<Integer> ticks)
		{
			this.size = size;
			this.thickness = size >= 100 ? 12f : 8f;
			this.score = score;
			this.segments = segments;
			this.ticks = ticks;
		}

		/** Show the tier's crest in the middle instead of the number. */
		Donut withCrest(int itemId, Color color)
		{
			this.crestItemId = itemId;
			this.crestColor = color;
			return this;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(size, size);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return getPreferredSize();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			double inset = thickness / 2 + 1;
			double d = size - inset * 2;
			double cx = size / 2.0, cy = size / 2.0;

			g2.setStroke(new BasicStroke(thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
			g2.setColor(ChatComponents.BASE_BG);
			g2.draw(new Ellipse2D.Double(inset, inset, d, d));

			// Clockwise from 12 o'clock
			double start = 90;
			for (Segment s : segments)
			{
				double extent = s.points / 100.0 * 360;
				if (extent <= 0)
				{
					continue;
				}
				g2.setColor(s.color);
				g2.draw(new Arc2D.Double(inset, inset, d, d, start, -extent, Arc2D.OPEN));
				start -= extent;
			}

			// Checkpoint ticks cut across the ring
			g2.setStroke(new BasicStroke(2f));
			g2.setColor(ChatComponents.PANEL_BG);
			double rIn = d / 2 - thickness / 2 - 1, rOut = d / 2 + thickness / 2 + 1;
			for (int at : ticks)
			{
				double a = Math.toRadians(90 - at / 100.0 * 360);
				g2.draw(new Line2D.Double(cx + Math.cos(a) * rIn, cy - Math.sin(a) * rIn, cx + Math.cos(a) * rOut, cy - Math.sin(a) * rOut));
			}

			if (crestColor != null)
			{
				int inner = (int) (d - thickness * 2);
				Crest.paint(g2, this, crestItemId, crestColor, (int) cx, (int) cy, inner * 3 / 4, inner * 3 / 4);
				g2.dispose();
				return;
			}
			String value = String.valueOf((int) Math.floor(score));
			boolean compact = size < 100;
			Font big = FontManager.getRunescapeBoldFont().deriveFont(compact ? 20f : 32f);
			Font small = FontManager.getRunescapeSmallFont();
			FontMetrics bm = g2.getFontMetrics(big);
			FontMetrics sm = g2.getFontMetrics(small);
			int total = compact ? bm.getAscent() - 4 : bm.getAscent() - 4 + sm.getAscent();
			int top = (int) (cy - total / 2.0);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g2.setFont(big);
			g2.setColor(Color.WHITE);
			g2.drawString(value, (int) (cx - bm.stringWidth(value) / 2.0), top + bm.getAscent() - 4);
			if (compact)
			{
				g2.dispose();
				return;
			}
			g2.setFont(small);
			g2.setColor(ChatComponents.MUTED);
			String of = "of 100";
			g2.drawString(of, (int) (cx - sm.stringWidth(of) / 2.0), top + bm.getAscent() - 4 + sm.getAscent());
			g2.dispose();
		}
	}

	static final class Checkpoint
	{
		final String label;
		final String tooltip;
		final boolean reached;

		Checkpoint(String label, String tooltip, boolean reached)
		{
			this.label = label;
			this.tooltip = tooltip;
			this.reached = reached;
		}
	}

	/** Evenly spaced stage nodes joined by a line; reached stages are filled, the line fills toward the next one. */
	static final class Track extends JComponent
	{
		private static final int NODE = 10;
		private final List<Checkpoint> checkpoints;
		private final double towardNext;

		/** towardNext: 0..1 progress from the current stage to the next. */
		Track(List<Checkpoint> checkpoints, double towardNext)
		{
			this.checkpoints = checkpoints;
			this.towardNext = Math.max(0, Math.min(1, towardNext));
			StringBuilder tip = new StringBuilder("<html>");
			for (Checkpoint c : checkpoints)
			{
				tip.append(c.reached ? "&#10003; " : "&nbsp;&nbsp;&nbsp;").append(c.tooltip).append("<br>");
			}
			setToolTipText(tip.append("</html>").toString());
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(200, NODE + 4 + getFontMetrics(FontManager.getRunescapeSmallFont()).getHeight());
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int n = checkpoints.size();
			Font font = FontManager.getRunescapeSmallFont();
			FontMetrics fm = g2.getFontMetrics(font);
			// Keep the end labels inside the component
			int margin = Math.max(fm.stringWidth(checkpoints.get(0).label), fm.stringWidth(checkpoints.get(n - 1).label)) / 2;
			margin = Math.max(margin, NODE / 2);
			double span = getWidth() - margin * 2;
			int cy = NODE / 2;
			int current = -1;
			for (int i = 0; i < n; i++)
			{
				if (checkpoints.get(i).reached)
				{
					current = i;
				}
			}

			g2.setStroke(new BasicStroke(2f));
			for (int i = 0; i < n - 1; i++)
			{
				double x1 = margin + span * i / (n - 1), x2 = margin + span * (i + 1) / (n - 1);
				g2.setColor(ChatComponents.BASE_BG);
				g2.draw(new Line2D.Double(x1, cy, x2, cy));
				double fill = i < current ? 1 : i == current ? towardNext : 0;
				if (fill > 0)
				{
					g2.setColor(ChatComponents.ACCENT);
					g2.draw(new Line2D.Double(x1, cy, x1 + (x2 - x1) * fill, cy));
				}
			}

			g2.setFont(font);
			for (int i = 0; i < n; i++)
			{
				Checkpoint c = checkpoints.get(i);
				double x = margin + span * i / (n - 1);
				java.awt.Shape node = Pixel.shape(x - NODE / 2.0, 0, NODE, NODE, NODE / 2.0);
				g2.setColor(c.reached ? ChatComponents.ACCENT : ChatComponents.BASE_BG);
				g2.fill(node);
				if (i == current)
				{
					g2.setColor(Color.WHITE);
					Pixel.draw(g2, x - NODE / 2.0, 0, NODE, NODE, NODE / 2.0);
					Pixel.draw(g2, x - NODE / 2.0 + 1, 1, NODE - 2, NODE - 2, NODE / 2.0 - 1);
				}
				else if (!c.reached)
				{
					g2.setColor(ChatComponents.BORDER);
					Pixel.draw(g2, x - NODE / 2.0, 0, NODE, NODE, NODE / 2.0);
				}
				g2.setColor(i == current ? Color.WHITE : c.reached ? ChatComponents.MUTED.brighter() : ChatComponents.MUTED);
				g2.drawString(c.label, (int) Math.round(x - fm.stringWidth(c.label) / 2.0), NODE + 4 + fm.getAscent());
			}
			g2.dispose();
		}
	}

	/** One rank tier for the ladder. */
	static final class Tier
	{
		final String name;
		final int at;
		final int itemId;
		final Color color;
		final boolean reached;

		Tier(String name, int at, int itemId, Color color, boolean reached)
		{
			this.name = name;
			this.at = at;
			this.itemId = itemId;
			this.color = color;
			this.reached = reached;
		}
	}

	/**
	 * Every tier's crest in a row, lowest to highest: reached tiers in full colour, the current one underlined in
	 * orange, the ones to come faded. Hover a crest for its name and score.
	 */
	static final class Ladder extends JComponent
	{
		private static final int CREST = 22;
		private final java.util.List<Tier> tiers;

		Ladder(java.util.List<Tier> tiers)
		{
			this.tiers = tiers;
			setToolTipText("");
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(200, CREST + 8);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, CREST + 8);
		}

		private double slot()
		{
			return getWidth() / (double) Math.max(1, tiers.size());
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int i = (int) (e.getX() / slot());
			if (i < 0 || i >= tiers.size())
			{
				return null;
			}
			Tier t = tiers.get(i);
			return t.name + " (" + t.at + "+)" + (t.reached ? "" : ", not reached yet");
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			double slot = slot();
			int current = -1;
			for (int i = 0; i < tiers.size(); i++)
			{
				if (tiers.get(i).reached)
				{
					current = i;
				}
			}
			for (int i = 0; i < tiers.size(); i++)
			{
				Tier t = tiers.get(i);
				int cx = (int) Math.round(slot * i + slot / 2);
				Graphics2D c = (Graphics2D) g2.create();
				if (!t.reached)
				{
					c.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.3f));
				}
				Crest.paint(c, this, t.itemId, t.color, cx, CREST / 2, CREST, CREST);
				c.dispose();
				if (i == current)
				{
					g2.setColor(ChatComponents.ACCENT);
					Pixel.fill(g2, cx - 7, CREST + 4, 14, 3, 1.5);
				}
			}
			g2.dispose();
		}
	}

	private ScoreChart()
	{
	}
}
