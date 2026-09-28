package com.osrssync;

import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.HeightForWidth;
import com.osrssync.ChatComponents.MessageList;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
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
 * Shown until the player turns RS Buddy on: what it can do, a short note on what it sends to the RS Buddy server
 * (with the privacy page for the details), and Continue, which signs this install up and starts syncing.
 * Nothing is sent before that.
 */
class WelcomeView extends JPanel
{
	private static final Color ERROR = new Color(0xFF8A80);

	private final JLabel error = new JLabel();
	private final JButton next = new ChatComponents.AccentButton("Continue");

	/** {@code onTurnOn} gets a callback for errors (or null on success). */
	WelcomeView(String privacyUrl, Consumer<Consumer<String>> onTurnOn)
	{
		super(new BorderLayout());
		setOpaque(false);

		MessageList list = new MessageList(null, 0);
		list.setBorder(BorderFactory.createEmptyBorder(24, 8, 16, 8));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll);

		JLabel icon = new JLabel(new ImageIcon(BuddyIcon.create(40)), SwingConstants.CENTER);
		list.add(ChatComponents.place(icon, Align.FILL, 0));
		JLabel title = new JLabel("Welcome to RS Buddy", SwingConstants.CENTER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		list.add(ChatComponents.place(title, Align.FILL, 10));
		Wrapped intro = new Wrapped("An AI sidekick that knows your account.", ChatComponents.MUTED, true);
		list.add(ChatComponents.place(intro, Align.FILL, 4));

		list.add(ChatComponents.place(new Feature("progress", "Know what to do next",
			"Quests, diaries and skills, checked against your actual levels and account type."), Align.FILL, 22));
		list.add(ChatComponents.place(new Feature("sword", "Gear and DPS upgrades",
			"Realistic upgrades for mains, ironmen and hardcores, compared with a DPS calculator."), Align.FILL, 16));
		list.add(ChatComponents.place(new Feature("skull", "Boss setups",
			"Inventories for your next boss that you can export to Inventory Setups and Bank Tags."), Align.FILL, 16));
		list.add(ChatComponents.place(new Feature("chat-bubble", "Ask from the game",
			"Type ::buddy or press Ctrl+B to ask without leaving the chatbox."), Align.FILL, 16));

		// Bottom: the disclosure sits just above the button, like a system onboarding sheet
		MessageList bottom = new MessageList(null, 0);
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 14, 8));
		Wrapped disclosure = new Wrapped(
			"RS Buddy sends your character name, progress, bank, gear, location and messages to the RS Buddy server, "
				+ "where an AI model answers you. Never your password or other players' data. "
				+ "You can delete it all from Settings.", ChatComponents.MUTED, true);
		bottom.add(ChatComponents.place(disclosure, Align.FILL, 0));

		JLabel privacy = new JLabel("<html><u>What's sent and why</u></html>", SwingConstants.CENTER);
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
		bottom.add(ChatComponents.place(privacy, Align.FILL, 4));

		next.addActionListener(e ->
		{
			next.setEnabled(false);
			next.setText("Turning on...");
			error.setText("");
			onTurnOn.accept(err ->
			{
				next.setEnabled(true);
				next.setText("Continue");
				if (err != null)
				{
					error.setText("<html><div style='text-align:center'>" + MarkdownLite.escape(err) + "</div></html>");
				}
			});
		});
		bottom.add(ChatComponents.place(next, Align.FILL, 12));
		error.setHorizontalAlignment(SwingConstants.CENTER);
		error.setFont(FontManager.getRunescapeSmallFont());
		error.setForeground(ERROR);
		bottom.add(ChatComponents.place(error, Align.FILL, 6));
		add(bottom, BorderLayout.SOUTH);
	}

	/** A feature: accent icon on the left, a bold title and a wrapped description beside it. */
	private static class Feature extends JPanel implements HeightForWidth
	{
		private static final int ICON_COL = 28;
		private final JLabel icon;
		private final JLabel title;
		private final Wrapped body;

		Feature(String iconName, String titleText, String bodyText)
		{
			setOpaque(false);
			icon = new JLabel(SvgIcon.load(iconName, 16, ChatComponents.ACCENT));
			icon.setVerticalAlignment(SwingConstants.TOP);
			title = new JLabel(titleText);
			title.setFont(FontManager.getRunescapeBoldFont());
			title.setForeground(Color.WHITE);
			body = new Wrapped(bodyText, ChatComponents.MUTED, false);
			add(icon);
			add(title);
			add(body);
			setLayout(new LayoutManager()
			{
				@Override
				public void addLayoutComponent(String name, java.awt.Component comp)
				{
				}

				@Override
				public void removeLayoutComponent(java.awt.Component comp)
				{
				}

				@Override
				public Dimension preferredLayoutSize(Container parent)
				{
					return new Dimension(parent.getWidth(), heightForWidth(parent.getWidth()));
				}

				@Override
				public Dimension minimumLayoutSize(Container parent)
				{
					return new Dimension(0, 0);
				}

				@Override
				public void layoutContainer(Container parent)
				{
					int w = parent.getWidth();
					int th = title.getPreferredSize().height;
					icon.setBounds(0, 1, ICON_COL, 18);
					title.setBounds(ICON_COL, 0, w - ICON_COL, th);
					body.setBounds(ICON_COL, th + 2, w - ICON_COL, body.heightForWidth(w - ICON_COL));
				}
			});
		}

		@Override
		public int heightForWidth(int width)
		{
			return title.getPreferredSize().height + 2 + body.heightForWidth(Math.max(40, width - ICON_COL));
		}
	}

	/** Word-wrapped small text whose height follows the width it's given. */
	static class Wrapped extends JTextArea implements HeightForWidth
	{
		private final boolean centered;

		Wrapped(String text, Color color, boolean centered)
		{
			super(text);
			this.centered = centered;
			setLineWrap(true);
			setWrapStyleWord(true);
			setEditable(false);
			setFocusable(false);
			setOpaque(false);
			setFont(FontManager.getRunescapeSmallFont());
			setForeground(color);
			setBorder(null);
			setMargin(new Insets(0, 0, 0, 0));
		}

		@Override
		public int heightForWidth(int width)
		{
			if (centered)
			{
				return lines(width).length * getFontMetrics(getFont()).getHeight();
			}
			Dimension saved = getSize();
			setSize(Math.max(40, width), Short.MAX_VALUE);
			int h = super.getPreferredSize().height;
			setSize(saved);
			return h;
		}

		/** Greedy word wrap, used to centre each line (JTextArea can only left-align). */
		private String[] lines(int width)
		{
			java.awt.FontMetrics fm = getFontMetrics(getFont());
			java.util.List<String> out = new java.util.ArrayList<>();
			StringBuilder line = new StringBuilder();
			for (String word : getText().split(" "))
			{
				String tryLine = line.length() == 0 ? word : line + " " + word;
				if (line.length() > 0 && fm.stringWidth(tryLine) > width)
				{
					out.add(line.toString());
					line = new StringBuilder(word);
				}
				else
				{
					line = new StringBuilder(tryLine);
				}
			}
			if (line.length() > 0)
			{
				out.add(line.toString());
			}
			return out.toArray(new String[0]);
		}

		@Override
		protected void paintComponent(java.awt.Graphics g)
		{
			if (!centered)
			{
				super.paintComponent(g);
				return;
			}
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setFont(getFont());
			g2.setColor(getForeground());
			java.awt.FontMetrics fm = g2.getFontMetrics();
			int y = fm.getAscent();
			for (String l : lines(getWidth()))
			{
				g2.drawString(l, (getWidth() - fm.stringWidth(l)) / 2, y);
				y += fm.getHeight();
			}
			g2.dispose();
		}
	}
}
