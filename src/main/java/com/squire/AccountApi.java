package com.squire;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads the account's summaries from the server: /api/overview (progression) and /api/activity (time played).
 */
class AccountApi
{
	/** Either the JSON or a message explaining why there isn't any. */
	static final class Result
	{
		final JsonObject json;
		final String error;

		private Result(JsonObject json, String error)
		{
			this.json = json;
			this.error = error;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private final Supplier<String> endpoint;
	private final Supplier<String> token;
	private final Supplier<String> accountName;

	AccountApi(OkHttpClient http, Gson gson, Supplier<String> endpoint, Supplier<String> token, Supplier<String> accountName)
	{
		this.http = http;
		this.gson = gson;
		this.endpoint = endpoint;
		this.token = token;
		this.accountName = accountName;
	}

	/** Progression summary. Calls back on an OkHttp thread. */
	void overview(Consumer<Result> callback)
	{
		get("api/overview", Map.of(), callback);
	}

	/** Time played for a day, week or month (offset 0 = the current one), in this computer's timezone. */
	void activity(String range, int offset, Consumer<Result> callback)
	{
		get("api/activity", Map.of("range", range, "offset", String.valueOf(offset), "tz", ZoneId.systemDefault().getId()), callback);
	}

	/** Gains over a period: everything gained, or one metric's values and per-day gains (metric null for the list). */
	void gained(String period, String metric, Consumer<Result> callback)
	{
		Map<String, String> q = new java.util.HashMap<>();
		q.put("period", period);
		if (metric != null)
		{
			q.put("metric", metric);
		}
		get("api/gained", q, callback);
	}

	/** A Progress detail page: skills, quests, combat-achievements, diaries, collection-log, kill-counts or stats. */
	void progress(String view, Consumer<Result> callback)
	{
		get("api/progress", Map.of("view", view), callback);
	}

	/** One boss over a period (day, week, month, year): count history, time, kills per hour, PBs and loot. */
	void boss(String name, String period, Consumer<Result> callback)
	{
		get("api/progress", Map.of("boss", name, "period", period), callback);
	}

	/** The profile editor's data for the logged-in character: settings, options (with what's unlocked), share link. */
	void profile(Consumer<Result> callback)
	{
		send("GET", "api/me/profile" + accountQuery("?"), null, callback);
	}

	/** Change the profile: {public?, background?, frame?, emblem?, highlights?: [ids], gear?: {slot: id|null}}. */
	void editProfile(JsonObject edit, Consumer<Result> callback)
	{
		send("PATCH", "api/me/profile" + accountQuery("?"), edit, callback);
	}

	/** The character's own items: worn in an equipment slot (slot >= 0), or anything for highlights (slot < 0). */
	void profileItems(int slot, String query, Consumer<Result> callback)
	{
		String q = java.net.URLEncoder.encode(query == null ? "" : query, java.nio.charset.StandardCharsets.UTF_8);
		send("GET", "api/me/profile/items?q=" + q + (slot >= 0 ? "&slot=" + slot : "") + accountQuery("&"), null, callback);
	}

	/** Upload the avatar render (PNG) for gear revision {rev}. */
	void uploadAvatar(byte[] png, int rev, Consumer<Result> callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("png", java.util.Base64.getEncoder().encodeToString(png));
		body.addProperty("rev", rev);
		send("PUT", "api/me/profile/avatar" + accountQuery("?"), body, callback);
	}

	/** A one-time link that opens the profile editor in the browser. */
	void profileLink(Consumer<Result> callback)
	{
		send("POST", "api/me/profile/link" + accountQuery("?"), null, callback);
	}

	/** The Set up page: {synced, history: waiting|importing|done|none, bank, collectionLog}. */
	void setup(Consumer<Result> callback)
	{
		get("api/setup", Map.of(), callback);
	}

	/** The player's plan, checked against their account ({ plan: null } when they have none). */
	void plan(Consumer<Result> callback)
	{
		get("api/plan", Map.of(), callback);
	}

	/** Tick a step or checkpoint, remove a checkpoint, or delete the plan: {op, checkpoint?, step?}. */
	void editPlan(JsonObject edit, Consumer<Result> callback)
	{
		String name = accountName.get();
		String query = name == null || name.isBlank() ? "" : "?account=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
		send("PATCH", "api/plan" + query, edit, callback);
	}

	/** Re-evaluate a gear view after the player changes a slot (bonuses, DPS, swaps, export). */
	void gear(JsonObject request, Consumer<Result> callback)
	{
		String name = accountName.get();
		String query = name == null || name.isBlank() ? "" : "?account=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
		send("POST", "api/gear" + query, request, callback);
	}

	/** Forget part of the player's data on the server: a kind (bank, worn, storage, location, loot, activity, clog) or one item. */
	void forget(String kind, String item, Consumer<Result> callback)
	{
		StringBuilder q = new StringBuilder("api/me/data?");
		String name = accountName.get();
		if (name != null && !name.isBlank())
		{
			q.append("account=").append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)).append('&');
		}
		q.append(kind != null ? "kind=" + kind : "item=" + java.net.URLEncoder.encode(item, java.nio.charset.StandardCharsets.UTF_8));
		send("DELETE", q.toString(), null, callback);
	}

