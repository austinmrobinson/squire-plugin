package com.squire;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * The chat history on disk: one JSON file per conversation in Squire's plugin data folder, under chats. Reads and
 * writes run on their own thread so the panel never waits on the disk; writes replace the file atomically. With no
 * folder (RuneLite couldn't make one), history lasts only as long as the session.
 */
@Slf4j
class ChatStore
{
	private final Filepath dir;
	private final Gson gson;
	private final ExecutorService io = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "squire-chat-history");
		t.setDaemon(true);
		return t;
	});

	ChatStore(Filepath dir, Gson gson)
	{
		this.dir = dir;
		this.gson = gson;
	}

	/** Every saved conversation (called back on the history thread). */
	void loadAll(Consumer<List<JsonObject>> onLoaded)
	{
		io.execute(() ->
		{
			List<JsonObject> out = new ArrayList<>();
			for (Filepath f : files())
			{
				try (InputStream in = f.openInputStream())
				{
					JsonObject o = gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
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
			onLoaded.accept(out);
		});
	}

	private List<Filepath> files()
	{
		if (dir == null || !dir.isDirectory())
		{
			return List.of();
		}
		try (Stream<Filepath> walk = dir.walk(1))
		{
			return walk.filter(f -> f.getFileName().endsWith(".json") && f.isFile()).collect(Collectors.toList());
		}
		catch (IOException e)
		{
			log.warn("Couldn't list saved chats", e);
			return List.of();
		}
	}

	void save(JsonObject record)
	{
		String id = record.get("id").getAsString();
		String json = gson.toJson(record);
		if (dir == null)
		{
			return;
		}
		io.execute(() ->
		{
			try
			{
				dir.createDirectories();
				Filepath tmp = dir.joinSegment(fileName(id) + ".tmp");
				tmp.write(json.getBytes(StandardCharsets.UTF_8));
				tmp.moveTo(dir.joinSegment(fileName(id)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Couldn't save chat {}", id, e);
			}
		});
	}

	/** Remove a conversation the player deleted from the list. */
	void delete(String id)
	{
		if (dir == null)
		{
			return;
		}
		io.execute(() ->
		{
			try
			{
				dir.joinSegment(fileName(id)).deleteIfExists();
			}
			catch (IOException | RuntimeException e)
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
