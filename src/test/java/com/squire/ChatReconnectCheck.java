package com.squire;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;

/**
 * The chat stream against a fake server (./gradlew chatReconnect): a long answer split over many connections finishes,
 * a stream that stops sending gives up with "Lost connection" after backing off, and a 4xx ends the turn at once.
 */
public class ChatReconnectCheck
{
	private static int failures;

	public static void main(String[] args) throws Exception
	{
		// Scenario per session id: "long" sends one event per connection for 12 connections, then finishes;
		// "dead" never sends anything; "gone" answers the stream with 404
		AtomicInteger connections = new AtomicInteger();
		String[] scenario = {"long"};
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange ->
		{
			String path = exchange.getRequestURI().getPath();
			byte[] out;
			int status = 200;
			if (path.endsWith("/stream"))
			{
				int n = connections.incrementAndGet();
				StringBuilder events = new StringBuilder();
				if (scenario[0].equals("long"))
				{
					events.append(n == 1 ? "{\"type\":\"turn.started\"}\n" : "{\"type\":\"message.appended\",\"data\":{\"messageDelta\":\".\"}}\n");
					if (n >= 12)
					{
						events.append("{\"type\":\"session.waiting\"}\n");
					}
				}
				else if (scenario[0].equals("gone"))
				{
					status = 404;
					events.append("{\"error\":\"session_not_found\"}");
				}
				out = events.toString().getBytes(StandardCharsets.UTF_8);
			}
			else
			{
				out = ("{\"sessionId\":\"s-" + scenario[0] + "\"}").getBytes(StandardCharsets.UTF_8);
			}
			exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
			try (OutputStream body = exchange.getResponseBody())
			{
				body.write(out);
			}
		});
		server.start();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();

		for (String s : new String[]{"long", "dead", "gone"})
		{
			scenario[0] = s;
			connections.set(0);
			ChatClient client = new ChatClient(new OkHttpClient(), new Gson(), () -> base, () -> "t", () -> ModelCatalog.DEFAULT_ID);
			CountDownLatch done = new CountDownLatch(1);
			String[] error = {null};
			StringBuilder text = new StringBuilder();
			long start = System.currentTimeMillis();
			client.send("hi", Map.of(), new ChatClient.Listener()
			{
				public void onDelta(String t) { text.append(t); }
				public void onBlockCompleted() { }
				public void onToolUse(String d) { }
				public void onError(String m) { error[0] = m; done.countDown(); }
				public void onDone() { done.countDown(); }
			});
			boolean finished = done.await(30, TimeUnit.SECONDS);
			long ms = System.currentTimeMillis() - start;
			switch (s)
			{
				case "long":
					check("a long answer over 12 connections finishes", finished && error[0] == null && text.length() == 11, text + " " + error[0]);
					break;
				case "dead":
					check("a silent stream gives up with Lost connection", finished && "Lost connection to the chat stream".equals(error[0]), error[0]);
					check("after backing off (7 tries, several seconds)", connections.get() == 7 && ms > 5_000, connections.get() + " tries in " + ms + "ms");
					break;
				default:
					check("a 404 stream ends the turn at once", finished && error[0] != null && connections.get() == 1 && ms < 2_000, connections.get() + " tries, " + error[0]);
			}
			client.shutdown();
		}
		server.stop(0);
		System.out.println(failures == 0 ? "All passed" : failures + " failed");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void check(String name, boolean ok, Object detail)
	{
		System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : "  " + detail));
		if (!ok)
		{
			failures++;
		}
	}
}
