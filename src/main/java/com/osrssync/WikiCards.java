package com.osrssync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Hover cards for OSRS Wiki links in chat: the page's picture, title and first sentences, in a small popup.
 * The summaries come straight from the wiki's API and are cached. Clicking the link opens the page.
 */
final class WikiCards
{
	static final String WIKI = "https://oldschool.runescape.wiki/w/";
	private static final String API = "https://oldschool.runescape.wiki/api.php";
	static final String LOGO = "https://oldschool.runescape.wiki/images/Wiki.png";
	private static final int WIDTH = 240;
	private static final int SHOW_DELAY_MS = 350;

	static final class Card
	{
		final String title;
		final String url;
		final String summary;
		final String image;

		Card(String title, String url, String summary, String image)
		{
			this.title = title;
			this.url = url;
			this.summary = summary;
			this.image = image;
		}
	}

	private static OkHttpClient http;
	private static WikiImages images;
	private static final Map<String, Card> CACHE = new HashMap<>();
	private static final Map<String, List<Consumer<Card>>> PENDING = new HashMap<>();
	private static JWindow popup;
	private static Timer showTimer;
	private static String hovering;

	/** Set up by the plugin; without it (e.g. in previews) hovering does nothing. */
	static void install(OkHttpClient client)
	{
		http = client;
		images = new WikiImages(client);
	}

	static WikiImages images()
	{
		return images;
	}

	/** The page title for a wiki URL, or null if it isn't one. */
	static String titleOf(String url)
	{
		if (url == null || !url.startsWith(WIKI))
		{
			return null;
		}
		String rest = url.substring(WIKI.length());
		int hash = rest.indexOf('#');
		if (hash >= 0)
		{
			rest = rest.substring(0, hash);
		}
		try
		{
			return URLDecoder.decode(rest, StandardCharsets.UTF_8).replace('_', ' ');
		}
		catch (IllegalArgumentException e)
		{
			return rest.replace('_', ' ');
		}
	}

	/** The pointer moved onto a wiki link: show its card after a short pause. */
	static void hover(String url, Component over)
	{
		String title = titleOf(url);
		if (http == null || title == null)
		{
			return;
		}
		hovering = url;
		if (showTimer != null)
		{
			showTimer.stop();
		}
		showTimer = new Timer(SHOW_DELAY_MS, e ->
		{
			if (!url.equals(hovering))
			{
				return;
			}
			Point mouse = over.getMousePosition();
			if (mouse == null)
			{
				return;
			}
			SwingUtilities.convertPointToScreen(mouse, over);
			Point at = mouse;
			load(title, card ->
			{
				if (url.equals(hovering))
				{
					showCard(card, over, at);
				}
			});
		});
		showTimer.setRepeats(false);
		showTimer.start();
	}

	/** The pointer left the link. */
	static void unhover()
	{
		hovering = null;
		if (showTimer != null)
		{
			showTimer.stop();
		}
		if (popup != null)
		{
			popup.setVisible(false);
			popup.dispose();
			popup = null;
		}
	}

	private static void showCard(Card card, Component over, Point at)
	{
		if (popup != null)
		{
			popup.dispose();
		}
		Window owner = SwingUtilities.getWindowAncestor(over);
		popup = new JWindow(owner);
		popup.setFocusableWindowState(false);
		popup.setBackground(new Color(0, 0, 0, 0));
		popup.setContentPane(cardView(card));
		popup.pack();

		// Below and right of the pointer, kept on screen
		Rectangle screen = owner != null ? owner.getGraphicsConfiguration().getBounds() : new Rectangle(0, 0, 4000, 4000);
		GraphicsConfiguration gc = over.getGraphicsConfiguration();
		if (gc != null)
		{
			screen = gc.getBounds();
		}
		int x = Math.min(at.x + 12, screen.x + screen.width - popup.getWidth() - 8);
		int y = at.y + 16;
		if (y + popup.getHeight() > screen.y + screen.height - 8)
		{
			y = at.y - popup.getHeight() - 8;
		}
		popup.setLocation(Math.max(screen.x + 8, x), Math.max(screen.y + 8, y));
		popup.setVisible(true);
	}

