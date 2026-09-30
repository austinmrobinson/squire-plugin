package com.squire;

import static com.squire.Ui.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.squire.ActivityCharts.Column;
import com.squire.ActivityCharts.Part;
import com.squire.ChatComponents.Align;
import com.squire.ChatComponents.MessageList;
import com.squire.ChatComponents.Surface;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

/**
 * The Gained page, after Wise Old Man's: pick a period and a metric (overall XP, a skill, a boss or activity) and see
 * where it started and ended, the value over time, the gain per day, and what only the plugin knows (time spent, kills
 * per hour, loot). Below, everything gained in the period; picking a row shows it above. History is backfilled from WOM.
 */
class GainedView extends JPanel
{
	private static final String[] PERIODS = {"day", "week", "month", "year"};
	private static final String[] PERIOD_LABELS = {"Day", "Week", "Month", "Year"};

	private final AccountApi api;
	private final MessageList list = new MessageList(null, 0);
	private final ActivityCharts.Segmented picker;
	private int period = 1;
	private String metric = "overall";
	private int requestId;
	private JsonObject detail;
	private JsonObject all;

	GainedView(AccountApi api)
	{
		this.api = api;
		setLayout(new BorderLayout());
		setOpaque(false);
		picker = new ActivityCharts.Segmented(PERIOD_LABELS, period, new Dimension(220, 32), i ->
		{
			period = i;
			refresh();
		});
		list.setBorder(BorderFactory.createEmptyBorder(8, 8, FloatingAsk.CLEARANCE, 8));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		render("Loading your gains...");
	}

	/** Choose the metric to show next time the page loads (e.g. a skill tapped on the Activity page). */
	void setMetric(String metricName)
	{
		metric = metricName == null ? "overall" : metricName;
	}

	/** Show a metric (e.g. from Home or a chat link), then load it. */
	void show(String metricName)
	{
		metric = metricName == null ? "overall" : metricName;
		refresh();
	}

	void refresh()
	{
		int id = ++requestId;
		String p = PERIODS[period];
		api.gained(p, metric, r -> SwingUtilities.invokeLater(() ->
		{
			if (id != requestId)
			{
				return;
			}
			detail = r.json;
			if (r.json == null)
			{
				render(r.error);
				return;
			}
			api.gained(p, null, r2 -> SwingUtilities.invokeLater(() ->
			{
				if (id != requestId)
				{
					return;
				}
				all = r2.json;
				render(null);
			}));
		}));
	}

	private void render(String message)
	{
		list.removeAll();
		list.add(ChatComponents.place(picker, Align.FILL, 0));
		if (detail == null || detail.has("error"))
		{
			String err = detail != null && detail.has("error") ? str(detail, "error") : message;
			list.add(ChatComponents.place(small(err == null ? "" : err), Align.FILL, 12));
			if (detail != null && !"overall".equals(metric))
			{
				list.add(ChatComponents.place(HomeView.listRow(null, "Show overall XP", null, () -> show("overall")), Align.FILL, 8));
			}
		}
		else
		{
			list.add(ChatComponents.place(Ui.withAsk(metricCard(detail), "How did my " + str(detail, "metric") + " go this " + PERIODS[period] + "?"), Align.FILL, 10));
		}
		if (all != null)
		{
			JComponent gains = allCard(all);
			if (gains != null)
			{
				list.add(ChatComponents.place(gains, Align.FILL, 10));
			}
		}
		list.revalidate();
		list.repaint();
	}

	// ---- The metric: header, value over time, gains per bucket, extras

