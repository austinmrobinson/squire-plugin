package com.osrssync;

import static com.osrssync.Ui.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrssync.ActivityCharts.Column;
import com.osrssync.ActivityCharts.Part;
import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.Bubble;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Activity page: what the account has been doing, by day, week or month.
 * A headline (total or daily average) with a stacked column chart, then the split by activity.
 */
class ActivityView extends JPanel
{
	private static final String[] RANGES = {"day", "week", "month"};
	private static final String[] RANGE_LABELS = {"Day", "Week", "Month"};

	private final AccountApi api;
	private final MessageList list = new MessageList(null, 0);
	private final ActivityCharts.Segmented picker;
	private final JLabel periodLabel = new JLabel("", SwingConstants.CENTER);
	private final JButton older = ChatComponents.iconButton("chevron-left", "Earlier");
	private final JButton newer = ChatComponents.iconButton("chevron-right", "Later");
	private final JPanel nav = new JPanel(new BorderLayout());
	private final ActivityCharts.Segmented metric;
	private int range = 1;
	private int offset;
	private int requestId;
	/** Show XP gained instead of time played. */
	private boolean xp;
	private JsonObject shown;

	ActivityView(AccountApi api)
	{
		this.api = api;
		setLayout(new BorderLayout());
		setOpaque(false);

		picker = new ActivityCharts.Segmented(RANGE_LABELS, range, i ->
		{
			range = i;
			offset = 0;
			refresh();
		});
		metric = new ActivityCharts.Segmented(new String[]{"Time", "XP"}, 0, new Dimension(88, 24), i ->
		{
			xp = i == 1;
			if (shown != null)
			{
				render(shown, null);
			}
		});
		periodLabel.setFont(FontManager.getRunescapeBoldFont());
		periodLabel.setForeground(Color.WHITE);
		older.addActionListener(e ->
		{
			offset++;
			refresh();
		});
		newer.addActionListener(e ->
		{
			offset = Math.max(0, offset - 1);
			refresh();
		});
		nav.setOpaque(false);
		nav.add(older, BorderLayout.WEST);
		nav.add(periodLabel, BorderLayout.CENTER);
		nav.add(newer, BorderLayout.EAST);
		nav.setPreferredSize(new Dimension(200, 32));

		// Room at the bottom to scroll clear of the floating composer
		list.setBorder(BorderFactory.createEmptyBorder(8, 8, FloatingAsk.CLEARANCE, 8));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		Surface card = new Surface(ChatComponents.CARD_BG, 8, true).border(ChatComponents.PANEL_BG);
		card.setLayout(new BorderLayout());
		card.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
		card.add(scroll, BorderLayout.CENTER);
		add(card, BorderLayout.CENTER);

		render(null, "Loading your activity...");
	}

	AccountApi api()
	{
		return api;
	}

	/** Reload the period on screen (e.g. when the page opens or after a sync). */
	void refresh()
	{
		int id = ++requestId;
		periodLabel.setText(periodLabel.getText().isEmpty() ? "..." : periodLabel.getText());
		api.activity(RANGES[range], offset, result -> SwingUtilities.invokeLater(() ->
		{
			if (id != requestId)
			{
				return; // a newer request is in flight
			}
			render(result.json, result.error);
		}));
	}

	/** Switch between time played and XP gained (previews; players use the toggle). */
	void showXp(boolean value)
	{
		xp = value;
		metric.setSelected(value ? 1 : 0);
		if (shown != null)
		{
			render(shown, null);
		}
	}

