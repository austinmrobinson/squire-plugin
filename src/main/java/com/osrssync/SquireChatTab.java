package com.osrssync;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * A "Squire" stone among the chatbox's bottom tabs, between Trade and Report: the other tabs narrow a little to
 * make room. Its second line shows Squire's status (Ready, Thinking, Observing, New). Clicking it focuses the
 * chatbox on Squire (only Squire's lines show) and opens the Ask prompt; clicking any game tab goes back.
 * Nothing is typed for the player: questions go through RuneLite's own chatbox prompt, and never to the game.
 * All methods run on the client thread.
 */
class SquireChatTab
{
	/** The game's tabs, left to right; Squire goes in before Report. */
	private static final int[] TABS = {
		InterfaceID.Chatbox.CHAT_ALL, InterfaceID.Chatbox.CHAT_GAME, InterfaceID.Chatbox.CHAT_PUBLIC, InterfaceID.Chatbox.CHAT_PRIVATE,
		InterfaceID.Chatbox.CHAT_FRIENDSCHAT, InterfaceID.Chatbox.CHAT_CLAN, InterfaceID.Chatbox.CHAT_TRADE,
	};
	private static final int REPORT = InterfaceID.Chatbox.REPORTABUSE;
	private static final int YELLOW = 0xFFFF00, ORANGE = 0xFF981F, WHITE = 0xFFFFFF;

	private final Client client;
	private final Runnable openPrompt;
	private final Supplier<String> busyStatus;
	private final Runnable rebuildChat;

	private Widget graphic;
	private Widget name;
	/** The status icon left of the name (Ready, Thinking, Observing, New). */
	private Widget status;

	// Status icons, drawn here and registered as sprites under ids the game doesn't use
	private static final int SPRITE_BASE = 0x5C0100;
	private static final int READY = SPRITE_BASE, THINKING = SPRITE_BASE + 1, OBSERVING = SPRITE_BASE + 2, NEW = SPRITE_BASE + 3;
	private static final int ICON = 13;
	private boolean spritesAdded;
	private Widget parent;
	/** The tabs' own positions, to put back when the tab is turned off. */
	private final Map<Integer, int[]> original = new HashMap<>();
	/** The game's own tab row: {left, right, y, height}, and the bar width it was measured at. */
	private int[] natural;
	private int naturalBarWidth = -1;
	private boolean focused;
	private int unread;

	/**
	 * @param openPrompt opens the Ask Squire chatbox prompt
	 * @param busyStatus "Thinking" or "Observing" while that's happening, else null
	 * @param rebuildChat redraws the chatbox (later, not from inside the game's click handler)
	 */
	SquireChatTab(Client client, Runnable openPrompt, Supplier<String> busyStatus, Runnable rebuildChat)
	{
		this.rebuildChat = rebuildChat;
		this.client = client;
		this.openPrompt = openPrompt;
		this.busyStatus = busyStatus;
	}

	boolean focused()
	{
		return focused;
	}

	/** A Squire line was printed: counts as unread unless the tab is focused. */
	void onSquireLine()
	{
		if (!focused)
		{
			unread++;
		}
	}

	/** Put the tab in place (and keep it there after the game redraws the chatbox). Cheap when nothing changed. */
	void update()
	{
		Widget all = client.getWidget(TABS[0]);
		Widget report = client.getWidget(REPORT);
		if (all == null || report == null || all.getParent() == null)
		{
			return;
		}
		Widget bar = all.getParent();
		if (bar != parent || graphic == null || graphic.getParent() != bar)
		{
			parent = bar;
			create(bar);
		}
		layout(bar);
		refreshStatus();
	}

	/** Remove the tab and put the game's tabs back as they were. */
	void remove()
	{
		setFocused(false);
		restoreGameTabs();
		natural = null;
		for (Widget w : new Widget[]{graphic, name, status})
		{
			if (w != null)
			{
				w.setHidden(true);
			}
		}
		graphic = name = status = null;
		parent = null;
		removeSprites();
	}

	/** Called for every chat line while filtering: false hides it (focused mode shows only Squire's lines). */
	boolean keep(int messageId)
	{
		if (!focused)
		{
			return true;
		}
		MessageNode node = client.getMessages().get(messageId);
		return node != null && InGameChat.isSquireLine(node.getValue());
	}

	/** The player clicked one of the game's tabs: leave Squire focus. */
	void onGameTabClicked()
	{
		if (focused)
		{
			setFocused(false);
		}
	}

	static boolean isGameTab(int widgetId)
	{
		for (int t : TABS)
		{
			if (t == widgetId)
			{
				return true;
			}
		}
		return widgetId == REPORT;
	}

	// ----

	private void setFocused(boolean on)
	{
		if (focused == on)
		{
			return;
		}
		focused = on;
		if (on)
		{
			unread = 0;
		}
		refreshStatus();
		// Redraw the chatbox so the filter applies
		rebuildChat.run();
	}

	private void create(Widget bar)
	{
		Widget sampleGraphic = child(TABS[TABS.length - 1], 0);
		Widget sampleName = client.getWidget(InterfaceID.Chatbox.CHAT_TRADE_TEXT);
		graphic = bar.createChild(-1, WidgetType.GRAPHIC);
		learnSprites();
		if (normalSprite >= 0)
		{
			graphic.setSpriteId(normalSprite);
		}
		else if (sampleGraphic != null)
		{
			graphic.setSpriteId(sampleGraphic.getSpriteId());
		}
		name = bar.createChild(-1, WidgetType.TEXT);
		name.setText("Squire");
		styleText(name, sampleName, WHITE);
		// One centred line, like a tab without a filter label
		name.setYTextAlignment(WidgetTextAlignment.CENTER);
		addSprites();
		status = bar.createChild(-1, WidgetType.GRAPHIC);
		status.setSpriteId(READY);

		// No target name: the game's own tabs show just the option ("Switch tab"), not "Option Target"
		graphic.setName("");
		graphic.setAction(0, "View Squire");
		graphic.setAction(1, "Ask Squire");
		graphic.setHasListener(true);
		graphic.setOnOpListener((JavaScriptCallback) ev ->
		{
			switch (ev.getOp())
			{
				case 1:
					// View: show only Squire's lines (and ask, since that's why you'd look)
					setFocused(true);
					openPrompt.run();
					break;
				case 2:
					openPrompt.run();
					break;
				default:
					setFocused(false);
			}
		});
	}

	private static void styleText(Widget w, Widget sample, int color)
	{
		if (sample != null)
		{
			w.setFontId(sample.getFontId());
			w.setTextShadowed(sample.getTextShadowed());
		}
		w.setTextColor(color);
		// Same alignment as the game's tab text: the name at the top, the status at the bottom
		w.setXTextAlignment(sample != null ? sample.getXTextAlignment() : WidgetTextAlignment.CENTER);
		w.setYTextAlignment(sample != null ? sample.getYTextAlignment() : WidgetTextAlignment.CENTER);
	}

	/** Nine even slots across the bar: the seven chat tabs, Squire, Report. */
	private void layout(Widget bar)
	{
		Widget all = client.getWidget(TABS[0]);
		Widget report = client.getWidget(REPORT);
		// Where the game itself puts the tabs (captured before we move anything, per bar width)
		if (natural == null || naturalBarWidth != bar.getWidth())
		{
			restoreGameTabs();
			natural = new int[]{all.getRelativeX(), report.getRelativeX() + report.getWidth(), all.getRelativeY(), all.getHeight()};
			naturalBarWidth = bar.getWidth();
			org.slf4j.LoggerFactory.getLogger(SquireChatTab.class).info("Squire chat tab: bar width {}, tabs from {} to {}, y {}, height {}, report x {} w {} (xMode {})",
				bar.getWidth(), natural[0], natural[1], natural[2], natural[3], report.getRelativeX(), report.getWidth(), report.getXPositionMode());
		}
		int left = natural[0], right = natural[1];
		int slots = TABS.length + 2;
		int w = (right - left) / slots;
		// Never make things worse: if the numbers look wrong, leave the game's tabs alone and hide ours
		if (w < 30 || w > 120 || natural[3] <= 0)
		{
			restoreGameTabs();
			for (Widget x : new Widget[]{graphic, name, status})
			{
				if (x != null)
				{
					x.setHidden(true);
				}
			}
			return;
		}
		for (int id : TABS)
		{
			remember(id);
		}
		remember(REPORT);
		for (int i = 0; i < TABS.length; i++)
		{
			Widget tab = client.getWidget(TABS[i]);
			if (tab != null)
			{
				place(tab, left + i * w, w);
			}
		}
		int squireX = left + TABS.length * w;
		place(report, squireX + w, right - (squireX + w));

		// The stone, then the status icon and "Squire" side by side, centred
		int y = natural[2], h = natural[3];
		put(graphic, squireX, y, w, h);
		int textWidth = 36;
		int group = ICON + 3 + textWidth;
		int gx = squireX + Math.max(2, (w - group) / 2);
		put(status, gx, y + (h - ICON) / 2, ICON, ICON);
		put(name, gx + ICON + 3, y, Math.min(textWidth + 4, squireX + w - (gx + ICON + 3)), h);
		name.setXTextAlignment(WidgetTextAlignment.LEFT);

		learnSprites();
		// While focused, no game tab looks selected: the Squire stone is
		if (focused && normalSprite >= 0)
		{
			for (int id : TABS)
			{
				Widget g = child(id, 0);
				if (g != null && g.getSpriteId() != normalSprite)
				{
					g.setSpriteId(normalSprite);
				}
			}
		}
	}

	/** The game's stone graphics: the one most tabs use (normal) and the odd one out (the selected tab). */
	private int normalSprite = -1, selectedSprite = -1;

	private void learnSprites()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		for (int id : TABS)
		{
			Widget g = child(id, 0);
			if (g != null && g.getSpriteId() >= 0)
			{
				counts.merge(g.getSpriteId(), 1, Integer::sum);
			}
		}
		if (counts.isEmpty())
		{
			return;
		}
		int best = -1, bestCount = -1;
		for (Map.Entry<Integer, Integer> e : counts.entrySet())
		{
			if (e.getValue() > bestCount)
			{
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		normalSprite = best;
		for (int sprite : counts.keySet())
		{
			if (sprite != best && !focused)
			{
				selectedSprite = sprite;
			}
		}
	}

	private void refreshStatus()
	{
		if (status == null || name == null)
		{
			return;
		}
		String busy = busyStatus.get();
		int icon = "Observing".equals(busy) ? OBSERVING : busy != null ? THINKING : unread > 0 && !focused ? NEW : READY;
		if (status.getSpriteId() != icon)
		{
			status.setSpriteId(icon);
		}
		name.setTextColor(WHITE);
		if (graphic != null)
		{
			graphic.setAction(2, focused ? "Show all chat" : null);
			int sprite = focused && selectedSprite >= 0 ? selectedSprite : normalSprite;
			if (sprite >= 0 && graphic.getSpriteId() != sprite)
			{
				graphic.setSpriteId(sprite);
			}
		}
	}

	/** Draw the status icons (13px pixel art) and register them as sprites. */
	private void addSprites()
	{
		if (spritesAdded)
		{
			return;
		}
		java.util.Map<Integer, net.runelite.api.SpritePixels> overrides = client.getSpriteOverrides();
		overrides.put(READY, net.runelite.client.util.ImageUtil.getImageSpritePixels(SquireIcon.create(ICON), client));
		overrides.put(THINKING, net.runelite.client.util.ImageUtil.getImageSpritePixels(dots(), client));
		overrides.put(OBSERVING, net.runelite.client.util.ImageUtil.getImageSpritePixels(redDot(), client));
		overrides.put(NEW, net.runelite.client.util.ImageUtil.getImageSpritePixels(helmWithBadge(), client));
		spritesAdded = true;
	}

	private void removeSprites()
	{
		if (spritesAdded)
		{
			for (int id : new int[]{READY, THINKING, OBSERVING, NEW})
			{
				client.getSpriteOverrides().remove(id);
			}
			spritesAdded = false;
		}
	}

	private static java.awt.image.BufferedImage canvas()
	{
		return new java.awt.image.BufferedImage(ICON, ICON, java.awt.image.BufferedImage.TYPE_INT_ARGB);
	}

	/** Thinking: three yellow dots with a dark outline. */
	private static java.awt.image.BufferedImage dots()
	{
		java.awt.image.BufferedImage img = canvas();
		java.awt.Graphics2D g = img.createGraphics();
		for (int x : new int[]{0, 5, 10})
		{
			g.setColor(java.awt.Color.BLACK);
			g.fillRect(x, 5, 3, 4);
			g.setColor(new java.awt.Color(YELLOW));
			g.fillRect(x, 5, 3, 3);
		}
		g.dispose();
		return img;
	}

	/** Observing: a red dot, like a recording light. */
	private static java.awt.image.BufferedImage redDot()
	{
		java.awt.image.BufferedImage img = canvas();
		java.awt.Graphics2D g = img.createGraphics();
		g.setColor(java.awt.Color.BLACK);
		g.fillRect(3, 2, 7, 9);
		g.fillRect(2, 3, 9, 7);
		g.setColor(new java.awt.Color(0xE5534B));
		g.fillRect(4, 3, 5, 7);
		g.fillRect(3, 4, 7, 5);
		g.setColor(new java.awt.Color(0xFF8A80));
		g.fillRect(4, 4, 2, 2);
		g.dispose();
		return img;
	}

	/** New reply: the helm with an orange badge in the corner. */
	private static java.awt.image.BufferedImage helmWithBadge()
	{
		java.awt.image.BufferedImage img = SquireIcon.create(ICON);
		java.awt.Graphics2D g = img.createGraphics();
		g.setColor(java.awt.Color.BLACK);
		g.fillRect(8, 0, 5, 5);
		g.setColor(new java.awt.Color(ORANGE));
		g.fillRect(9, 1, 3, 3);
		g.dispose();
		return img;
	}

	/** The game's own geometry for a tab and its children: {xMode, x, widthMode, width} each. */
	private void remember(int id)
	{
		if (original.containsKey(id))
		{
			return;
		}
		Widget w = client.getWidget(id);
		if (w == null)
		{
			return;
		}
		Widget[] kids = w.getStaticChildren();
		int n = kids == null ? 0 : kids.length;
		int[] g = new int[4 + n * 4];
		store(g, 0, w);
		for (int i = 0; i < n; i++)
		{
			store(g, 4 + i * 4, kids[i]);
		}
		original.put(id, g);
	}

	private static void store(int[] g, int at, Widget w)
	{
		g[at] = w.getXPositionMode();
		g[at + 1] = w.getOriginalX();
		g[at + 2] = w.getWidthMode();
		g[at + 3] = w.getOriginalWidth();
	}

	private static void apply(int[] g, int at, Widget w)
	{
		w.setXPositionMode(g[at]);
		w.setOriginalX(g[at + 1]);
		w.setWidthMode(g[at + 2]);
		w.setOriginalWidth(g[at + 3]);
		w.revalidate();
	}

	/** Put the game's tabs back exactly as the game had them. */
	private void restoreGameTabs()
	{
		for (Map.Entry<Integer, int[]> e : original.entrySet())
		{
			Widget w = client.getWidget(e.getKey());
			if (w == null)
			{
				continue;
			}
			int[] g = e.getValue();
			apply(g, 0, w);
			Widget[] kids = w.getStaticChildren();
			for (int i = 0; kids != null && i < kids.length && 4 + i * 4 < g.length; i++)
			{
				apply(g, 4 + i * 4, kids[i]);
			}
		}
		original.clear();
	}

	/**
	 * Move a tab to x and give it width w. Children that spanned the whole tab (its graphic) get the new width; the
	 * rest keep their own sizing (text lines that follow their parent's width already).
	 */
	private void place(Widget tab, int x, int w)
	{
		if (tab.getRelativeX() == x && tab.getWidth() == w)
		{
			return;
		}
		int oldWidth = tab.getWidth();
		tab.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		tab.setWidthMode(WidgetSizeMode.ABSOLUTE);
		tab.setOriginalX(x);
		tab.setOriginalWidth(w);
		Widget[] kids = tab.getStaticChildren();
		if (kids != null)
		{
			for (Widget k : kids)
			{
				if (k.getWidthMode() == WidgetSizeMode.ABSOLUTE && k.getWidth() == oldWidth)
				{
					k.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
					k.setOriginalX(0);
					k.setOriginalWidth(w);
				}
			}
		}
		tab.revalidate();
		if (kids != null)
		{
			for (Widget k : kids)
			{
				k.revalidate();
			}
		}
	}

	private static void put(Widget w, int x, int y, int width, int height)
	{
		if (w == null)
		{
			return;
		}
		if (w.getOriginalX() != x || w.getOriginalY() != y || w.getOriginalWidth() != width || w.getOriginalHeight() != height || w.isHidden())
		{
			w.setHidden(false);
			w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			w.setWidthMode(WidgetSizeMode.ABSOLUTE);
			w.setHeightMode(WidgetSizeMode.ABSOLUTE);
			w.setOriginalX(x);
			w.setOriginalY(y);
			w.setOriginalWidth(width);
			w.setOriginalHeight(height);
			w.revalidate();
		}
	}

	/** A tab's n-th static child (0 = its graphic). */
	private Widget child(int tabId, int n)
	{
		Widget tab = client.getWidget(tabId);
		Widget[] kids = tab == null ? null : tab.getStaticChildren();
		return kids != null && kids.length > n ? kids[n] : null;
	}
}
