package com.squire;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import okhttp3.OkHttpClient;

/**
 * Times the sidebar's UI work on the Swing thread, offscreen: page switches (switch + layout + one frame's paint),
 * rebuilding Home, long chats (appending, streaming, painting) and Activity. Anything that takes more than a
 * frame (16ms) is a visible hitch. ./gradlew panelPerf
 */
public class PanelPerf
{
	private static final int FRAME_MS = 16;
	private static JFrame frame;
	private static SquireSidebar sidebar;

	public static void main(String[] args) throws Exception
	{
		System.setProperty("perf.sample", System.getProperty("perf.sample", "false"));
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				run();
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		System.exit(0);
	}

	private static void run() throws Exception
	{
		RuneLiteLAF.setup();
		ChatClient client = new ChatClient(new OkHttpClient(), new Gson(), () -> "http://127.0.0.1:1", () -> "x", () -> ModelCatalog.DEFAULT_ID);
		ModelCatalog models = new ModelCatalog(new OkHttpClient(), new Gson(), () -> "", () -> "");
		ChatSessions sessions = new ChatSessions(() -> new ChatView(new ChatClient(new OkHttpClient(), new Gson(), () -> "http://127.0.0.1:1", () -> "x", () -> ModelCatalog.DEFAULT_ID), Map::of, models, () -> ChatView.AUTO_MODEL, id -> {}), null);
				ChatView chat = sessions.current();
		SettingsView settings = new SettingsView(() -> {});
		AccountApi api = new AccountApi(new OkHttpClient(), new Gson(), () -> "", () -> "", () -> null);
		SkillIconManager icons = new SkillIconManager();
		ProgressView progress = new ProgressView(skill -> icons.getSkillImage(skill, true), null);
		ActivityView activity = new ActivityView(api);
		HomeView[] home = new HomeView[1];
		sidebar = new SquireSidebar(actions -> home[0] = new HomeView(api, progress, actions, sessions, java.util.List::of),
			progress, activity, sessions, settings, SquireSidebar.DEFAULT_WIDTH, w -> {});
		JsonObject overview = new Gson().fromJson(PanelPreview.SAMPLE_OVERVIEW, JsonObject.class);
		JsonObject today = PanelPreview.sampleActivity("day");
		home[0].show(overview, today);
		progress.show(overview);
		activity.render(PanelPreview.sampleActivity("week"), null);

		frame = new JFrame();
		frame.setUndecorated(true);
		frame.setContentPane(sidebar);
		frame.setSize(SquireSidebar.DEFAULT_WIDTH, 760);
		frame.addNotify();
		frame.validate();
		frame();

		System.out.println("Sidebar performance (ms on the Swing thread; a frame is " + FRAME_MS + "ms)\n");
		System.out.printf("%-34s %7s %7s %7s%n", "", "median", "p95", "max");

		// Warm up class loading, fonts and HTML views
		for (String p : new String[]{"progress", "activity", "chat", "settings", "home"})
		{
			sidebar.showPage(p);
			frame();
		}

		for (String p : new String[]{"home", "progress", "activity", "chat", "settings"})
		{
			String from = p.equals("home") ? "progress" : "home";
			report("Switch " + from + " -> " + p, 30, () -> sidebar.showPage(from), () ->
			{
				sidebar.showPage(p);
				frame();
			});
		}

		sidebar.showPage("home");
		report("Home: rebuild with new data", 30, null, () ->
		{
			home[0].show(overview, today);
			frame();
		});
		sidebar.showPage("activity");
		report("Activity: render a week", 30, null, () ->
		{
			activity.render(PanelPreview.sampleActivity("week"), null);
			frame();
		});
		sidebar.showPage("progress");
		report("Progress: render", 30, null, () ->
		{
			progress.show(overview);
			frame();
		});

		// A long conversation
		sidebar.showPage("chat");
		List<Long> perExchange = new ArrayList<>();
		for (int i = 0; i < 60; i++)
		{
			long t0 = System.nanoTime();
			exchange(chat, i);
			frame();
			perExchange.add(System.nanoTime() - t0);
		}
		print("Chat: add an exchange (60 in)", perExchange);
		report("Chat: repaint at 60 exchanges", 20, null, PanelPerf::frame);
		report("Switch home -> chat (60 exchanges)", 20, () -> sidebar.showPage("home"), () ->
		{
			sidebar.showPage("chat");
			frame();
		});

		// Streaming a long reply: each render re-parses the whole reply so far
		chat.addUser("stream test");
		chat.beginAssistant();
		String reply = longReply();
		List<Long> perChunk = new ArrayList<>();
		for (int at = 0; at < reply.length(); at += 60)
		{
			String delta = reply.substring(at, Math.min(reply.length(), at + 60));
			long t0 = System.nanoTime();
			chat.appendAssistant(delta);
			chat.flushRender();
			frame();
			perChunk.add(System.nanoTime() - t0);
		}
		chat.endAssistant();
		print("Chat: stream a " + reply.length() + "-char reply", perChunk);
		System.out.println("\n(last streamed chunk: " + ms(perChunk.get(perChunk.size() - 1)) + "ms)");

		// Chat history: switching between long chats, reopening one from disk, the list of chats
		JsonObject saved = chat.toRecord();
		for (int i = 0; i < 60; i++)
		{
			JsonObject u = new JsonObject();
			u.addProperty("role", "user");
			u.addProperty("text", "Question number " + i + ": what should I do next at [[Vorkath]]?");
			JsonObject r = new JsonObject();
			r.addProperty("role", "assistant");
			r.addProperty("text", longReply().substring(0, 700));
			saved.getAsJsonArray("turns").add(u);
			saved.getAsJsonArray("turns").add(r);
		}
		saved.addProperty("title", "Saved long chat");
		for (int i = 0; i < 50; i++)
		{
			JsonObject copy = saved.deepCopy();
			copy.addProperty("id", "saved-" + i);
			copy.addProperty("updatedAt", System.currentTimeMillis() - i * 3_600_000L);
			sessions.addSaved(copy);
		}
		String firstId = chat.id();
		List<Long> reopen = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			String id = "saved-" + i;
			long t0 = System.nanoTime();
			sessions.open(id);
			sidebar.showPage("chat");
			frame();
			reopen.add(System.nanoTime() - t0);
		}
		print("Open a saved 120-message chat", reopen);
		report("Switch between two long chats", 20, () -> sessions.open("saved-1"), () ->
		{
			sessions.open("saved-2");
			sidebar.showPage("chat");
			frame();
		});
		if (Boolean.getBoolean("perf.sample"))
		{
			sample(() ->
			{
				for (int i = 0; i < 10; i++)
				{
					sessions.open(i % 2 == 0 ? "saved-1" : "saved-2");
					sidebar.showPage("chat");
					frame();
				}
			});
		}
		report("Chats page (50 chats)", 20, () -> sidebar.showPage("home"), () ->
		{
			sidebar.showPage("chats");
			frame();
		});
		sessions.open(firstId);
		sidebar.showPage("chat");
		frame();

