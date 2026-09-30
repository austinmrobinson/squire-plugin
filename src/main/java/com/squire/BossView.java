package com.squire;

import static com.squire.Ui.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.squire.ChatComponents.Align;
import com.squire.ChatComponents.MessageList;
import com.squire.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

/**
 * One boss (or raid, minigame, clue tier) over time, opened from the kill counts: its picture and count, the count over a
 * period with kills per day, time spent and kills per hour (the Gained page's card), personal bests and the setups they
 * were set in, and loot. Data from /api/progress?boss=.
 */
class BossView extends JPanel
{
	private static final String[] PERIODS = {"week", "month", "year", "all"};
	private static final String[] PERIOD_LABELS = {"Week", "Month", "Year", "All"};

	private final AccountApi api;
	private final WikiImages images;
	private final MessageList list = new MessageList(null, 0);
	private final ActivityCharts.Segmented picker;
	private int period = 1;
	private String boss;
	private JsonObject shown;
	private int requestId;

	BossView(AccountApi api, WikiImages images)
	{
		this.api = api;
		this.images = images;
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
	}

	String pageTitle()
	{
		return boss == null ? "Boss" : boss;
	}

	void show(String name)
	{
		boss = name;
		shown = null;
		message("Loading " + name + "...");
		refresh();
	}

	/** For previews: show a boss from data instead of loading it. */
	void showData(JsonObject data)
	{
		boss = str(data, "boss");
		shown = data;
		render(data);
	}

	void refresh()
	{
		if (boss == null)
		{
			return;
		}
		int id = ++requestId;
		api.boss(boss, PERIODS[period], r -> SwingUtilities.invokeLater(() ->
		{
			if (id != requestId)
			{
				return;
			}
			if (r.json == null || r.json.has("error"))
			{
				message(r.json != null ? str(r.json, "error") : r.error);
				return;
			}
			shown = r.json;
			render(r.json);
		}));
	}

	/** This boss's numbers, as context for a question asked from this page. */
	Attachment contextAttachment()
	{
		if (shown == null)
		{
			return null;
		}
		StringBuilder md = new StringBuilder("# ").append(str(shown, "boss")).append("\n\n");
		md.append("Kill count: ").append(fmt(num(shown, "count"))).append("\n");
		for (JsonElement e : shown.getAsJsonArray("personalBests"))
		{
			JsonObject p = e.getAsJsonObject();
			if (p.has("personal_best") && !p.get("personal_best").isJsonNull())
			{
				md.append("PB (").append(str(p, "activity")).append("): ").append(ProgressDetailView.clock(num(p, "personal_best"))).append("\n");
			}
		}
		JsonObject g = obj(shown, "gains");
		if (g != null && !g.has("error") && g.has("gained") && !g.get("gained").isJsonNull())
		{
			md.append(period == 3 ? "Kills, all time" : "Kills in the last " + PERIODS[period]).append(": ").append(fmt(num(g, "gained"))).append("\n");
		}
		return Attachment.context(str(shown, "boss"), str(shown, "boss"), md.toString(), SquireIcon.create(26));
	}

	private void message(String text)
	{
		list.removeAll();
		list.add(ChatComponents.place(small(text == null ? "" : text), Align.FILL, 12));
		relayout();
	}

	private void render(JsonObject o)
	{
		list.removeAll();
		String name = str(o, "boss");
		list.add(ChatComponents.place(Ui.withAsk(headerCard(o), "How can I get faster, more consistent kills at " + name + "?"), Align.FILL, 0));
		list.add(ChatComponents.place(picker, Align.FILL, 8));
		JsonObject gains = obj(o, "gains");
		if (gains != null && !gains.has("error"))
		{
			list.add(ChatComponents.place(GainedView.metricCard(gains), Align.FILL, 8));
		}
		JComponent pbs = personalBestsCard(o.getAsJsonArray("personalBests"));
		if (pbs != null)
		{
			list.add(ChatComponents.place(Ui.withAsk(pbs, "What's holding back my " + name + " times?"), Align.FILL, 8));
		}
		JComponent loot = lootCard(obj(o, "loot"));
		if (loot != null)
		{
			list.add(ChatComponents.place(Ui.withAsk(loot, "Am I dry at " + name + "?"), Align.FILL, 8));
		}
		relayout();
	}

	private JComponent headerCard(JsonObject o)
	{
		Surface c = HomeView.homeCard();
		JPanel top = new JPanel(new BorderLayout(12, 0));
		top.setOpaque(false);
		ProgressView.BossPicture pic = new ProgressView.BossPicture();
		if (images != null && o.has("image") && !o.get("image").isJsonNull())
		{
			images.load(str(o, "image"), pic::setImage);
		}
		top.add(pic, BorderLayout.WEST);
		JPanel side = new JPanel();
		side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
		side.setOpaque(false);
		JLabel count = bold(fmt(num(o, "count")));
		count.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
		side.add(row(count, null));
		side.add(row(text("kills · " + ProgressDetailView.categoryLabel(str(o, "category")), ChatComponents.MUTED), null));
		if (o.has("lastKill") && !o.get("lastKill").isJsonNull())
		{
			side.add(row(small("Last kill " + ago(str(o, "lastKill"))), null));
		}
		top.add(side, BorderLayout.CENTER);
		top.setAlignmentX(LEFT_ALIGNMENT);
		top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
		c.add(top);
		return c;
	}

