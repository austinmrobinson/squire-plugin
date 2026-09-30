package com.squire;

import static com.squire.Ui.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.squire.ChatComponents.Align;
import com.squire.ChatComponents.Bubble;
import com.squire.ChatComponents.MessageList;
import com.squire.ChatComponents.ProgressBar;
import com.squire.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Progress page: the rank (straight on the page), then a summary card per area (skills, quests, combat achievements,
 * diaries, collection log, kill counts, fun stats). Each card opens its detail page (ProgressDetailView); a boss in the kill
 * counts opens its own page (BossView). Same card style as Home.
 */
class ProgressView extends JPanel
{
	// The in-game skills tab, read row by row
	private static final Skill[] SKILL_ORDER = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};
	private static final Color DONE = new Color(0x3FA33F);

	private final Function<Skill, BufferedImage> skillIcons;
	private final WikiImages images;
	private final MessageList list = new MessageList(null, 0);
	/** Open a detail page (view id, title) or a boss's page; set by the sidebar. */
	private java.util.function.BiConsumer<String, String> openDetail = (view, title) -> {};
	private java.util.function.Consumer<String> openBoss = boss -> {};

	void setNavigation(java.util.function.BiConsumer<String, String> openDetail, java.util.function.Consumer<String> openBoss)
	{
		this.openDetail = openDetail;
		this.openBoss = openBoss;
	}

	Function<Skill, BufferedImage> skillIcons()
	{
		return skillIcons;
	}

	WikiImages images()
	{
		return images;
	}

	ProgressView(Function<Skill, BufferedImage> skillIcons, WikiImages images)
	{
		this.skillIcons = skillIcons;
		this.images = images;
		setLayout(new BorderLayout());
		setOpaque(false);
		// Cards straight on the panel background, like Home; 1px in so their outline shows
		// Room at the bottom to scroll clear of the floating composer
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, FloatingAsk.CLEARANCE, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll, BorderLayout.CENTER);
		showMessage("Loading your progress...");
	}

	void showMessage(String markdown)
	{
		list.removeAll();
		Bubble b = new Bubble(ChatComponents.CARD_BG, ColorScheme.LIGHT_GRAY_COLOR, false, true);
		b.setHtml(MarkdownLite.toHtml(markdown));
		list.add(ChatComponents.place(b, Align.FILL, 0));
		relayout();
	}

	private JsonObject shown;

	/** The rank and what the score is made of, as context for a question asked from this page. */
	Attachment contextAttachment()
	{
		JsonObject score = shown == null ? null : obj(shown, "score");
		if (score == null)
		{
			return null;
		}
		StringBuilder md = new StringBuilder("# Progress\n\n");
		JsonObject tier = obj(score, "tier");
		md.append("Rank: ").append(tier != null ? str(tier, "name") : str(score, "stage")).append(" (score ").append(oneDecimal(num(score, "score"))).append(" / 100)\n");
		JsonObject next = obj(score, "next");
		if (next != null)
		{
			md.append("Next: ").append(str(next, "name")).append(", ").append(oneDecimal(num(next, "pointsToGo"))).append(" points to go\n");
		}
		md.append("\n");
		for (JsonElement e : score.getAsJsonArray("parts"))
		{
			JsonObject part = e.getAsJsonObject();
			md.append("- ").append(str(part, "label")).append(": ").append(oneDecimal(num(part, "points"))).append(" / ").append((int) num(part, "weight")).append("\n");
		}
		BufferedImage icon = tier != null ? Crest.itemImage((int) num(tier, "itemId"), this) : null;
		return Attachment.context("Progress", "Progress", md.toString(), icon != null ? icon : SquireIcon.create(26));
	}

	void show(JsonObject o)
	{
		shown = o;
		list.removeAll();
		int gap = 0;
		for (JComponent card : new JComponent[]{
			rankCard(o), skillsCard(o), questsCard(o), combatAchievementsCard(o), diariesCard(o), collectionLogCard(o), killCountsCard(o), funCard(o)})
		{
			if (card != null)
			{
				Object ask = card.getClientProperty("ask");
				list.add(ChatComponents.place(ask instanceof String ? Ui.withAsk(card, (String) ask) : card, Align.FILL, gap));
				gap = 4;
			}
		}
		relayout();
	}

	// ---- Cards

	/** The rank: tier crest in the score ring, points to the next tier, the tier ladder and what the score is made of. */
	private JComponent rankCard(JsonObject o)
	{
		JsonObject score = obj(o, "score");
		if (score == null)
		{
			return null;
		}
		// Straight on the page, not in a card: it's the page's headline
		JPanel c = new JPanel();
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setOpaque(false);
		c.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));

		List<ScoreChart.Segment> segments = new ArrayList<>();
		JsonArray parts = score.getAsJsonArray("parts");
		for (JsonElement e : parts)
		{
			JsonObject part = e.getAsJsonObject();
			segments.add(new ScoreChart.Segment(num(part, "points"), ScoreChart.partColor(str(part, "key"))));
		}
		List<Integer> ticks = new ArrayList<>();
		for (JsonElement e : score.getAsJsonArray("checkpoints"))
		{
			int at = (int) num(e.getAsJsonObject(), "at");
			if (at > 0)
			{
				ticks.add(at);
			}
		}
		double value = num(score, "score");
		ScoreChart.Donut donut = new ScoreChart.Donut(value, segments, ticks);
		JsonObject tier = obj(score, "tier");
		if (tier != null)
		{
			donut.withCrest((int) num(tier, "itemId"), HomeView.tierColor(tier));
		}
		donut.setToolTipText("Account score: " + oneDecimal(value) + " / 100 (100 = completionist)");

		JPanel side = new JPanel();
		side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
		side.setOpaque(false);
		JLabel stage = bold(str(score, "stage"));
		stage.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		side.add(row(stage, null));
		JsonObject next = obj(score, "next");
		side.add(row(text(next == null ? "Top tier reached" : oneDecimal(num(next, "pointsToGo")) + " pts to " + str(next, "name"), ChatComponents.MUTED), null));
		side.add(Box.createVerticalStrut(4));
		side.add(row(small("Score " + oneDecimal(value) + " / 100"), null));

		JPanel hero = new JPanel(new BorderLayout(16, 0));
		hero.setOpaque(false);
		hero.add(donut, BorderLayout.WEST);
		hero.add(centred(side), BorderLayout.CENTER);
		hero.setAlignmentX(LEFT_ALIGNMENT);
		hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, hero.getPreferredSize().height));
		c.add(hero);

		if (score.has("tiers"))
		{
			c.add(Box.createVerticalStrut(14));
			List<ScoreChart.Tier> tiers = new ArrayList<>();
			for (JsonElement e : score.getAsJsonArray("tiers"))
			{
				JsonObject t = e.getAsJsonObject();
				tiers.add(new ScoreChart.Tier(str(t, "name"), (int) num(t, "at"), (int) num(t, "itemId"), HomeView.tierColor(t), t.get("reached").getAsBoolean()));
			}
			ScoreChart.Ladder ladder = new ScoreChart.Ladder(tiers);
			ladder.setAlignmentX(LEFT_ALIGNMENT);
			c.add(ladder);
		}

		c.add(Box.createVerticalStrut(14));
		for (int i = 0; i < parts.size(); i++)
		{
			JsonObject part = parts.get(i).getAsJsonObject();
			if (i > 0)
			{
				c.add(Box.createVerticalStrut(4));
			}
			JLabel label = text(str(part, "label"), ColorScheme.LIGHT_GRAY_COLOR);
			label.setIcon(swatch(ScoreChart.partColor(str(part, "key"))));
			label.setIconTextGap(8);
			String tip = Math.round(num(part, "fraction") * 100) + "% complete";
			if (str(part, "key").equals("collectionLog") && score.has("clogTotalEstimated") && score.get("clogTotalEstimated").getAsBoolean())
			{
				tip += " (total slots estimated until you open the collection log)";
			}
			label.setToolTipText(tip);
			c.add(row(label, pair(oneDecimal(num(part, "points")), " / " + (int) num(part, "weight"))));
		}
		c.putClientProperty("ask", next == null ? "What's left for me to max out my score?" : "How do I reach " + str(next, "name") + " fastest?");
		return c;
	}

	private JComponent skillsCard(JsonObject o)
	{
		JsonArray skills = o.getAsJsonArray("skills");
		if (skills == null || skills.size() == 0)
		{
			return null;
		}
		int maxed = 0;
		for (JsonElement e : skills)
		{
			if (num(e.getAsJsonObject(), "level") >= 99)
			{
				maxed++;
			}
		}
		Surface c = HomeView.listCard();
		c.add(top("Skills", fmt(num(o, "totalLevel")) + " / " + fmt(skills.size() * 99), "total level", shortNumber(num(o, "totalXp")), "total XP",
			num(o, "totalLevel") / Math.max(1, skills.size() * 99)));
		c.add(HomeView.divider());
		c.add(HomeView.listRow(HomeView.dotIcon(ChatComponents.ACCENT), "Level 99", value(maxed + " of " + skills.size()), null));
		return opens(c, "skills", "Skills", "What should I train next, and how?");
	}

	private JComponent questsCard(JsonObject o)
	{
		JsonObject q = obj(o, "quests");
		if (q == null || num(q, "total") == 0)
		{
			return null;
		}
		double done = num(q, "finished"), total = num(q, "total"), inProgress = num(q, "inProgress");
		Surface c = HomeView.listCard();
		c.add(top("Quests", fmt(done) + " / " + fmt(total), "quests done", fmt(num(q, "questPoints")), "quest points", done / total));
		c.add(HomeView.divider());
		c.add(HomeView.listRow(HomeView.dotIcon(ChatComponents.ACCENT), "In progress", value(fmt(inProgress)), null));
		c.add(HomeView.divider());
		c.add(HomeView.listRow(HomeView.dotIcon(ChatComponents.BORDER), "Not started", value(fmt(Math.max(0, total - done - inProgress))), null));
		return opens(c, "quests", "Quests", "Which quests should I do next?");
	}

	private JComponent combatAchievementsCard(JsonObject o)
	{
		JsonObject ca = obj(o, "combatAchievements");
		if (ca == null || num(ca, "total") == 0)
		{
			return null;
		}
		String points = ca.has("points") && !ca.get("points").isJsonNull() ? fmt(num(ca, "points")) : null;
		Surface c = HomeView.listCard();
		c.add(top("Combat achievements", fmt(num(ca, "done")) + " / " + fmt(num(ca, "total")), "tasks done", points, "points", num(ca, "done") / num(ca, "total")));
		addNextTierRow(c, ca.getAsJsonArray("tiers"));
		return opens(c, "combat-achievements", "Combat achievements", "Which combat achievements are easiest for me?");
	}

	private JComponent diariesCard(JsonObject o)
	{
		JsonObject d = obj(o, "diaries");
		if (d == null || num(d, "total") == 0)
		{
			return null;
		}
		Surface c = HomeView.listCard();
		c.add(top("Achievement diaries", fmt(num(d, "done")) + " / " + fmt(num(d, "total")), "tiers done", null, null, num(d, "done") / num(d, "total")));
		addNextTierRow(c, d.getAsJsonArray("tiers"));
		return opens(c, "diaries", "Achievement diaries", "Which diary should I do next?");
	}

	private JComponent collectionLogCard(JsonObject o)
	{
		JsonObject clog = obj(o, "collectionLog");
		if (clog == null)
		{
			return null;
		}
		double obtained = num(clog, "obtained");
		boolean hasTotal = clog.has("total") && !clog.get("total").isJsonNull();
		Surface c = HomeView.listCard();
		c.add(top("Collection log", fmt(obtained) + (hasTotal ? " / " + fmt(num(clog, "total")) : ""), "slots filled", null, null,
			hasTotal ? obtained / Math.max(1, num(clog, "total")) : -1));
		if (!hasTotal)
		{
			c.add(HomeView.divider());
			c.add(HomeView.listRow(null, "Open the log in game to see the total", null, null));
		}
		return opens(c, "collection-log", "Collection log", "Which collection log slots are quickest for me?");
	}

	/** The top six kill counts: the boss's picture, the count, the name; three to a row. Each opens the boss's page. */
	private JComponent killCountsCard(JsonObject o)
	{
		JsonArray kcs = o.getAsJsonArray("topKillCounts");
		if (kcs == null || kcs.size() == 0)
		{
			return null;
		}
		Surface c = HomeView.homeCard();
		JComponent header = HomeView.header("Kill counts", null);
		header.setAlignmentX(LEFT_ALIGNMENT);
		onClick(header, () -> openDetail.accept("kill-counts", "Kill counts"));
		c.add(header);
		c.add(Box.createVerticalStrut(12));
		JPanel grid = new JPanel(new GridLayout(0, 3, 12, 14));
		grid.setOpaque(false);
		for (JsonElement e : kcs)
		{
			JsonObject k = e.getAsJsonObject();
			String boss = str(k, "boss");
			JPanel cell = new JPanel();
			cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
			cell.setOpaque(false);
			BossPicture pic = new BossPicture();
			pic.setAlignmentX(LEFT_ALIGNMENT);
			if (images != null)
			{
				images.load(str(k, "image"), pic::setImage);
			}
			cell.add(pic);
			cell.add(Box.createVerticalStrut(6));
			JLabel count = bold(fmt(num(k, "count")));
			count.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
			count.setAlignmentX(LEFT_ALIGNMENT);
			cell.add(count);
			JLabel name = text(str(k, "boss"), ChatComponents.MUTED);
			name.setToolTipText(str(k, "boss") + ": " + fmt(num(k, "count")) + " kills");
			name.setAlignmentX(LEFT_ALIGNMENT);
			cell.add(name);
			cell.setToolTipText(boss + ": " + fmt(num(k, "count")) + " kills. Click for trends.");
			onClick(cell, () -> openBoss.accept(boss));
			grid.add(cell);
		}
		grid.setAlignmentX(LEFT_ALIGNMENT);
		c.add(grid);
		return c;
	}

	/** Fun tracking: your favourite weapon (most time wielded in combat), time played, most killed and biggest drop. */
	private JComponent funCard(JsonObject o)
	{
		JsonObject st = obj(o, "stats");
		if (st == null)
		{
			return null;
		}
		Surface c = HomeView.listCard();
		JPanel head = new JPanel();
		head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
		head.setOpaque(false);
		head.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
		head.add(HomeView.header("For fun", null));
		head.add(Box.createVerticalStrut(10));
		JsonObject weapon = obj(st, "favouriteWeapon");
		JLabel big = bold(weapon != null ? str(weapon, "weapon") : "Not enough fights yet");
		big.setFont(FontManager.getRunescapeBoldFont().deriveFont(weapon != null ? 18f : 16f));
		if (weapon != null && weapon.has("id") && !weapon.get("id").isJsonNull())
		{
			BufferedImage icon = Crest.itemImage((int) num(weapon, "id"), big);
			if (icon != null)
			{
				big.setIcon(new javax.swing.ImageIcon(icon));
				big.setIconTextGap(8);
			}
		}
		head.add(row(big, null));
		String caption = "favourite weapon";
		if (weapon != null)
		{
			List<String> bits = new ArrayList<>();
			if (num(weapon, "minutes") > 0)
			{
				bits.add(Ui.duration(num(weapon, "minutes")) + " wielded");
			}
			if (num(weapon, "kills") > 0)
			{
				bits.add(fmt(num(weapon, "kills")) + " kills");
			}
			caption = "favourite weapon" + (bits.isEmpty() ? "" : " · " + String.join(", ", bits));
		}
		head.add(row(text(caption, ChatComponents.MUTED), null));
		head.setAlignmentX(LEFT_ALIGNMENT);
		c.add(head);
		if (num(st, "minutesPlayed") > 0)
		{
			c.add(HomeView.divider());
			c.add(HomeView.listRow(null, "Time played", value(Ui.duration(num(st, "minutesPlayed"))), null));
		}
		JsonObject most = obj(st, "mostKilled");
		if (most != null)
		{
			c.add(HomeView.divider());
			c.add(HomeView.listRow(null, "Most killed", value(str(most, "boss") + " · " + fmt(num(most, "count"))), null));
		}
		JsonObject drop = obj(st, "biggestDrop");
		if (drop != null)
		{
			c.add(HomeView.divider());
			c.add(HomeView.listRow(null, "Biggest drop", value(str(drop, "name") + " · " + shortNumber(num(drop, "value"))), null));
		}
		return opens(c, "stats", "For fun", "What does my playtime say about how I play?");
	}

	// ---- Pieces

	/** The card opens its detail page when clicked. */
	private JComponent opens(Surface c, String view, String title, String ask)
	{
		// The whole card opens its page (the chevron says so); the page itself has the ask button
		Ui.clickable(c, () -> openDetail.accept(view, title));
		return c;
	}

	/** Make a component open something on click (for cards whose parts open different things). */
	static void onClick(JComponent target, Runnable action)
	{
		target.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.getComponent().contains(e.getPoint()))
				{
					action.run();
				}
			}
		};
		target.addMouseListener(m);
		for (java.awt.Component child : target.getComponents())
		{
			child.addMouseListener(m);
		}
	}

	/** A card title with a chevron: the card opens a page. */
	private static JComponent sectionHeader(String title)
	{
		return HomeView.header(title, null);
	}

	/** The top of a list card: title, the headline numbers, and a progress bar (skipped when fraction < 0). */
	private static JComponent top(String title, String big, String bigCaption, String side, String sideCaption, double fraction)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
		p.add(sectionHeader(title));
		p.add(Box.createVerticalStrut(10));
		p.add(totals(big, bigCaption, side, sideCaption));
		if (fraction >= 0)
		{
			p.add(Box.createVerticalStrut(10));
			ProgressBar bar = new ProgressBar(fraction);
			bar.setAlignmentX(LEFT_ALIGNMENT);
			bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
			p.add(bar);
		}
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	/** Big number with a caption; optionally a second one on the right (like Home's time and XP). */
	private static JComponent totals(String big, String bigCaption, String side, String sideCaption)
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
		p.add(row(text(bigCaption, ChatComponents.MUTED), side == null || sideCaption == null ? null : text(sideCaption, ChatComponents.MUTED)));
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	/** The tier being worked on (the lowest with tasks left): "Hard: 12 left". */
	private static void addNextTierRow(Surface c, JsonArray tiers)
	{
		if (tiers == null)
		{
			return;
		}
		for (JsonElement e : tiers)
		{
			JsonObject t = e.getAsJsonObject();
			double done = num(t, "done"), total = num(t, "total");
			if (done < total)
			{
				c.add(HomeView.divider());
				c.add(HomeView.listRow(HomeView.dotIcon(ChatComponents.ACCENT), "Working on " + title(str(t, "tier")), value(fmt(total - done) + " left"), null));
				return;
			}
		}
		c.add(HomeView.divider());
		c.add(HomeView.listRow(HomeView.dotIcon(DONE), "Every tier done", null, null));
	}

	/** One row per tier: dot, name, "done / total"; green once every task in the tier is done. Hover says if its reward is unlocked. */
	static void addTierRows(Surface c, JsonArray tiers, java.util.Set<String> unlocked)
	{
		if (tiers == null)
		{
			return;
		}
		for (JsonElement e : tiers)
		{
			JsonObject t = e.getAsJsonObject();
			String tier = str(t, "tier");
			double done = num(t, "done"), total = num(t, "total");
			boolean finished = done >= total;
			c.add(HomeView.divider());
			JLabel v = text(fmt(done) + " / " + fmt(total), finished ? DONE : Color.WHITE);
			if (unlocked.contains(tier))
			{
				v.setToolTipText(title(tier) + " reward unlocked");
			}
			c.add(HomeView.listRow(HomeView.dotIcon(finished ? DONE : ChatComponents.BORDER), title(tier), v, null));
		}
	}

	private static JLabel value(String s)
	{
		return text(s, Color.WHITE);
	}

	private static JComponent pair(String strong, String faint)
	{
		JPanel right = new JPanel(new BorderLayout());
		right.setOpaque(false);
		right.add(text(strong, Color.WHITE), BorderLayout.WEST);
		right.add(small(faint), BorderLayout.EAST);
		return right;
	}

	private static JComponent centred(JComponent c)
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		p.add(c, gbc);
		return p;
	}

	JComponent skillGrid(JsonArray skills)
	{
		Map<String, JsonObject> byName = new HashMap<>();
		for (JsonElement e : skills)
		{
			JsonObject s = e.getAsJsonObject();
			byName.put(str(s, "skill").toUpperCase(Locale.ROOT), s);
		}
		JPanel grid = new JPanel(new GridLayout(0, 3, 4, 4));
		grid.setOpaque(false);
		for (Skill skill : SKILL_ORDER)
		{
			JsonObject s = byName.get(skill.name());
			if (s == null)
			{
				continue;
			}
			int level = (int) num(s, "level");
			Surface cell = new Surface(ChatComponents.PANEL_BG, 4, true);
			cell.setLayout(new BorderLayout());
			cell.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 8));
			cell.setPreferredSize(new Dimension(60, 28));
			cell.setToolTipText(skill.getName() + ": " + fmt(num(s, "xp")) + " XP");
			cell.add(new JLabel(Ui.skillIcon(skillIcons, skill)), BorderLayout.WEST);
			JLabel lvl = new JLabel(String.valueOf(level), SwingConstants.RIGHT);
			lvl.setFont(FontManager.getRunescapeBoldFont());
			lvl.setForeground(level >= 99 ? ChatComponents.ACCENT : Color.WHITE);
			cell.add(lvl, BorderLayout.CENTER);
			grid.add(cell);
		}
		return grid;
	}

	/** A boss picture from the wiki, fitted into its box without stretching; blank until it downloads. */
	static final class BossPicture extends JComponent
	{
		private static final int HEIGHT = 56;
		private BufferedImage image;

		BossPicture()
		{
			setPreferredSize(new Dimension(72, HEIGHT));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
		}

		void setImage(BufferedImage image)
		{
			this.image = image;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (image == null)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
			int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
			g2.drawImage(image, 0, (getHeight() - h) / 2, w, h, null);
			g2.dispose();
		}
	}

	private void relayout()
	{
		list.revalidate();
		list.repaint();
	}
}