		// Where a streaming update's time goes
		String md = longReply();
		report("  markdown -> html (2.8k chars)", 30, null, () -> MarkdownLite.toHtml(md, null));
		String html = MarkdownLite.toHtml(md, null);
		ChatComponents.Bubble b = new ChatComponents.Bubble(null, java.awt.Color.WHITE, false, false);
		frame.getContentPane().add(b);
		report("  bubble setHtml (2.8k chars)", 30, null, () -> b.setHtml(html));
		report("  bubble measure at new html", 30, () -> b.setHtml(html), () -> b.heightForWidth(260));
		frame.getContentPane().remove(b);
		report("  whole update (html + layout)", 30, null, () -> { chat.flushRender(); frame(); });
		report("  chat list relayout only", 30, null, () -> { chat.relayoutForTest(); frame(); });
		{
			List<Long> v = new ArrayList<>(), pt = new ArrayList<>();
			for (int i = 0; i < 20; i++)
			{
				chat.flushRender();
				long t0 = System.nanoTime();
				frame.validate();
				long t1 = System.nanoTime();
				BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
				Graphics2D g = img.createGraphics();
				sidebar.paint(g);
				g.dispose();
				long t2 = System.nanoTime();
				v.add(t1 - t0);
				pt.add(t2 - t1);
			}
			print("  layout after update", v);
			print("  first paint after update", pt);
		}
		if (Boolean.getBoolean("perf.sample"))
		{
			Thread edt = Thread.currentThread();
			java.util.Map<String, Integer> hits = new java.util.HashMap<>();
			java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
			Thread sampler = new Thread(() ->
			{
				while (on.get())
				{
					StackTraceElement[] st = edt.getStackTrace();
					// the first frames in our code or javax.swing below the layout call
					StringBuilder key = new StringBuilder();
					int n = 0;
					for (StackTraceElement e : st)
					{
						String c = e.getClassName();
						if (c.startsWith("com.squire") || (c.startsWith("javax.swing") && n < 6))
						{
							key.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append(" < ");
							if (++n >= 8)
							{
								break;
							}
						}
					}
					hits.merge(key.toString(), 1, Integer::sum);
					try
					{
						Thread.sleep(1);
					}
					catch (InterruptedException ex)
					{
						return;
					}
				}
			});
			sampler.start();
			for (int i = 0; i < 30; i++)
			{
				chat.flushRender();
				frame.validate();
			}
			on.set(false);
			hits.entrySet().stream().sorted((a, bb) -> bb.getValue() - a.getValue()).limit(12)
				.forEach(e -> System.out.println("SAMPLE " + e.getValue() + "  " + e.getKey()));
		}
		report("  update without painting", 30, null, chat::flushRender);
		report("  paint only (after update)", 30, chat::flushRender, PanelPerf::frame);