	void render(JsonObject a, String error)
	{
		shown = a;
		list.removeAll();
		list.add(ChatComponents.place(picker, Align.FILL, 0));
		list.add(ChatComponents.place(nav, Align.FILL, 8));
		if (a == null)
		{
			periodLabel.setText("");
			older.setEnabled(false);
			newer.setEnabled(false);
			list.add(ChatComponents.place(message(error), Align.FILL, 8));
			relayout();
			return;
		}
		int shownRange = java.util.Arrays.asList(RANGES).indexOf(str(a, "range"));
		if (shownRange >= 0)
		{
			range = shownRange;
			picker.setSelected(shownRange);
		}
		periodLabel.setText(str(a, "label"));
		older.setEnabled(a.get("hasOlder").getAsBoolean() || num(a, "totalMinutes") > 0);
		newer.setEnabled(a.get("hasNewer").getAsBoolean());

		// Colours follow the activities' rank for the whole period, so the chart and the list match
		List<String> names = new ArrayList<>();
		for (JsonElement e : a.getAsJsonArray("activities"))
		{
			names.add(str(e.getAsJsonObject(), "name"));
		}
		Map<String, Color> colors = ActivityCharts.colorMap(names);

		list.add(ChatComponents.place(chartCard(a, colors, names), Align.FILL, 8));
		if (num(a, "totalMinutes") > 0)
		{
			list.add(ChatComponents.place(Ui.withAsk(breakdownCard(a, colors), "Review my playtime " + (range == 0 ? "today" : "this " + RANGES[range])), Align.FILL, 8));
		}
		else if (offset == 0)
		{
			list.add(ChatComponents.place(message("Nothing tracked " + (range == 0 ? "today" : "this " + RANGES[range])
				+ " yet. Squire notes what you're doing each minute you play: the monster you're fighting, the skill you're training, or **Other**. "
				+ "Idle minutes aren't counted."), Align.FILL, 8));
		}
		relayout();
	}

	private JComponent chartCard(JsonObject a, Map<String, Color> colors, List<String> order)
	{
		Surface c = card();
		JsonObject headline = obj(a, "headline");
		String headlineLabel = str(headline, "label");
		if (xp)
		{
			headlineLabel = range == 0 ? "XP gained" : "Daily average XP";
		}
		c.add(row(small(headlineLabel), metric));
		c.add(Box.createVerticalStrut(2));
		JLabel value = bold(xp ? "+" + shortNumber(num(headline, "xp")) : duration(num(headline, "minutes")));
		value.setFont(FontManager.getRunescapeBoldFont().deriveFont(24f));
		JLabel change = null;
		String changeKey = xp ? "xpChangePct" : "changePct";
		if (headline.has(changeKey) && !headline.get(changeKey).isJsonNull())
		{
			int pct = (int) num(headline, changeKey);
			change = small((pct >= 0 ? "Up " : "Down ") + Math.abs(pct) + "% vs " + str(headline, "comparedTo"));
			change.setVerticalAlignment(SwingConstants.BOTTOM);
		}
		JPanel line = new JPanel(new BorderLayout(8, 0));
		line.setOpaque(false);
		line.add(value, BorderLayout.WEST);
		if (change != null)
		{
			JPanel wrap = new JPanel(new BorderLayout());
			wrap.setOpaque(false);
			wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
			wrap.add(change, BorderLayout.SOUTH);
			line.add(wrap, BorderLayout.CENTER);
		}
		line.setAlignmentX(LEFT_ALIGNMENT);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
		c.add(line);
		c.add(Box.createVerticalStrut(10));

		List<Column> columns = new ArrayList<>();
		for (JsonElement e : a.getAsJsonArray("buckets"))
		{
			JsonObject b = e.getAsJsonObject();
			List<Part> parts = new ArrayList<>();
			JsonArray segs = b.getAsJsonArray("segments");
			// Stack in rank order, biggest activity at the bottom
			for (String name : order)
			{
				for (JsonElement s : segs)
				{
					JsonObject seg = s.getAsJsonObject();
					if (str(seg, "name").equals(name))
					{
						double v = num(seg, xp ? "xp" : "minutes");
						if (v > 0)
						{
							parts.add(new Part(name, v, colors.getOrDefault(name, Ui.OTHER)));
						}
					}
				}
			}
			String title = columnTitle(a, b);
			columns.add(new Column(str(b, "label"), b.has("sub") && !b.get("sub").isJsonNull() ? str(b, "sub") : null,
				ActivityCharts.tooltip(title, parts, xp ? v -> fmt(v) + " XP" : Ui::duration), b.get("current").getAsBoolean(), b.get("future").getAsBoolean(), parts));
		}
		ActivityCharts.ColumnChart chart = new ActivityCharts.ColumnChart(columns, xp);
		chart.setAlignmentX(LEFT_ALIGNMENT);
		c.add(chart);
		return c;
	}

