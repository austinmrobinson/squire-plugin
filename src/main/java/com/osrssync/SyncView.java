package com.osrssync;

import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import com.osrssync.WelcomeView.Wrapped;
import java.awt.BorderLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

/**
 * Settings, What's synced: turn each kind of data on or off, and hide individual items. Turning something off
 * stops the plugin sending it and removes what the server already has; hiding an item does the same for that item.
 */
class SyncView extends JPanel
{
	interface Controller
	{
		boolean isOn(String key);

		/** Turn a kind on or off (off also forgets it on the server). */
		void set(String key, String kind, boolean on);

		List<String> hidden();

		void hide(String item, boolean hide);
	}

	/** {config key, server kind, label, what it's for} */
	private static final String[][] KINDS = {
		{"syncBank", "bank", "Bank", "Your bank's items and value"},
		{"syncWorn", "worn", "Inventory and equipment", "What you carry and wear"},
		{"syncStorage", "storage", "Other storage", "Looting bag, rune pouch, seed vault, house"},
		{"syncLocation", "location", "Location and world", "For answers about where you are"},
		{"syncLoot", "loot", "Loot drops", "Loot history and drop luck"},
		{"syncActivity", "activity", "What you're doing", "Time played by monster and skill"},
		{"syncClog", "clog", "Collection log", "Your collection log slots"},
	};

	private final Controller controller;
	private final MessageList list = new MessageList(null, 0);

	SyncView(Controller controller)
	{
		super(new BorderLayout());
		this.controller = controller;
		setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, 16, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll);
		render();
	}

	void onShown()
	{
		render();
	}

	private void render()
	{
		list.removeAll();
		Wrapped intro = new Wrapped("Choose what Squire syncs. Turning something off stops sending it and removes what "
			+ "the server already has. Your levels, quests, diaries and kill counts are always synced: they're what Squire "
			+ "needs to help.", ChatComponents.MUTED, false);
		intro.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 11));
		list.add(ChatComponents.place(intro, Align.FILL, 6));

		list.add(ChatComponents.place(title("Synced"), Align.LEFT, 16));
		Surface kinds = HomeView.listCard();
		for (int i = 0; i < KINDS.length; i++)
		{
			String[] k = KINDS[i];
			if (i > 0)
			{
				kinds.add(HomeView.divider());
			}
			boolean on = controller.isOn(k[0]);
			JLabel value = Ui.text(on ? "On" : "Off", on ? ChatComponents.MUTED : ChatComponents.MUTED.darker());
			JComponent row = HomeView.listRow(null, k[2], value, () ->
			{
				if (on)
				{
					int answer = JOptionPane.showConfirmDialog(this,
						"Stop syncing " + k[2].toLowerCase() + "? Squire will also forget what it already has.",
						"What's synced", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
					if (answer != JOptionPane.OK_OPTION)
					{
						return;
					}
				}
				controller.set(k[0], k[1], !on);
				render();
			});
			row.setToolTipText(k[3]);
			kinds.add(row);
		}
		list.add(ChatComponents.place(kinds, Align.FILL, 6));

		list.add(ChatComponents.place(title("Hidden items"), Align.LEFT, 18));
		Surface hidden = HomeView.listCard();
		for (String item : controller.hidden())
		{
			JComponent row = HomeView.listRow(null, item, Ui.text("Show", ChatComponents.MUTED), () ->
			{
				controller.hide(item, false);
				render();
			});
			row.setToolTipText("Sync " + item + " again");
			hidden.add(row);
			hidden.add(HomeView.divider());
		}
		JComponent add = HomeView.listRow(null, "Hide an item", new JLabel(SvgIcon.load("chevron-right", 16, null)), () ->
		{
			String name = JOptionPane.showInputDialog(this, "Item name, exactly as in game (e.g. Twisted bow):", "Hide an item", JOptionPane.PLAIN_MESSAGE);
			if (name != null && !name.isBlank())
			{
				controller.hide(name.trim(), true);
				render();
			}
		});
		add.setToolTipText("Never sync this item, and remove it from the server");
		hidden.add(add);
		list.add(ChatComponents.place(hidden, Align.FILL, 6));
		list.revalidate();
		list.repaint();
	}

	private static JLabel title(String text)
	{
		JLabel l = Ui.bold(text);
		l.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 0));
		return l;
	}
}
