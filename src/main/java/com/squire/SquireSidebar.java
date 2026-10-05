package com.squire;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's single sidebar entry. Now, Activity, Account, a chat, all chats and Settings are top-level: the menu
 * button beside every composer goes to any of them (and the recent chats). The pages under them (Gained, a progress
 * detail, a boss, the plan, the profile) get an iOS-style bar with back to the page above. The "Ask anything..."
 * composer is docked along the bottom of every page but the chats, and settles into the chat's composer when used.
 * Drag the left edge to resize.
 */
class SquireSidebar extends PluginPanel
{
	private enum Page
	{
		HOME("Now"), PROGRESS("Account"), ACTIVITY("Activity"), CHATS("Chats"), CHAT("Chat"), SETTINGS("Settings"), CONNECT("Connect an AI app"), SYNC("What's synced"), PLAN("Plan"), GAINED("Gained"),
		PROGRESS_DETAIL("Progress"), BOSS("Boss"), WELCOME("Squire"), SETUP("Set up"), PROFILE("Profile");

		final String title;

		Page(String title)
		{
			this.title = title;
		}

		/** The page one level up. */
		Page parent()
		{
			switch (this)
			{
				// Top-level: reached from the menu, not a stack
				case HOME:
				case WELCOME:
				case PROGRESS:
				case ACTIVITY:
				case CHAT:
				case CHATS:
				case SETTINGS:
					return null;
				case GAINED:
					return ACTIVITY;
				case CONNECT:
				case SYNC:
					return SETTINGS;
				case PROGRESS_DETAIL:
					return PROGRESS;
				case BOSS:
					return PROGRESS_DETAIL;
				default:
					return HOME;
			}
		}
	}

	static final int DEFAULT_WIDTH = 292; // 30% wider than RuneLite's standard 225
	static final int MIN_WIDTH = PANEL_WIDTH;
	static final int MAX_WIDTH = 600;
	private static final int GRIP = 6;

	private final ChatSessions sessions;
	/** Holds whichever conversation is on screen. */
	private final CardLayout chatCards = new CardLayout();
	private final JPanel chatHolder = new JPanel(chatCards);
	private final ChatHistoryView history;
	private final HomeView home;
	private final ActivityView activity;
	private final GainedView gained;
	private final ProgressDetailView progressDetail;
	private final BossView boss;
	private final SettingsView settings;
	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	private final NavBar nav = new NavBar();
	private final FloatingAsk ask;
	private final JButton newChatButton = ChatComponents.iconButton("new-chat", "New chat");
	private final JButton chatMenuButton = ChatComponents.iconButton("more", "More");
	private Page page;
	private int panelWidth;
	private final IntConsumer onWidthChosen;
	private boolean gripActive;

