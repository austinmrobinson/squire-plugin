package com.osrssync;

import static com.osrssync.Ui.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrssync.ActivityCharts.Part;
import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.Bubble;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Home: three summary cards that open the full pages. Progress (the account score), Activity (time played today)
 * and Chat (continue, situational starters, or ask anything).
 */
class HomeView extends JPanel
{
	/** What Home's cards do. */
	interface Actions
	{
		void openProgress();

		void openActivity();

		void openChat();

		/** Open a conversation from the history. */
		void openChat(String id);

		/** The list of all conversations. */
		void openChats();

		/** Open the chat and start a new conversation with this message. */
		void startChat(String message);

		/** Open the chat with a fresh, empty conversation. */
		void newChat();

		void openSettings();
	}

	private static final long REFRESH_AFTER_MS = 60_000;
	private static final String[] FALLBACK_PROMPTS = {
		"What should I work on next?", "What's my best gear for each style?", "Make me some money",
	};

	private final AccountApi api;
	private final ProgressView progress;
	private final Actions actions;
	private final ChatSessions sessions;
	/** Conversation starters for what the player is doing right now (see Scenarios); may be empty. */
	private final Supplier<List<Scenarios.Prompt>> livePrompts;
	private final MessageList list = new MessageList(null, 0);
	private JsonObject overview;
	private String overviewError;
	private JsonObject today;
	private java.awt.image.BufferedImage chathead;
	private long lastFetch;

	HomeView(AccountApi api, ProgressView progress, Actions actions, ChatSessions sessions, Supplier<List<Scenarios.Prompt>> livePrompts)
	{
		this.api = api;
		this.progress = progress;
		this.actions = actions;
		this.sessions = sessions;
		this.livePrompts = livePrompts;
		// Keep the chat card current (a chat finishing, a new one) while Home is on screen
		sessions.addListener(this::chatMaybeChanged);
		setOpaque(false);

		// Cards sit straight on the panel background; 1px in so their dark outline shows.
		// The bottom leaves room to scroll the last card clear of the floating composer.
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, FloatingAsk.CLEARANCE, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		// The sidebar floats its "Ask anything..." composer over the cards
		setLayout(new BorderLayout());
		add(scroll);
		render();
	}

	/** Called when Home is shown: redraw if the chat card changed meanwhile, and refetch if stale. */
	void onShown()
	{
		if (!chatCardKey().equals(renderedChatKey))
		{
			render();
		}
		if (System.currentTimeMillis() - lastFetch > REFRESH_AFTER_MS)
		{
			refresh();
		}
	}

	/** Refetch the overview (also feeding the Progress page) and today's activity. */
	void refresh()
	{
		lastFetch = System.currentTimeMillis();
		api.overview(result -> SwingUtilities.invokeLater(() ->
		{
			overview = result.json;
			overviewError = result.error;
			if (result.json != null)
			{
				progress.show(result.json);
			}
			else
			{
				lastFetch = 0;
				progress.showMessage(result.error);
			}
			render();
		}));
		api.activity("day", 0, result -> SwingUtilities.invokeLater(() ->
		{
			today = result.json;
			render();
		}));
	}

	/** For previews: show this data without fetching. */
	void show(JsonObject overview, JsonObject today)
	{
		this.overview = overview;
		this.today = today;
		render();
	}

	/** What the chat card shows besides the overview; Home only rebuilds when this changed. */
	private String chatCardKey()
	{
		StringBuilder key = new StringBuilder();
		List<ChatSessions.Chat> chats = sessions.chats();
		for (ChatSessions.Chat c : chats.subList(0, Math.min(RECENT_CHATS, chats.size())))
		{
			key.append(c.id).append(c.title).append(c.running()).append(c.unread).append('|');
		}
		for (Scenarios.Prompt p : livePrompts.get())
		{
			key.append(p.title).append('|');
		}
		return key.toString();
	}

	/** Rebuild the page if the chat card would change and Home is showing (sessions or live prompts changed). */
	void chatMaybeChanged()
	{
		if (isShowing() && !chatCardKey().equals(renderedChatKey))
		{
			render();
		}
	}

	private static final int RECENT_CHATS = 2;
	private static final int CHAT_CARD_ROWS = 4;

	private String renderedChatKey = "";