		Runtime rt = Runtime.getRuntime();
		System.gc();
		System.out.println("Heap in use: " + (rt.totalMemory() - rt.freeMemory()) / 1_000_000 + " MB");
	}

	/** Print where the Swing thread spends its time while running the task (stack samples every 1ms). */
	private static void sample(Runnable task)
	{
		Thread edt = Thread.currentThread();
		java.util.Map<String, Integer> hits = new java.util.HashMap<>();
		java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
		Thread sampler = new Thread(() ->
		{
			while (on.get())
			{
				StringBuilder key = new StringBuilder();
				int n = 0;
				for (StackTraceElement e : edt.getStackTrace())
				{
					String c = e.getClassName();
					if (c.startsWith("com.squire") || c.startsWith("javax.swing") || c.startsWith("java.awt"))
					{
						key.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append(" < ");
						if (++n >= 7)
						{
							break;
						}
					}
				}
				hits.merge(key.toString(), 1, Integer::sum);
				try
				{
					Thread.sleep(1);
				}
				catch (InterruptedException ex)
				{
					return;
				}
			}
		});
		sampler.start();
		task.run();
		on.set(false);
		hits.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
			.forEach(e -> System.out.println("SWITCH " + e.getValue() + "  " + e.getKey()));
	}

	private static void exchange(ChatView chat, int i)
	{
		chat.addUser("Question number " + i + ": what should I do next at [[Vorkath]]?");
		chat.beginAssistant();
		chat.traceReasoning("Checking their gear and the wiki.");
		chat.traceActions(PanelPreview.actions("a" + i, "osrs__wiki_read", "{\"page\":\"Vorkath/Strategies\"}"));
		chat.traceResult("a" + i, PanelPreview.mcp("{\"title\":\"Vorkath/Strategies\",\"url\":\"https://oldschool.runescape.wiki/w/Vorkath/Strategies\"}"), true);
		chat.appendAssistant(longReply().substring(0, 700));
		chat.endAssistant();
	}

	private static String longReply()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 12; i++)
		{
			sb.append("Use your **[[Toxic blowpipe]]** with [[Dragon dart]]s and bring a [[Super antifire potion]]. ")
				.append("Kill it in about 1:30 with [[Rigour]].\n\n- Bring [[Prayer potion(4)]]s\n- Pray [[Protect from Magic]]\n- Use the [[Ruby dragon bolts (e)]] spec\n\n");
		}
		return sb.toString();
	}

	/** Lay out and paint one frame, like the screen would. */
	private static void frame()
	{
		frame.validate();
		validateTree(sidebar);
		BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		sidebar.paint(g);
		g.dispose();
	}

	private static void validateTree(Container c)
	{
		c.validate();
	}

	private static void report(String label, int runs, Runnable before, Runnable action)
	{
		List<Long> times = new ArrayList<>();
		for (int i = 0; i < runs; i++)
		{
			if (before != null)
			{
				before.run();
				frame();
			}
			long t0 = System.nanoTime();
			action.run();
			times.add(System.nanoTime() - t0);
		}
		print(label, times);
	}

	private static void print(String label, List<Long> times)
	{
		List<Long> sorted = new ArrayList<>(times);
		Collections.sort(sorted);
		long median = sorted.get(sorted.size() / 2);
		long p95 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
		long max = sorted.get(sorted.size() - 1);
		String flag = ms(p95) > FRAME_MS ? "  <- hitch" : "";
		System.out.printf("%-34s %7.1f %7.1f %7.1f%s%n", label, ms(median), ms(p95), ms(max), flag);
	}

	private static double ms(long nanos)
	{
		return nanos / 1_000_000.0;
	}
}
