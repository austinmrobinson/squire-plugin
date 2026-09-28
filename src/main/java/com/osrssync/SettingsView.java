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
	private final MessageList list = new MessageList(null, 0);
	private final Runnable onUpdate;
	private final Supplier<List<String[]>> chatSettings;
	/** The sync headline ("Last synced 08:41:12") and any details from the last sync ("Kill counts: 58"). */
	private String syncHeadline = "Not synced yet this session";
	private final List<String[]> syncDetails = new ArrayList<>();
	// This install on the RS Buddy server: today's messages, personal key, and the delete button
	private String usageLine = "Loading...";
	private boolean personalKey;
	private Runnable onDeleteData = () -> {};
	private Runnable onRemoveKey = () -> {};
	private Runnable onShownHook = () -> {};

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
	 * {@code chatSettings}: the in-game chat settings as label/value pairs (read-only here; they're changed in
	 * RuneLite's plugin settings).
	 */
	SettingsView(Runnable onUpdate, Supplier<List<String[]>> chatSettings)
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

		// Sync: just the action, with the latest status as a small line under it
		group("Sync");
		Surface sync = HomeView.listCard();
		StringBuilder details = new StringBuilder(syncHeadline);
		for (String[] d : syncDetails)
		{
			details.append("\n").append(d[0]).append(d[1].isEmpty() ? "" : ": " + d[1]);
		}
		sync.add(actionRow("Update now", syncHeadline, details.toString(), onUpdate));
		list.add(ChatComponents.place(sync, Align.FILL, 4));
		note("Re-reads skills, quests, diaries and items, backfills kill counts and PBs, and reads every combat "
			+ "achievement. Open your collection log afterwards to include it.");

		// In-game chat
		List<String[]> chat = chatSettings.get();
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
				c.add(row(chat.get(i)[0], chat.get(i)[1], null, null));
			}
			list.add(ChatComponents.place(c, Align.FILL, 4));
		}
		note("Change the shortcut and sync delay, or add your own AI Gateway key, in RuneLite's plugin settings (the wrench icon, then RS Buddy).");

		// Your data on the RS Buddy server
		group("Your data");
		Surface data = HomeView.listCard();
		data.add(row("Messages today", usageLine, null, null));
		if (personalKey)
		{
			data.add(HomeView.divider());
			data.add(row("Remove your AI Gateway key", null, "Go back to the free daily messages", onRemoveKey));
		}
		data.add(HomeView.divider());
		data.add(row("Delete my data", null, "Delete everything RS Buddy stored for you", () ->
		{
			int answer = javax.swing.JOptionPane.showConfirmDialog(this,
				"Delete everything RS Buddy stored for you (account data, the assistant's notes and chat history)?\nThis turns RS Buddy off and can't be undone.",
				"Delete my data", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (answer == javax.swing.JOptionPane.OK_OPTION)
			{
				onDeleteData.run();
			}
		}));
		list.add(ChatComponents.place(data, Align.FILL, 4));
		note("Deleting removes your data from the RS Buddy server and your chat history from this computer.");

		list.revalidate();
		list.repaint();
	}

	/** An action row with a small muted detail line under its label (e.g. the sync status). */
	private static JComponent actionRow(String label, String detail, String tooltip, Runnable onClick)
	{
		Surface r = new Surface(null, 0, false);
		r.setLayout(new BorderLayout(8, 0));
		r.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
		javax.swing.JPanel words = new javax.swing.JPanel();
		words.setOpaque(false);
		words.setLayout(new javax.swing.BoxLayout(words, javax.swing.BoxLayout.Y_AXIS));
		JLabel title = Ui.text(label, net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR);
		JLabel sub = Ui.small(detail);
		title.setAlignmentX(LEFT_ALIGNMENT);
		sub.setAlignmentX(LEFT_ALIGNMENT);
		words.add(title);
		words.add(sub);
		r.add(words, BorderLayout.CENTER);
		JLabel chevron = new JLabel(SvgIcon.load("chevron-right", 16, null));
		r.add(chevron, BorderLayout.EAST);
		r.setToolTipText("<html>" + MarkdownLite.escape(tooltip).replace("\n", "<br>") + "</html>");
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
		r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		java.awt.event.MouseAdapter m = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				r.setFill(ChatComponents.HOVER_BG);
				title.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				if (!r.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), r)))
				{
					r.setFill(null);
					title.setForeground(net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR);
				}
			}
		};
		for (java.awt.Component c : new java.awt.Component[]{r, words, title, sub, chevron})
		{
			c.addMouseListener(m);
		}
		return r;
	}

	private void group(String title)
	{
		JLabel label = Ui.sectionLabel(title);
		label.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
		list.add(ChatComponents.place(label, Align.LEFT, list.getComponentCount() == 0 ? 4 : 16));
	}

	private void note(String text)
	{
		list.add(ChatComponents.place(new Note(text), Align.FILL, 6));
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
