package com.squire;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the agent did for one reply: its thinking, the tools it called (in plain words), and the sources
 * those tools drew on. Sources get stable numbers so links in the reply can cite them as [1], [2], ...
 */
class ChatTrace
{
	/** A thought or a tool call, in the order they happened. */
	static final class Entry
	{
		final boolean thought;
		final StringBuilder text = new StringBuilder();
		String label;
		String detail;
		String url;
		boolean done;
		boolean failed;

		private Entry(boolean thought)
		{
			this.thought = thought;
		}
	}

	static final class Source
	{
		final String title;
		final String url;
		/** Who it's from: "OSRS Wiki", "Wiki prices", "Squire" (the player's synced data), ... */
		final String origin;

		Source(String title, String url, String origin)
		{
			this.title = title;
			this.url = url;
			this.origin = origin;
		}
	}


	final List<Entry> entries = new ArrayList<>();
	final List<Source> sources = new ArrayList<>();
	private final Map<String, Entry> byCall = new LinkedHashMap<>();
	private final Map<String, String> toolByCall = new LinkedHashMap<>();
	private Entry thinking;
	long startedAt = System.currentTimeMillis();
	long finishedAt;

	// ---- Saving with the chat history

	JsonObject toJson()
	{
		JsonObject o = new JsonObject();
		o.addProperty("startedAt", startedAt);
		o.addProperty("finishedAt", finishedAt);
		JsonArray es = new JsonArray();
		for (Entry e : entries)
		{
			JsonObject j = new JsonObject();
			j.addProperty("thought", e.thought);
			if (e.text.length() > 0)
			{
				j.addProperty("text", e.text.toString());
			}
			j.addProperty("label", e.label);
			j.addProperty("detail", e.detail);
			j.addProperty("url", e.url);
			j.addProperty("done", e.done);
			j.addProperty("failed", e.failed);
			es.add(j);
		}
		o.add("entries", es);
		JsonArray ss = new JsonArray();
		for (Source src : sources)
		{
			JsonObject j = new JsonObject();
			j.addProperty("title", src.title);
			j.addProperty("url", src.url);
			j.addProperty("origin", src.origin);
			ss.add(j);
		}
		o.add("sources", ss);
		JsonArray xs = new JsonArray();
		exports.forEach(x -> xs.add(x.toJson()));
		o.add("exports", xs);
		JsonArray gs = new JsonArray();
		gears.forEach(gs::add);
		o.add("gears", gs);
		o.addProperty("savedPlan", savedPlan);
		if (plan != null)
		{
			o.add("plan", plan);
		}
		return o;
	}

	static ChatTrace fromJson(JsonObject o)
	{
		ChatTrace t = new ChatTrace();
		t.startedAt = o.has("startedAt") ? o.get("startedAt").getAsLong() : 0;
		t.finishedAt = o.has("finishedAt") ? o.get("finishedAt").getAsLong() : t.startedAt;
		if (o.has("entries"))
		{
			for (JsonElement el : o.getAsJsonArray("entries"))
			{
				JsonObject j = el.getAsJsonObject();
				Entry e = new Entry(j.has("thought") && j.get("thought").getAsBoolean());
				e.text.append(str(j, "text"));
				e.label = j.has("label") && !j.get("label").isJsonNull() ? j.get("label").getAsString() : null;
				e.detail = j.has("detail") && !j.get("detail").isJsonNull() ? j.get("detail").getAsString() : null;
				e.url = j.has("url") && !j.get("url").isJsonNull() ? j.get("url").getAsString() : null;
				e.done = !j.has("done") || j.get("done").getAsBoolean();
				e.failed = j.has("failed") && j.get("failed").getAsBoolean();
				t.entries.add(e);
			}
		}
		if (o.has("sources"))
		{
			for (JsonElement el : o.getAsJsonArray("sources"))
			{
				JsonObject j = el.getAsJsonObject();
				t.sources.add(new Source(str(j, "title"), str(j, "url"), str(j, "origin")));
			}
		}
		t.savedPlan = o.has("savedPlan") && o.get("savedPlan").getAsBoolean();
		if (o.has("plan") && o.get("plan").isJsonObject())
		{
			t.plan = o.getAsJsonObject("plan");
		}
		if (o.has("gears"))
		{
			for (JsonElement el : o.getAsJsonArray("gears"))
			{
				t.gears.add(el.getAsJsonObject());
			}
		}
		if (o.has("exports"))
		{
			for (JsonElement el : o.getAsJsonArray("exports"))
			{
				ExportCards.Export x = ExportCards.Export.from(el.getAsJsonObject());
				if (x != null)
				{
					t.exports.add(x);
				}
			}
		}
		return t;
	}

