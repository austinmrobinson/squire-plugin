package com.osrssync;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Downloads and caches pictures from the OSRS Wiki (boss renders for the Progress page). Wiki URLs only. */
class WikiImages
{
	private static final String HOST = "oldschool.runescape.wiki";

	private final OkHttpClient http;
	private final Map<String, BufferedImage> cache = new HashMap<>();
	private final Map<String, List<Consumer<BufferedImage>>> pending = new HashMap<>();

	WikiImages(OkHttpClient http)
	{
		this.http = http;
	}

	/** Calls back on the Swing thread with the image (right away if cached); never for failed or non-wiki URLs. */
	void load(String url, Consumer<BufferedImage> onLoaded)
	{
		HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
		if (parsed == null || !parsed.isHttps() || !HOST.equals(parsed.host()))
		{
			return;
		}
		BufferedImage cached = cache.get(url);
		if (cached != null)
		{
			onLoaded.accept(cached);
			return;
		}
		List<Consumer<BufferedImage>> waiting = pending.get(url);
		if (waiting != null)
		{
			waiting.add(onLoaded);
			return;
		}
		waiting = new ArrayList<>();
		waiting.add(onLoaded);
		pending.put(url, waiting);
		http.newCall(new Request.Builder().url(parsed).header("User-Agent", "RS-Buddy RuneLite plugin").build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				SwingUtilities.invokeLater(() -> pending.remove(url));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				BufferedImage image = null;
				try (response)
				{
					ResponseBody body = response.body();
					if (response.isSuccessful() && body != null)
					{
						try (InputStream in = body.byteStream())
						{
							image = ImageIO.read(in);
						}
					}
				}
				catch (IOException ignored)
				{
					// leave it blank
				}
				BufferedImage result = image;
				SwingUtilities.invokeLater(() ->
				{
					List<Consumer<BufferedImage>> callbacks = pending.remove(url);
					if (result != null)
					{
						cache.put(url, result);
						if (callbacks != null)
						{
							callbacks.forEach(c -> c.accept(result));
						}
					}
				});
			}
		});
	}
}
