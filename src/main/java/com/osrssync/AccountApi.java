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

	private void get(String path, Map<String, String> query, Consumer<Result> callback)
	{
		HttpUrl base = HttpUrl.parse(endpoint.get().trim());
		if (base == null || token.get().isBlank())
		{
			callback.accept(new Result(null, "Set the server URL and ingest token in RuneLite's plugin settings (wrench icon, then **RS Buddy**)."));
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
						callback.accept(new Result(null, "The server rejected the ingest token. Check it in the plugin settings."));
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