	private static String columnTitle(JsonObject a, JsonObject bucket)
	{
		String key = str(bucket, "key");
		if ("day".equals(str(a, "range")))
		{
			int h = Integer.parseInt(key);
			return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? "am" : "pm") + " - " + ((h + 1) % 12 == 0 ? 12 : (h + 1) % 12) + (h + 1 < 12 || h + 1 == 24 ? "am" : "pm");
		}
		try
		{
			java.time.LocalDate d = java.time.LocalDate.parse(key);
			return d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " "
				+ d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " " + d.getDayOfMonth();
		}
		catch (RuntimeException e)
		{
			return key;
		}
	}

	private JComponent breakdownCard(JsonObject a, Map<String, Color> colors)
	{
		Surface c = card();
		String valueKey = xp ? "xp" : "minutes";
		if (xp)
		{
			c.add(row(small(range == 0 ? "XP gained" : "XP gained, total"), bold("+" + fmt(num(a, "totalXp")))));
		}
		else
		{
			c.add(row(small(range == 0 ? "Time played" : "Time played, total"), bold(duration(num(a, "totalMinutes")))));
		}
		c.add(Box.createVerticalStrut(8));
		// XP view: only activities that gained XP, biggest first
		List<JsonObject> acts = new ArrayList<>();
		for (JsonElement e : a.getAsJsonArray("activities"))
		{
			JsonObject act = e.getAsJsonObject();
			if (!xp || num(act, "xp") > 0)
			{
				acts.add(act);
			}
		}
		if (xp)
		{
			acts.sort((x, y) -> Double.compare(num(y, "xp"), num(x, "xp")));
		}
		if (acts.isEmpty())
		{
			c.add(row(small("No XP gained in this period."), null));
			return c;
		}
		List<Part> parts = new ArrayList<>();
		for (JsonObject act : acts)
		{
			parts.add(new Part(str(act, "name"), num(act, valueKey), colors.getOrDefault(str(act, "name"), Ui.OTHER)));
		}
		ActivityCharts.ShareBar bar = new ActivityCharts.ShareBar(parts);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		c.add(bar);
		c.add(Box.createVerticalStrut(10));
		for (int i = 0; i < acts.size(); i++)
		{
			JsonObject act = acts.get(i);
			if (i > 0)
			{
				c.add(Box.createVerticalStrut(6));
			}
			JLabel name = text(str(act, "name"), ColorScheme.LIGHT_GRAY_COLOR);
			name.setIcon(swatch(colors.getOrDefault(str(act, "name"), Ui.OTHER)));
			name.setIconTextGap(8);
			// The other metric, on hover
			name.setToolTipText(xp ? duration(num(act, "minutes")) + " played" : num(act, "xp") > 0 ? fmt(num(act, "xp")) + " XP" : null);
			JLabel amount = text(xp ? "+" + shortNumber(num(act, "xp")) : duration(num(act, "minutes")), Color.WHITE);
			JLabel pct = small((int) num(act, xp ? "xpPct" : "pct") + "%");
			pct.setHorizontalAlignment(SwingConstants.RIGHT);
			pct.setPreferredSize(new Dimension(34, pct.getPreferredSize().height));
			JPanel right = new JPanel(new BorderLayout(0, 0));
			right.setOpaque(false);
			right.add(amount, BorderLayout.CENTER);
			right.add(pct, BorderLayout.EAST);
			c.add(row(name, right));
		}
		return c;
	}

	private static Bubble message(String markdown)
	{
		Bubble b = new Bubble(ChatComponents.PANEL_BG, ColorScheme.LIGHT_GRAY_COLOR, false, true);
		b.setHtml(MarkdownLite.toHtml(markdown == null ? "" : markdown));
		return b;
	}

	private void relayout()
	{
		list.revalidate();
		list.repaint();
	}
}