	/** Upload an observed session's summary for review. */
	void uploadSession(JsonObject summary, Consumer<Result> callback)
	{
		String name = accountName.get();
		String query = name == null || name.isBlank() ? "" : "?account=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
		send("POST", "api/sessions" + query, summary, callback);
	}

	/** This install's daily allowance and linked accounts. */
	void me(Consumer<Result> callback)
	{
		send("GET", "api/me", null, callback);
	}

	/** Delete this install and everything it synced from the server. */
	void deleteMe(Consumer<Result> callback)
	{
		send("DELETE", "api/me", null, callback);
	}

	/** Use the player's own AI Gateway key (null removes it). */
	void setGatewayKey(String key, Consumer<Result> callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("key", key);
		send("PUT", "api/me/gateway-key", body, callback);
	}

	/** Join the private beta with an invite code. */
	void redeemInvite(String code, Consumer<Result> callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		send("POST", "api/me/invite", body, callback);
	}

	/** The player's own provider keys: {providers: [{id, label}], keys: [{provider, label, models}]} (never the keys). */
	void providerKeys(Consumer<Result> callback)
	{
		send("GET", "api/me/keys", null, callback);
	}

	/** Check and save a provider key (sent once, stored encrypted on the server). */
	void saveProviderKey(String provider, String key, Consumer<Result> callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("provider", provider);
		body.addProperty("key", key);
		send("PUT", "api/me/keys", body, callback);
	}

	void removeProviderKey(String provider, Consumer<Result> callback)
	{
		send("DELETE", "api/me/keys?provider=" + java.net.URLEncoder.encode(provider, java.nio.charset.StandardCharsets.UTF_8), null, callback);
	}

	/** A one-time code (10 minutes) for connecting another AI app, plus the MCP URL to add. */
	void pairingCode(Consumer<Result> callback)
	{
		send("POST", "api/me/pairing", null, callback);
	}

	/** What Squire remembers about the logged-in account: {notes: [{id, kind, text, done_at}], chats: [{summary, at}]}. */
	void notes(Consumer<Result> callback)
	{
		send("GET", "api/me/notes" + accountQuery("?"), null, callback);
	}

	/** Forget one note. */
	void forgetNote(int id, Consumer<Result> callback)
	{
		send("DELETE", "api/me/notes?id=" + id + accountQuery("&"), null, callback);
	}

	/** Mark a goal done (kept as a record). */
	void finishNote(int id, Consumer<Result> callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("done", true);
		send("PATCH", "api/me/notes?id=" + id + accountQuery("&"), body, callback);
	}

	private String accountQuery(String sep)
	{
		String name = accountName.get();
		return name == null || name.isBlank() ? "" : sep + "account=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
	}

	/** AI apps connected to this install. */
	void connections(Consumer<Result> callback)
	{
		send("GET", "api/me/connections", null, callback);
	}

	/** Disconnect an AI app: its tokens stop working. */
	void disconnect(String appId, Consumer<Result> callback)
	{
		send("DELETE", "api/me/connections?app=" + java.net.URLEncoder.encode(appId, java.nio.charset.StandardCharsets.UTF_8), null, callback);
	}

