package com.osrssync;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** Manual check against a running dev server: ./gradlew chatSmoke */
public class ChatClientSmoke
{
	public static void main(String[] args) throws Exception
	{
		String token = Files.readAllLines(Path.of("../server/.env.local")).stream()
			.filter(l -> l.startsWith("INGEST_TOKEN=")).findFirst().orElseThrow().substring("INGEST_TOKEN=".length());
		ChatClient client = new ChatClient(new OkHttpClient(), new Gson(), () -> "http://127.0.0.1:3000", () -> token, () -> ModelCatalog.DEFAULT_ID);
		for (String q : new String[]{"How many quest points do I have?", "And my total level?"})
		{
			CountDownLatch done = new CountDownLatch(1);
			StringBuilder text = new StringBuilder();
			System.out.println(">> " + q);
			client.send(q, Map.of("character", "Maximvs597"), new ChatClient.Listener()
			{
				public void onDelta(String t) { text.append(t); }
				public void onBlockCompleted() { text.append("\n"); }
				public void onToolUse(String d) { System.out.println("   [tool] " + d); }
				public void onError(String m) { System.out.println("   [error] " + m.substring(0, Math.min(140, m.length()))); }
				public void onDone() { System.out.println("   [done] " + text.toString().trim()); done.countDown(); }
			});
			if (!done.await(120, TimeUnit.SECONDS))
			{
				System.out.println("   [timeout]");
			}
		}
		System.out.println(MarkdownLite.toHtml("**Best ranged:**\n- `Blazing blowpipe`\n- Crystal bow\n\n| a | b |\n|---|---|\n| 1 | 2 |"));
		client.shutdown();
		System.exit(0);
	}
}
