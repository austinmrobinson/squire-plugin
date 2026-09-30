package com.squire;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * All of the player's conversations: the one on screen, others still answering in the background, and the saved
 * history. Each conversation has its own view and its own connection to the agent, so several can run at once.
 * Saved chats are only rebuilt into a view when opened. Swing thread only.
 */
class ChatSessions
{
	static final class Chat
	{
		final String id;
		String title;
		long updatedAt;
		/** The last thing said, for the history list. */
		String preview = "";
		/** Built when the chat is first opened; until then only the saved record exists. */
		ChatView view;
		private JsonObject saved;
		/** Finished answering while the player was looking at something else. */
		boolean unread;
		/** When it was last on screen, to unload the least recently used. */
		long shownAt;
		/** The conversation held through the game's chatbox. */
		boolean inGame;

		private Chat(String id)
		{
			this.id = id;
		}

		boolean running()
		{
			return view != null && view.isBusy();
		}

		boolean hasMessages()
		{
			return view != null ? view.hasConversation() : saved != null;
		}
	}

	private final Supplier<ChatView> factory;
	private final ChatStore store;
	private final List<Chat> chats = new ArrayList<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private Consumer<ChatView> onCurrent = v -> {};
	private Consumer<ChatView> onUnloaded = v -> {};
	/** Chats kept built besides the one on screen and any still answering; older ones go back to their record. */
	private static final int KEEP_LOADED = 3;
	private Chat current;

	/** {@code store} may be null (previews): nothing is loaded or saved. */
	ChatSessions(Supplier<ChatView> factory, ChatStore store)
	{
		this.factory = factory;
		this.store = store;
		current = create();
	}

	/** Read the saved history (in the background) and list it. */
	void load()
	{
		if (store == null)
		{
			return;
		}
		store.loadAll(records -> SwingUtilities.invokeLater(() ->
		{
			records.forEach(this::addSaved);
			changed();
		}));
	}

	/** List a saved conversation (from disk); its view is built when it's opened. */
	Chat addSaved(JsonObject r)
	{
		String id = r.get("id").getAsString();
		Chat existing = find(id);
		if (existing != null)
		{
			return existing;
		}
		Chat c = new Chat(id);
		c.saved = r;
		c.title = r.has("title") ? r.get("title").getAsString() : "Chat";
		c.updatedAt = r.has("updatedAt") ? r.get("updatedAt").getAsLong() : 0;
		c.preview = previewOf(r.has("turns") ? r.getAsJsonArray("turns") : new JsonArray());
		c.inGame = r.has("inGame") && r.get("inGame").getAsBoolean();
		chats.add(c);
		return c;
	}

	void addListener(Runnable listener)
	{
		listeners.add(listener);
	}

	/** Told whenever a different conversation comes on screen. */
	void setOnCurrent(Consumer<ChatView> onCurrent)
	{
		this.onCurrent = onCurrent;
	}

	/** Told when a conversation's view is dropped (unloaded or deleted), so the panel can let go of it. */
	void setOnUnloaded(Consumer<ChatView> onUnloaded)
	{
		this.onUnloaded = onUnloaded;
	}

	ChatView current()
	{
		return current.view;
	}

	Chat currentChat()
	{
		return current;
	}

	/** Conversations with messages, most recent first. */
	List<Chat> chats()
	{
		List<Chat> out = new ArrayList<>();
		for (Chat c : chats)
		{
			if (c.hasMessages())
			{
				out.add(c);
			}
		}
		out.sort(Comparator.comparingLong((Chat c) -> c.updatedAt).reversed());
		return out;
	}

	/** Show an empty conversation, reusing the current one if nothing has been said in it yet. */
	ChatView newChat()
	{
		if (!current.hasMessages() && !current.running())
		{
			current.view.focusInput();
			return current.view;
		}
		Chat c = create();
		show(c);
		return c.view;
	}

	/** Start a new conversation with this message (Home's prompts and composer). */
	void startWith(String message, java.util.List<Attachment> context)
	{
		newChat().startWith(message, context);
	}

	void startWith(String message)
	{
		newChat().startWith(message);
	}

	void open(String id)
	{
		Chat c = find(id);
		if (c == null)
		{
			return;
		}
		if (c.view == null)
		{
			c.view = factory.get();
			c.view.restore(c.saved);
			c.saved = null;
			wire(c);
		}
		show(c);
	}

