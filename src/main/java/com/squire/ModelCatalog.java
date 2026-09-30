package com.squire;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * The models players can choose, fetched from the server's /api/models (with a built-in fallback).
 */
class ModelCatalog
{
	static final class Option
	{
		final String id;
		final String label;
		final String blurb;
		/** "key": runs on the player's own API key (no daily limit); "squire": Squire's free daily messages. */
		final String source;

		Option(String id, String label, String blurb)
		{
			this(id, label, blurb, "squire");
		}

		Option(String id, String label, String blurb, String source)
		{
			this.id = id;
			this.label = label;
			this.blurb = blurb;
			this.source = source;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	static final String DEFAULT_ID = "anthropic/claude-sonnet-5";

	// Mirrors server/agent/models.ts; used until the server answers
	private static final List<Option> FALLBACK = List.of(
		new Option("anthropic/claude-sonnet-5", "Claude Sonnet 5", "Balanced (default)"),
		new Option("anthropic/claude-opus-5.5", "Claude Opus 5.5", "Smartest, slower, pricier"),
		new Option("anthropic/claude-haiku-4.5", "Claude Haiku 4.5", "Fast and cheap"),
		new Option("openai/gpt-6-sol", "GPT-6 Sol", "Balanced"),
		new Option("openai/gpt-6-luna", "GPT-6 Luna", "Fast and cheap"),
		new Option("google/gemini-3.8-flash", "Gemini 3.8 Flash", "Fast"),
		new Option("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "Cheapest")
	);

	private final OkHttpClient http;
	private final Gson gson;
	private final Supplier<String> endpoint;
	private final Supplier<String> token;
	private volatile List<Option> options = FALLBACK;

	ModelCatalog(OkHttpClient http, Gson gson, Supplier<String> endpoint, Supplier<String> token)
	{
		this.http = http;
		this.gson = gson;
		this.endpoint = endpoint;
		this.token = token;
	}

	List<Option> options()
	{
		return options;
	}

	Option find(String id)
	{
		for (Option o : options)
		{
			if (o.id.equals(id))
			{
				return o;
			}
		}
		return options.get(0);
	}

	/** Refresh from the server; calls back (on an OkHttp thread) only when the list arrives. */
	void refresh(Consumer<List<Option>> onLoaded)
	{
		HttpUrl base = HttpUrl.parse(endpoint.get().trim());
		if (base == null || token.get().isBlank())
		{
			return;
		}
		Request request = new Request.Builder()
			.url(base.newBuilder().addPathSegments("api/models").build())
			.header("Authorization", "Bearer " + token.get().trim())
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					ResponseBody body = response.body();
					if (!response.isSuccessful() || body == null)
					{
						return;
					}
					JsonArray models = gson.fromJson(body.string(), JsonObject.class).getAsJsonArray("models");
					List<Option> loaded = new ArrayList<>();
					for (JsonElement e : models)
					{
						JsonObject m = e.getAsJsonObject();
						loaded.add(new Option(m.get("id").getAsString(), m.get("label").getAsString(), m.has("blurb") ? m.get("blurb").getAsString() : "",
							m.has("source") ? m.get("source").getAsString() : "squire"));
					}
					if (!loaded.isEmpty())
					{
						options = loaded;
						onLoaded.accept(loaded);
					}
				}
			}
		});
	}
}