	private void render()
	{
		renderedChatKey = chatCardKey();
		list.removeAll();
		list.add(ChatComponents.place(profileCard(), Align.FILL, 0));
		list.add(ChatComponents.place(progressCard(), Align.FILL, 4));
		list.add(ChatComponents.place(activityCard(), Align.FILL, 4));
		list.add(ChatComponents.place(chatCard(), Align.FILL, 4));
		list.revalidate();
		list.repaint();
	}

	/** The player's chathead, drawn by the plugin from the game's models (null until logged in). */
	void setChathead(java.awt.image.BufferedImage image)
	{
		chathead = image;
		render();
	}

	// ---- Cards

	/** Who this is: chathead, name, account type, combat and total level, XP. */
	/** Who this is, in place of the title bar: chathead, name with account badge and combat level, centred; settings in the corner. */
	private JComponent profileCard()
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setOpaque(false);
		column.setBorder(BorderFactory.createEmptyBorder(32, 12, 16, 12));
		Portrait portrait = new Portrait(chathead);
		portrait.setAlignmentX(CENTER_ALIGNMENT);
		column.add(portrait);
		column.add(Box.createVerticalStrut(12));

		JLabel name;
		JLabel sub;
		if (overview == null)
		{
			name = bold(overviewError == null ? "Loading..." : "Your character");
			sub = text(overviewError == null ? " " : "Log in and sync to see your stats", ChatComponents.MUTED);
		}
		else
		{
			name = bold(str(overview, "name"));
			String type = str(overview, "accountType");
			ImageIcon badge = accountBadge(type);
			if (badge != null)
			{
				name.setIcon(badge);
				name.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
				name.setIconTextGap(8);
				name.setToolTipText(title(type.replace('_', ' ')));
			}
			sub = text("Level " + fmt(num(overview, "combatLevel")), ChatComponents.MUTED);
		}
		name.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
		name.setAlignmentX(CENTER_ALIGNMENT);
		sub.setAlignmentX(CENTER_ALIGNMENT);
		column.add(name);
		column.add(sub);

