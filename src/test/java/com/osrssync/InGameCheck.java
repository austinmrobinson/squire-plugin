package com.osrssync;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;

/**
 * Checks the in-game chat path without the game: chat-line formatting, and a question asked "from the chatbox"
 * against the local server (npm run dev) coming back short and filed under an In-game chat. ./gradlew inGameCheck
 */
public class InGameCheck
{
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		// Formatting
		List<String> lines = InGameChat.toChatLines("Use your **[[Toxic blowpipe]]** with [[Dragon dart|dragon darts]].\n\n"
			+ "- Bring a *super antifire*\n* Pray [[Protect from Magic]]\n\n## Heading\nSee [the guide](https://oldschool.runescape.wiki/w/Vorkath).");
		lines.forEach(l -> System.out.println("  | " + l));
		check("links and emphasis become plain text", lines.get(0).equals("Use your Toxic blowpipe with dragon darts."));
		check("bullets kept as dashes", lines.get(1).equals("- Bring a super antifire") && lines.get(2).equals("- Pray Protect from Magic"));
		check("headings and markdown links flattened", lines.get(3).equals("Heading") && lines.get(4).equals("See the guide."));

		// A question from the chatbox, against the local server
		String token = null;
		for (String line : Files.readAllLines(new File("../server/.env.local").toPath()))
		{
			if (line.startsWith("INGEST_TOKEN="))
			{
				token = line.substring("INGEST_TOKEN=".length()).trim().replaceAll("^\"|\"$", "");
			}
		}
		String t = token;
		Gson gson = new Gson();
		ModelCatalog models = new ModelCatalog(new OkHttpClient(), gson, () -> "", () -> "");
		AtomicReference<String> reply = new AtomicReference<>();
		AtomicReference<ChatSessions> sessions = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			sessions.set(new ChatSessions(() -> new ChatView(new ChatClient(new OkHttpClient(), gson, () -> "http://127.0.0.1:3000", () -> t, () -> "anthropic/claude-sonnet-5"),
				Map::of, models, () -> "anthropic/claude-sonnet-5", id -> {}), null));
			ChatView v = sessions.get().inGame(view -> view.setInGame(
				Map.of("replyIn", "game chatbox", "replyGuide", "Answer in 1-3 short sentences with no lists, headings or markdown."),
				new ChatView.ReplyListener()
				{
					@Override
					public void reply(String markdown, boolean more)
					{
						reply.set(markdown + (more ? "\n[more in panel]" : ""));
					}

					@Override
					public void error(String message)
					{
						reply.set("ERROR: " + message);
					}
				}));
			v.sendMessage("what's the fastest prayer xp for an ironman?");
		});
		long end = System.currentTimeMillis() + 180_000;
		while (reply.get() == null && System.currentTimeMillis() < end)
		{
			Thread.sleep(250);
		}
		String r = reply.get() == null ? "" : reply.get();
		System.out.println("\nReply as chat lines:");
		InGameChat.toChatLines(r).forEach(l -> System.out.println("  Squire | " + l));
		check("got a reply", !r.isEmpty() && !r.startsWith("ERROR"));
		check("fits the chatbox (<= 5 lines)", InGameChat.toChatLines(r.replace("\n[more in panel]", "")).size() <= 5);
		check("no interim narration", !r.toLowerCase().matches("(?s).*(let me|i'll check|checking).*"));
		check("no markdown lists or headings", !r.matches("(?s).*(^|\\n)\\s*([-*] |#).*"));
		AtomicReference<String> title = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> title.set(sessions.get().chats().get(0).title));
		check("filed as an In-game chat (" + title.get() + ")", title.get().startsWith("In-game: "));

		System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void check(String label, boolean ok)
	{
		System.out.println((ok ? "PASS  " : "FAIL  ") + label);
		if (!ok)
		{
			failures++;
		}
	}
}
