package com.osrssync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * The chat history on disk: one JSON file per conversation in ~/.runelite/account-sync/chats. Reads and writes
 * run on their own thread so the panel never waits on the disk; writes replace the file atomically.
 */
@Slf4j
class ChatStore
{
	private final Path dir;
	private final Gson gson;
	private final ExecutorService io = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "rs-buddy-chat-history");
		t.setDaemon(true);
		return t;
	});

	ChatStore(File dir, Gson gson)
	{
		this.dir = dir.toPath();
		this.gson = gson;
	}

	/** Every saved conversation (called back on the history thread). */
	void loadAll(Consumer<List<JsonObject>> onLoaded)
	{
		io.execute(() ->
		{
			List<JsonObject> out = new ArrayList<>();
			File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".json"));
			if (files != null)
			{
				for (File f : files)
				{
					try
					{
						JsonObject o = gson.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), JsonObject.class);
						if (o != null && o.has("id"))
						{
							out.add(o);
						}
					}
					catch (Exception e)
					{
						log.debug("Skipping unreadable chat {}", f, e);
					}
				}
			}
			onLoaded.accept(out);
		});
	}

	void save(JsonObject record)
	{
		String id = record.get("id").getAsString();
		String json = gson.toJson(record);
		io.execute(() ->
		{
			try
			{
				Files.createDirectories(dir);
				Path tmp = dir.resolve(fileName(id) + ".tmp");
				Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
				Files.move(tmp, dir.resolve(fileName(id)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException e)
			{
				log.warn("Couldn't save chat {}", id, e);
			}
		});
	}

	/** Remove a conversation the player deleted from the list. */
	void delete(String id)
	{
		io.execute(() ->
		{
			try
			{
				Files.deleteIfExists(dir.resolve(fileName(id)));
			}
			catch (IOException e)
			{
				log.warn("Couldn't delete chat {}", id, e);
			}
		});
	}

	void shutdown()
	{
		io.shutdown();
	}

	private static String fileName(String id)
	{
		return id.replaceAll("[^A-Za-z0-9-]", "_") + ".json";
	}
}