	private JComponent metricCard(JsonObject d)
	{
		Surface c = HomeView.homeCard();
		boolean count = "count".equals(str(d, "unit"));
		String unit = count ? "" : " xp";
		JLabel title = bold(str(d, "metric"));
		title.setAlignmentX(LEFT_ALIGNMENT);
		c.add(title);
		c.add(Box.createVerticalStrut(4));
		double gained = num(d, "gained");
		JLabel big = new JLabel((d.get("gained").isJsonNull() ? "No data" : (gained > 0 ? "+" : "") + Ui.shortNumber(gained) + unit));
		big.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
		big.setForeground(gained > 0 ? PlanView.DONE : Color.WHITE);
		big.setAlignmentX(LEFT_ALIGNMENT);
		c.add(big);
		if (!d.get("startValue").isJsonNull())
		{
			String range = fmtValue(num(d, "startValue"), count) + "  ->  " + fmtValue(num(d, "endValue"), count);
			JLabel r = small(range);
			r.setAlignmentX(LEFT_ALIGNMENT);
			c.add(r);
		}
		if (d.has("partial") && d.get("partial").getAsBoolean())
		{
			JLabel note = small("History starts " + day(str(d, "historySince")) + ", so the gain may be higher");
			note.setForeground(ChatComponents.MUTED);
			note.setAlignmentX(LEFT_ALIGNMENT);
			c.add(note);
		}

		// Value over time
		JsonArray points = d.getAsJsonArray("points");
		List<Double> values = new ArrayList<>();
		for (JsonElement e : points)
		{
			JsonElement v = e.getAsJsonObject().get("value");
			values.add(v == null || v.isJsonNull() ? null : v.getAsDouble());
		}
		c.add(Box.createVerticalStrut(10));
		LineChart line = new LineChart(values, count);
		line.setAlignmentX(LEFT_ALIGNMENT);
		c.add(line);

		// Gains per bucket, as columns
		JsonArray buckets = d.getAsJsonArray("buckets");
		if (buckets.size() > 0)
		{
			c.add(Box.createVerticalStrut(12));
			JLabel per = small("hour".equals(str(d, "bucket")) ? "Gained per hour" : "week".equals(str(d, "bucket")) ? "Gained per week" : "Gained per day");
			per.setAlignmentX(LEFT_ALIGNMENT);
			c.add(per);
			c.add(Box.createVerticalStrut(4));
			List<Column> cols = new ArrayList<>();
			int n = buckets.size();
			int labelEvery = Math.max(1, n / 6);
			for (int i = 0; i < n; i++)
			{
				JsonObject b = buckets.get(i).getAsJsonObject();
				double g = num(b, "gained");
				String when = label(str(b, "t"), str(d, "bucket"));
				List<Part> parts = new ArrayList<>();
				if (g > 0)
				{
					parts.add(new Part(str(d, "metric"), g, ChatComponents.ACCENT));
				}
				cols.add(new Column(i % labelEvery == 0 || i == n - 1 ? when : "", null, when + ": +" + Ui.shortNumber(g) + unit, i == n - 1, false, parts));
			}
			ActivityCharts.ColumnChart chart = new ActivityCharts.ColumnChart(cols, !count, count);
			chart.setAlignmentX(LEFT_ALIGNMENT);
			c.add(chart);
		}

		// What only the plugin knows
		List<String[]> extras = new ArrayList<>();
		if (d.has("minutes") && !d.get("minutes").isJsonNull() && num(d, "minutes") > 0)
		{
			extras.add(new String[]{"Time spent", Ui.duration(num(d, "minutes"))});
		}
		if (d.has("perHour") && !d.get("perHour").isJsonNull())
		{
			extras.add(new String[]{"Per hour", String.valueOf(num(d, "perHour"))});
		}
		if (d.has("lootValue") && !d.get("lootValue").isJsonNull() && num(d, "lootValue") > 0)
		{
			extras.add(new String[]{"Loot", Ui.shortNumber(num(d, "lootValue")) + " gp"});
		}
		if (!extras.isEmpty())
		{
			c.add(Box.createVerticalStrut(10));
			for (String[] e : extras)
			{
				JPanel row = new JPanel(new BorderLayout());
				row.setOpaque(false);
				row.add(small(e[0]), BorderLayout.WEST);
				JLabel v = small(e[1]);
				v.setForeground(Color.WHITE);
				row.add(v, BorderLayout.EAST);
				row.setAlignmentX(LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
				c.add(row);
			}
		}
		return c;
	}

	// ---- Everything gained in the period

	private JComponent allCard(JsonObject a)
	{
		JsonArray skills = a.getAsJsonArray("skills");
		JsonArray counts = a.getAsJsonArray("counts");
		if ((skills == null || skills.size() == 0) && (counts == null || counts.size() == 0))
		{
			return null;
		}
		Surface c = HomeView.listCard();
		addRow(c, "Overall", "+" + Ui.shortNumber(num(a, "overallXp")) + " xp", "overall", true);
		if (counts != null)
		{
			for (JsonElement e : counts)
			{
				JsonObject r = e.getAsJsonObject();
				c.add(HomeView.divider());
				addRow(c, str(r, "metric"), "+" + Ui.shortNumber(num(r, "gained")), str(r, "metric"), false);
			}
		}
		if (skills != null)
		{
			for (JsonElement e : skills)
			{
				JsonObject r = e.getAsJsonObject();
				c.add(HomeView.divider());
				addRow(c, str(r, "metric"), "+" + Ui.shortNumber(num(r, "gained")) + " xp", str(r, "metric"), false);
			}
		}
		return c;
	}

	private void addRow(Surface c, String name, String value, String key, boolean first)
	{
		boolean selected = key.equalsIgnoreCase(metric) || key.equalsIgnoreCase(detail == null ? "" : str(detail, "metric"));
		JLabel v = text(value, selected ? Color.WHITE : PlanView.DONE);
		JComponent row = HomeView.listRow(null, name, v, () -> show(key));
		if (selected)
		{
			row.setToolTipText("Showing above");
		}
		c.add(row);
	}

	// ---- Formatting

	private static String fmtValue(double v, boolean count)
	{
		return count ? String.format(Locale.US, "%,d", (long) v) : Ui.shortNumber(v) + " xp";
	}

	private static String day(String iso)
	{
		try
		{
			return DateTimeFormatter.ofPattern("d MMM", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.parse(iso));
		}
		catch (RuntimeException e)
		{
			return iso;
		}
	}

	private static String label(String iso, String bucket)
	{
		try
		{
			String pattern = "hour".equals(bucket) ? "HH:mm" : "week".equals(bucket) ? "d MMM" : "d";
			return DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.systemDefault()).format(Instant.parse(iso));
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	/** The metric's value over the period: a stepped pixel line over a soft fill, with the axis on the right. */
	private static final class LineChart extends JComponent
	{
		private static final int HEIGHT = 80, AXIS = 34;
		private final List<Double> values;
		private final boolean count;

		LineChart(List<Double> values, boolean count)
		{
			this.values = values;
			this.count = count;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(200, HEIGHT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
			for (Double v : values)
			{
				if (v != null)
				{
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			}
			int w = getWidth() - AXIS, top = 4, bottom = HEIGHT - 6;
			g2.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g2.getFontMetrics();
			g2.setColor(ChatComponents.OUTLINE);
			g2.drawLine(0, top, w, top);
			g2.drawLine(0, bottom, w, bottom);
			if (min == Double.MAX_VALUE)
			{
				g2.setColor(ChatComponents.MUTED);
				g2.drawString("No history yet", 4, (top + bottom) / 2 + fm.getAscent() / 2);
				g2.dispose();
				return;
			}
			if (max == min)
			{
				max = min + 1;
			}
			g2.setColor(ChatComponents.MUTED);
			String hi = count ? String.format(Locale.US, "%,d", (long) max) : Ui.shortNumber(max);
			String lo = count ? String.format(Locale.US, "%,d", (long) min) : Ui.shortNumber(min);
			g2.drawString(hi, getWidth() - fm.stringWidth(hi), top + fm.getAscent());
			g2.drawString(lo, getWidth() - fm.stringWidth(lo), bottom);

			// Stepped line: flat until the value changes, then a vertical step (how a count actually moves)
			int n = values.size();
			int[] xs = new int[n];
			int[] ys = new int[n];
			Double last = null;
			for (int i = 0; i < n; i++)
			{
				Double v = values.get(i) != null ? values.get(i) : last;
				xs[i] = n == 1 ? w / 2 : (int) Math.round((double) i * (w - 1) / (n - 1));
				ys[i] = v == null ? -1 : (int) Math.round(bottom - (v - min) / (max - min) * (bottom - top - 2) - 1);
				last = v;
			}
			// Soft fill under the line
			g2.setColor(new Color(ChatComponents.ACCENT.getRed(), ChatComponents.ACCENT.getGreen(), ChatComponents.ACCENT.getBlue(), 40));
			for (int i = 0; i < n - 1; i++)
			{
				if (ys[i] >= 0)
				{
					g2.fillRect(xs[i], ys[i], xs[i + 1] - xs[i], bottom - ys[i]);
				}
			}
			g2.setColor(ChatComponents.ACCENT);
			g2.setStroke(new BasicStroke(2f));
			for (int i = 0; i < n - 1; i++)
			{
				if (ys[i] < 0)
				{
					continue;
				}
				g2.fillRect(xs[i], ys[i] - 1, xs[i + 1] - xs[i] + 1, 2);
				if (ys[i + 1] >= 0 && ys[i + 1] != ys[i])
				{
					int y0 = Math.min(ys[i], ys[i + 1]), y1 = Math.max(ys[i], ys[i + 1]);
					g2.fillRect(xs[i + 1] - 1, y0 - 1, 2, y1 - y0 + 2);
				}
			}
			// The latest value as a pixel dot
			if (ys[n - 1] >= 0)
			{
				g2.setColor(Color.WHITE);
				g2.fillRect(xs[n - 1] - 2, ys[n - 1] - 3, 4, 4);
			}
			g2.dispose();
		}
	}
}