	// ---- Events

	void reasoning(String delta)
	{
		if (delta == null || delta.isEmpty())
		{
			return;
		}
		if (thinking == null)
		{
			thinking = new Entry(true);
			entries.add(thinking);
		}
		thinking.text.append(delta);
	}

	void reasoningDone()
	{
		thinking = null;
	}

	void actions(JsonArray actions)
	{
		thinking = null;
		for (JsonElement e : actions)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject a = e.getAsJsonObject();
			String callId = str(a, "callId");
			String kind = str(a, "kind");
			String tool = str(a, "toolName").replaceFirst("^osrs__", "");
			JsonObject input = a.has("input") && a.get("input").isJsonObject() ? a.getAsJsonObject("input") : new JsonObject();
			toolByCall.put(callId, "load-skill".equals(kind) ? "load-skill" : tool);
			if ("connection_search".equals(tool))
			{
				continue; // the agent finding its own tools; not interesting to players
			}
			Entry entry = new Entry(false);
			if ("load-skill".equals(kind))
			{
				entry.label = "Followed the " + str(input, "skill").replace('-', ' ') + " playbook";
			}
			else
			{
				entry.label = label(tool);
				entry.detail = detail(tool, input);
			}
			entries.add(entry);
			byCall.put(callId, entry);
		}
	}

	/** Data the agent made for other plugins this turn (setups, tags, markers), shown as cards to copy. */
	final List<ExportCards.Export> exports = new ArrayList<>();
	/** Gear views this turn ({gear, export}): the game's equipment screen, editable, with a copy button. */
	final List<JsonObject> gears = new ArrayList<>();
	/** Squire saved the player's plan this turn: the reply links to it, and Home and the Plan page reload it. */
	boolean savedPlan;
	/** The plan as saved (checked against the account), shown as a card under the reply. */
	JsonObject plan;
	/** Set by the plugin: reload the plan, and open the Plan page. */
	static Runnable planSaved = () -> {};
	static Runnable openPlan = () -> {};
	/** Set by the plugin: Squire's start_session_review tool starts observing with this label. */
	static java.util.function.Consumer<String> sessionStart = label -> {};

	void result(String callId, JsonElement output, boolean ok)
	{
		Entry entry = byCall.get(callId);
		String tool = toolByCall.getOrDefault(callId, "");
		if (entry != null)
		{
			entry.done = true;
			entry.failed = !ok || isError(output);
		}
		if (!ok || isError(output))
		{
			return;
		}
		JsonElement data = unwrap(output);
		if ((tool.equals("show_gear") || tool.equals("create_inventory_setup")) && data != null && data.isJsonObject() && data.getAsJsonObject().has("gear"))
		{
			JsonObject g = new JsonObject();
			g.add("gear", data.getAsJsonObject().get("gear"));
			g.add("export", data.getAsJsonObject().get("export"));
			gears.add(g);
			return;
		}
		if (tool.equals("start_session_review") && data != null && data.isJsonObject())
		{
			String label = str(data.getAsJsonObject(), "label");
			javax.swing.SwingUtilities.invokeLater(() -> sessionStart.accept(label.isEmpty() ? "Session" : label));
			return;
		}
		if (tool.equals("save_plan"))
		{
			savedPlan = true;
			if (data != null && data.isJsonObject() && data.getAsJsonObject().get("plan") instanceof JsonObject)
			{
				plan = data.getAsJsonObject().getAsJsonObject("plan");
			}
			javax.swing.SwingUtilities.invokeLater(planSaved);
			return;
		}
		if (tool.startsWith("create_") && data != null && data.isJsonObject() && data.getAsJsonObject().has("export"))
		{
			ExportCards.Export export = ExportCards.Export.from(data.getAsJsonObject());
			if (export != null)
			{
				exports.add(export);
			}
			return;
		}
		// Squire's own tools (the player's synced data, the calculators) aren't listed as sources:
		// the Steps line already shows they were used. Sources are what came from outside.
		if (tool.equals("wiki_read") && data != null && data.isJsonObject())
		{
			JsonObject o = data.getAsJsonObject();
			String url = str(o, "url");
			String title = str(o, "title") + (str(o, "section").isEmpty() ? "" : ": " + str(o, "section"));
			if (!url.isEmpty())
			{
				addSource(new Source(title.isEmpty() ? titleFromUrl(url) : title, url, "OSRS Wiki"));
				if (entry != null)
				{
					entry.url = url;
				}
			}
		}
		else if (Set.of("drop_table", "drop_luck", "ge_price", "ge_history").contains(tool) && data != null && data.isJsonObject())
		{
			String url = str(data.getAsJsonObject(), "source");
			if (url.startsWith("http"))
			{
				boolean prices = url.contains("prices.runescape.wiki");
				String title = prices ? "GE prices" + (entry != null && entry.detail != null ? ": " + entry.detail : "") : titleFromUrl(url);
				addSource(new Source(title, url, prices ? "Wiki prices" : "OSRS Wiki"));
				if (entry != null)
				{
					entry.url = url;
				}
			}
		}
		else if ((tool.equals("read_webpage") || tool.equals("web_fetch")) && data != null && data.isJsonObject())
		{
			JsonObject o = data.getAsJsonObject();
			String url = str(o, "url");
			if (url.startsWith("http"))
			{
				String title = str(o, "title");
				addSource(new Source(title.isEmpty() ? hostOf(url) : title, url, originOf(url)));
				if (entry != null)
				{
					entry.url = url;
				}
			}
		}
		else if (tool.equals("hiscores_lookup") && entry != null && entry.detail != null)
		{
			addSource(new Source("Hiscores: " + entry.detail, null, "Jagex"));
		}
	}

	void finish()
	{
		finishedAt = System.currentTimeMillis();
		thinking = null;
	}

	// ---- Citations

	/** The 1-based number of this URL among the sources, adding it if a reply links somewhere new. */
	int cite(String url, String linkText)
	{
		String key = pageKey(url);
		for (int i = 0; i < sources.size(); i++)
		{
			Source s = sources.get(i);
			if (s.url != null && pageKey(s.url).equals(key))
			{
				return i + 1;
			}
		}
		addSource(new Source(linkText == null || linkText.isBlank() ? titleFromUrl(url) : linkText, url, originOf(url)));
		for (int i = 0; i < sources.size(); i++)
		{
			if (url.equals(sources.get(i).url))
			{
				return i + 1;
			}
		}
		return sources.size();
	}

	int toolCount()
	{
		return (int) entries.stream().filter(e -> !e.thought).count();
	}

	boolean hasThoughts()
	{
		return entries.stream().anyMatch(e -> e.thought);
	}

	private static final Map<String, String> PRESENT = Map.ofEntries(
		Map.entry("Read", "Reading"), Map.entry("Checked", "Checking"), Map.entry("Searched", "Searching"),
		Map.entry("Listed", "Listing"), Map.entry("Looked", "Looking"), Map.entry("Queried", "Querying"),
		Map.entry("Worked", "Working"), Map.entry("Ran", "Running"), Map.entry("Found", "Finding"),
		Map.entry("Ranked", "Ranking"), Map.entry("Followed", "Following"));

	/** The step in progress, in the present tense ("Reading the wiki"), for the live status line. */
	String liveStatus()
	{
		for (int i = entries.size() - 1; i >= 0; i--)
		{
			Entry e = entries.get(i);
			if (!e.thought)
			{
				String label = e.label;
				int space = label.indexOf(' ');
				if (space > 0 && PRESENT.containsKey(label.substring(0, space)))
				{
					label = PRESENT.get(label.substring(0, space)) + label.substring(space);
				}
				return e.done ? "Thinking" : label;
			}
		}
		return "Thinking";
	}

	private void addSource(Source source)
	{
		for (Source s : sources)
		{
			boolean sameUrl = s.url != null && source.url != null && pageKey(s.url).equals(pageKey(source.url)) && s.title.equals(source.title);
			boolean sameTitle = s.url == null && source.url == null && s.title.equals(source.title);
			if (sameUrl || sameTitle)
			{
				return;
			}
		}
		// Linked sources (the wiki) come first, then the player's own data
		int at = sources.size();
		if (source.url != null)
		{
			for (int i = 0; i < sources.size(); i++)
			{
				if (sources.get(i).url == null)
				{
					at = i;
					break;
				}
			}
		}
		sources.add(at, source);
	}

	// ---- Plain-language labels

	static String label(String tool)
	{
		switch (tool)
		{
			case "get_account_md":
				return "Read your account summary";
			case "list_accounts":
				return "Listed your synced accounts";
			case "get_account_overview":
				return "Read your account overview";
			case "get_activity":
				return "Checked what you've been doing";
			case "get_xp_gains":
				return "Checked your XP gains";
			case "search_items":
				return "Searched your items";
			case "get_container":
				return "Checked your items in";
			case "get_wealth_history":
				return "Checked your wealth history";
			case "get_gear":
				return "Checked your gear";
			case "get_quests":
				return "Checked your quests";
			case "get_achievements":
				return "Checked your achievement diaries";
			case "get_collection_log":
				return "Checked your collection log";
			case "get_combat_achievements":
				return "Checked your combat achievements";
			case "get_kill_counts":
				return "Checked your kill counts";
			case "get_recent_events":
				return "Checked your recent activity";
			case "get_loot_summary":
				return "Checked your loot";
			case "describe_schema":
				return "Looked at how your data is stored";
			case "run_sql":
				return "Queried your synced data";
			case "wiki_search":
				return "Searched the wiki";
			case "web_search":
				return "Searched the web";
			case "osrs_news":
				return "Checked the OSRS news";
			case "read_webpage":
			case "web_fetch":
				return "Read a page";
			case "wiki_read":
				return "Read the wiki";
			case "drop_table":
				return "Read the drop table";
			case "drop_luck":
				return "Worked out your drop luck";
			case "ge_price":
				return "Checked GE prices";
			case "ge_history":
				return "Checked GE price history";
			case "hiscores_lookup":
				return "Looked up the hiscores";
			case "where_to_get":
				return "Looked up where to get it";
			case "check_requirements":
				return "Checked the requirements";
			case "get_plan":
				return "Read your plan";
			case "save_plan":
				return "Saved your plan";
			case "show_gear":
				return "Put together a gear setup";
			case "start_session_review":
				return "Started observing";
			case "get_session":
				return "Read your session";
			case "list_sessions":
				return "Checked your past sessions";
			case "create_inventory_setup":
				return "Made an inventory setup";
			case "create_bank_tag":
				return "Made a bank tag tab";
			case "create_ground_markers":
				return "Made ground markers";
			case "player__save_memory":
				return "Remembered something about you";
			case "player__remove_memory":
				return "Forgot an old note";
			case "player__update_memory":
				return "Updated a note about you";
			case "get_personal_bests":
				return "Checked your personal bests";
			case "get_gains":
				return "Checked your gains";
			case "strategy_gear":
				return "Read the wiki's gear guide";
			case "get_progression_guide":
				return "Checked the progression guide";
			case "monster_lookup":
				return "Looked up monster stats";
			case "dps_calc":
				return "Ran the DPS calculator";
			case "best_gear":
				return "Found your best gear";
			case "gear_upgrades":
				return "Ranked gear upgrades";
			// The agent framework's own sandbox tools: Squire reading its guides (skills) and scratch notes
			case "Bash":
			case "bash":
			case "Read":
			case "read":
			case "Glob":
			case "glob":
			case "Grep":
			case "grep":
				return "Checked its guides";
			case "Write":
			case "write":
			case "Edit":
			case "edit":
				return "Made some notes";
			default:
				String words = tool.replace('_', ' ').trim();
				return words.isEmpty() ? "Used a tool" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
		}
	}

	private static String detail(String tool, JsonObject input)
	{
		if (tool.equals("wiki_read"))
		{
			String page = str(input, "page");
			String section = str(input, "section");
			return page.isEmpty() ? null : page + (section.isEmpty() ? "" : ": " + section);
		}
		if (tool.startsWith("create_") && !str(input, "name").isEmpty())
		{
			return str(input, "name");
		}
		if (tool.equals("run_sql") || tool.equals("describe_schema"))
		{
			return null;
		}
		if ((tool.equals("read_webpage") || tool.equals("web_fetch")) && !str(input, "url").isEmpty())
		{
			String host = hostOf(str(input, "url"));
			return host.isEmpty() ? null : host;
		}
		for (String key : new String[]{"query", "monster", "target", "item", "player", "container", "source", "range", "style"})
		{
			String v = str(input, key);
			if (!v.isEmpty())
			{
				return key.equals("query") ? "\"" + v + "\"" : v;
			}
		}
		if (input.has("items") && input.get("items").isJsonArray())
		{
			List<String> items = new ArrayList<>();
			input.getAsJsonArray("items").forEach(i -> items.add(i.isJsonPrimitive() ? i.getAsString() : ""));
			return String.join(", ", items);
		}
		return null;
	}

	// ---- Tool output parsing

	/** MCP results wrap JSON as text in content[0]; skills return plain strings. */
	private static JsonElement unwrap(JsonElement output)
	{
		if (output == null || output.isJsonNull())
		{
			return null;
		}
		if (output.isJsonObject() && output.getAsJsonObject().has("content"))
		{
			JsonElement content = output.getAsJsonObject().get("content");
			if (content.isJsonArray() && content.getAsJsonArray().size() > 0)
			{
				JsonElement first = content.getAsJsonArray().get(0);
				if (first.isJsonObject() && first.getAsJsonObject().has("text"))
				{
					String text = first.getAsJsonObject().get("text").getAsString();
					try
					{
						return new JsonParser().parse(text);
					}
					catch (RuntimeException e)
					{
						return null;
					}
				}
			}
		}
		return output;
	}

	private static boolean isError(JsonElement output)
	{
		return output != null && output.isJsonObject() && output.getAsJsonObject().has("isError")
			&& output.getAsJsonObject().get("isError").isJsonPrimitive() && output.getAsJsonObject().get("isError").getAsBoolean();
	}

	/** Where a link points, in words: "OSRS Wiki", "OSRS News", "Reddit", "X", "YouTube", or the site's host. */
	static String originOf(String url)
	{
		String host = hostOf(url);
		if (host.equals("prices.runescape.wiki"))
		{
			return "Wiki prices";
		}
		if (host.endsWith("runescape.wiki"))
		{
			return "OSRS Wiki";
		}
		if (host.endsWith("runescape.com"))
		{
			return "OSRS News";
		}
		if (host.endsWith("reddit.com") || host.equals("redd.it"))
		{
			return "Reddit";
		}
		if (host.equals("x.com") || host.endsWith("twitter.com"))
		{
			return "X";
		}
		if (host.endsWith("youtube.com") || host.equals("youtu.be"))
		{
			return "YouTube";
		}
		return host.isEmpty() ? "Web" : host;
	}

	static String hostOf(String url)
	{
		try
		{
			String host = java.net.URI.create(url.trim()).getHost();
			return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", "");
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	/** Page identity ignoring the #section, so a link and a section read of the same page match. */
	private static String pageKey(String url)
	{
		int hash = url.indexOf('#');
		return (hash >= 0 ? url.substring(0, hash) : url).replaceAll("/+$", "").toLowerCase();
	}

	/** "https://oldschool.runescape.wiki/w/Desert_Treasure_II#Details" -> "Desert Treasure II: Details". */
	static String titleFromUrl(String url)
	{
		try
		{
			int w = url.indexOf("/w/");
			if (w < 0)
			{
				return url.replaceFirst("^https?://", "");
			}
			String rest = url.substring(w + 3);
			String section = null;
			int hash = rest.indexOf('#');
			if (hash >= 0)
			{
				section = rest.substring(hash + 1);
				rest = rest.substring(0, hash);
			}
			String page = URLDecoder.decode(rest, StandardCharsets.UTF_8).replace('_', ' ');
			return section == null ? page : page + ": " + URLDecoder.decode(section, StandardCharsets.UTF_8).replace('_', ' ');
		}
		catch (RuntimeException e)
		{
			return url;
		}
	}

	private static String str(JsonObject o, String key)
	{
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}
}