	/** Each PB (per team size for raids): the time, recent average and gap, how it improved, and the fastest setups. */
	private JComponent personalBestsCard(JsonArray pbs)
	{
		if (pbs == null || pbs.size() == 0)
		{
			return null;
		}
		Surface c = HomeView.listCard();
		for (JsonElement e : pbs)
		{
			JsonObject p = e.getAsJsonObject();
			if (!p.has("personal_best") || p.get("personal_best").isJsonNull())
			{
				continue;
			}
			JPanel head = new JPanel();
			head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
			head.setOpaque(false);
			head.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
			head.add(row(bold("Personal best" + (pbs.size() > 1 ? ": " + str(p, "activity") : "")), null));
			head.add(Box.createVerticalStrut(6));
			JLabel time = bold(ProgressDetailView.clock(num(p, "personal_best")));
			time.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
			time.setForeground(ChatComponents.ACCENT);
			JLabel avg = null;
			if (p.has("recent_average_seconds") && !p.get("recent_average_seconds").isJsonNull())
			{
				avg = bold(ProgressDetailView.clock(num(p, "recent_average_seconds")));
				avg.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
			}
			head.add(row(time, avg));
			String gap = p.has("recent_gap_to_pb_pct") && !p.get("recent_gap_to_pb_pct").isJsonNull() ? " (+" + (int) num(p, "recent_gap_to_pb_pct") + "%)" : "";
			head.add(row(text("personal best", ChatComponents.MUTED), avg == null ? null : text("recent average" + gap, ChatComponents.MUTED)));
			head.setAlignmentX(LEFT_ALIGNMENT);
			c.add(head);

			JsonArray history = p.getAsJsonArray("history");
			// Newest improvements first
			for (int i = history.size() - 1, shownRows = 0; i >= 0 && shownRows < 5; i--, shownRows++)
			{
				JsonObject h = history.get(i).getAsJsonObject();
				c.add(HomeView.divider());
				JComponent r = HomeView.listRow(HomeView.dotIcon(i == history.size() - 1 ? ChatComponents.ACCENT : ChatComponents.BORDER),
					str(h, "time"), small(ago(str(h, "ts"))), null);
				JsonElement gear = h.get("gear");
				if (gear != null && gear.isJsonArray())
				{
					List<String> names = new java.util.ArrayList<>();
					gear.getAsJsonArray().forEach(g -> names.add(g.getAsString()));
					r.setToolTipText("Wearing " + String.join(", ", names));
				}
				c.add(r);
			}
			JsonArray setups = p.getAsJsonArray("by_setup");
			if (setups != null && setups.size() > 1)
			{
				c.add(HomeView.divider());
				c.add(HomeView.listRow(null, "Fastest by setup", null, null));
				for (JsonElement s : setups)
				{
					JsonObject x = s.getAsJsonObject();
					c.add(HomeView.divider());
					String gearNames = str(x, "gear");
					String label = gearNames.length() > 36 ? gearNames.substring(0, 35) + "…" : gearNames;
					JComponent r = HomeView.listRow(null, label, text(ProgressDetailView.clock(num(x, "best_seconds")) + " · " + fmt(num(x, "kills")) + " kills", Color.WHITE), null);
					r.setToolTipText(gearNames);
					c.add(r);
				}
			}
		}
		return c.getComponentCount() == 0 ? null : c;
	}

	private JComponent lootCard(JsonObject loot)
	{
		if (loot == null || num(loot, "drops") == 0)
		{
			return null;
		}
		Surface c = HomeView.listCard();
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
		head.setOpaque(false);
		head.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		head.add(row(bold("Loot"), null));
		head.add(Box.createVerticalStrut(6));
		JLabel value = bold(shortNumber(num(loot, "value")) + " gp");
		value.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		head.add(row(value, null));
		head.add(row(text("from " + fmt(num(loot, "drops")) + " drops", ChatComponents.MUTED), null));
		head.setAlignmentX(LEFT_ALIGNMENT);
		c.add(head);
		for (JsonElement e : loot.getAsJsonArray("items"))
		{
			JsonObject item = e.getAsJsonObject();
			BufferedImage img = Crest.itemImage((int) num(item, "id"), this);
			c.add(HomeView.divider());
			c.add(HomeView.listRow(img == null ? null : new ImageIcon(img), str(item, "name") + " x" + fmt(num(item, "quantity")),
				text(shortNumber(num(item, "value")), Color.WHITE), null));
		}
		return c;
	}

	private void relayout()
	{
		list.revalidate();
		list.repaint();
	}
}
