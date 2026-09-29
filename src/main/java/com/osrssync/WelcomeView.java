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
 * Shown until the player turns Squire on: what it can do, a short note on what it sends to the Squire server
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

		// Left-aligned and quiet, like the website: the helm, a title, then the four things Squire does as numbered cards
		JLabel icon = new JLabel(new ImageIcon(SquireIcon.create(40)), SwingConstants.LEFT);
		list.add(ChatComponents.place(icon, Align.FILL, 0));
		JLabel title = new JLabel("Meet Squire", SwingConstants.LEFT);
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		title.setForeground(Color.WHITE);
		list.add(ChatComponents.place(title, Align.FILL, 12));
		Wrapped intro = new Wrapped("A squire for your account. It knows your stats, bank, quests and gear.", ChatComponents.MUTED, false);
		list.add(ChatComponents.place(intro, Align.FILL, 4));

		list.add(ChatComponents.place(new Feature("01", "Know", "It knows your account",
			"Answers from your own stats, bank and quests, checked against the wiki and a DPS calculator."), Align.FILL, 18));
		list.add(ChatComponents.place(new Feature("02", "Plan", "A plan that keeps itself",
			"Checkpoints like Barrows gloves or a fire cape that tick off as you play."), Align.FILL, 6));
		list.add(ChatComponents.place(new Feature("03", "Gear", "Gear you can act on",
			"Swap a slot, see the DPS change, and copy the trip to Inventory Setups."), Align.FILL, 6));
		list.add(ChatComponents.place(new Feature("04", "Improve", "Reviews of your runs",
			"Ask it to watch your next run, play, and get what to change after."), Align.FILL, 6));
		Wrapped where = new Wrapped("Ask here, from the Squire chat tab, or with ::squire in the chatbox.", ChatComponents.MUTED, false);
		list.add(ChatComponents.place(where, Align.FILL, 14));

		// Bottom: the disclosure sits just above the button, like a system onboarding sheet
		MessageList bottom = new MessageList(null, 0);
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 14, 8));
		Wrapped disclosure = new Wrapped(
			"Squire sends your character name, progress, bank, gear, location and messages to the Squire server, "
				+ "where an AI model answers you. Never your password or other players' data. "
				+ "You can delete it all from Settings.", ChatComponents.MUTED, false);
		bottom.add(ChatComponents.place(disclosure, Align.FILL, 0));

		JLabel privacy = new JLabel("<html><u>What's sent and why</u></html>", SwingConstants.LEFT);
		privacy.setFont(FontManager.getRunescapeSmallFont());
		privacy.setForeground(ChatComponents.MUTED);
		privacy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		privacy.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
				if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
				{
					return;
				}
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

	/** A numbered card: "01 · KNOW" in Squire blue, a bold title and a wrapped description. */
	private static class Feature extends ChatComponents.Surface implements HeightForWidth
	{
		private static final int PAD_X = 10, PAD_Y = 8;
		private static final Color LABEL = new Color(0x8E98FF);
		private final JLabel label;
		private final JLabel title;
		private final Wrapped body;

		Feature(String number, String tag, String titleText, String bodyText)
		{
			super(ChatComponents.CARD_BG, 6, true);
			label = new JLabel(number + "  " + tag.toUpperCase());
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(LABEL);
			title = new JLabel(titleText);
			title.setFont(FontManager.getRunescapeBoldFont());
			title.setForeground(Color.WHITE);
			body = new Wrapped(bodyText, ChatComponents.MUTED, false);
			add(label);
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
					int w = parent.getWidth() - PAD_X * 2;
					int y = PAD_Y;
					int lh = label.getPreferredSize().height;
					label.setBounds(PAD_X, y, w, lh);
					y += lh + 2;
					int th = title.getPreferredSize().height;
					title.setBounds(PAD_X, y, w, th);
					y += th + 1;
					body.setBounds(PAD_X, y, w, body.heightForWidth(w));
				}
			});
		}

		@Override
		public int heightForWidth(int width)
		{
			return PAD_Y * 2 + label.getPreferredSize().height + 2 + title.getPreferredSize().height + 1
				+ body.heightForWidth(Math.max(40, width - PAD_X * 2));
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
