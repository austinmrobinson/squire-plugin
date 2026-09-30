package com.squire;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.laf.RuneLiteLAF;
import okhttp3.OkHttpClient;

/**
 * End-to-end check of the chat history against a running local server (npm run dev): two chats answering at
 * once, saved and reloaded from disk, a reopened chat continuing its session, and an expired session falling back
 * to a recap. Sends no account, so the player's memory isn't touched. ./gradlew chatHistoryCheck
 */
public class ChatHistoryCheck
{
	private static final String SERVER = "http://127.0.0.1:3000";
	private static final String MODEL = "anthropic/claude-sonnet-5";
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		String token = token();
		Path dir = Files.createTempDirectory("squire-chats");
		Gson gson = new Gson();
		ModelCatalog models = new ModelCatalog(new OkHttpClient(), gson, () -> "", () -> "");
		on(() ->
		{
			RuneLiteLAF.setup();
			return null;
		});

		// 1. Two conversations at once
		ChatSessions sessions = on(() -> new ChatSessions(() -> view(gson, models, token), new ChatStore(dir.toFile(), gson)));
		ChatView a = on(() ->
		{
			sessions.startWith("Remember the word PINEAPPLE for later. Reply with just: ok");
			return sessions.current();
		});
		ChatView b = on(() ->
		{
			sessions.startWith("In one short sentence: what is the Grand Exchange?");
			return sessions.current();
		});
		check("both chats working at once", on(() -> a.isBusy() && b.isBusy() && a != b));
		waitIdle(a, 120);
		waitIdle(b, 120);
		check("both answered", on(() -> lastText(a).length() > 0 && lastText(b).length() > 0));
		check("two chats listed", on(() -> sessions.chats().size() == 2));
		Thread.sleep(500); // let the history thread write

		// 2. Reload from disk
		ChatSessions reloaded = on(() -> new ChatSessions(() -> view(gson, models, token), new ChatStore(dir.toFile(), gson)));
		on(() ->
		{
			reloaded.load();
			return null;
		});
		Thread.sleep(800);
		String idA = on(a::id);
		check("history reloads both chats", on(() -> reloaded.chats().size() == 2));
		ChatView a2 = on(() ->
		{
			reloaded.open(idA);
			return reloaded.current();
		});
		check("reopened chat has its messages", on(() -> a2.toRecord().getAsJsonArray("turns").size() == 2));

		// 3. Continue the same server session
		on(() ->
		{
			a2.sendMessage("What word did I ask you to remember? Reply with just the word.");
			return null;
		});
		waitIdle(a2, 120);
		String reply = on(() -> lastText(a2));
		check("reopened chat continues its session (" + reply.trim() + ")", reply.toUpperCase().contains("PINEAPPLE"));
		check("no replayed old events in the reply", !reply.toLowerCase().contains("ok\n") && reply.length() < 200);

		// 4. Expired session: the recap carries the conversation into a new one
		JsonObject rec = on(a2::toRecord);
		rec.addProperty("sessionId", "does-not-exist");
		rec.addProperty("streamIndex", 0);
		ChatView a3 = on(() ->
		{
			ChatView v = view(gson, models, token);
			v.restore(rec);
			v.sendMessage("Say the remembered word again, just the word.");
			return v;
		});
		waitIdle(a3, 120);
		String reply3 = on(() -> lastText(a3));
		check("expired session recovers via recap (" + reply3.trim() + ")", reply3.toUpperCase().contains("PINEAPPLE"));

		on(() ->
		{
			sessions.shutdown();
			reloaded.shutdown();
			a3.close();
			return null;
		});
		System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static ChatView view(Gson gson, ModelCatalog models, String token)
	{
		ChatClient client = new ChatClient(new OkHttpClient(), gson, () -> SERVER, () -> token, () -> MODEL);
		return new ChatView(client, Map::of, models, () -> MODEL, id -> {});
	}

	private static String lastText(ChatView v)
	{
		var turns = v.toRecord().getAsJsonArray("turns");
		for (int i = turns.size() - 1; i >= 0; i--)
		{
			JsonObject t = turns.get(i).getAsJsonObject();
			if (!"user".equals(t.get("role").getAsString()))
			{
				return t.get("role").getAsString().equals("error") ? "ERROR: " + t.get("text").getAsString() : t.get("text").getAsString();
			}
		}
		return "";
	}

	private static void waitIdle(ChatView v, int seconds) throws Exception
	{
		long end = System.currentTimeMillis() + seconds * 1000L;
		Thread.sleep(500);
		while (on(v::isBusy) && System.currentTimeMillis() < end)
		{
			Thread.sleep(250);
		}
	}

	private static void check(String label, boolean ok)
	{
		System.out.println((ok ? "PASS  " : "FAIL  ") + label);
		if (!ok)
		{
			failures++;
		}
	}

	private static <T> T on(Callable<T> task) throws Exception
	{
		AtomicReference<T> out = new AtomicReference<>();
		AtomicReference<Exception> err = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				out.set(task.call());
			}
			catch (Exception e)
			{
				err.set(e);
			}
		});
		if (err.get() != null)
		{
			throw err.get();
		}
		return out.get();
	}

	/** The server's ingest token, read from its env file (never printed). */
	private static String token() throws Exception
	{
		for (String line : Files.readAllLines(new File("../server/.env.local").toPath()))
		{
			if (line.startsWith("INGEST_TOKEN="))
			{
				return line.substring("INGEST_TOKEN=".length()).trim().replaceAll("^\"|\"$", "");
			}
		}
		throw new IllegalStateException("INGEST_TOKEN not found in server/.env.local");
	}
}