	/**
	 * Home is built from a factory because its cards navigate within this sidebar.
	 */
	SquireSidebar(Function<HomeView.Actions, HomeView> homeFactory, ProgressView progress, ActivityView activity, ChatSessions sessions,
		SettingsView settings, int initialWidth, IntConsumer onWidthChosen)
	{
		super(false);
		this.sessions = sessions;
		this.activity = activity;
		this.gained = new GainedView(activity.api());
		// A skill or boss tapped on the Activity page opens it on the Gained page
		ActivityView.openGained = m ->
		{
			gained.setMetric(m);
			show(Page.GAINED);
		};
		this.settings = settings;
		this.progressView = progress;
		// Progress cards open a detail page; a boss (from the kill counts) opens its own page under "Kill counts"
		this.boss = new BossView(activity.api(), progress.images());
		this.progressDetail = new ProgressDetailView(activity.api(), progress, this::openBoss);
		progress.setNavigation((view, title) ->
		{
			progressDetail.show(view, title);
			show(Page.PROGRESS_DETAIL);
		}, this::openBoss);
		this.home = homeFactory.apply(new HomeView.Actions()
		{
			@Override
			public void openProgress()
			{
				show(Page.PROGRESS);
			}

			@Override
			public void openActivity()
			{
				show(Page.ACTIVITY);
			}

			@Override
			public void openGained()
			{
				show(Page.GAINED);
			}

			@Override
			public void openChat()
			{
				show(Page.CHAT);
			}

			@Override
			public void openChat(String id)
			{
				sessions.open(id);
				show(Page.CHAT);
			}

			@Override
			public void openChats()
			{
				show(Page.CHATS);
			}

			@Override
			public void startChat(String message)
			{
				sessions.startWith(message);
				show(Page.CHAT);
			}

			@Override
			public void newChat()
			{
				sessions.newChat();
				show(Page.CHAT);
			}

			@Override
			public void openSettings()
			{
				show(Page.SETTINGS);
			}

			@Override
			public void openPlan()
			{
				show(Page.PLAN);
			}

			@Override
			public void openSetup()
			{
				showSetup();
			}

			@Override
			public void openProfile()
			{
				if (profile != null)
				{
					show(Page.PROFILE);
				}
			}
		});
		// The menu beside every composer
		Ui.openMenu = this::showNavMenu;
		// "Ask Squire" lines on the Progress and Activity cards start a chat with their question
		Ui.askSquire = message ->
		{
			sessions.startWith(message);
			show(Page.CHAT);
		};
		this.panelWidth = clamp(initialWidth);
		this.onWidthChosen = onWidthChosen;

		setLayout(new BorderLayout(0, 4));
		// A 1px line along the top in the same colour as RuneLite's line between the panel and the sidebar tabs
		java.awt.Color edge = javax.swing.UIManager.getColor("TabbedPane.contentAreaColor");
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, edge != null ? edge : net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 4, 4, 4)));
		setBackground(ChatComponents.BASE_BG);
		installResizeGrip();

		newChatButton.addActionListener(e ->
		{
			sessions.newChat();
			show(Page.CHAT);
		});
		chatMenuButton.addActionListener(e -> showChatMenu());
		// The nav bar, and under it the "Observing" bar while Squire observes a session for review
		north.setOpaque(false);
		north.add(nav, BorderLayout.NORTH);
		add(north, BorderLayout.NORTH);

		body.setOpaque(false);
		body.add(home, Page.HOME.name());
		body.add(progress, Page.PROGRESS.name());
		body.add(home.planView(), Page.PLAN.name());
		body.add(activity, Page.ACTIVITY.name());
		body.add(gained, Page.GAINED.name());
		body.add(progressDetail, Page.PROGRESS_DETAIL.name());
		body.add(boss, Page.BOSS.name());
		chatHolder.setOpaque(false);
		chatHolder.add(sessions.current(), sessions.current().id());
		body.add(chatHolder, Page.CHAT.name());
		history = new ChatHistoryView(sessions, id ->
		{
			sessions.open(id);
			show(Page.CHAT);
		}, () ->
		{
			sessions.newChat();
			show(Page.CHAT);
		});
		body.add(history, Page.CHATS.name());
		body.add(settings, Page.SETTINGS.name());

		// The floating composer over the pages; it's hidden on the chat pages
		ask = new FloatingAsk(this::askFromFloating, () -> show(Page.CHAT));
		JPanel stage = new JPanel();
		stage.setOpaque(false);
		stage.setLayout(new FloatingAsk.Overlay(body, ask));
		stage.add(ask); // added first so it paints on top
		stage.add(body);
		add(stage, BorderLayout.CENTER);

		// Loaded chats stay mounted and the holder flips between them: removing a long chat's components
		// from the window is what made switching slow
		sessions.setOnCurrent(view ->
		{
			if (view.getParent() != chatHolder)
			{
				chatHolder.add(view, view.id());
			}
			chatCards.show(chatHolder, view.id());
			view.focusInput();
		});
		sessions.setOnUnloaded(view ->
		{
			if (view.getParent() == chatHolder)
			{
				chatHolder.remove(view);
			}
		});
		sessions.addListener(() ->
		{
			if (page == Page.CHAT)
			{
				nav.setTitle(sessions.current().title());
			}
		});
		show(Page.HOME);
	}

	/**
	 * Before the player turns Squire on, only the Welcome page shows (nothing is sent until then); after, Home.
	 */
	void setTurnedOn(boolean on, JComponent welcome)
	{
		if (welcome != null && welcome.getParent() != body)
		{
			body.add(welcome, Page.WELCOME.name());
		}
		show(on ? Page.HOME : Page.WELCOME);
	}

	private final RecordingBar recordingBar = new RecordingBar();
	private ProgressView progressView;
	/** The nav bar, plus the Observing bar only while a session is observed (it's added and removed, never hidden). */
	private final JPanel north = new JPanel(new BorderLayout(0, 4));

	/** Show (label non-null) or hide the Observing bar; {@code onStop} runs when the player presses Stop. */
	void setRecording(String label, long startedAt, Runnable onStop)
	{
		recordingBar.set(label, startedAt, onStop);
		if (label != null && recordingBar.getParent() != north)
		{
			north.add(recordingBar, BorderLayout.SOUTH);
		}
		else if (label == null && recordingBar.getParent() == north)
		{
			north.remove(recordingBar);
		}
		revalidate();
		repaint();
	}

	/** A red dot, what's being recorded, the time so far, and Stop. Nothing is shown in the game itself. */
	private static final class RecordingBar extends ChatComponents.Surface
	{
		private final JLabel text = new JLabel();
		private final javax.swing.Timer timer;
		private String label;
		private long startedAt;
		private Runnable onStop = () -> {};

		RecordingBar()
		{
			super(ChatComponents.PANEL_BG, 4, true);
			setLayout(new BorderLayout(8, 0));
			setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
			text.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
			text.setForeground(java.awt.Color.WHITE);
			text.setIcon(new javax.swing.Icon()
			{
				@Override
				public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y)
				{
					g.setColor(new java.awt.Color(0xE5534B));
					Pixel.fill((java.awt.Graphics2D) g, x, y + 1, 8, 8, 2);
				}

				@Override
				public int getIconWidth()
				{
					return 8;
				}

				@Override
				public int getIconHeight()
				{
					return 10;
				}
			});
			text.setIconTextGap(8);
			add(text, BorderLayout.CENTER);
			JLabel stop = PlanView.link("Stop");
			stop.setForeground(java.awt.Color.WHITE);
			stop.addMouseListener(PlanView.click(() -> onStop.run()));
			add(stop, BorderLayout.EAST);
			setToolTipText("Squire is observing this to review it afterwards. Nothing is shown during play.");
			timer = new javax.swing.Timer(1000, e -> update());
		}

		void set(String label, long startedAt, Runnable onStop)
		{
			this.label = label;
			this.startedAt = startedAt;
			this.onStop = onStop == null ? () -> {} : onStop;
			setVisible(label != null);
			if (label != null)
			{
				update();
				timer.start();
			}
			else
			{
				timer.stop();
			}
		}

		private void update()
		{
			long s = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
			text.setText("Observing " + label + " · " + String.format("%d:%02d", s / 60, s % 60));
		}
	}

	private ConnectView connect;
	private SyncView sync;

	/** The What's synced page (opened from Settings). */
	void setSyncView(SyncView view)
	{
		sync = view;
		body.add(view, Page.SYNC.name());
	}

	private SetupView setup;
	private ProfileView profile;

	/** The Profile page (from Home's portrait). */
	void setProfileView(ProfileView view)
	{
		profile = view;
		body.add(view, Page.PROFILE.name());
	}

	/** The Set up page, shown after Continue and reachable from Home until it's done. */
	void setSetupView(SetupView view)
	{
		setup = view;
		body.add(view, Page.SETUP.name());
	}

	void showSetup()
	{
		if (setup != null)
		{
			show(Page.SETUP);
		}
	}

	void showHome()
	{
		show(Page.HOME);
	}

	/** The Connect an AI app page (opened from Settings). */
	void setConnectView(ConnectView view)
	{
		connect = view;
		body.add(view, Page.CONNECT.name());
	}

	/** Go to a page. Home has no bar (its profile row stands in); the rest get back, title and actions. */
	private void show(Page next)
	{
		if (page == Page.CONNECT && next != Page.CONNECT && connect != null)
		{
			connect.onHidden();
		}
		if (page == Page.SETUP && next != Page.SETUP && setup != null)
		{
			setup.onHidden();
		}
		page = next;
		cards.show(body, next.name());
		nav.setVisible(next != Page.HOME && next != Page.WELCOME);
		List<NavBar.Crumb> ancestors = new ArrayList<>();
		for (Page p = next.parent(); p != null; p = p.parent())
		{
			Page target = p;
			ancestors.add(new NavBar.Crumb(p.title, () -> show(target)));
		}
		List<JButton> actions = new ArrayList<>();
		if (next == Page.CHAT)
		{
			actions.add(newChatButton);
			actions.add(chatMenuButton);
		}
		else if (next == Page.CHATS)
		{
			actions.add(newChatButton);
		}
		nav.set(titleOf(next), ancestors, actions);
		ask.setPlaceholder(placeholderFor(next));
		ask.setVisible(next != Page.CHAT && next != Page.CHATS && next != Page.WELCOME && next != Page.CONNECT && next != Page.SYNC && next != Page.SETUP && next != Page.PROFILE);

		if (next == Page.CHAT)
		{
			sessions.current().refreshModelLabel();
			sessions.current().focusInput();
		}
		else if (next == Page.CHATS)
		{
			history.onShown();
		}
		else if (next == Page.HOME)
		{
			home.onShown();
		}
		else if (next == Page.PLAN)
		{
			home.planView().refresh();
		}
		else if (next == Page.ACTIVITY)
		{
			activity.refresh();
		}
		else if (next == Page.GAINED)
		{
			gained.refresh();
		}
		else if (next == Page.SETTINGS)
		{
			settings.onShown();
		}
		else if (next == Page.SYNC && sync != null)
		{
			sync.onShown();
		}
		else if (next == Page.CONNECT && connect != null)
		{
			connect.onShown();
		}
		else if (next == Page.SETUP && setup != null)
		{
			setup.onShown();
		}
		else if (next == Page.PROFILE && profile != null)
		{
			profile.onShown();
		}
		revalidate();
		repaint();
	}

	/** The floating composer's hint says what it will use: this plan, your progress, your playtime. */
	private String placeholderFor(Page p)
	{
		switch (p)
		{
			case PLAN:
				return home.planView().plan() != null ? "Ask about or change this plan..." : "Ask Squire to make a plan...";
			case PROGRESS:
				return "Ask about your progress...";
			case ACTIVITY:
				return "Ask about your playtime...";
			case GAINED:
				return "Ask about your gains...";
			case PROGRESS_DETAIL:
				return "For fun".equals(progressDetail.pageTitle()) ? "Ask about how you play..." : "Ask about your " + progressDetail.pageTitle().toLowerCase() + "...";
			case BOSS:
				return "Ask about " + boss.pageTitle() + "...";
			case HOME:
				return home.placeholder();
			default:
				return "Ask anything...";
		}
	}

	/** What a question from the floating composer carries from the page it was asked on. */
	private java.util.List<Attachment> contextFor(Page p)
	{
		Attachment a = p == Page.PLAN ? home.planView().contextAttachment() : p == Page.PROGRESS ? progressView.contextAttachment()
			: p == Page.BOSS ? boss.contextAttachment() : null;
		return a == null ? java.util.List.of() : java.util.List.of(a);
	}

	private String titleOf(Page p)
	{
		switch (p)
		{
			case CHAT:
				return sessions.current().title();
			case PROGRESS_DETAIL:
				return progressDetail.pageTitle();
			case BOSS:
				return boss.pageTitle();
			default:
				return p.title;
		}
	}

	/** A boss's page; back goes to every kill count. */
	private void openBoss(String name)
	{
		if (!"kill-counts".equals(progressDetail.view()))
		{
			progressDetail.show("kill-counts", "Kill counts");
		}
		boss.show(name);
		show(Page.BOSS);
	}

	/**
	 * Send from the floating composer: start a new chat, switch to it, and let the floating box glide down into the
	 * chat's composer.
	 */
	private void askFromFloating(String text)
	{
		Rectangle from = ask.isDisplayable() && ask.isVisible() ? ask.boxBoundsIn(this) : null;
		ask.clear();
		// The page's context (the plan, your progress) goes along as a tile on the message
		sessions.startWith(text, contextFor(page));
		show(Page.CHAT);
		validate();
		ChatView chat = sessions.current();
		Rectangle to = chat.isDisplayable() ? chat.composerBoundsIn(this) : null;
		chat.setComposerHidden(true);
		ComposerTransition.play(this, from, to, text, () ->
		{
			chat.setComposerHidden(false);
			chat.focusInput();
		});
	}

	/** The floating composer (previews). */
	FloatingAsk floatingAsk()
	{
		return ask;
	}

	private static final int MENU_CHATS = 3;

	/**
	 * The menu beside the composer, opening upward: Now, Activity and Account; the recent chats, a new chat and all
	 * chats; Settings. The page on screen is bold in the accent colour.
	 */
	private void showNavMenu(java.awt.Component anchor)
	{
		// The panel's width less a margin each side, opening upward from just above the button
		JPopupMenu menu = navMenu(getWidth() - 2 * MENU_MARGIN);
		java.awt.Point at = javax.swing.SwingUtilities.convertPoint(anchor, 0, 0, this);
		menu.show(this, MENU_MARGIN, at.y - menu.getPreferredSize().height - 4);
	}

	private static final int MENU_MARGIN = 4;
	private static final int MENU_RADIUS = 8;

	/** The menu itself, styled to match the panel rather than the look and feel (also for previews). */
	JPopupMenu navMenu(int width)
	{
		// A card like the rest of the panel: dark outline, stepped pixel corners, lit from the top left
		JPopupMenu menu = new JPopupMenu()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				int w = getWidth(), h = getHeight(), r = MENU_RADIUS;
				g2.setColor(ChatComponents.OUTLINE);
				Pixel.fill(g2, 0, 0, w, h, r + 1);
				g2.setColor(ChatComponents.CARD_BG);
				Pixel.fill(g2, 1, 1, w - 2, h - 2, r);
				Pixel.bevel(g2, 1, 1, w - 2, h - 2, r, ChatComponents.CARD_LIGHT, ChatComponents.CARD_DARK);
				g2.dispose();
			}
		};
		menu.setOpaque(false);
		menu.setBackground(new java.awt.Color(0, 0, 0, 0));
		menu.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
		// Every item has its icon; the page on screen has it in the accent colour, like its text
		java.util.function.BiFunction<String, Boolean, JMenuItem> item = (label, current) ->
		{
			JMenuItem i = new JMenuItem(label);
			i.setIconTextGap(8);
			i.setFont(current ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
			i.setForeground(current ? ChatComponents.ACCENT.brighter() : net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR);
			i.setBackground(ChatComponents.HOVER_BG);
			i.setOpaque(false);
			i.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 16));
			// Only the hovered row is filled, in the panel's own hover colour; the card shows through the rest
			i.getModel().addChangeListener(e ->
			{
				i.setOpaque(i.isArmed());
				i.repaint();
			});
			menu.add(i);
			return i;
		};
		Runnable separator = () ->
		{
			javax.swing.JComponent line = new javax.swing.JComponent()
			{
				@Override
				protected void paintComponent(java.awt.Graphics g)
				{
					g.setColor(ChatComponents.BORDER);
					g.fillRect(8, 4, getWidth() - 16, 1);
				}
			};
			line.setPreferredSize(new Dimension(10, 9));
			menu.add(line);
		};
		java.util.function.BiConsumer<JMenuItem, String> icon = (i, name) ->
			i.setIcon(SvgIcon.load(name, 16, i.getFont() == FontManager.getRunescapeBoldFont() ? ChatComponents.ACCENT.brighter() : null));
		JMenuItem nowItem = item.apply("Now", page == Page.HOME);
		icon.accept(nowItem, "home");
		nowItem.addActionListener(e -> show(Page.HOME));
		JMenuItem activityItem = item.apply("Activity", page == Page.ACTIVITY || page == Page.GAINED);
		icon.accept(activityItem, "progress");
		activityItem.addActionListener(e -> show(Page.ACTIVITY));
		JMenuItem accountItem = item.apply("Account", page == Page.PROGRESS || page == Page.PROGRESS_DETAIL || page == Page.BOSS);
		icon.accept(accountItem, "shield");
		accountItem.addActionListener(e -> show(Page.PROGRESS));
		separator.run();
		List<ChatSessions.Chat> chats = sessions.chats();
		if (!chats.isEmpty())
		{
			javax.swing.JLabel recent = new javax.swing.JLabel("Recent chats");
			recent.setFont(FontManager.getRunescapeSmallFont());
			recent.setForeground(ChatComponents.MUTED);
			recent.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
			menu.add(recent);
		}
		for (ChatSessions.Chat chat : chats.subList(0, Math.min(MENU_CHATS, chats.size())))
		{
			String title = chat.title.length() > 28 ? chat.title.substring(0, 27) + "..." : chat.title;
			boolean open = page == Page.CHAT && chat.id.equals(sessions.current().id());
			JMenuItem chatItem = item.apply(title + (chat.running() ? "  · working" : chat.unread ? "  · new" : ""), open);
			icon.accept(chatItem, "chat-bubble");
			chatItem.addActionListener(e ->
			{
				sessions.open(chat.id);
				show(Page.CHAT);
			});
		}
		JMenuItem fresh = item.apply("New chat", false);
		icon.accept(fresh, "new-chat");
		fresh.addActionListener(e ->
		{
			sessions.newChat();
			show(Page.CHAT);
		});
		if (!chats.isEmpty())
		{
			JMenuItem all = item.apply("All chats", page == Page.CHATS);
			icon.accept(all, "chats");
			all.addActionListener(e -> show(Page.CHATS));
		}
		separator.run();
		JMenuItem settingsItem = item.apply("Settings", page == Page.SETTINGS || page == Page.CONNECT || page == Page.SYNC);
		icon.accept(settingsItem, "settings");
		settingsItem.addActionListener(e -> show(Page.SETTINGS));
		menu.setPreferredSize(new Dimension(Math.max(160, width), menu.getPreferredSize().height));
		return menu;
	}

	/** The chat's "..." menu: copy its ID or the whole conversation, or delete it. */
	private void showChatMenu()
	{
		ChatView chat = sessions.current();
		JPopupMenu menu = new JPopupMenu();
		JMenuItem copyId = new JMenuItem("Copy chat ID");
		copyId.addActionListener(e -> copy(chat.id()));
		JMenuItem copyText = new JMenuItem("Copy conversation");
		copyText.addActionListener(e -> copy(chat.transcript()));
		copyText.setEnabled(chat.hasConversation());
		JMenuItem delete = new JMenuItem("Delete chat");
		delete.setEnabled(chat.hasConversation());
		delete.addActionListener(e ->
		{
			int answer = JOptionPane.showConfirmDialog(this, "Delete \"" + chat.title() + "\"? This can't be undone.",
				"Delete chat", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (answer == JOptionPane.OK_OPTION)
			{
				sessions.delete(chat.id());
				show(Page.CHATS);
			}
		});
		for (JMenuItem item : new JMenuItem[]{copyId, copyText, delete})
		{
			item.setFont(FontManager.getRunescapeFont());
		}
		menu.add(copyId);
		menu.add(copyText);
		menu.addSeparator();
		menu.add(delete);
		menu.show(chatMenuButton, chatMenuButton.getWidth() - menu.getPreferredSize().width, chatMenuButton.getHeight());
	}

	private static void copy(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	/** Refetch what Home and the open page show (e.g. after a sync). */
	void refreshData()
	{
		home.refresh();
		if (page == Page.ACTIVITY)
		{
			activity.refresh();
		}
		else if (page == Page.GAINED)
		{
			gained.refresh();
		}
		else if (page == Page.PROGRESS_DETAIL)
		{
			progressDetail.refresh();
		}
		else if (page == Page.BOSS)
		{
			boss.refresh();
		}
	}

	HomeView home()
	{
		return home;
	}

	/** For previews: the Progress detail and boss pages. */
	ProgressDetailView progressDetail()
	{
		return progressDetail;
	}

	BossView boss()
	{
		return boss;
	}

	/** For previews: open a page by name ("home", "progress", "activity", "chat" or "settings"). */
	void showPage(String name)
	{
		show(Page.valueOf(name.toUpperCase()));
	}

	// ---- Width

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(panelWidth, super.getPreferredSize().height);
	}

	@Override
	public Dimension getMinimumSize()
	{
		return new Dimension(panelWidth, super.getMinimumSize().height);
	}

	private static int clamp(int w)
	{
		return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, w));
	}

	void setPanelWidth(int w)
	{
		int next = clamp(w);
		if (next == panelWidth)
		{
			return;
		}
		panelWidth = next;
		// RuneLite sizes the sidebar to the selected panel, so relayout up to the window
		JTabbedPane sidebar = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, this);
		if (sidebar != null)
		{
			sidebar.revalidate();
		}
		revalidate();
		Window window = SwingUtilities.getWindowAncestor(this);
		if (window != null)
		{
			window.validate();
		}
		repaint();
	}

	/** A few pixels on the left edge drag to resize; double-click resets to the default width. */
	private void installResizeGrip()
	{
		MouseAdapter grip = new MouseAdapter()
		{
			private int startX;
			private int startWidth;
			private boolean dragging;

			private boolean onGrip(MouseEvent e)
			{
				return e.getX() <= GRIP;
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				boolean on = onGrip(e);
				setCursor(on ? Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR) : Cursor.getDefaultCursor());
				if (on != gripActive)
				{
					gripActive = on;
					repaint(0, 0, GRIP, getHeight());
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!dragging)
				{
					gripActive = false;
					setCursor(Cursor.getDefaultCursor());
					repaint(0, 0, GRIP, getHeight());
				}
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onGrip(e))
				{
					dragging = true;
					startX = e.getXOnScreen();
					startWidth = panelWidth;
				}
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (dragging)
				{
					// The panel sits on the right, so dragging left makes it wider
					setPanelWidth(startWidth + (startX - e.getXOnScreen()));
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (dragging)
				{
					dragging = false;
					onWidthChosen.accept(panelWidth);
				}
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (onGrip(e) && e.getClickCount() == 2)
				{
					setPanelWidth(DEFAULT_WIDTH);
					onWidthChosen.accept(panelWidth);
				}
			}
		};
		addMouseListener(grip);
		addMouseMotionListener(grip);
		setToolTipText(null);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (gripActive)
		{
			g.setColor(ChatComponents.BORDER);
			g.fillRect(0, 0, 2, getHeight());
		}
	}

	@Override
	public void onActivate()
	{
		if (page == Page.CHAT)
		{
			sessions.current().focusInput();
		}
		else if (page == Page.HOME)
		{
			home.onShown();
		}
	}
}