		// Settings in the top-right corner, where the title bar's button would be
		javax.swing.JButton settings = ChatComponents.iconButton("settings", "Settings");
		settings.addActionListener(e -> actions.openSettings());
		JPanel row = new JPanel(null)
		{
			@Override
			public Dimension getPreferredSize()
			{
				return column.getPreferredSize();
			}

			@Override
			public void doLayout()
			{
				column.setBounds(0, 0, getWidth(), getHeight());
				Dimension b = settings.getPreferredSize();
				settings.setBounds(getWidth() - b.width, 0, b.width, b.height);
			}
		};
		row.setOpaque(false);
		row.add(settings);
		row.add(column);
		return row;
	}

	/** The in-game account badge (RuneLite ships these with its Hiscore plugin); null for normal accounts. */
	private static ImageIcon accountBadge(String type)
	{
		String file = type.contains("hardcore") ? "hardcore_ironman.png" : type.contains("ultimate") ? "ultimate_ironman.png" : type.contains("ironman") ? "ironman.png" : null;
		if (file == null)
		{
			return null;
		}
		try (java.io.InputStream in = HomeView.class.getResourceAsStream("/net/runelite/client/plugins/hiscore/" + file))
		{
			return in == null ? null : new ImageIcon(javax.imageio.ImageIO.read(in));
		}
		catch (java.io.IOException e)
		{
			return null;
		}
	}

	private static JComponent stat(String value, String label)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.add(bold(value));
		p.add(small(label));
		return p;
	}

	/** The chathead in a sunken square, like the game's dialogue portrait; the Squire icon until it's ready. */
	private static final class Portrait extends JComponent
	{
		private static final int SIZE = 56;
		private final java.awt.image.BufferedImage image;

		Portrait(java.awt.image.BufferedImage image)
		{
			this.image = image;
			setPreferredSize(new Dimension(SIZE, SIZE));
			setMaximumSize(new Dimension(SIZE, SIZE));
			setToolTipText(image == null ? "Your chathead appears once you're logged in" : null);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			if (image != null)
			{
				g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				g2.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, SIZE, SIZE, 12, 12));
				g2.drawImage(image, 0, 0, SIZE, SIZE, null);
			}
			else
			{
				g2.setColor(ChatComponents.BASE_BG);
				g2.fillRoundRect(0, 0, SIZE, SIZE, 12, 12);
				g2.setColor(ChatComponents.OUTLINE);
				g2.drawRoundRect(0, 0, SIZE - 1, SIZE - 1, 12, 12);
				java.awt.image.BufferedImage icon = SquireIcon.create(28);
				g2.drawImage(icon, (SIZE - icon.getWidth()) / 2, (SIZE - icon.getHeight()) / 2, null);
			}
			g2.dispose();
		}
	}

	private JComponent progressCard()
	{
		Surface c = homeCard();
		c.setBorder(BorderFactory.createEmptyBorder(10, 12, 16, 12));
		JComponent head = header("Progress", null);
		c.add(head);
		c.add(Box.createVerticalStrut(16));
		JsonObject score = overview == null ? null : obj(overview, "score");
		if (score == null)
		{
			c.add(row(small(overview == null && overviewError == null ? "Loading..." : "No progress synced yet"), null));
			if (overviewError != null)
			{
				c.add(Box.createVerticalStrut(6));
				c.add(note(overviewError));
			}
			Ui.clickable(c, actions::openProgress);
			return c;
		}

		List<ScoreChart.Segment> segments = new ArrayList<>();
		for (JsonElement e : score.getAsJsonArray("parts"))
		{
			JsonObject part = e.getAsJsonObject();
			segments.add(new ScoreChart.Segment(num(part, "points"), ScoreChart.partColor(str(part, "key"))));
		}
		List<Integer> ticks = new ArrayList<>();
		for (JsonElement e : score.getAsJsonArray("checkpoints"))
		{
			int at = (int) num(e.getAsJsonObject(), "at");
			if (at > 0)
			{
				ticks.add(at);
			}
		}
		double value = num(score, "score");
		JsonObject tier = obj(score, "tier");
		String tierName = tier != null ? str(tier, "name") : str(score, "stage");

		ScoreChart.Donut donut = new ScoreChart.Donut(72, value, segments, ticks);
		if (tier != null)
		{
			donut.withCrest((int) num(tier, "itemId"), tierColor(tier));
		}
		donut.setToolTipText("Account score " + oneDecimal(value) + " / 100");

		JPanel side = new JPanel();
		side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
		side.setOpaque(false);
		JLabel stage = bold(tierName);
		stage.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		side.add(row(stage, null));
		JsonObject next = obj(score, "next");
		side.add(row(text(next == null ? "Top tier reached" : oneDecimal(num(next, "pointsToGo")) + " pts to " + str(next, "name"), ChatComponents.MUTED), null));

		JPanel hero = new JPanel(new BorderLayout(16, 0));
		hero.setOpaque(false);
		hero.add(donut, BorderLayout.WEST);
		JPanel centred = new JPanel(new GridBagLayout());
		centred.setOpaque(false);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		centred.add(side, gbc);
		hero.add(centred, BorderLayout.CENTER);
		hero.setAlignmentX(LEFT_ALIGNMENT);
		hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, hero.getPreferredSize().height));
		c.add(hero);
		Ui.clickable(c, actions::openProgress);
		return c;
	}

	static Color tierColor(JsonObject tier)
	{
		try
		{
			return Color.decode(str(tier, "color"));
		}
		catch (RuntimeException e)
		{
			return ChatComponents.ACCENT;
		}
	}

	private JComponent activityCard()
	{
		Surface c = listCard();
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		top.add(header("Activity", null));
		top.add(Box.createVerticalStrut(10));

		double total = today == null ? 0 : num(today, "totalMinutes");
		double xpToday = today == null ? 0 : num(today, "totalXp");
		JLabel big = bold(duration(total));
		big.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		JLabel xpLabel = null;
		if (xpToday > 0)
		{
			xpLabel = bold("+" + shortNumber(xpToday) + " XP");
			xpLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
			xpLabel.setForeground(ChatComponents.ACCENT);
		}
		top.add(row(big, xpLabel));
		top.add(row(text(today == null ? "Loading..." : total > 0 ? "played today" : "Nothing tracked yet today", ChatComponents.MUTED),
			xpToday > 0 ? text("gained today", ChatComponents.MUTED) : null));

		List<Part> parts = new ArrayList<>();
		if (today != null && total > 0)
		{
			JsonArray acts = today.getAsJsonArray("activities");
			for (int i = 0; i < acts.size(); i++)
			{
				JsonObject a = acts.get(i).getAsJsonObject();
				parts.add(new Part(str(a, "name"), num(a, "minutes"), Ui.activityColor(i, str(a, "name"))));
			}
			top.add(Box.createVerticalStrut(10));
			ActivityCharts.ShareBar bar = new ActivityCharts.ShareBar(parts);
			bar.setAlignmentX(LEFT_ALIGNMENT);
			top.add(bar);
		}
		clickableRow(top, actions::openActivity);
		top.setAlignmentX(LEFT_ALIGNMENT);
		c.add(top);

		for (int i = 0; i < Math.min(3, parts.size()); i++)
		{
			Part p = parts.get(i);
			if (i > 0)
			{
				c.add(divider());
			}
			c.add(listRow(dotIcon(p.color), p.name, text(duration(p.value), Color.WHITE), actions::openActivity));
		}
		c.add(divider());
		c.add(listRow(SvgIcon.load("more", 16, null), "See more", null, actions::openActivity));
		return c;
	}

	private JComponent chatCard()
	{
		Surface c = listCard();
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
		top.add(header("Chat", null), BorderLayout.CENTER);
		clickableRow(top, actions::openChats);
		top.setAlignmentX(LEFT_ALIGNMENT);
		top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
		c.add(top);

		javax.swing.Icon bubble = SvgIcon.load("chat-bubble", 16, null);
		int shown = 0;
		// Recent conversations first, with what they're doing
		List<ChatSessions.Chat> chats = sessions.chats();
		for (ChatSessions.Chat chat : chats.subList(0, Math.min(RECENT_CHATS, chats.size())))
		{
			JComponent status = chat.running() ? ChatTraceViews.spinner(ChatComponents.ACCENT)
				: chat.unread ? text("New", ChatComponents.ACCENT) : null;
			if (shown > 0)
			{
				c.add(divider());
			}
			c.add(listRow(bubble, chat.title, status, () -> actions.openChat(chat.id)));
			shown++;
		}

		// Then conversation starters: what they're doing right now, then ideas from their account
		List<String[]> prompts = new ArrayList<>();
		for (Scenarios.Prompt p : livePrompts.get())
		{
			prompts.add(new String[]{p.title, p.prompt});
		}
		JsonArray fromServer = overview == null ? null : overview.getAsJsonArray("chatPrompts");
		if (fromServer != null)
		{
			for (JsonElement e : fromServer)
			{
				JsonObject p = e.getAsJsonObject();
				prompts.add(new String[]{str(p, "title"), str(p, "prompt")});
			}
		}
		if (prompts.isEmpty())
		{
			for (String p : FALLBACK_PROMPTS)
			{
				prompts.add(new String[]{p, p});
			}
		}
		javax.swing.Icon spark = SvgIcon.load("new-chat", 16, null);
		for (String[] p : prompts)
		{
			if (shown >= CHAT_CARD_ROWS)
			{
				break;
			}
			if (shown > 0)
			{
				c.add(divider());
			}
			c.add(listRow(spark, p[0], null, () -> actions.startChat(p[1])));
			shown++;
		}
		c.add(divider());
		c.add(listRow(SvgIcon.load("chats", 16, null), chats.isEmpty() ? "New chat" : "All chats", null,
			chats.isEmpty() ? actions::newChat : actions::openChats));
		return c;
	}

	/** A Home card whose rows run edge to edge (Activity, Chat). */
	static Surface listCard()
	{
		Surface c = new Surface(ChatComponents.CARD_BG, 8, true);
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
		return c;
	}

	/** A full-width 32px row: icon, text, optional value on the right; highlights under the pointer. */
	static JComponent listRow(javax.swing.Icon icon, String text, JComponent right, Runnable onClick)
	{
		Surface r = new Surface(null, 0, false);
		r.setLayout(new BorderLayout(8, 0));
		r.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 12));
		JLabel label = text(text, ColorScheme.LIGHT_GRAY_COLOR);
		label.setIcon(icon);
		label.setIconTextGap(8);
		label.setToolTipText(text);
		r.add(label, BorderLayout.CENTER);
		if (right != null)
		{
			r.add(right, BorderLayout.EAST);
		}
		r.setPreferredSize(new Dimension(100, 32));
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		r.setAlignmentX(LEFT_ALIGNMENT);
		if (onClick == null)
		{
			return r;
		}
		r.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter m = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				r.setFill(ChatComponents.HOVER_BG);
				label.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!r.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), r)))
				{
					r.setFill(null);
					label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				}
			}
		};
		r.addMouseListener(m);
		label.addMouseListener(m);
		if (right != null)
		{
			right.addMouseListener(m);
		}
		return r;
	}

	/** 1px line between rows, in the raised-surface colour. */
	static JComponent divider()
	{
		JComponent d = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(ChatComponents.PANEL_BG);
				g.fillRect(0, 0, getWidth(), 1);
			}
		};
		d.setPreferredSize(new Dimension(10, 1));
		d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
		d.setAlignmentX(LEFT_ALIGNMENT);
		return d;
	}

	/** A 16px icon with a rounded 8px colour square in the middle (an activity's colour). */
	static javax.swing.Icon dotIcon(Color color)
	{
		return new javax.swing.Icon()
		{
			@Override
			public void paintIcon(java.awt.Component comp, Graphics g, int x, int y)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.fillRoundRect(x + 4, y + 4, 8, 8, 4, 4);
				g2.dispose();
			}

			@Override
			public int getIconWidth()
			{
				return 16;
			}

			@Override
			public int getIconHeight()
			{
				return 16;
			}
		};
	}

	// ---- Pieces

	/** A Home card: the conversation-card colour, rounded, with the framed edge. */
	static Surface homeCard()
	{
		Surface c = new Surface(ChatComponents.CARD_BG, 8, true);
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		return c;
	}

	/** Card title on the left; optional muted note and a chevron on the right. */
	static JComponent header(String title, String note)
	{
		JLabel t = bold(title);
		JLabel chevron = new JLabel(SvgIcon.load("chevron-right", 16, null));
		JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setOpaque(false);
		if (note != null)
		{
			right.add(small(note), BorderLayout.CENTER);
		}
		right.add(chevron, BorderLayout.EAST);
		return row(t, right);
	}

	private static void clickableRow(JComponent row, Runnable action)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter m = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}
		};
		row.addMouseListener(m);
		for (java.awt.Component child : row.getComponents())
		{
			child.addMouseListener(m);
		}
	}

	/** A borderless row (e.g. "New chat") that highlights under the pointer. */
	private static JComponent plainRow(String text, Runnable onClick)
	{
		Surface s = new Surface(null, 4, false);
		s.setLayout(new BorderLayout());
		s.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 8));
		JLabel l = text(text, ColorScheme.LIGHT_GRAY_COLOR);
		s.add(l, BorderLayout.CENTER);
		s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter m = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				s.setFill(ChatComponents.HOVER_BG);
				l.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!s.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), s)))
				{
					s.setFill(null);
					l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				}
			}
		};
		s.addMouseListener(m);
		l.addMouseListener(m);
		s.setAlignmentX(LEFT_ALIGNMENT);
		s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
		return s;
	}

	/** A sunken, full-width conversation starter. */
	private static JComponent starter(String text, Runnable onClick, boolean emphasised)
	{
		Surface s = new Surface(ChatComponents.CARD_BG, 4, true);
		s.setLayout(new BorderLayout(8, 0));
		s.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 8));
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeFont());
		Color rest = emphasised ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR;
		l.setForeground(rest);
		l.setToolTipText(text);
		s.add(l, BorderLayout.CENTER);
		s.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		MouseAdapter m = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				s.setFill(ChatComponents.HOVER_BG);
				l.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!s.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), s)))
				{
					s.setFill(ChatComponents.CARD_BG);
					l.setForeground(rest);
				}
			}
		};
		s.addMouseListener(m);
		l.addMouseListener(m);
		s.setAlignmentX(LEFT_ALIGNMENT);
		s.setMaximumSize(new Dimension(Integer.MAX_VALUE, s.getPreferredSize().height));
		return s;
	}

	private static JComponent note(String markdown)
	{
		Bubble b = new Bubble(null, ChatComponents.MUTED, false, false, new java.awt.Insets(0, 0, 0, 0));
		b.setHtml(MarkdownLite.toHtml(markdown));
		JPanel wrap = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getPreferredSize()
			{
				int w = getParent() == null ? 200 : getParent().getWidth() - 24;
				return new Dimension(w, b.heightForWidth(Math.max(40, w)));
			}
		};
		wrap.setOpaque(false);
		wrap.add(b, BorderLayout.CENTER);
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		return wrap;
	}
}
