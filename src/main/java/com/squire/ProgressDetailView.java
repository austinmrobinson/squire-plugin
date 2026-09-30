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
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
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
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * A Progress detail page, opened from its summary card: every skill, quests by state, combat achievements with the
 * tier being worked on, diaries by area, recent collection log slots, every kill count (each opens the boss's page),
 * or the fun stats. Data from /api/progress?view=.
 */
class ProgressDetailView extends JPanel
{
	private static final Color DONE = new Color(0x3FA33F);

	private final AccountApi api;
	private final ProgressView progress;
	private final Consumer<String> openBoss;
	private final MessageList list = new MessageList(null, 0);
	private String view;
	private String title = "Progress";
	private int requestId;

	ProgressDetailView(AccountApi api, ProgressView progress, Consumer<String> openBoss)
	{
		this.api = api;
		this.progress = progress;
		this.openBoss = openBoss;
		setLayout(new BorderLayout());
		setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, FloatingAsk.CLEARANCE, 1));
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
		return title;
	}

	String view()
	{
		return view;
	}

	/** Show a detail page (view id and title), then load it. */
	void show(String view, String title)
	{
		this.view = view;
		this.title = title;
		message("Loading...");
		refresh();
	}

	/** For previews: show a page from data instead of loading it. */
	void showData(String view, String title, JsonObject data)
	{
		this.view = view;
		this.title = title;
		render(view, data);
	}

	void refresh()
	{
		if (view == null)
		{
			return;
		}
		int id = ++requestId;
		String v = view;
		api.progress(v, r -> SwingUtilities.invokeLater(() ->
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
			render(v, r.json);
		}));
	}

	private void message(String text)
	{
		list.removeAll();
		list.add(ChatComponents.place(small(text == null ? "" : text), Align.FILL, 12));
		relayout();
	}

	private void render(String v, JsonObject o)
	{
		list.removeAll();
		switch (v)
		{
			case "skills":
				skills(o);
				break;
			case "quests":
				quests(o);
				break;
			case "combat-achievements":
				combatAchievements(o);
				break;
			case "diaries":
				diaries(o);
				break;
			case "collection-log":
				collectionLog(o);
				break;
			case "kill-counts":
				killCounts(o);
				break;
			case "stats":
				stats(o);
				break;
			default:
				break;
		}
		relayout();
	}

	private int gap()
	{
		return list.getComponentCount() == 0 ? 0 : 4;
	}

	private void addCard(JComponent card, String ask)
	{
		list.add(ChatComponents.place(ask == null ? card : Ui.withAsk(card, ask), Align.FILL, gap()));
	}

	// ---- Pages

	private void skills(JsonObject o)
	{
		JsonArray skills = o.getAsJsonArray("skills");
		Surface head = HomeView.homeCard();
		head.add(bigNumbers(fmt(num(o, "totalLevel")) + " / " + fmt(num(o, "maxTotalLevel")), "total level",
			shortNumber(num(o, "totalXp")), "total XP"));
		addCard(head, null);

		Surface rows = HomeView.listCard();
		rows.add(sectionLabelRow("XP to next level, and this week"));
		for (JsonElement e : skills)
		{
			JsonObject s = e.getAsJsonObject();
			String name = str(s, "skill");
			int level = (int) num(s, "level");
			String right = level >= 99 ? "Maxed" : shortNumber(num(s, "xpToNext")) + " to " + (level + 1);
			if (num(s, "week") > 0)
			{
				right += " · +" + shortNumber(num(s, "week"));
			}
			rows.add(HomeView.divider());
			rows.add(HomeView.listRow(Ui.skillIcon(progress.skillIcons(), name), name + " " + level, text(right, level >= 99 ? ChatComponents.ACCENT : Color.WHITE), null));
		}
		addCard(rows, "What should I train next, and how?");
	}

	private void quests(JsonObject o)
	{
		JsonArray inProgress = o.getAsJsonArray("inProgress"), notStarted = o.getAsJsonArray("notStarted"), finished = o.getAsJsonArray("finished");
		Surface head = HomeView.homeCard();
		head.add(bigNumbers(fmt(finished.size()) + " / " + fmt(finished.size() + inProgress.size() + notStarted.size()), "quests done",
			fmt(num(o, "questPoints")), "quest points"));
		addCard(head, null);
		nameList("In progress", inProgress, ChatComponents.ACCENT, "Which quests should I do next?");
		nameList("Not started", notStarted, ChatComponents.BORDER, inProgress.size() == 0 ? "Which quests should I do next?" : null);
		if (finished.size() > 0)
		{
			nameList("Finished", finished, DONE, null);
		}
	}

	private void combatAchievements(JsonObject o)
	{
		Surface tiers = HomeView.listCard();
		String points = o.has("points") && !o.get("points").isJsonNull() ? fmt(num(o, "points")) + " points" : null;
		tiers.add(sectionLabelRow(points != null ? "Tiers · " + points : "Tiers"));
		ProgressView.addTierRows(tiers, o.getAsJsonArray("tiers"), java.util.Collections.emptySet());
		addCard(tiers, "Which combat achievements are easiest for me?");

		JsonArray open = o.getAsJsonArray("open");
		if (open != null && open.size() > 0)
		{
			Surface c = HomeView.listCard();
			c.add(sectionLabelRow(title(str(o, "nextTier")) + " tasks left"));
			for (JsonElement e : open)
			{
				JsonObject t = e.getAsJsonObject();
				c.add(HomeView.divider());
				JComponent row = HomeView.listRow(null, str(t, "name"), small(str(t, "monster")), null);
				row.setToolTipText(str(t, "description"));
				c.add(row);
			}
			addCard(c, "Which of my " + str(o, "nextTier") + " combat achievements should I do first?");
		}
		JsonArray recent = o.getAsJsonArray("recent");
		if (recent != null && recent.size() > 0)
		{
			Surface c = HomeView.listCard();
			c.add(sectionLabelRow("Recently done"));
			for (JsonElement e : recent)
			{
				JsonObject t = e.getAsJsonObject();
				c.add(HomeView.divider());
				c.add(HomeView.listRow(HomeView.dotIcon(DONE), str(t, "name"), small(ago(str(t, "at"))), null));
			}
			addCard(c, null);
		}
	}

	private void diaries(JsonObject o)
	{
		JsonArray tiers = o.getAsJsonArray("tiers");
		Surface c = HomeView.listCard();
		StringBuilder legend = new StringBuilder();
		for (JsonElement t : tiers)
		{
			legend.append(legend.length() == 0 ? "" : " · ").append(title(t.getAsString()));
		}
		c.add(sectionLabelRow(legend.toString()));
		for (JsonElement e : o.getAsJsonArray("areas"))
		{
			JsonObject a = e.getAsJsonObject();
			JPanel dots = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			dots.setOpaque(false);
			JsonArray done = a.getAsJsonArray("tiers");
			for (int i = 0; i < done.size(); i++)
			{
				boolean d = done.get(i).getAsBoolean();
				JLabel dot = new JLabel(HomeView.dotIcon(d ? DONE : ChatComponents.BORDER));
				dot.setToolTipText(title(tiers.get(i).getAsString()) + (d ? ": done" : ": not done"));
				dots.add(dot);
			}
			c.add(HomeView.divider());
			c.add(HomeView.listRow(null, str(a, "area"), dots, null));
		}
		addCard(c, "Which diary should I do next?");
	}

	private void collectionLog(JsonObject o)
	{
		boolean hasTotal = o.has("total") && !o.get("total").isJsonNull();
		Surface head = HomeView.homeCard();
		head.add(bigNumbers(fmt(num(o, "obtained")) + (hasTotal ? " / " + fmt(num(o, "total")) : ""), "slots filled", null, null));
		if (o.has("capturedAt") && !o.get("capturedAt").isJsonNull())
		{
			head.add(row(small("Full log captured " + ago(str(o, "capturedAt"))), null));
		}
		addCard(head, null);
		JsonArray recent = o.getAsJsonArray("recent");
		Surface c = HomeView.listCard();
		c.add(sectionLabelRow("New slots"));
		if (recent.size() == 0)
		{
			c.add(HomeView.divider());
			c.add(HomeView.listRow(null, "New slots show here as you get them", null, null));
		}
		for (JsonElement e : recent)
		{
			JsonObject r = e.getAsJsonObject();
			c.add(HomeView.divider());
			c.add(HomeView.listRow(HomeView.dotIcon(ChatComponents.ACCENT), str(r, "item"), small(ago(str(r, "at"))), null));
		}
		addCard(c, "Which collection log slots are quickest for me?");
	}

	/** Every kill count, most first; each row opens the boss's page. */
	private void killCounts(JsonObject o)
	{
		JsonArray kcs = o.getAsJsonArray("killCounts");
		Surface c = HomeView.listCard();
		c.add(sectionLabelRow(fmt(kcs.size()) + " bosses, raids, minigames and clues"));
		for (JsonElement e : kcs)
		{
			JsonObject k = e.getAsJsonObject();
			String boss = str(k, "boss");
			String right = fmt(num(k, "count"));
			if (k.has("personalBestSeconds") && !k.get("personalBestSeconds").isJsonNull())
			{
				right += " · PB " + clock(num(k, "personalBestSeconds"));
			}
			c.add(HomeView.divider());
			JComponent row = HomeView.listRow(null, boss, text(right, Color.WHITE), () -> openBoss.accept(boss));
			// The boss's picture as a small icon, once it downloads
			String image = k.has("image") && !k.get("image").isJsonNull() ? str(k, "image") : null;
			if (image != null && progress.images() != null)
			{
				JLabel label = findLabel(row);
				if (label != null)
				{
					progress.images().load(image, img -> SwingUtilities.invokeLater(() -> label.setIcon(thumbnail(img, 20))));
				}
			}
			c.add(row);
		}
		addCard(c, "Which boss should I learn next?");
	}

	private void stats(JsonObject o)
	{
		JsonArray weapons = o.getAsJsonArray("weapons");
		Surface w = HomeView.listCard();
		w.add(sectionLabelRow("Weapons: time wielded in combat, and kills"));
		if (weapons.size() == 0)
		{
			w.add(HomeView.divider());
			w.add(HomeView.listRow(null, "Squire tracks this as you fight", null, null));
		}
		for (JsonElement e : weapons)
		{
			JsonObject x = e.getAsJsonObject();
			String right = num(x, "minutes") > 0 ? Ui.duration(num(x, "minutes")) : "";
			if (num(x, "kills") > 0)
			{
				right += (right.isEmpty() ? "" : " · ") + fmt(num(x, "kills")) + " kills";
			}
			ImageIcon icon = null;
			if (x.has("id") && !x.get("id").isJsonNull())
			{
				BufferedImage img = Crest.itemImage((int) num(x, "id"), this);
				icon = img == null ? null : new ImageIcon(img);
			}
			w.add(HomeView.divider());
			w.add(HomeView.listRow(icon, str(x, "weapon"), text(right, Color.WHITE), null));
		}
		addCard(w, "Which of my weapons should I be using more?");

		Surface c = HomeView.listCard();
		c.add(sectionLabelRow(o.has("trackedSince") && !o.get("trackedSince").isJsonNull() ? "Tracked by Squire for the last " + ago(str(o, "trackedSince")) : "Totals"));
		c.add(HomeView.divider());
		c.add(HomeView.listRow(null, "Time played", value(Ui.duration(num(o, "minutesPlayed"))), null));
		for (JsonElement e : o.getAsJsonArray("mostPlayed"))
		{
			JsonObject m = e.getAsJsonObject();
			c.add(HomeView.divider());
			c.add(HomeView.listRow(HomeView.dotIcon(ChatComponents.ACCENT), str(m, "activity"), value(Ui.duration(num(m, "minutes"))), null));
		}
		JsonObject drop = obj(o, "biggestDrop");
		if (drop != null)
		{
			c.add(HomeView.divider());
			JComponent row = HomeView.listRow(null, "Biggest drop", value(str(drop, "name") + " · " + shortNumber(num(drop, "value"))), null);
			row.setToolTipText("From " + str(drop, "source") + ", " + ago(str(drop, "at")));
			c.add(row);
		}
		c.add(HomeView.divider());
		c.add(HomeView.listRow(null, "Loot", value(shortNumber(num(o, "lootValue")) + " gp"), null));
		c.add(HomeView.divider());
		c.add(HomeView.listRow(null, "Deaths", value(fmt(num(o, "deaths"))), null));
		addCard(c, null);
	}

	// ---- Pieces

	/** A card's small heading, as the first row of a list card. */
	private static JComponent sectionLabelRow(String text)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
		p.add(small(text), BorderLayout.WEST);
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	/** A list of names under a heading, e.g. the quests in progress. */
	private void nameList(String heading, JsonArray names, Color dot, String ask)
	{
		if (names == null || names.size() == 0)
		{
			return;
		}
		Surface c = HomeView.listCard();
		c.add(sectionLabelRow(heading + " (" + names.size() + ")"));
		for (JsonElement e : names)
		{
			c.add(HomeView.divider());
			c.add(HomeView.listRow(HomeView.dotIcon(dot), e.getAsString(), null, null));
		}
		addCard(c, ask);
	}

	private static JComponent bigNumbers(String big, String bigCaption, String side, String sideCaption)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		JLabel b = bold(big);
		b.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		JLabel s = null;
		if (side != null)
		{
			s = bold(side);
			s.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
			s.setForeground(ChatComponents.ACCENT);
		}
		p.add(row(b, s));
		p.add(row(text(bigCaption, ChatComponents.MUTED), sideCaption == null ? null : text(sideCaption, ChatComponents.MUTED)));
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	private static JLabel value(String s)
	{
		return text(s, Color.WHITE);
	}

	/** A kill count's category as a label: "Boss", "Clue", "Minigame", "Agility". */
	static String categoryLabel(String category)
	{
		switch (category)
		{
			case "bosses":
				return "Boss";
			case "clues":
				return "Clue";
			case "minigames":
				return "Minigame";
			case "agility":
				return "Agility course";
			default:
				return title(category);
		}
	}

	/** Seconds as m:ss (or h:mm:ss). */
	static String clock(double seconds)
	{
		long total = Math.round(seconds);
		long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}

	private static ImageIcon thumbnail(BufferedImage img, int size)
	{
		if (img == null)
		{
			return null;
		}
		double scale = Math.min(size / (double) img.getWidth(), size / (double) img.getHeight());
		return new ImageIcon(img.getScaledInstance(Math.max(1, (int) (img.getWidth() * scale)), Math.max(1, (int) (img.getHeight() * scale)), java.awt.Image.SCALE_SMOOTH));
	}

	private static JLabel findLabel(java.awt.Container c)
	{
		for (java.awt.Component child : c.getComponents())
		{
			if (child instanceof JLabel)
			{
				return (JLabel) child;
			}
		}
		return null;
	}

	private void relayout()
	{
		list.revalidate();
		list.repaint();
	}
}
