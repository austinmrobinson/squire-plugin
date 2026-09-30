package com.osrssync;

import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

/**
 * Settings, laid out like a phone's settings page: grouped lists with a label above each group, one row per
 * setting with its value on the right, and a short note under the group.
 */
class SettingsView extends javax.swing.JPanel
{
	private static final Color DANGER = new Color(0xF0625A);
	private final MessageList list = new MessageList(null, 0);
	private final Runnable onUpdate;
	private final Supplier<List<Item>> chatSettings;
	/** The sync headline ("Last synced 08:41:12") and any details from the last sync ("Kill counts: 58"). */
	private String syncHeadline = "Not synced yet this session";
	private final List<String[]> syncDetails = new ArrayList<>();
	// This install on the Squire server: today's messages, personal key, and the delete button
	private String usageLine = "Loading...";
	private boolean personalKey;
	private Runnable onDeleteData = () -> {};
	private Runnable onRemoveKey = () -> {};
	private Runnable onShownHook = () -> {};
	// Other AI apps connected over MCP: {id, name}
	private List<String[]> apps = List.of();
	private Runnable onConnect = () -> {};
	private java.util.function.Consumer<String> onDisconnect = id -> {};

	private Runnable onSyncPage = () -> {};
	// What Squire remembers: {id, kind, text, done ("1"/"")}
	private List<String[]> memory = List.of();
	private java.util.function.Consumer<String[]> onForget = n -> {};
	private java.util.function.Consumer<String[]> onFinish = n -> {};

	/** Forget a note, or mark a goal done. */
	void setMemoryActions(java.util.function.Consumer<String[]> onForget, java.util.function.Consumer<String[]> onFinish)
	{
		this.onForget = onForget;
		this.onFinish = onFinish;
	}

	/** Squire's notes about the player as {id, kind, text, done} rows. Any thread. */
	void setMemory(List<String[]> notes)
	{
		Runnable apply = () ->
		{
			memory = List.copyOf(notes);
			render();
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			apply.run();
		}
		else
		{
			SwingUtilities.invokeLater(apply);
		}
	}

	/** Open the What's synced page. */
	void setSyncPage(Runnable open)
	{
		this.onSyncPage = open;
	}

	/** Open the Connect page, and disconnect an app by id. */
	void setConnectActions(Runnable onConnect, java.util.function.Consumer<String> onDisconnect)
	{
		this.onConnect = onConnect;
		this.onDisconnect = onDisconnect;
	}

	/** The connected apps as {id, name} pairs. Any thread. */
	void setConnectedApps(List<String[]> connected)
	{
		Runnable apply = () ->
		{
			apps = List.copyOf(connected);
			render();
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			apply.run();
		}
		else
		{
			SwingUtilities.invokeLater(apply);
		}
	}

	/** Account actions and a hook to refresh the usage line when the page opens. */
	void setAccountActions(Runnable onDeleteData, Runnable onRemoveKey, Runnable onShown)
	{
		this.onDeleteData = onDeleteData;
		this.onRemoveKey = onRemoveKey;
		this.onShownHook = onShown;
	}

	/** Today's usage ("12 of 30 free messages today", "Unlimited (your key)") and whether a personal key is set. */
	void setUsage(String line, boolean hasPersonalKey)
	{
		Runnable apply = () ->
		{
			usageLine = line;
			personalKey = hasPersonalKey;
			render();
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			apply.run();
		}
		else
		{
			SwingUtilities.invokeLater(apply);
		}
	}

	/**
	 * {@code chatSettings}: the in-game chat settings, each editable in place (a toggle, a choice, or a shortcut
	 * the player presses).
	 */
	SettingsView(Runnable onUpdate, Supplier<List<Item>> chatSettings)
	{
		this.onUpdate = onUpdate;
		this.chatSettings = chatSettings;
		setLayout(new BorderLayout());
		setOpaque(false);
		// Room at the bottom to scroll clear of the floating composer
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, FloatingAsk.CLEARANCE, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		render();
	}

	SettingsView(Runnable onUpdate)
	{
		this(onUpdate, List::of);
	}

	/** Called when the page is shown: pick up changed settings. */
	void onShown()
	{
		onShownHook.run();
		render();
	}