	/**
	 * The conversation for questions asked in the game's chatbox: the latest in-game chat if it was active in the
	 * last couple of hours, else a new one. Built if needed but not brought on screen.
	 */
	ChatView inGame(Consumer<ChatView> setUp)
	{
		Chat found = null;
		for (Chat c : chats)
		{
			if (c.inGame && System.currentTimeMillis() - c.updatedAt < IN_GAME_CONTINUES_MS && (found == null || c.updatedAt > found.updatedAt))
			{
				found = c;
			}
		}
		if (found == null)
		{
			found = create();
			found.inGame = true;
		}
		else if (found.view == null)
		{
			found.view = factory.get();
			found.view.restore(found.saved);
			found.saved = null;
			wire(found);
		}
		found.shownAt = System.nanoTime();
		setUp.accept(found.view);
		return found.view;
	}

	private static final long IN_GAME_CONTINUES_MS = 2 * 3_600_000L;

	/** Forget a conversation (the player deleted it from the list). */
	void delete(String id)
	{
		Chat c = find(id);
		if (c == null)
		{
			return;
		}
		chats.remove(c);
		if (c.view != null)
		{
			onUnloaded.accept(c.view);
			c.view.close();
		}
		if (store != null)
		{
			store.delete(id);
		}
		if (c == current)
		{
			current = create();
			onCurrent.accept(current.view);
		}
		changed();
	}

	/** Every conversation that has a view (open now or earlier this session). */
	void forEachView(Consumer<ChatView> action)
	{
		for (Chat c : chats)
		{
			if (c.view != null)
			{
				action.accept(c.view);
			}
		}
	}

	/** Forget every conversation (the player deleted their data); a fresh empty chat comes on screen. */
	void clearAll()
	{
		for (Chat c : new java.util.ArrayList<>(chats))
		{
			if (c.view != null)
			{
				onUnloaded.accept(c.view);
				c.view.close();
			}
		}
		chats.clear();
		current = create();
		onCurrent.accept(current.view);
		changed();
	}

	void shutdown()
	{
		for (Chat c : chats)
		{
			if (c.view != null)
			{
				c.view.close();
			}
		}
		if (store != null)
		{
			store.shutdown();
		}
	}

	private Chat create()
	{
		ChatView view = factory.get();
		Chat c = new Chat(view.id());
		c.view = view;
		c.shownAt = System.nanoTime();
		c.title = view.title();
		c.updatedAt = System.currentTimeMillis();
		wire(c);
		chats.add(c);
		return c;
	}

	private void show(Chat c)
	{
		current = c;
		c.unread = false;
		c.shownAt = System.nanoTime();
		// Leave at most one empty conversation lying around
		chats.removeIf(o -> o != current && !o.hasMessages() && !o.running() && closeQuietly(o));
		onCurrent.accept(c.view);
		unloadOld();
		changed();
	}

	private boolean closeQuietly(Chat c)
	{
		if (c.view != null)
		{
			onUnloaded.accept(c.view);
			c.view.close();
		}
		return true;
	}

	/** Keep memory bounded: idle chats beyond the most recent few go back to their saved record. */
	private void unloadOld()
	{
		List<Chat> idle = new ArrayList<>();
		for (Chat c : chats)
		{
			if (c.view != null && c != current && !c.running() && c.hasMessages())
			{
				idle.add(c);
			}
		}
		idle.sort(Comparator.comparingLong((Chat c) -> c.shownAt).reversed());
		for (Chat c : idle.subList(Math.min(KEEP_LOADED, idle.size()), idle.size()))
		{
			c.saved = c.view.toRecord();
			onUnloaded.accept(c.view);
			c.view.close();
			c.view = null;
		}
	}

	private void wire(Chat c)
	{
		c.view.setOnChanged(() ->
		{
			c.title = c.view.title();
			c.updatedAt = c.view.updatedAt();
			JsonObject record = c.view.toRecord();
			c.preview = previewOf(record.getAsJsonArray("turns"));
			if (!c.view.isBusy() && c != current && c.view.hasConversation())
			{
				c.unread = true;
			}
			if (store != null && c.view.hasConversation())
			{
				store.save(record);
			}
			changed();
		});
	}

	private Chat find(String id)
	{
		for (Chat c : chats)
		{
			if (c.id.equals(id))
			{
				return c;
			}
		}
		return null;
	}

	private void changed()
	{
		listeners.forEach(Runnable::run);
	}

	private static String previewOf(JsonArray turns)
	{
		for (int i = turns.size() - 1; i >= 0; i--)
		{
			JsonElement t = turns.get(i);
			if (t.isJsonObject() && t.getAsJsonObject().has("text"))
			{
				String text = MarkdownLite.normalize(t.getAsJsonObject().get("text").getAsString())
					.replaceAll("\\[\\[([^\\]|]*\\|)?([^\\]]*)\\]\\]", "$2")
					.replaceAll("[*_`#>]", "")
					.replaceAll("\\s+", " ")
					.trim();
				if (!text.isEmpty())
				{
					return text.length() > 90 ? text.substring(0, 89).trim() + "..." : text;
				}
			}
		}
		return "";
	}
}
