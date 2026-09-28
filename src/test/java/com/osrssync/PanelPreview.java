package com.osrssync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.laf.RuneLiteLAF;
import okhttp3.OkHttpClient;

/** Renders the sidebar offscreen to PNGs for design review: ./gradlew panelPreview */
public class PanelPreview
{
	static final String SAMPLE_OVERVIEW = "{"
		+ "\"name\":\"Zezima\",\"accountType\":\"ironman\",\"combatLevel\":118,\"totalLevel\":1987,\"totalXp\":187654321,"
		+ "\"syncedAt\":\"" + java.time.OffsetDateTime.now().minusMinutes(4) + "\","
		+ "\"score\":{\"score\":54.9,\"stage\":\"Adamant\",\"tier\":{\"name\":\"Adamant\",\"at\":50,\"itemId\":1199,\"color\":\"#4e7a4e\"},\"next\":{\"name\":\"Rune\",\"at\":62,\"itemId\":1201,\"pointsToGo\":7.1},"
		+ "\"tiers\":[{\"name\":\"Bronze\",\"at\":0,\"itemId\":1189,\"color\":\"#a0703a\",\"reached\":true},{\"name\":\"Iron\",\"at\":10,\"itemId\":1191,\"color\":\"#7d7d7d\",\"reached\":true},{\"name\":\"Steel\",\"at\":20,\"itemId\":1193,\"color\":\"#a7a7a7\",\"reached\":true},{\"name\":\"Black\",\"at\":30,\"itemId\":1195,\"color\":\"#3a3a3a\",\"reached\":true},{\"name\":\"Mithril\",\"at\":40,\"itemId\":1197,\"color\":\"#4a5a8c\",\"reached\":true},{\"name\":\"Adamant\",\"at\":50,\"itemId\":1199,\"color\":\"#4e7a4e\",\"reached\":true},{\"name\":\"Rune\",\"at\":62,\"itemId\":1201,\"color\":\"#3e7fa0\",\"reached\":false},{\"name\":\"Dragon\",\"at\":75,\"itemId\":21895,\"color\":\"#b02020\",\"reached\":false},{\"name\":\"3rd age\",\"at\":90,\"itemId\":10352,\"color\":\"#d8c27a\",\"reached\":false}],"
		+ "\"checkpoints\":[{\"name\":\"Bronze\",\"at\":0,\"reached\":true},{\"name\":\"Iron\",\"at\":10,\"reached\":true},{\"name\":\"Steel\",\"at\":20,\"reached\":true},{\"name\":\"Black\",\"at\":30,\"reached\":true},{\"name\":\"Mithril\",\"at\":40,\"reached\":true},{\"name\":\"Adamant\",\"at\":50,\"reached\":true},{\"name\":\"Rune\",\"at\":62,\"reached\":false},{\"name\":\"Dragon\",\"at\":75,\"reached\":false},{\"name\":\"3rd age\",\"at\":90,\"reached\":false}],"
		+ "\"parts\":[{\"key\":\"skills\",\"label\":\"Skills\",\"weight\":35,\"fraction\":0.824,\"points\":28.9},{\"key\":\"combatAchievements\",\"label\":\"Combat achievements\",\"weight\":20,\"fraction\":0.12,\"points\":2.4},"
		+ "{\"key\":\"collectionLog\",\"label\":\"Collection log\",\"weight\":20,\"fraction\":0.19,\"points\":3.8},{\"key\":\"quests\",\"label\":\"Quests\",\"weight\":15,\"fraction\":0.826,\"points\":12.4},"
		+ "{\"key\":\"diaries\",\"label\":\"Diaries\",\"weight\":10,\"fraction\":0.75,\"points\":7.5}],\"clogTotalEstimated\":true},"
		+ "\"chatPrompts\":[{\"title\":\"Plan my way to End game\",\"prompt\":\"x\"},{\"title\":\"Easiest combat achievements for me\",\"prompt\":\"x\"},{\"title\":\"Gear for my slayer task\",\"prompt\":\"x\"},{\"title\":\"Am I dry at Vorkath?\",\"prompt\":\"x\"}],"
		+ "\"skills\":[" + skillsJson() + "],"
		+ "\"quests\":{\"finished\":151,\"inProgress\":4,\"total\":172,\"questPoints\":289},"
		+ "\"diaries\":{\"done\":31,\"total\":48,\"tiers\":[{\"tier\":\"easy\",\"done\":12,\"total\":12},{\"tier\":\"medium\",\"done\":11,\"total\":12},{\"tier\":\"hard\",\"done\":6,\"total\":12},{\"tier\":\"elite\",\"done\":2,\"total\":12}]},"
		+ "\"combatAchievements\":{\"done\":214,\"total\":637,\"points\":1123,\"tiersComplete\":[\"easy\",\"medium\"],\"tiers\":[{\"tier\":\"easy\",\"done\":41,\"total\":41},{\"tier\":\"medium\",\"done\":50,\"total\":55},{\"tier\":\"hard\",\"done\":62,\"total\":109},{\"tier\":\"elite\",\"done\":45,\"total\":182},{\"tier\":\"master\",\"done\":14,\"total\":151},{\"tier\":\"grandmaster\",\"done\":2,\"total\":99}]},"
		+ "\"collectionLog\":{\"obtained\":642},\"slayer\":{\"points\":1320,\"streak\":212},\"wealth\":287400000,"
		+ "\"xpThisWeek\":{\"total\":2345678,\"top\":[{\"skill\":\"Slayer\",\"xp\":912000},{\"skill\":\"Ranged\",\"xp\":640500},{\"skill\":\"Hitpoints\",\"xp\":401200}]},"
		+ "\"topKillCounts\":[{\"boss\":\"Vorkath\",\"count\":612},{\"boss\":\"Zulrah\",\"count\":488},{\"boss\":\"Kraken\",\"count\":1247},{\"boss\":\"Cerberus\",\"count\":693},{\"boss\":\"Chambers of Xeric\",\"count\":97},{\"boss\":\"The Leviathan\",\"count\":8}],"
		+ "\"recent\":["
		+ "{\"ts\":\"" + java.time.OffsetDateTime.now().minusMinutes(12) + "\",\"type\":\"level_up\",\"summary\":\"Slayer level 93\"},"
		+ "{\"ts\":\"" + java.time.OffsetDateTime.now().minusHours(3) + "\",\"type\":\"collection_log\",\"summary\":\"Dragonbone necklace\"},"
		+ "{\"ts\":\"" + java.time.OffsetDateTime.now().minusDays(2) + "\",\"type\":\"quest_complete\",\"summary\":\"Desert Treasure II - The Fallen Empire\"}"
		+ "]}";