	/**
	 * The latest sync status. Accepts the plugin's short HTML ("Last synced 08:41:12<ul><li>Kill counts: 58</li></ul>");
	 * list items become rows. Any thread.
	 */
	void setStatus(String html)
	{
		Runnable apply = () ->
		{
			String text = html == null ? "" : html;
			int list = text.indexOf("<ul>");
			syncHeadline = strip(list >= 0 ? text.substring(0, list) : text);
			syncDetails.clear();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("<li>(.*?)</li>").matcher(text);
			while (m.find())
			{
				String item = strip(m.group(1));
				int colon = item.indexOf(':');
				syncDetails.add(colon > 0 ? new String[]{item.substring(0, colon).trim(), item.substring(colon + 1).trim()} : new String[]{item, ""});
			}
			render();
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			apply.run();
		}
		else
		{
			SwingUtilities.invokeLater(apply);
		}
	}

	private void render()
	{
		list.removeAll();

		// Sync: the action, with the latest status as its subtitle and what it does on hover
		group("Sync");
		Surface sync = HomeView.listCard();
		StringBuilder details = new StringBuilder("Re-reads skills, quests, diaries and items, backfills kill counts and PBs, and reads every "
			+ "combat achievement. Open your collection log afterwards to include it.\n\n").append(syncHeadline);
		for (String[] d : syncDetails)
		{
			details.append("\n").append(d[0]).append(d[1].isEmpty() ? "" : ": " + d[1]);
		}
		sync.add(actionRow("Update now", syncHeadline, details.toString(), null, onUpdate));
		list.add(ChatComponents.place(sync, Align.FILL, 6));

		// In-game chat
		List<Item> chat = chatSettings.get();
		if (!chat.isEmpty())
		{
			group("In-game chat");
			Surface c = HomeView.listCard();
			for (int i = 0; i < chat.size(); i++)
			{
				if (i > 0)
				{
					c.add(HomeView.divider());
				}
				c.add(itemRow(chat.get(i)));
			}
			list.add(ChatComponents.place(c, Align.FILL, 6));
		}

		// Other AI apps using Squire's tools (MCP connectors)
		group("Connected apps");
		Surface conn = HomeView.listCard();
		for (String[] app : apps)
		{
			conn.add(row(app[1], null, "Disconnect " + app[1], () ->
			{
				int answer = javax.swing.JOptionPane.showConfirmDialog(this,
					"Disconnect " + app[1] + "? It will no longer be able to read your account.",
					"Disconnect app", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
				if (answer == javax.swing.JOptionPane.OK_OPTION)
				{
					onDisconnect.accept(app[0]);
				}
			}));
			conn.add(HomeView.divider());
		}
		conn.add(actionRow("Connect an AI app", "Claude, ChatGPT, Cursor and more",
			"Let Claude, ChatGPT, Cursor or another AI app read your synced account with Squire's tools.", null, onConnect));
		list.add(ChatComponents.place(conn, Align.FILL, 6));

		// What Squire remembers from your chats (shared with connected apps)
		group("Memory");
		Surface mem = HomeView.listCard();
		List<String[]> open = memory.stream().filter(n -> n[3].isEmpty()).collect(java.util.stream.Collectors.toList());
		if (open.isEmpty())
		{
			mem.add(row("Nothing yet", "", "Squire remembers your goals, preferences and decisions from your chats.", null));
		}
		for (int i = 0; i < open.size(); i++)
		{
			String[] n = open.get(i);
			if (i > 0)
			{
				mem.add(HomeView.divider());
			}
			String label = n[2].length() > 34 ? n[2].substring(0, 33) + "\u2026" : n[2];
			mem.add(row(label, null, "<html><body style='width:220px'>" + escape(n[2]) + "<br><br>" + n[1] + "</body></html>", () -> memoryMenu(n)));
		}
		list.add(ChatComponents.place(mem, Align.FILL, 6));

		// Your data on the Squire server
		group("Your data");
		Surface data = HomeView.listCard();
		data.add(row("Messages today", usageLine, null, null));
		data.add(HomeView.divider());
		data.add(row("What's synced", null, "Choose what Squire syncs, and hide items", onSyncPage));
		if (personalKey)
		{
			data.add(HomeView.divider());
			data.add(row("Remove your AI Gateway key", null, "Go back to the free daily messages", onRemoveKey));
		}
		list.add(ChatComponents.place(data, Align.FILL, 6));

		// Deleting stands on its own, in red, so it's never clicked by mistake
		Surface danger = HomeView.listCard();
		danger.add(actionRow("Delete my data", null,
			"Removes your data from the Squire server and your chat history from this computer.", DANGER, () ->
		{
			int answer = javax.swing.JOptionPane.showConfirmDialog(this,
				"Delete everything Squire stored for you (account data, the assistant's notes and chat history)?\nThis turns Squire off and can't be undone.",
				"Delete my data", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (answer == javax.swing.JOptionPane.OK_OPTION)
			{
				onDeleteData.run();
			}
		}));
		list.add(ChatComponents.place(danger, Align.FILL, 16));

		list.revalidate();
		list.repaint();
	}

	/**
	 * An action row: label with an optional small muted detail line under it (e.g. the sync status), a chevron, and
	 * a tooltip. {@code color} tints the label and chevron (red for destructive actions); null for the default.
	 */
	private static JComponent actionRow(String label, String detail, String tooltip, Color color, Runnable onClick)
	{
		Color rest = color != null ? color : net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR;
		Color hot = color != null ? color.brighter() : Color.WHITE;
		Surface r = new Surface(null, 0, false);
		r.setLayout(new BorderLayout(8, 0));
		r.setBorder(detail == null ? BorderFactory.createEmptyBorder(0, 10, 0, 12) : BorderFactory.createEmptyBorder(7, 10, 7, 12));
		javax.swing.JPanel words = new javax.swing.JPanel();
		words.setOpaque(false);
		words.setLayout(new javax.swing.BoxLayout(words, javax.swing.BoxLayout.Y_AXIS));
		JLabel title = Ui.text(label, rest);
		JLabel sub = Ui.small(detail == null ? "" : detail);
		title.setAlignmentX(LEFT_ALIGNMENT);
		sub.setAlignmentX(LEFT_ALIGNMENT);
		if (detail != null)
		{
			words.add(title);
			words.add(sub);
		}
		else
		{
			// Just the label: centre it in the row
			words.add(javax.swing.Box.createVerticalGlue());
			words.add(title);
			words.add(javax.swing.Box.createVerticalGlue());
		}
		r.add(words, BorderLayout.CENTER);
		JLabel chevron = new JLabel(SvgIcon.load("chevron-right", 16, color));
		r.add(chevron, BorderLayout.EAST);
		r.setToolTipText("<html>" + MarkdownLite.escape(tooltip).replace("\n", "<br>") + "</html>");
		r.setAlignmentX(LEFT_ALIGNMENT);
		int height = detail == null ? 32 : r.getPreferredSize().height;
		r.setPreferredSize(new java.awt.Dimension(100, height));
		r.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, height));
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
				if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
				{
					return;
				}
				onClick.run();
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				r.setFill(ChatComponents.HOVER_BG);
				title.setForeground(hot);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				if (!r.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), r)))
				{
					r.setFill(null);
					title.setForeground(rest);
				}
			}
		};
		for (java.awt.Component c : new java.awt.Component[]{r, words, title, sub, chevron})
		{
			c.addMouseListener(m);
		}
		return r;
	}

	/** Forget a note, or for a goal, mark it done. */
	private void memoryMenu(String[] n)
	{
		boolean goal = "goal".equals(n[1]);
		Object[] options = goal ? new Object[]{"Mark done", "Forget", "Cancel"} : new Object[]{"Forget", "Cancel"};
		int choice = javax.swing.JOptionPane.showOptionDialog(this, n[2], goal ? "Goal" : "Squire remembers",
			javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE, null, options, options[options.length - 1]);
		if (goal && choice == 0)
		{
			onFinish.accept(n);
		}
		else if (choice == (goal ? 1 : 0))
		{
			onForget.accept(n);
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void group(String title)
	{
		JLabel label = Ui.bold(title);
		// Indented to line up with the row labels inside the cards
		label.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 0));
		list.add(ChatComponents.place(label, Align.LEFT, list.getComponentCount() == 0 ? 6 : 18));
	}

	private void note(String text)
	{
		list.add(ChatComponents.place(new Note(text), Align.FILL, 6));
	}

	/** An editable setting: clicking runs {@code onClick}, or, with {@code onShortcut}, records the next key combo. */
	static final class Item
	{
		final String label;
		final String value;
		final String tooltip;
		final Runnable onClick;
		final java.util.function.Consumer<net.runelite.client.config.Keybind> onShortcut;
		/** Non-null for on/off settings, drawn as a switch. */
		final Boolean on;

		private Item(String label, String value, String tooltip, Runnable onClick, java.util.function.Consumer<net.runelite.client.config.Keybind> onShortcut, Boolean on)
		{
			this.label = label;
			this.value = value;
			this.tooltip = tooltip;
			this.onClick = onClick;
			this.onShortcut = onShortcut;
			this.on = on;
		}

		/** An on/off setting, shown as a switch. */
		static Item toggle(String label, boolean on, String tooltip, Runnable onClick)
		{
			return new Item(label, null, tooltip, onClick, null, on);
		}

		/** A value that changes when clicked (a toggle, or the next option). */
		static Item choice(String label, String value, String tooltip, Runnable onClick)
		{
			return new Item(label, value, tooltip, onClick, null, null);
		}

		/** A keyboard shortcut: click, then press the new combination (Esc cancels, Backspace clears). */
		static Item shortcut(String label, String value, String tooltip, java.util.function.Consumer<net.runelite.client.config.Keybind> onShortcut)
		{
			return new Item(label, value, tooltip, null, onShortcut, null);
		}

		/** Read-only. */
		static Item info(String label, String value)
		{
			return new Item(label, value, null, null, null, null);
		}
	}

	private JComponent itemRow(Item item)
	{
		if (item.on != null)
		{
			JComponent r = HomeView.listRow(null, item.label, new ChatComponents.Switch(item.on), item.onClick);
			r.setToolTipText(item.tooltip);
			return r;
		}
		JLabel value = Ui.text(item.value == null ? "" : item.value, ChatComponents.MUTED);
		if (item.onShortcut == null)
		{
			JComponent r = HomeView.listRow(null, item.label, value, item.onClick);
			r.setToolTipText(item.tooltip);
			return r;
		}
		// Shortcut: the row takes focus and records the next key combination
		JComponent[] holder = new JComponent[1];
		holder[0] = HomeView.listRow(null, item.label, value, () ->
		{
			value.setText("Press keys...");
			value.setForeground(ChatComponents.ACCENT);
			holder[0].setFocusable(true);
			holder[0].requestFocusInWindow();
		});
		JComponent r = holder[0];
		r.setToolTipText(item.tooltip);
		r.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyPressed(java.awt.event.KeyEvent e)
			{
				if (!"Press keys...".equals(value.getText()))
				{
					return;
				}
				int code = e.getKeyCode();
				if (code == java.awt.event.KeyEvent.VK_SHIFT || code == java.awt.event.KeyEvent.VK_CONTROL
					|| code == java.awt.event.KeyEvent.VK_ALT || code == java.awt.event.KeyEvent.VK_META)
				{
					return; // wait for the actual key
				}
				e.consume();
				if (code == java.awt.event.KeyEvent.VK_ESCAPE)
				{
					render();
					return;
				}
				item.onShortcut.accept(code == java.awt.event.KeyEvent.VK_BACK_SPACE
					? net.runelite.client.config.Keybind.NOT_SET
					: new net.runelite.client.config.Keybind(code, e.getModifiersEx()));
			}
		});
		r.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				if ("Press keys...".equals(value.getText()))
				{
					render();
				}
			}
		});
		return r;
	}

	/** A settings row: label, value on the right (or a chevron when it does something). */
	private static JComponent row(String label, String value, String tooltip, Runnable onClick)
	{
		JComponent right;
		if (onClick != null)
		{
			right = new JLabel(SvgIcon.load("chevron-right", 16, null));
		}
		else
		{
			JLabel v = Ui.text(value == null ? "" : value, ChatComponents.MUTED);
			v.setToolTipText(tooltip);
			right = v;
		}
		JComponent r = HomeView.listRow(null, label, right, onClick);
		r.setToolTipText(tooltip);
		return r;
	}

	private static String strip(String html)
	{
		return html.replaceAll("<[^>]+>", "").replace("&amp;", "&").trim();
	}

	/** Muted, wrapping text under a group; the list asks it for its height at the width it gets. */
	private static final class Note extends JTextArea implements ChatComponents.HeightForWidth
	{
		Note(String text)
		{
			super(text);
			setLineWrap(true);
			setWrapStyleWord(true);
			setEditable(false);
			setFocusable(false);
			setOpaque(false);
			setFont(FontManager.getRunescapeSmallFont());
			setForeground(ChatComponents.MUTED);
			setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		}

		@Override
		public int heightForWidth(int width)
		{
			setSize(width, Short.MAX_VALUE);
			return getPreferredSize().height;
		}

		@Override
		public Color getBackground()
		{
			return new Color(0, 0, 0, 0);
		}
	}
}
