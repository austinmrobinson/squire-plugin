package com.squire;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the Squire agent (eve) over its session HTTP API: create a session, post messages,
 * and follow the NDJSON event stream for each turn.
 */
@Slf4j
class ChatClient
{
	interface Listener
	{
		/** Assistant text as it streams in. */
		void onDelta(String text);

		/** One assistant text block finished (a turn can have several around tool calls). */
		void onBlockCompleted();

		/** The agent started calling tools. */
		void onToolUse(String description);

		/** A piece of the model's (summarized) thinking. */
		default void onReasoning(String delta)
		{
		}

		/** One block of thinking finished. */
		default void onReasoningDone()
		{
		}

		/** The raw tool calls the agent made (callId, kind, toolName, input), for the Steps panel. */
		default void onActions(JsonArray actions)
		{
		}

		/** A tool call finished; output is the tool's result (MCP content or plain JSON). */
		default void onActionResult(String callId, JsonElement output, boolean ok)
		{
		}

		void onError(String message);

		/** The turn is over and the session is ready for another message. */
		void onDone();
	}

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int MAX_STREAM_RECONNECTS = 5;
	private static final long NOT_READY_RETRY_MS = 20_000;

	private final OkHttpClient http;
	private final Gson gson;
	private final Supplier<String> endpoint;
	private final Supplier<String> token;
	private final Supplier<String> model;
	/** The logged-in account's hash, so the agent's memory is kept per account (null when logged out). */
	private Supplier<String> account = () -> null;
	// One turn at a time, in order
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "squire-chat");
		t.setDaemon(true);
		return t;
	});

	private volatile String sessionId;
	// Absolute count of stream events already consumed, so each turn reads only its own events
	private volatile int streamIndex;
	private volatile Call activeStream;

	ChatClient(OkHttpClient http, Gson gson, Supplier<String> endpoint, Supplier<String> token, Supplier<String> model)
	{
		this.model = model;
		// Streams stay open while the model thinks and tools run
		this.http = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build();
		this.gson = gson;
		this.endpoint = endpoint;
		this.token = token;
	}

	void setAccount(Supplier<String> account)
	{
		this.account = account;
	}

	void send(String message, Map<String, Object> clientContext, Listener listener)
	{
		send(message, java.util.List.of(), clientContext, listener, null);
	}

	/** The server session this conversation continues (saved with the chat history). */
	String sessionId()
	{
		return sessionId;
	}

	/** The index of the next stream event; a resumed session reads on from here, not from the start. */
	int streamIndex()
	{
		return streamIndex;
	}

	void resume(String sessionId, int streamIndex)
	{
		this.sessionId = sessionId;
		this.streamIndex = streamIndex;
	}

	/**
	 * Sends a message; with attachments it goes as content parts (text, then files/images). {@code recap} is the
	 * conversation so far, sent only if the server has to start a new session for it (e.g. a chat reopened
	 * from the history after its session expired), so the agent still has the earlier messages.
	 */
	void send(String message, java.util.List<Attachment> attachments, Map<String, Object> clientContext, Listener listener, String recap)
	{
		pendingRecap = recap;
		Object content = message;
		if (!attachments.isEmpty())
		{
			java.util.List<Object> parts = new java.util.ArrayList<>();
			Map<String, Object> text = new LinkedHashMap<>();
			text.put("type", "text");
			text.put("text", message.isEmpty() ? "(see attached)" : message);
			parts.add(text);
			attachments.forEach(a -> parts.add(a.toPart()));
			content = parts;
		}
		Object body = content;
		worker.execute(() ->
		{
			try
			{
				postMessage(body, clientContext);
				followTurn(listener);
			}
			catch (Exception e)
			{
				log.warn("Squire chat failed", e);
				listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
			}
		});
	}

	/** Stops the in-flight turn; the stream then reports turn.cancelled and the session waits again. */
	void cancel()
	{
		String id = sessionId;
		if (id == null)
		{
			return;
		}
		Request request = authed(url("eve/v1/session/" + id + "/cancel"))
			.post(RequestBody.create(JSON, "{}"))
			.build();
		http.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cancel failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	/** Forget the conversation; the next message starts a fresh session. */
	void reset()
	{
		Call stream = activeStream;
		if (stream != null)
		{
			stream.cancel();
		}
		sessionId = null;
		streamIndex = 0;
	}

	void shutdown()
	{
		reset();
		worker.shutdownNow();
	}

	private volatile String pendingRecap;

	private void postMessage(Object message, Map<String, Object> clientContext) throws IOException, InterruptedException
	{
		Map<String, Object> body = new LinkedHashMap<>();
		String recap = pendingRecap;
		if (sessionId == null && recap != null && !recap.isEmpty())
		{
			// A fresh session for an existing conversation: lead with what was said before
			java.util.List<Object> parts = new java.util.ArrayList<>();
			Map<String, Object> earlier = new LinkedHashMap<>();
			earlier.put("type", "text");
			earlier.put("text", "Earlier in this conversation (restored from the chat history):\n\n" + recap + "\n\n---\nThe player's new message:");
			parts.add(earlier);
			if (message instanceof java.util.List)
			{
				parts.addAll((java.util.List<?>) message);
			}
			else
			{
				Map<String, Object> text = new LinkedHashMap<>();
				text.put("type", "text");
				text.put("text", message);
				parts.add(text);
			}
			body.put("message", parts);
		}
		else
		{
			body.put("message", message);
		}
		if (clientContext != null && !clientContext.isEmpty())
		{
			body.put("clientContext", clientContext);
		}

		if (sessionId == null)
		{
			JsonObject created = postJson("eve/v1/session", body);
			sessionId = created.get("sessionId").getAsString();
			streamIndex = 0;
			return;
		}

		long deadline = System.currentTimeMillis() + NOT_READY_RETRY_MS;
		long backoff = 250;
		while (true)
		{
			try
			{
				postJson("eve/v1/session/" + sessionId, body);
				return;
			}
			catch (HttpError e)
			{
				if (e.status == 409 && System.currentTimeMillis() < deadline && (e.getMessage() == null || !e.getMessage().contains("session_not_active")))
				{
					// session_not_ready: the durable inbox is still starting
					Thread.sleep(backoff);
					backoff = Math.min(backoff * 2, 2000);
					continue;
				}
				if (e.status == 404 || e.status == 410 || (e.status == 409 && e.getMessage() != null && e.getMessage().contains("session_not_active")))
				{
					// Session expired, ended or was reset: start a new one with this message (and the recap)
					sessionId = null;
					postMessage(message, clientContext);
					return;
				}
				throw e;
			}
		}
	}

	private void followTurn(Listener listener) throws IOException
	{
		boolean turnStarted = false;
		// One failure is reported as both turn.failed and session.failed: show it once, and don't reconnect after it
		boolean failed = false;
		for (int attempt = 0; attempt <= MAX_STREAM_RECONNECTS; attempt++)
		{
			HttpUrl url = url("eve/v1/session/" + sessionId + "/stream").newBuilder()
				.addQueryParameter("startIndex", Integer.toString(streamIndex))
				.build();
			Call call = http.newCall(authed(url).get().build());
			activeStream = call;
			try (Response response = call.execute())
			{
				if (!response.isSuccessful())
				{
					throw new HttpError(response.code(), bodyText(response));
				}
				ResponseBody body = response.body();
				if (body == null)
				{
					throw new IOException("Empty stream response");
				}
				BufferedReader reader = new BufferedReader(body.charStream());
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (line.isBlank())
					{
						continue;
					}
					streamIndex++;
					JsonObject event = gson.fromJson(line, JsonObject.class);
					String type = event.get("type").getAsString();
					JsonObject data = event.has("data") && event.get("data").isJsonObject() ? event.getAsJsonObject("data") : new JsonObject();

					switch (type)
					{
						case "turn.started":
							turnStarted = true;
							break;
						case "message.appended":
							listener.onDelta(string(data, "messageDelta"));
							break;
						case "message.completed":
							listener.onBlockCompleted();
							break;
						case "actions.requested":
							listener.onToolUse(describeActions(data));
							if (data.has("actions") && data.get("actions").isJsonArray())
							{
								listener.onActions(data.getAsJsonArray("actions"));
							}
							break;
						case "reasoning.appended":
							listener.onReasoning(string(data, "reasoningDelta"));
							break;
						case "reasoning.completed":
							listener.onReasoningDone();
							break;
						case "action.result":
						{
							JsonObject result = data.has("result") && data.get("result").isJsonObject() ? data.getAsJsonObject("result") : new JsonObject();
							listener.onActionResult(string(result, "callId"), result.get("output"), "completed".equals(string(data, "status")) || !data.has("status"));
							break;
						}
						case "turn.failed":
						case "session.failed":
							if (!failed)
							{
								failed = true;
								listener.onError(string(data, "message"));
							}
							if ("session.failed".equals(type))
							{
								// The session can't take more turns; the next message starts a new one
								sessionId = null;
								return;
							}
							break;
						case "session.waiting":
						case "session.completed":
							// A waiting event before our turn began belongs to an earlier turn
							if (turnStarted || "session.completed".equals(type))
							{
								if ("session.completed".equals(type))
								{
									sessionId = null;
								}
								listener.onDone();
								return;
							}
							break;
						default:
							break;
					}
				}
			}
			catch (InterruptedIOException e)
			{
				// Read timeout or stream lease ended; reconnect from our cursor
				if (call.isCanceled())
				{
					return;
				}
				log.debug("Chat stream interrupted, reconnecting from {}", streamIndex);
				continue;
			}
			catch (IOException e)
			{
				if (call.isCanceled())
				{
					return;
				}
				if (attempt == MAX_STREAM_RECONNECTS)
				{
					throw e;
				}
				continue;
			}
			finally
			{
				activeStream = null;
			}
			if (failed)
			{
				// The turn already ended in an error the player has seen
				return;
			}
			// Server closed the stream cleanly mid-turn (lease renewal): reconnect
		}
		throw new IOException("Lost connection to the chat stream");
	}

	private static String describeActions(JsonObject data)
	{
		JsonElement actions = data.get("actions");
		if (actions != null && actions.isJsonArray())
		{
			StringBuilder names = new StringBuilder();
			for (JsonElement a : (JsonArray) actions)
			{
				if (!a.isJsonObject())
				{
					continue;
				}
				String name = firstString(a.getAsJsonObject(), "toolName", "name");
				if (name != null)
				{
					// connection tools arrive as "osrs__get_gear"
					if (name.equals("connection_search"))
					{
						name = "finding account tools";
					}
					name = name.replaceFirst("^osrs__", "").replace('_', ' ');
					if (names.length() > 0)
					{
						names.append(", ");
					}
					names.append(name);
				}
			}
			if (names.length() > 0)
			{
				return names.toString();
			}
		}
		return "looking something up";
	}

	private static String firstString(JsonObject o, String... keys)
	{
		for (String k : keys)
		{
			if (o.has(k) && o.get(k).isJsonPrimitive())
			{
				return o.get(k).getAsString();
			}
		}
		return null;
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private JsonObject postJson(String path, Object body) throws IOException
	{
		Request request = authed(url(path)).post(RequestBody.create(JSON, gson.toJson(body))).build();
		try (Response response = http.newCall(request).execute())
		{
			String text = bodyText(response);
			if (!response.isSuccessful())
			{
				throw new HttpError(response.code(), text);
			}
			return gson.fromJson(text, JsonObject.class);
		}
	}

	private Request.Builder authed(HttpUrl url)
	{
		Request.Builder b = new Request.Builder().url(url)
			.header("Authorization", "Bearer " + token.get().trim())
			.header("X-Squire-Model", model.get());
		String hash = account.get();
		if (hash != null)
		{
			b.header("X-Squire-Account", hash);
		}
		return b;
	}

	private HttpUrl url(String path)
	{
		HttpUrl base = HttpUrl.parse(endpoint.get().trim());
		if (base == null)
		{
			throw new IllegalStateException("Set the server URL in the Squire settings");
		}
		return base.newBuilder().addPathSegments(path).build();
	}

	private static String bodyText(Response response) throws IOException
	{
		ResponseBody body = response.body();
		return body == null ? "" : body.string();
	}

	static class HttpError extends IOException
	{
		final int status;

		HttpError(int status, String body)
		{
			super(status == 401 ? "Not authorized; turn Squire on again from its Home page" : "Server returned " + status + ": " + body);
			this.status = status;
		}
	}
}