	/** The card: a picture well on top, then the title, where it's from, and a couple of sentences. */
	private static JComponent cardView(Card card)
	{
		ChatComponents.Surface s = new ChatComponents.Surface(ChatComponents.USER_BG, 8, true);
		s.setLayout(new BorderLayout());
		s.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		if (card.image != null)
		{
			Picture pic = new Picture();
			if (images != null)
			{
				images.load(card.image, pic::setImage);
			}
			s.add(pic, BorderLayout.NORTH);
		}

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		text.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JLabel title = new JLabel(card.title);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		text.add(title);
		JLabel from = new JLabel("OSRS Wiki");
		from.setFont(FontManager.getRunescapeFont());
		from.setForeground(ChatComponents.MUTED);
		text.add(from);
		text.add(javax.swing.Box.createVerticalStrut(4));
		JTextArea summary = new JTextArea(card.summary == null ? "Loading..." : MarkdownLite.normalize(card.summary));
		summary.setLineWrap(true);
		summary.setWrapStyleWord(true);
		summary.setEditable(false);
		summary.setOpaque(false);
		summary.setFocusable(false);
		summary.setFont(FontManager.getRunescapeFont());
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setBorder(null);
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);
		int textWidth = WIDTH - 8 - 16;
		summary.setSize(textWidth, Short.MAX_VALUE);
		summary.setPreferredSize(new Dimension(textWidth, Math.min(summary.getPreferredSize().height, 96)));
		text.add(summary);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		from.setAlignmentX(Component.LEFT_ALIGNMENT);
		s.add(text, BorderLayout.CENTER);

		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
		wrap.add(s);
		wrap.setPreferredSize(new Dimension(WIDTH, wrap.getPreferredSize().height));
		return wrap;
	}

	/** The picture well: a dark rounded box with the wiki image fitted inside it. */
	private static final class Picture extends JComponent
	{
		private static final int HEIGHT = 72;
		private BufferedImage image;

		Picture()
		{
			setPreferredSize(new Dimension(WIDTH - 10, HEIGHT));
		}

		void setImage(BufferedImage image)
		{
			this.image = image;
			Window w = SwingUtilities.getWindowAncestor(this);
			repaint();
			if (w != null)
			{
				w.repaint();
			}
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ChatComponents.BASE_BG);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			if (image != null)
			{
				g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				int pad = 6;
				double scale = Math.min((getWidth() - pad * 2) / (double) image.getWidth(), (getHeight() - pad * 2) / (double) image.getHeight());
				scale = Math.min(scale, 2.0);
				int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
				g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
			}
			g2.dispose();
		}
	}

	/** The card as a component, for previews. */
	static JComponent preview(Card card)
	{
		return cardView(card);
	}


	/** The card for a page (cached); calls back on the Swing thread. */
	static void load(String title, Consumer<Card> onLoaded)
	{
		Card cached = CACHE.get(title);
		if (cached != null)
		{
			onLoaded.accept(cached);
			return;
		}
		List<Consumer<Card>> waiting = PENDING.get(title);
		if (waiting != null)
		{
			waiting.add(onLoaded);
			return;
		}
		PENDING.put(title, new ArrayList<>(List.of(onLoaded)));
		HttpUrl url = HttpUrl.parse(API).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("prop", "extracts|pageimages")
			.addQueryParameter("exintro", "1")
			.addQueryParameter("explaintext", "1")
			.addQueryParameter("exsentences", "2")
			.addQueryParameter("pithumbsize", "120")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.addQueryParameter("titles", title)
			.build();
		http.newCall(new Request.Builder().url(url).header("User-Agent", "RS-Buddy RuneLite plugin").build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				SwingUtilities.invokeLater(() -> PENDING.remove(title));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Card card = null;
				try (response)
				{
					ResponseBody body = response.body();
					if (response.isSuccessful() && body != null)
					{
						JsonObject root = new JsonParser().parse(body.string()).getAsJsonObject();
						JsonArray pages = root.getAsJsonObject("query").getAsJsonArray("pages");
						if (pages != null && pages.size() > 0)
						{
							JsonObject p = pages.get(0).getAsJsonObject();
							if (!p.has("missing"))
							{
								String t = p.get("title").getAsString();
								String summary = p.has("extract") ? p.get("extract").getAsString() : null;
								JsonElement thumb = p.get("thumbnail");
								String image = thumb != null && thumb.isJsonObject() ? thumb.getAsJsonObject().get("source").getAsString() : null;
								card = new Card(t, WIKI + t.replace(' ', '_'), summary, image);
							}
						}
					}
				}
				catch (IOException | RuntimeException ignored)
				{
					// no card
				}
				Card result = card;
				SwingUtilities.invokeLater(() ->
				{
					List<Consumer<Card>> callbacks = PENDING.remove(title);
					if (result != null)
					{
						CACHE.put(title, result);
						if (callbacks != null)
						{
							callbacks.forEach(c -> c.accept(result));
						}
					}
				});
			}
		});
	}

	private WikiCards()
	{
	}
}
