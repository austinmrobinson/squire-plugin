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
	private static final int GREEN = 0x00FF00, YELLOW = 0xFFFF00, ORANGE = 0xFF981F, WHITE = 0xFFFFFF;

	private final Client client;
	private final Runnable openPrompt;
	private final Supplier<String> busyStatus;
	private final Runnable rebuildChat;

	private Widget graphic;
	private Widget name;
	private Widget status;
	private Widget parent;
	/** The tabs' own positions, to put back when the tab is turned off. */
	private final Map<Integer, int[]> original = new HashMap<>();
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
		for (Map.Entry<Integer, int[]> e : original.entrySet())
		{
			Widget w = client.getWidget(e.getKey());
			if (w != null)
			{
				place(w, e.getValue()[0], e.getValue()[1]);
			}
		}
		for (Widget w : new Widget[]{graphic, name, status})
		{
			if (w != null)
			{
				w.setHidden(true);
			}
		}
		graphic = name = status = null;
		parent = null;
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
		Widget sampleFilter = client.getWidget(InterfaceID.Chatbox.CHAT_TRADE_FILTER);
		graphic = bar.createChild(-1, WidgetType.GRAPHIC);
		if (sampleGraphic != null)
		{
			graphic.setSpriteId(sampleGraphic.getSpriteId());
		}
		name = bar.createChild(-1, WidgetType.TEXT);
		name.setText("Squire");
		styleText(name, sampleName, WHITE);
		status = bar.createChild(-1, WidgetType.TEXT);
		styleText(status, sampleFilter, GREEN);

		graphic.setName("<col=ff9040>Squire</col>");
		graphic.setAction(0, "Squire");
		graphic.setAction(1, "Ask Squire");
		graphic.setAction(2, "Show all chat");
		graphic.setHasListener(true);
		graphic.setOnOpListener((JavaScriptCallback) ev ->
		{
			switch (ev.getOp())
			{
				case 1:
					// First click focuses; clicking again while focused asks
					if (focused)
					{
						openPrompt.run();
					}
					else
					{
						setFocused(true);
						openPrompt.run();
					}
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
		w.setXTextAlignment(WidgetTextAlignment.CENTER);
		w.setYTextAlignment(WidgetTextAlignment.CENTER);
	}

	/** Nine even slots across the bar: the seven chat tabs, Squire, Report. */
	private void layout(Widget bar)
	{
		Widget all = client.getWidget(TABS[0]);
		Widget report = client.getWidget(REPORT);
		for (int id : TABS)
		{
			remember(id);
		}
		remember(REPORT);
		int left = original.get(TABS[0])[0];
		int right = original.get(REPORT)[0] + original.get(REPORT)[1];
		int slots = TABS.length + 2;
		int w = (right - left) / slots;
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

		// Squire's pieces sit where a tab's graphic and two text lines would
		int y = all.getOriginalY(), h = all.getOriginalHeight();
		Widget sampleName = client.getWidget(InterfaceID.Chatbox.CHAT_TRADE_TEXT);
		Widget sampleFilter = client.getWidget(InterfaceID.Chatbox.CHAT_TRADE_FILTER);
		put(graphic, squireX, y, w, h);
		put(name, squireX, y + (sampleName != null ? sampleName.getOriginalY() : 0), w, sampleName != null ? sampleName.getOriginalHeight() : h / 2);
		put(status, squireX, y + (sampleFilter != null ? sampleFilter.getOriginalY() : h / 2), w, sampleFilter != null ? sampleFilter.getOriginalHeight() : h / 2);

		// While focused, no game tab looks selected: the Squire stone is
		Widget normal = child(TABS[TABS.length - 1], 0);
		if (focused && normal != null)
		{
			for (int id : TABS)
			{
				Widget g = child(id, 0);
				if (g != null && g.getSpriteId() != normal.getSpriteId())
				{
					g.setSpriteId(normal.getSpriteId());
				}
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
		String text;
		int color;
		if (busy != null)
		{
			text = busy;
			color = YELLOW;
		}
		else if (unread > 0 && !focused)
		{
			text = "New";
			color = ORANGE;
		}
		else
		{
			text = focused ? "On" : "Ready";
			color = GREEN;
		}
		if (!text.equals(status.getText()))
		{
			status.setText(text);
		}
		status.setTextColor(color);
		name.setTextColor(focused ? YELLOW : WHITE);
	}

	private void remember(int id)
	{
		if (!original.containsKey(id))
		{
			Widget w = client.getWidget(id);
			if (w != null)
			{
				original.put(id, new int[]{w.getOriginalX(), w.getOriginalWidth()});
			}
		}
	}

	/** Move a tab and stretch its graphic and text lines to the new width. */
	private static void place(Widget tab, int x, int w)
	{
		if (tab.getOriginalX() != x || tab.getOriginalWidth() != w)
		{
			tab.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			tab.setWidthMode(WidgetSizeMode.ABSOLUTE);
			tab.setOriginalX(x);
			tab.setOriginalWidth(w);
			Widget[] kids = tab.getStaticChildren();
			if (kids != null)
			{
				for (Widget k : kids)
				{
					k.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
					k.setWidthMode(WidgetSizeMode.ABSOLUTE);
					k.setOriginalX(0);
					k.setOriginalWidth(w);
					k.revalidate();
				}
			}
			tab.revalidate();
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
