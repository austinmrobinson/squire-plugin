package com.osrssync;

import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Shown until the player turns RS Buddy on: what it does, what it sends to the RS Buddy server, a link to the
 * privacy page, and the button that signs this install up and starts syncing. Nothing is sent before that.
 */
class WelcomeView extends JPanel
{
	private final JLabel error = new JLabel();
	private final JButton turnOn = new ChatComponents.AccentButton("Turn on RS Buddy");

	/** {@code onTurnOn} gets a callback for errors (or null on success). */
	WelcomeView(String privacyUrl, Consumer<Consumer<String>> onTurnOn)
	{
		super(new BorderLayout());
		setOpaque(false);
		MessageList list = new MessageList(null, 0);
		list.setBorder(BorderFactory.createEmptyBorder(16, 4, 16, 4));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll);

		JLabel icon = new JLabel(new javax.swing.ImageIcon(BuddyIcon.create(40)), SwingConstants.CENTER);
		list.add(ChatComponents.place(icon, Align.FILL, 8));
		JLabel title = new JLabel("Welcome to RS Buddy", SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		list.add(ChatComponents.place(title, Align.FILL, 10));
		list.add(ChatComponents.place(paragraph(
			"An AI sidekick that knows your account: ask what to do next, how to gear for a boss, or where to get an item, "
				+ "and see your progress and time played.", ChatComponents.MUTED), Align.FILL, 8));

		Surface card = HomeView.listCard();
		card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		JLabel what = Ui.bold("What it sends to the RS Buddy server");
		what.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(what);
		JTextArea items = paragraph(
			"- Your character name and progress (levels, quests, diaries, combat achievements, collection log, kill counts)\n"
				+ "- Your bank, inventory and equipment, and notable loot\n"
				+ "- What you spend your time on, your world and location\n"
				+ "- Your messages to RS Buddy (answered by an AI model)\n"
				+ "- Your IP address, as with any website\n\n"
				+ "Never your password, other players' information or your chat with other players. "
				+ "You can delete your data from Settings at any time.", ChatComponents.MUTED);
		items.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(javax.swing.Box.createVerticalStrut(6));
		card.add(items);
		list.add(ChatComponents.place(card, Align.FILL, 14));

		JLabel privacy = new JLabel("<html><u>Privacy details</u></html>", SwingConstants.CENTER);
		privacy.setFont(FontManager.getRunescapeSmallFont());
		privacy.setForeground(ChatComponents.MUTED);
		privacy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		privacy.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(privacyUrl);
			}
		});
		list.add(ChatComponents.place(privacy, Align.FILL, 8));

		turnOn.addActionListener(e ->
		{
			turnOn.setEnabled(false);
			turnOn.setText("Turning on...");
			error.setText("");
			onTurnOn.accept(err ->
			{
				turnOn.setEnabled(true);
				turnOn.setText("Turn on RS Buddy");
				if (err != null)
				{
					error.setText("<html><div style='text-align:center'>" + MarkdownLite.escape(err) + "</div></html>");
				}
			});
		});
		list.add(ChatComponents.place(turnOn, Align.FILL, 14));
		error.setHorizontalAlignment(SwingConstants.CENTER);
		error.setFont(FontManager.getRunescapeSmallFont());
		error.setForeground(new Color(0xFF8A80));
		list.add(ChatComponents.place(error, Align.FILL, 6));
	}

	private static JTextArea paragraph(String text, Color color)
	{
		JTextArea t = new JTextArea(text)
		{
			@Override
			public java.awt.Dimension getPreferredSize()
			{
				java.awt.Container p = getParent();
				int w = p == null || p.getWidth() <= 0 ? 240 : p.getWidth() - (p.getInsets().left + p.getInsets().right);
				setSize(Math.max(100, w), Short.MAX_VALUE);
				return new java.awt.Dimension(w, super.getPreferredSize().height);
			}
		};
		t.setLineWrap(true);
		t.setWrapStyleWord(true);
		t.setEditable(false);
		t.setFocusable(false);
		t.setOpaque(false);
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setForeground(color);
		t.setBorder(null);
		return t;
	}
}