	/** Plausible activity for previews: Kraken and Farming most days, some Cerberus and Hunter. */
	/** XP per minute for the sample activities (Kraken, Farming, Cerberus, Hunter, Other). */
	private static final int[] XP_RATE = {1200, 2600, 1500, 1800, 0};

	static JsonObject sampleActivity(String range)
	{
		String[] names = {"Kraken", "Farming", "Cerberus", "Hunter", "Other"};
		String[] cats = {"combat", "skilling", "combat", "skilling", "other"};
		int columns = range.equals("day") ? 24 : range.equals("week") ? 7 : 30;
		int current = range.equals("day") ? 19 : range.equals("week") ? 3 : 12;
		java.util.Random rnd = new java.util.Random(range.hashCode());
		double[] totals = new double[names.length];
		double[] xpTotals = new double[names.length];
		com.google.gson.JsonArray buckets = new com.google.gson.JsonArray();
		String[] days = {"M", "T", "W", "T", "F", "S", "S"};
		for (int i = 0; i < columns; i++)
		{
			JsonObject b = new JsonObject();
			b.addProperty("key", range.equals("day") ? String.valueOf(i) : "2026-09-" + String.format("%02d", i + 1));
			b.addProperty("label", range.equals("day") ? (i % 6 == 0 ? (i == 0 ? "12a" : i < 12 ? i + "a" : i == 12 ? "12p" : (i - 12) + "p") : "")
				: range.equals("week") ? days[i] : (i % 7 == 0 ? String.valueOf(i + 1) : ""));
			if (range.equals("week"))
			{
				b.addProperty("sub", String.valueOf(22 + i));
			}
			b.addProperty("current", i == current);
			b.addProperty("future", i > current);
			com.google.gson.JsonArray segs = new com.google.gson.JsonArray();
			double sum = 0;
			boolean active = i <= current && (range.equals("day") ? (i >= 9 && i != 13) : rnd.nextDouble() > 0.15);
			for (int k = 0; k < names.length && active; k++)
			{
				double scale = range.equals("day") ? 60 : 300;
				double m = Math.round(scale * rnd.nextDouble() * new double[]{0.45, 0.3, 0.2, 0.12, 0.08}[k]);
				if (m <= 0)
				{
					continue;
				}
				JsonObject seg = new JsonObject();
				seg.addProperty("name", names[k]);
				seg.addProperty("minutes", m);
				seg.addProperty("xp", m * XP_RATE[k]);
				xpTotals[k] += m * XP_RATE[k];
				segs.add(seg);
				totals[k] += m;
				sum += m;
			}
			b.add("segments", segs);
			b.addProperty("minutes", sum);
			buckets.add(b);
		}
		double total = java.util.Arrays.stream(totals).sum();
		com.google.gson.JsonArray acts = new com.google.gson.JsonArray();
		for (int k = 0; k < names.length; k++)
		{
			JsonObject a = new JsonObject();
			a.addProperty("name", names[k]);
			a.addProperty("category", cats[k]);
			a.addProperty("minutes", totals[k]);
			a.addProperty("xp", xpTotals[k]);
			a.addProperty("pct", Math.round(totals[k] / total * 100));
			a.addProperty("xpPct", Math.round(xpTotals[k] / java.util.Arrays.stream(xpTotals).sum() * 100));
			acts.add(a);
		}
		JsonObject o = new JsonObject();
		o.addProperty("range", range);
		o.addProperty("label", range.equals("day") ? "Today" : range.equals("week") ? "This week" : "This month");
		o.addProperty("totalMinutes", total);
		o.addProperty("totalXp", java.util.Arrays.stream(xpTotals).sum());
		o.add("activities", acts);
		o.add("buckets", buckets);
		JsonObject headline = new JsonObject();
		headline.addProperty("label", range.equals("day") ? "Total" : "Daily average");
		headline.addProperty("minutes", range.equals("day") ? total : Math.round(total / (current + 1)));
		headline.addProperty("changePct", range.equals("day") ? 12 : -4);
		headline.addProperty("xp", Math.round(java.util.Arrays.stream(xpTotals).sum() / (range.equals("day") ? 1 : current + 1)));
		headline.addProperty("xpChangePct", 18);
		headline.addProperty("comparedTo", range.equals("day") ? "the day before" : "last " + range);
		o.add("headline", headline);
		o.addProperty("hasOlder", true);
		o.addProperty("hasNewer", false);
		return o;
	}

