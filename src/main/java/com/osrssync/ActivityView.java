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
	/** Set by the sidebar: open the Gained page on a metric (a skill or boss tapped here). */
	static java.util.function.Consumer<String> openGained = m -> {};

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

		if (a.has("totals"))
		{
			list.add(ChatComponents.place(tiles(a), Align.FILL, 8));
		}
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
		String when = range == 0 ? "today" : "this " + RANGES[range];
		addIf(skillsCard(a), "Which skills did I train most " + when + "?");
		addIf(killsCard(a), "How did my bossing go " + when + "?");
		addIf(lootCard(a), "What was my best loot " + when + "?");
		addIf(sessionsCard(a), "Summarise my play sessions " + when);
		addIf(milestonesCard(a), null);
		list.add(ChatComponents.place(HomeView.listRow(SvgIcon.load("progress", 16, null), "Gains over a month or year", null, () -> openGained.accept("overall")), Align.FILL, 8));
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
			c.add(row(small(range == 0 ? "XP gained" : "XP gained, total"), clearOfAsk(bold("+" + fmt(num(a, "totalXp"))))));
		}
		else
		{
			c.add(row(small(range == 0 ? "Time played" : "Time played, total"), clearOfAsk(bold(duration(num(a, "totalMinutes"))))));
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

	private void addIf(JComponent card, String ask)
	{
		if (card != null)
		{
			list.add(ChatComponents.place(ask == null ? card : Ui.withAsk(card, ask), Align.FILL, 8));
		}
	}

	// ---- Tiles: time, XP, kills, loot, each against the period before

	private JComponent tiles(JsonObject a)
	{
		JsonObject h = obj(a, "headline");
		JsonObject t = obj(a, "totals");
		JPanel grid = new JPanel(new java.awt.GridLayout(2, 2, 6, 6));
		grid.setOpaque(false);
		grid.add(new ActivityCharts.Tile("Time played", duration(num(a, "totalMinutes")), pct(h, "changePct")));
		// XP from the skill snapshots when there are any (what the XP by skill card adds up), else from activity minutes
		double xpTotal = 0;
		JsonArray skills = a.getAsJsonArray("skills");
		if (skills != null)
		{
			for (JsonElement e : skills)
			{
				xpTotal += num(e.getAsJsonObject(), "xp");
			}
		}
		grid.add(new ActivityCharts.Tile("XP gained", "+" + shortNumber(xpTotal > 0 ? xpTotal : num(a, "totalXp")), pct(h, "xpChangePct")));
		grid.add(new ActivityCharts.Tile("Kills", fmt(num(t, "kills")), pct(t, "killsChangePct")));
		grid.add(new ActivityCharts.Tile("Loot", shortNumber(num(t, "loot")) + " gp", pct(t, "lootChangePct")));
		return grid;
	}

	private static Integer pct(JsonObject o, String key)
	{
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? (int) num(o, key) : null;
	}

	private static JComponent header(Surface c, String title, String right)
	{
		c.add(row(small(title), right == null ? null : clearOfAsk(bold(right))));
		c.add(Box.createVerticalStrut(8));
		return c;
	}

	/** Keep a header value clear of the ask-Squire button in the card's top-right corner. */
	private static JComponent clearOfAsk(JLabel value)
	{
		value.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 26));
		return value;
	}

	/** A bar that opens the Gained page on its metric. */
	private static JComponent tappable(ActivityCharts.HBar bar, String metric)
	{
		bar.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		bar.setToolTipText("See " + metric + " over time");
		bar.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.getComponent().contains(e.getPoint()))
				{
					openGained.accept(metric);
				}
			}
		});
		bar.setAlignmentX(LEFT_ALIGNMENT);
		return bar;
	}

	// ---- XP by skill

	private JComponent skillsCard(JsonObject a)
	{
		JsonArray skills = a.getAsJsonArray("skills");
		if (skills == null || skills.size() == 0)
		{
			return null;
		}
		Surface c = card();
		double total = 0, top = num(skills.get(0).getAsJsonObject(), "xp");
		for (JsonElement e : skills)
		{
			total += num(e.getAsJsonObject(), "xp");
		}
		header(c, "XP by skill", "+" + shortNumber(total));
		for (int i = 0; i < Math.min(8, skills.size()); i++)
		{
			JsonObject sk = skills.get(i).getAsJsonObject();
			String name = str(sk, "skill");
			c.add(tappable(new ActivityCharts.HBar(name, "+" + shortNumber(num(sk, "xp")), "Level " + (int) num(sk, "level"),
				num(sk, "xp") / top, Ui.activityColor(i, name), null), name));
			c.add(Box.createVerticalStrut(6));
		}
		return c;
	}

	// ---- Kills by boss, with rates and kill times from the kill log

	private JComponent killsCard(JsonObject a)
	{
		JsonArray kills = a.getAsJsonArray("kills");
		if (kills == null || kills.size() == 0)
		{
			return null;
		}
		Surface c = card();
		double top = num(kills.get(0).getAsJsonObject(), "kills");
		header(c, "Kills", fmt(num(obj(a, "totals"), "kills")));
		for (JsonElement e : kills)
		{
			JsonObject k = e.getAsJsonObject();
			List<String> bits = new ArrayList<>();
			if (k.has("perHour") && !k.get("perHour").isJsonNull())
			{
				bits.add(num(k, "perHour") + "/hr");
			}
			if (k.has("avgSeconds") && !k.get("avgSeconds").isJsonNull())
			{
				bits.add("avg " + clock(num(k, "avgSeconds")));
			}
			if (k.has("bestSeconds") && !k.get("bestSeconds").isJsonNull())
			{
				bits.add("best " + clock(num(k, "bestSeconds")));
			}
			if (num(k, "minutes") > 0)
			{
				bits.add(duration(num(k, "minutes")));
			}
			c.add(tappable(new ActivityCharts.HBar(str(k, "boss"), fmt(num(k, "kills")), bits.isEmpty() ? null : String.join("  ·  ", bits),
				num(k, "kills") / top, ChatComponents.ACCENT, null), str(k, "boss")));
			c.add(Box.createVerticalStrut(6));
		}
		return c;
	}

	private static String clock(double seconds)
	{
		int s = (int) Math.round(seconds);
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	// ---- Loot: by source, and the best drops

	private static final Color GOLD = new Color(0xC8A24A);

	private JComponent lootCard(JsonObject a)
	{
		JsonObject loot = obj(a, "loot");
		if (loot == null || num(loot, "value") <= 0)
		{
			return null;
		}
		Surface c = card();
		header(c, "Loot  ·  " + fmt(num(loot, "drops")) + " drops", shortNumber(num(loot, "value")) + " gp");
		JsonArray sources = loot.getAsJsonArray("sources");
		double top = sources.size() > 0 ? num(sources.get(0).getAsJsonObject(), "value") : 1;
		for (JsonElement e : sources)
		{
			JsonObject src = e.getAsJsonObject();
			ActivityCharts.HBar bar = new ActivityCharts.HBar(str(src, "source"), shortNumber(num(src, "value")) + " gp",
				fmt(num(src, "drops")) + " drops", num(src, "value") / Math.max(1, top), GOLD, null);
			bar.setAlignmentX(LEFT_ALIGNMENT);
			c.add(bar);
			c.add(Box.createVerticalStrut(6));
		}
		JsonArray items = loot.getAsJsonArray("items");
		if (items.size() > 0)
		{
			c.add(Box.createVerticalStrut(4));
			c.add(row(small("Best drops"), null));
			c.add(Box.createVerticalStrut(4));
			for (JsonElement e : items)
			{
				JsonObject it = e.getAsJsonObject();
				JLabel name = text((num(it, "quantity") > 1 ? fmt(num(it, "quantity")) + " x " : "") + str(it, "name"), ColorScheme.LIGHT_GRAY_COLOR);
				java.awt.image.BufferedImage img = Crest.itemImage((int) num(it, "id"), name);
				if (img != null)
				{
					name.setIcon(new javax.swing.ImageIcon(img.getScaledInstance(img.getWidth() * 2 / 3, img.getHeight() * 2 / 3, java.awt.Image.SCALE_FAST)));
					name.setIconTextGap(6);
				}
				c.add(row(name, text(shortNumber(num(it, "value")), Color.WHITE)));
			}
		}
		return c;
	}

	// ---- Play sessions (runs of active minutes, split by breaks of 15+ minutes)

	private JComponent sessionsCard(JsonObject a)
	{
		JsonArray sessions = a.getAsJsonArray("sessions");
		if (sessions == null || sessions.size() == 0)
		{
			return null;
		}
		Surface c = card();
		header(c, "Sessions", sessions.size() + (sessions.size() == 1 ? " session" : " sessions"));
		double longest = 1;
		for (JsonElement e : sessions)
		{
			longest = Math.max(longest, num(e.getAsJsonObject(), "minutes"));
		}
		java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern(range == 0 ? "h:mma" : "EEE h:mma", java.util.Locale.US)
			.withZone(java.time.ZoneId.systemDefault());
		for (JsonElement e : sessions)
		{
			JsonObject ses = e.getAsJsonObject();
			String start;
			try
			{
				start = f.format(java.time.Instant.parse(str(ses, "start"))).toLowerCase(java.util.Locale.US).replace("am", "am").replace("pm", "pm");
				start = Character.toUpperCase(start.charAt(0)) + start.substring(1);
			}
			catch (RuntimeException ex)
			{
				start = "";
			}
			List<String> bits = new ArrayList<>();
			bits.add(str(ses, "main"));
			if (num(ses, "xp") > 0)
			{
				bits.add("+" + shortNumber(num(ses, "xp")) + " xp");
			}
			if (num(ses, "kills") > 0)
			{
				bits.add(fmt(num(ses, "kills")) + " kills");
			}
			if (num(ses, "loot") > 0)
			{
				bits.add(shortNumber(num(ses, "loot")) + " gp");
			}
			ActivityCharts.HBar bar = new ActivityCharts.HBar(start, duration(num(ses, "minutes")), String.join("  ·  ", bits),
				num(ses, "minutes") / longest, Ui.activityColor(0, str(ses, "main")), null);
			bar.setAlignmentX(LEFT_ALIGNMENT);
			c.add(bar);
			c.add(Box.createVerticalStrut(6));
		}
		return c;
	}

	// ---- Milestones: levels, collection log, pets, combat achievements, quests, deaths

	private JComponent milestonesCard(JsonObject a)
	{
		JsonArray ms = a.getAsJsonArray("milestones");
		if (ms == null || ms.size() == 0)
		{
			return null;
		}
		Surface c = card();
		header(c, "Milestones", String.valueOf(ms.size()));
		for (JsonElement e : ms)
		{
			JsonObject m = e.getAsJsonObject();
			JLabel label = text(str(m, "summary"), ColorScheme.LIGHT_GRAY_COLOR);
			label.setIcon(swatch(milestoneColor(str(m, "type"))));
			label.setIconTextGap(8);
			label.setToolTipText(str(m, "type").replace('_', ' '));
			c.add(row(label, null));
			c.add(Box.createVerticalStrut(4));
		}
		return c;
	}

	private static Color milestoneColor(String type)
	{
		switch (type)
		{
			case "level_up":
				return new Color(0x5FBF6A);
			case "collection_log":
				return new Color(0xB072E0);
			case "pet":
				return new Color(0xE07AB6);
			case "combat_achievement":
				return new Color(0xE0A34A);
			case "quest_complete":
				return ChatComponents.ACCENT;
			case "death":
				return new Color(0xE06A5A);
			case "personal_best":
				return new Color(0x4FC3D9);
			default:
				return ChatComponents.MUTED;
		}
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
