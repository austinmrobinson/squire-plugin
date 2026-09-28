package com.osrssync;

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

	/** A one-time code (10 minutes) for connecting another AI app, plus the MCP URL to add. */
	void pairingCode(Consumer<Result> callback)
	{
		send("POST", "api/me/pairing", null, callback);
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
						callback.accept(new Result(null, "Nothing synced for this character yet. Log in and it syncs automatically, or press **Update now** in settings."));
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