	static com.google.gson.JsonArray actions(String callId, String tool, String inputJson)
	{
		JsonObject a = new JsonObject();
		a.addProperty("callId", callId);
		a.addProperty("kind", "tool-call");
		a.addProperty("toolName", tool);
		a.add("input", new Gson().fromJson(inputJson, JsonObject.class));
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		arr.add(a);
		return arr;
	}

	/** A tool result shaped like MCP's: {content: [{type: text, text: json}]}. */
	static JsonObject mcp(String json)
	{
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", json);
		com.google.gson.JsonArray content = new com.google.gson.JsonArray();
		content.add(text);
		JsonObject o = new JsonObject();
		o.add("content", content);
		return o;
	}

	private static String skillsJson()
	{
		String[] names = {"Attack", "Hitpoints", "Mining", "Strength", "Agility", "Smithing", "Defence", "Herblore", "Fishing",
			"Ranged", "Thieving", "Cooking", "Prayer", "Crafting", "Firemaking", "Magic", "Fletching", "Woodcutting",
			"Runecraft", "Slayer", "Farming", "Construction", "Hunter", "Sailing"};
		int[] levels = {90, 95, 80, 94, 76, 85, 90, 88, 82, 99, 83, 99, 77, 84, 99, 96, 91, 86, 77, 93, 92, 83, 85, 52};
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < names.length; i++)
		{
			sb.append(i > 0 ? "," : "").append("{\"skill\":\"").append(names[i]).append("\",\"level\":").append(levels[i]).append(",\"xp\":").append(levels[i] * 100000).append('}');
		}
		return sb.toString();
	}

	public static void main(String[] args) throws Exception
	{
		File out = new File(args.length > 0 ? args[0] : "build/preview");
		out.mkdirs();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				RuneLiteLAF.setup();
				ChatClient client = new ChatClient(new OkHttpClient(), new Gson(), () -> "http://127.0.0.1:3000", () -> "x", () -> ModelCatalog.DEFAULT_ID);
				ModelCatalog models = new ModelCatalog(new OkHttpClient(), new Gson(), () -> "", () -> "");

				ChatSessions sessions = new ChatSessions(() -> new ChatView(new ChatClient(new OkHttpClient(), new Gson(), () -> "http://127.0.0.1:1", () -> "x", () -> ModelCatalog.DEFAULT_ID), Map::of, models, () -> ChatView.AUTO_MODEL, id -> {}), null);
				ChatView chat = sessions.current();
				SettingsView settings = new SettingsView(() -> {}, () -> java.util.List.of(
					SettingsView.Item.choice("::squire command", "On", null, () -> {}), SettingsView.Item.shortcut("Ask shortcut", "Ctrl+B", null, k -> {}),
					SettingsView.Item.choice("Shortcut opens", "Chatbox", null, () -> {})));
				AccountApi api = new AccountApi(new OkHttpClient(), new Gson(), () -> "", () -> "", () -> null);
				SkillIconManager icons = new SkillIconManager();
				ProgressView progress = new ProgressView(skill -> icons.getSkillImage(skill, true), null);
				ActivityView activity = new ActivityView(api);
				HomeView[] homeRef = new HomeView[1];
				@SuppressWarnings("unchecked")
				java.util.List<Scenarios.Prompt>[] live = new java.util.List[]{java.util.List.of()};
				BuddySidebar sidebar = new BuddySidebar(actions -> homeRef[0] = new HomeView(api, progress, actions, sessions, () -> live[0]),
					progress, activity, sessions, settings, BuddySidebar.DEFAULT_WIDTH, w -> {});
				JsonObject overviewJson = new Gson().fromJson(SAMPLE_OVERVIEW, JsonObject.class);
				homeRef[0].planView().show(new Gson().fromJson("{\"plan\": {\"title\": \"Road to Zulrah\", \"summary\": \"Barrows gloves first, then a blowpipe from Zulrah. Built for your ironman.\", \"completed\": 2, \"total\": 5, \"current\": 2, \"updatedAt\": \"\", \"checkpoints\": [{\"id\": \"a1\", \"title\": \"Dragon defender\", \"note\": null, \"icon\": {\"name\": \"Dragon defender\", \"id\": 12954}, \"goals\": [{\"label\": \"Dragon defender\", \"met\": true, \"have\": 1, \"need\": 1}], \"steps\": [], \"manuallyDone\": false, \"complete\": true, \"progress\": 1}, {\"id\": \"a2\", \"title\": \"Fire cape\", \"note\": null, \"icon\": {\"name\": \"Fire cape\", \"id\": 6570}, \"goals\": [{\"label\": \"Fire cape\", \"met\": true, \"have\": 1, \"need\": 1}], \"steps\": [], \"manuallyDone\": false, \"complete\": true, \"progress\": 1}, {\"id\": \"a3\", \"title\": \"Barrows gloves\", \"note\": \"The best all-round gloves until much later, and Recipe for Disaster unlocks a lot on the way.\", \"icon\": {\"name\": \"Barrows gloves\", \"id\": 7462}, \"goals\": [{\"label\": \"Recipe for Disaster\", \"met\": false, \"have\": 0, \"need\": 1}, {\"label\": \"70 Cooking\", \"met\": true, \"have\": 74, \"need\": 70}, {\"label\": \"175 quest points\", \"met\": false, \"have\": 161, \"need\": 175}], \"steps\": [{\"id\": \"s1\", \"text\": \"Finish Desert Treasure (Freeing Pirate Pete)\", \"done\": true}, {\"id\": \"s2\", \"text\": \"Do Monkey Madness I for King Awowogei\", \"done\": false}, {\"id\": \"s3\", \"text\": \"Bank 100k for the gloves\", \"done\": false}], \"manuallyDone\": false, \"complete\": false, \"progress\": 0.5}, {\"id\": \"a4\", \"title\": \"Toxic blowpipe\", \"note\": null, \"icon\": {\"name\": \"Toxic blowpipe\", \"id\": 12926}, \"goals\": [{\"label\": \"Regicide\", \"met\": false, \"have\": 0, \"need\": 1}, {\"label\": \"Tanzanite fang\", \"met\": false, \"have\": 0, \"need\": 1}], \"steps\": [], \"manuallyDone\": false, \"complete\": false, \"progress\": 0}, {\"id\": \"a5\", \"title\": \"Ava's assembler\", \"note\": null, \"icon\": {\"name\": \"Ava's assembler\", \"id\": 22109}, \"goals\": [{\"label\": \"Dragon Slayer II\", \"met\": false, \"have\": 0, \"need\": 1}, {\"label\": \"75 kills\", \"met\": false, \"have\": 0, \"need\": 75}], \"steps\": [], \"manuallyDone\": false, \"complete\": false, \"progress\": 0}]}}", com.google.gson.JsonObject.class), null);
				homeRef[0].show(overviewJson, sampleActivity("day"));
				progress.show(overviewJson);
				render(sidebar, new File(out, "0-home.png"));
				// Item sprites from RuneLite's icon CDN, so gear previews show real items
				Crest.setIconSource(id ->
				{
					try
					{
						return ImageIO.read(new java.net.URL("https://static.runelite.net/cache/item/icon/" + id + ".png"));
					}
					catch (Exception e)
					{
						return null;
					}
				});
				ChatComponents.MessageList gearList = new ChatComponents.MessageList(ChatComponents.BASE_BG, 0);
				gearList.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
				gearList.add(ChatComponents.place(new GearCard(new Gson().fromJson("{\"gear\": {\"name\": \"Vorkath (ranged)\", \"target\": \"Vorkath (Post-quest)\", \"style\": \"ranged\", \"equipment\": {\"head\": {\"id\": 26382, \"name\": \"Masori mask (f)\"}, \"cape\": {\"id\": 22109, \"name\": \"Ava's assembler\"}, \"neck\": {\"id\": 19547, \"name\": \"Necklace of anguish\"}, \"ammo\": {\"id\": 11212, \"name\": \"Dragon arrow\"}, \"weapon\": {\"id\": 25865, \"name\": \"Bow of faerdhinen (c)\"}, \"body\": {\"id\": 26384, \"name\": \"Masori body (f)\"}, \"shield\": null, \"legs\": {\"id\": 26386, \"name\": \"Masori chaps (f)\"}, \"hands\": {\"id\": 26235, \"name\": \"Zaryte vambraces\"}, \"feet\": {\"id\": 13237, \"name\": \"Pegasian boots\"}, \"ring\": {\"id\": 28310, \"name\": \"Venator ring\"}}, \"inventory\": [{\"id\": 12695, \"name\": \"Divine ranging potion(4)\", \"quantity\": 1}, {\"id\": 22461, \"name\": \"Extended antifire(4)\", \"quantity\": 1}, {\"id\": 12913, \"name\": \"Anti-venom+(4)\", \"quantity\": 1}, {\"id\": 27281, \"name\": \"Divine rune pouch\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 385, \"name\": \"Shark\", \"quantity\": 1}, {\"id\": 2434, \"name\": \"Prayer potion(4)\", \"quantity\": 1}, {\"id\": 2434, \"name\": \"Prayer potion(4)\", \"quantity\": 1}, {\"id\": 2434, \"name\": \"Prayer potion(4)\", \"quantity\": 1}, {\"id\": 2434, \"name\": \"Prayer potion(4)\", \"quantity\": 1}, {\"id\": 2434, \"name\": \"Prayer potion(4)\", \"quantity\": 1}, {\"id\": 2434, \"name\": \"Prayer potion(4)\", \"quantity\": 1}, {\"id\": 12791, \"name\": \"Rune pouch\", \"quantity\": 1}, null, {\"id\": 8013, \"name\": \"Teleport to house\", \"quantity\": 10}, null], \"bonuses\": {\"attack\": {\"stab\": 0, \"slash\": 0, \"crush\": 0, \"magic\": -20, \"ranged\": 212}, \"defence\": {\"stab\": 0, \"slash\": 0, \"crush\": 0, \"magic\": 0, \"ranged\": 0}, \"other\": {\"str\": 0, \"ranged_str\": 38, \"magic_str\": 0, \"prayer\": 6}}, \"dps\": {\"dps\": 6.12, \"maxHit\": 41, \"hitChance\": 71.3, \"secondsToKill\": 122, \"attackSpeedTicks\": 4, \"style\": \"ranged\", \"attackType\": \"ranged\"}, \"alternatives\": {\"weapon\": [{\"id\": 20997, \"name\": \"Twisted bow\", \"dpsChange\": 1.84, \"twoHanded\": true}]}, \"request\": {\"name\": \"Vorkath (ranged)\", \"target\": \"Vorkath\", \"equipment\": {}}}, \"export\": {\"kind\": \"inventory_setup\", \"text\": \"{}\", \"title\": \"Vorkath (ranged)\"}}", JsonObject.class)), ChatComponents.Align.FILL, 0));
				renderSized(gearList, new File(out, "14-gear.png"), BuddySidebar.DEFAULT_WIDTH, 720);
				sidebar.setSyncView(new SyncView(new SyncView.Controller()
				{
					@Override
					public boolean isOn(String key)
					{
						return !key.equals("syncLocation");
					}

					@Override
					public void set(String key, String kind, boolean on)
					{
					}

					@Override
					public java.util.List<String> hidden()
					{
						return java.util.List.of("Twisted bow");
					}

					@Override
					public void hide(String item, boolean hide)
					{
					}
				}));
				sidebar.showPage("sync");
				renderSized(sidebar, new File(out, "15-sync.png"), BuddySidebar.DEFAULT_WIDTH, 760);
				sidebar.showPage("home");
				sidebar.setRecording("Corrupted Gauntlet", System.currentTimeMillis() - 192_000, () -> {});
				renderSized(sidebar, new File(out, "16-recording.png"), BuddySidebar.DEFAULT_WIDTH, 420);
				sidebar.setRecording(null, 0, null);
				sidebar.showPage("plan");
				renderSized(sidebar, new File(out, "13-plan.png"), BuddySidebar.DEFAULT_WIDTH, 1100);
				sidebar.showPage("home");

				sidebar.showPage("progress");
				render(sidebar, new File(out, "0b-progress.png"));

				sidebar.showPage("activity");
				activity.render(sampleActivity("week"), null);
				render(sidebar, new File(out, "0c-activity-week.png"));
				activity.render(sampleActivity("day"), null);
				render(sidebar, new File(out, "0d-activity-day.png"));
				activity.render(sampleActivity("month"), null);
				render(sidebar, new File(out, "0e-activity-month.png"));
				activity.render(sampleActivity("week"), null);
				activity.showXp(true);
				render(sidebar, new File(out, "0f-activity-week-xp.png"));
				activity.showXp(false);

				sidebar.showPage("chat");
				render(sidebar, new File(out, "1-empty.png"));

				chat.addUser("What's my best ranged setup right now?");
				chat.beginAssistant();
				chat.appendAssistant("Your best ranged setup is built around the **Blazing blowpipe**:\n\n"
					+ "- **Head:** Crystal helm\n- **Neck:** Necklace of anguish\n- **Body/legs:** Crystal body + Masori chaps\n"
					+ "- **Cape:** Ava's assembler\n\nYou also own the full **crystal armour** set, so a `Bow of faerdhinen` would be a big upgrade. Want a plan to get one?");
				chat.endAssistant();
				chat.addUser("yes please");
				chat.beginAssistant();
				chat.showActivity("Checking combat achievements");
				render(sidebar, new File(out, "2-conversation.png"));

				chat.endAssistant();
				chat.addError("Server returned 500: something went wrong");
				render(sidebar, new File(out, "3-error.png"));

				chat.newChat();
				chat.addUser("glyphs");
				chat.beginAssistant();
				chat.appendAssistant("A: — B: – C: → D: ✓ E: ✅ F: … G: × H: ’ “ ” I: ≥ J: ½ K: ★ L: • M: ±");
				chat.endAssistant();
				render(sidebar, new File(out, "5-glyphs.png"));

				// Steps and sources: mid-turn (thinking visible), then finished (folded, with sources)
				chat.newChat();
				sidebar.showPage("chat");
				chat.addUser("Should I do Desert Treasure II next?");
				chat.beginAssistant();
				chat.traceReasoning("I should check their stats and quests, then the DT2 requirements on the wiki.");
				chat.traceActions(actions("c1", "osrs__get_account_md", "{}"));
				chat.traceResult("c1", mcp("{\"ok\":true}"), true);
				chat.traceActions(actions("c2", "osrs__wiki_read", "{\"page\":\"Desert Treasure II - The Fallen Empire\",\"section\":\"Details\"}"));
				render(sidebar, new File(out, "6a-steps-running.png"));
				chat.traceResult("c2", mcp("{\"title\":\"Desert Treasure II - The Fallen Empire\",\"section\":\"Details\",\"url\":\"https://oldschool.runescape.wiki/w/Desert_Treasure_II_-_The_Fallen_Empire#Details\"}"), true);
				chat.traceReasoning("Firemaking is 70 and the quest needs 75. The Garden of Death isn't done either.");
				chat.traceActions(actions("c3", "osrs__get_quests", "{}"));
				chat.traceResult("c3", mcp("{}"), true);
				chat.appendAssistant("Not quite yet. [[Desert Treasure II - The Fallen Empire|Desert Treasure II]] needs **75** [[Firemaking]] (you have 70) "
					+ "and [[The Garden of Death]]. Everything else is done.\n\nWant a plan for 75 Firemaking?");
				chat.endAssistant();
				render(sidebar, new File(out, "6b-steps-done.png"));
				chat.lastSteps().setExpanded(true);
				render(sidebar, new File(out, "6c-steps-open.png"));

				// Attachments on a sent message, outside sources, and attachments waiting in the composer
				chat.newChat();
				java.awt.image.BufferedImage shot = new java.awt.image.BufferedImage(640, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D sg = shot.createGraphics();
				sg.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(0x3b5d2a), 640, 400, new java.awt.Color(0xc9a23a)));
				sg.fillRect(0, 0, 640, 400);
				sg.dispose();
				chat.addUser("what's the best way to use this?", java.util.List.of(Attachment.image(shot, "Screenshot.png"), Attachment.text("# Loot\n- Tanzanite fang\n- Magic fang", "loot.md")));
				chat.beginAssistant();
				chat.traceActions(actions("w1", "web_search", "{\"query\":\"tanzanite fang reddit\"}"));
				chat.traceResult("w1", mcp("{\"results\":[]}"), true);
				chat.traceActions(actions("w2", "osrs__read_webpage", "{\"url\":\"https://www.reddit.com/r/2007scape/comments/abc/fang/\"}"));
				chat.traceResult("w2", mcp("{\"title\":\"Fang or blowpipe?\",\"url\":\"https://www.reddit.com/r/2007scape/comments/abc/fang/\",\"site\":\"reddit.com\",\"text\":\"...\"}"), true);
				chat.traceActions(actions("w3", "osrs__osrs_news", "{\"query\":\"blowpipe\"}"));
				chat.traceResult("w3", mcp("{\"items\":[]}"), true);
				chat.appendAssistant("That's a [[Tanzanite fang]]: use it on a [[Chisel]] to make a [[Toxic blowpipe]]. "
					+ "Most players on [this thread](https://www.reddit.com/r/2007scape/comments/abc/fang/) keep it for Zulrah and "
					+ "[the latest update](https://secure.runescape.com/m=news/blowpipe-changes?oldschool=1) changed its special attack.");
				chat.endAssistant();
				chat.addAttachment(Attachment.image(shot, "Screenshot.png"));
				chat.addAttachment(Attachment.text("## Drops\n" + "- line\n".repeat(20), null));
				chat.addAttachment(Attachment.text("plain notes", "notes.txt"));
				render(sidebar, new File(out, "6e-attachments.png"));

				// Exports for other plugins: a setup, a bank tag tab and ground markers, as cards to copy
				chat.newChat();
				chat.addUser("gear me for vorkath");
				chat.beginAssistant();
				String setupText = "{\\\"setup\\\":{\\\"inv\\\":[{\\\"id\\\":385}],\\\"eq\\\":[null,null,null,{\\\"id\\\":12926}],\\\"name\\\":\\\"Vorkath\\\"},\\\"layout\\\":[]}";
				chat.traceActions(actions("e1", "osrs__create_inventory_setup", "{\"name\":\"Vorkath\"}"));
				chat.traceResult("e1", mcp("{\"export\":{\"kind\":\"inventory_setup\",\"target\":\"Inventory Setups\",\"title\":\"Vorkath\",\"subtitle\":\"Inventory setup · 26 items\",\"text\":\"" + setupText + "\",\"howTo\":\"In the Inventory Setups panel, click the import button and paste.\"},\"unresolved\":[]}"), true);
				chat.traceActions(actions("e2", "osrs__create_bank_tag", "{\"name\":\"vorkath\"}"));
				chat.traceResult("e2", mcp("{\"export\":{\"kind\":\"bank_tag\",\"target\":\"Bank Tags\",\"title\":\"vorkath\",\"subtitle\":\"Bank tag tab · 18 items\",\"text\":\"banktags,1,vorkath,22978,385\",\"howTo\":\"In your bank, right-click the + button and choose Import tag tab.\"},\"unresolved\":[\"Divine ranging potion\"]}"), true);
				chat.traceActions(actions("e3", "osrs__create_ground_markers", "{\"name\":\"Vorkath safespots\"}"));
				chat.traceResult("e3", mcp("{\"export\":{\"kind\":\"ground_markers\",\"target\":\"Ground Markers\",\"title\":\"Vorkath safespots\",\"subtitle\":\"Ground markers · 4 tiles\",\"text\":\"[{\\\"color\\\":\\\"#FF00FF00\\\"}]\",\"howTo\":\"Right-click the world map orb and choose Import Ground Markers.\"}}"), true);
				chat.appendAssistant("Here's a [[Vorkath]] setup from your bank, a tag tab for the trip and the woox-walk tiles.");
				chat.endAssistant();
				render(sidebar, new File(out, "6i-exports.png"));

				// Composer: one line, wrapped onto a second line, and past the 8-line cap
				chat.newChat();
				render(sidebar, new File(out, "6f-composer-1.png"));
				chat.setDraft("testing");
				render(sidebar, new File(out, "6f-composer-1.png"));
				chat.setDraft("testing testingtestingtesting testing testing some more words");
				render(sidebar, new File(out, "6g-composer-2.png"));
				chat.setDraft("line\n".repeat(12) + "last line");
				render(sidebar, new File(out, "6h-composer-max.png"));
				chat.setDraft("");

				// Every Markdown feature the chat renders
				chat.newChat();
				chat.addUser("show me all the formatting");
				chat.beginAssistant();
				chat.appendAssistant("# Heading\n"
					+ "A paragraph with **bold**, *italic*, `inline code` and a wiki link to [[Vorkath]] "
					+ "(or [[Desert Treasure II - The Fallen Empire|DT2]]), plus a [plain link](https://oldschool.runescape.wiki/w/Zulrah).\n\n"
					+ "- Bullet one\n- Bullet **two** with `code`\n- Bullet three is long enough to wrap onto a second line in the panel\n\n"
					+ "1. First step\n2. Second step\n3. Third step\n\n"
					+ "```\nBank tab 1: 28 slots\nRune pouch: 3 runes\n```\n\n"
					+ "| Boss | KC |\n|---|---|\n| Vorkath | 612 |\n| Zulrah | 488 |\n\n"
					+ "Glyphs the font lacks get swapped: -> arrows, - dashes, \"quotes\".");
				chat.endAssistant();
				render(sidebar, new File(out, "7-markdown.png"));

				// Chat history: one answering, one with an unseen reply, older ones; then Home's chat card with live starters
				long now = System.currentTimeMillis();
				String[][] saved = {
					{"Vorkath gear check", "Your blowpipe with dragon darts is best here.", "600000"},
					{"Quest cape plan", "33 quests left; start with the Fremennik ones.", "7200000"},
					{"Money makers for me", "Herb runs and Zulrah are your best bets.", "259200000"},
				};
				for (int i = 0; i < saved.length; i++)
				{
					String[] c = saved[i];
					JsonObject rec = new JsonObject();
					rec.addProperty("id", "saved-" + i);
					rec.addProperty("title", c[0]);
					rec.addProperty("updatedAt", now - Long.parseLong(c[2]));
					com.google.gson.JsonArray turns = new com.google.gson.JsonArray();
					JsonObject u = new JsonObject();
					u.addProperty("role", "user");
					u.addProperty("text", c[0]);
					JsonObject r = new JsonObject();
					r.addProperty("role", "assistant");
					r.addProperty("text", c[1]);
					turns.add(u);
					turns.add(r);
					rec.add("turns", turns);
					sessions.addSaved(rec).unread = i == 0;
				}
				// One still answering (the unreachable test server's error can't arrive until this render is done)
				sessions.newChat().sendMessage("Is Vorkath worth it for me?");
				sidebar.showPage("chats");
				render(sidebar, new File(out, "8-chats.png"));

				live[0] = java.util.List.of(
					new Scenarios.Prompt("What does 80 Slayer unlock?", "x"),
					new Scenarios.Prompt("Kill Vorkath faster", "x"));
				sidebar.showPage("home");
				homeRef[0].chatMaybeChanged();
				renderSized(sidebar, new File(out, "8b-home-chat-card.png"), BuddySidebar.DEFAULT_WIDTH, 1000);

				// Nav bar and floating composer
				sidebar.showPage("progress");
				sidebar.floatingAsk().setDraft("What's the best");
				render(sidebar, new File(out, "9a-progress-ask-short.png"));
				sidebar.floatingAsk().setDraft("What's the best way to get 99 Slayer as an ironman with my stats");
				render(sidebar, new File(out, "9b-progress-ask-wide.png"));
				sidebar.floatingAsk().setDraft("What's the best way to get 99 Slayer as an ironman with my stats, and which masters should I use along the way? Also what should I block and skip?");
				render(sidebar, new File(out, "9c-progress-ask-multiline.png"));
				sidebar.floatingAsk().setDraft("");
				sidebar.showPage("chats");
				render(sidebar, new File(out, "9d-chats-nav.png"));
				sessions.open("saved-1");
				sidebar.showPage("chat");
				render(sidebar, new File(out, "9e-chat-nav.png"));
				// The send transition halfway: floating box on its way down into the chat composer
				{
					javax.swing.JLayeredPane stageLayer = new javax.swing.JLayeredPane();
					stageLayer.setPreferredSize(new java.awt.Dimension(BuddySidebar.DEFAULT_WIDTH, 620));
					JComponent ghost = ComposerTransition.frame(new java.awt.Rectangle(46, 520, 200, 40), new java.awt.Rectangle(8, 572, 276, 40),
						"Kill Vorkath faster", 0.5);
					ghost.setBounds(0, 0, BuddySidebar.DEFAULT_WIDTH, 620);
					javax.swing.JPanel bg = new javax.swing.JPanel();
					bg.setBackground(ChatComponents.BASE_BG);
					bg.setBounds(0, 0, BuddySidebar.DEFAULT_WIDTH, 620);
					stageLayer.add(bg, Integer.valueOf(0));
					stageLayer.add(ghost, Integer.valueOf(1));
					render(stageLayer, new File(out, "9f-transition-mid.png"));
				}

				// Before the player turns Squire on: the Welcome page
				sidebar.setTurnedOn(false, new WelcomeView("https://example.com/privacy", done -> {}));
				renderSized(sidebar, new File(out, "11-welcome.png"), BuddySidebar.DEFAULT_WIDTH, 760);
				sidebar.setTurnedOn(true, null);
				settings.setUsage("12 of 30 free", false);
				sidebar.showPage("settings");
				renderSized(sidebar, new File(out, "11b-settings-data.png"), BuddySidebar.DEFAULT_WIDTH, 760);
				ImageIO.write(SquireIcon.create(48), "png", new File(out, "icon.png"));
				settings.setConnectedApps(java.util.List.<String[]>of(new String[]{"c1", "Claude"}));
				renderSized(sidebar, new File(out, "11c-settings-apps.png"), BuddySidebar.DEFAULT_WIDTH, 1100);
				sidebar.setConnectView(new ConnectView(new ConnectView.Source()
				{
					@Override
					public void newCode(ConnectView.PairingCallback callback)
					{
						callback.done("KX7P-M2QD", java.time.Instant.now().plusSeconds(583), "https://rs-buddy.vercel.app/api/mcp", null);
					}

					@Override
					public void connectedApps(java.util.function.Consumer<java.util.List<String>> callback)
					{
						callback.accept(java.util.List.of());
					}
				}));
				sidebar.showPage("connect");
				renderSized(sidebar, new File(out, "12-connect.png"), BuddySidebar.DEFAULT_WIDTH, 760);


				JComponent card = WikiCards.preview(new WikiCards.Card("Vorkath", WikiCards.WIKI + "Vorkath",
					"Vorkath is a draconic boss-monster first encountered during the Dragon Slayer II quest. After the quest, players can fight it again on Ungael.", "https://oldschool.runescape.wiki/images/thumb/Vorkath.png/120px-Vorkath.png"));
				JPanel holder = new JPanel(new java.awt.BorderLayout());
				holder.setBackground(ChatComponents.BASE_BG);
				holder.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
				holder.add(card);
				renderSized(holder, new File(out, "6d-wiki-card.png"), 264, holder.getPreferredSize().height);

				sidebar.showPage("settings");
				settings.setStatus("Last synced 08:41:12<ul><li>Combat achievements: 114 tasks done</li><li>Kill counts: 58</li></ul>");
				render(sidebar, new File(out, "4-settings.png"));
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		System.exit(0);
	}

	private static void render(Component c, File file) throws Exception
	{
		int w = Integer.getInteger("preview.width", c.getPreferredSize().width);
		int h = Integer.getInteger("preview.height", 620);
		int scale = Integer.getInteger("preview.scale", 2);
		// Lay out inside an undisplayed frame so fonts and HTML views resolve
		JFrame frame = new JFrame();
		frame.setUndecorated(true);
		frame.setContentPane((Container) c);
		frame.setSize(w, h);
		frame.addNotify();
		frame.validate();
		for (int i = 0; i < 3; i++)
		{
			validateTree((Container) c);
		}
		BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		c.paint(g);
		g.dispose();
		ImageIO.write(img, "png", file);
		frame.setContentPane(new javax.swing.JPanel());
		frame.dispose();
	}

	private static void renderSized(Component c, File file, int w, int h) throws Exception
	{
		System.setProperty("preview.width", String.valueOf(w));
		System.setProperty("preview.height", String.valueOf(h));
		render(c, file);
		System.clearProperty("preview.width");
		System.clearProperty("preview.height");
	}

	private static void validateTree(Container c)
	{
		c.invalidate();
		c.validate();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				((Container) child).doLayout();
			}
		}
	}
}