	/** Sign this install up; the reply carries its token. No token needed. */
	void register(Consumer<Result> callback)
	{
		HttpUrl base = HttpUrl.parse(endpoint.get().trim());
		if (base == null)
		{
			callback.accept(new Result(null, "The server URL isn't valid."));
			return;
		}
		Request request = new Request.Builder()
			.url(base.newBuilder().addPathSegments("api/register").build())
			.post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), "{}"))
			.build();
		http.newCall(request).enqueue(handler(callback));
	}

	private void send(String method, String path, JsonObject body, Consumer<Result> callback)
	{
		HttpUrl base = HttpUrl.parse(endpoint.get().trim());
		if (base == null || token.get().isBlank())
		{
			callback.accept(new Result(null, "Squire isn't turned on yet."));
			return;
		}
		okhttp3.RequestBody payload = body == null ? null : okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), gson.toJson(body));
		Request request = new Request.Builder()
			.url(withPath(base, path))
			.header("Authorization", "Bearer " + token.get().trim())
			.method(method, payload == null && !method.equals("GET") && !method.equals("DELETE") ? okhttp3.RequestBody.create(null, new byte[0]) : payload)
			.build();
		http.newCall(request).enqueue(handler(callback));
	}

	/** base + "a/b?x=y" (the query is kept, not encoded into the path). */
	private static HttpUrl withPath(HttpUrl base, String path)
	{
		int q = path.indexOf('?');
		HttpUrl.Builder b = base.newBuilder().addPathSegments(q < 0 ? path : path.substring(0, q));
		if (q >= 0)
		{
			b.encodedQuery(path.substring(q + 1));
		}
		return b.build();
	}

	private Callback handler(Consumer<Result> callback)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.accept(new Result(null, "Couldn't reach the server."));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					ResponseBody body = response.body();
					String text = body == null ? "" : body.string();
					JsonObject json = text.isEmpty() ? null : gson.fromJson(text, JsonObject.class);
					if (response.isSuccessful())
					{
						callback.accept(new Result(json, null));
					}
					else
					{
						String error = json != null && json.has("error") ? json.get("error").getAsString() : "Server returned " + response.code() + ".";
						callback.accept(new Result(null, error));
					}
				}
				catch (IOException | RuntimeException e)
				{
					callback.accept(new Result(null, "Couldn't read the server's reply."));
				}
			}
		};
	}

	private String notFoundReason(ResponseBody body)
	{
		try
		{
			JsonObject json = body == null ? null : gson.fromJson(body.string(), JsonObject.class);
			if (json != null && json.has("error") && json.get("error").isJsonPrimitive())
			{
				String error = json.get("error").getAsString();
				return error.startsWith("No synced account")
					? "Nothing synced for this character yet. Log in and it syncs automatically, or press **Update now** in settings."
					: error;
			}
		}
		catch (IOException | RuntimeException e)
		{
			// Not JSON: the server doesn't have this page
		}
		return "This page needs a newer Squire server. It should work after the next update.";
	}

	private void get(String path, Map<String, String> query, Consumer<Result> callback)
	{
		HttpUrl base = HttpUrl.parse(endpoint.get().trim());
		if (base == null || token.get().isBlank())
		{
			callback.accept(new Result(null, "Turn on Squire on the Home page to sync your account."));
			return;
		}
		HttpUrl.Builder url = base.newBuilder().addPathSegments(path);
		query.forEach(url::addQueryParameter);
		String name = accountName.get();
		if (name != null && !name.isBlank())
		{
			url.addQueryParameter("account", name);
		}
		Request request = new Request.Builder()
			.url(url.build())
			.header("Authorization", "Bearer " + token.get().trim())
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.accept(new Result(null, "Couldn't reach the server."));
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					ResponseBody body = response.body();
					if (response.code() == 401)
					{
						callback.accept(new Result(null, "The server didn't recognise this install. Turn Squire off and on again in settings."));
					}
					else if (response.code() == 404)
					{
						// The server says why (e.g. no account synced yet); a 404 without a reason means the page is newer than the server
						callback.accept(new Result(null, notFoundReason(body)));
					}
					else if (!response.isSuccessful() || body == null)
					{
						callback.accept(new Result(null, "Server returned " + response.code() + "."));
					}
					else
					{
						callback.accept(new Result(gson.fromJson(body.string(), JsonObject.class), null));
					}
				}
				catch (RuntimeException e)
				{
					callback.accept(new Result(null, "Couldn't read the server's reply."));
				}
			}
		});
	}
}
