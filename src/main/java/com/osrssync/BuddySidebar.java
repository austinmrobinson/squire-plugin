package com.osrssync;

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
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's single sidebar entry. Pages form a stack under Home: Progress, Activity, Settings and Chats, with a
 * chat under Chats. Every page but Home has an iOS-style bar: back to the page above (right-click or long-press it
 * for the whole stack), the page's title centred, its actions on the right. The floating "Ask anything..." composer
 * sits over every page except the chats, and settles into the chat's composer when used. Drag the left edge to resize.
 */
class BuddySidebar extends PluginPanel
{
	private enum Page
	{
		HOME("RS Buddy"), PROGRESS("Progress"), ACTIVITY("Activity"), CHATS("Chats"), CHAT("Chat"), SETTINGS("Settings"), CONNECT("Connect an AI app"), WELCOME("RS Buddy");

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
				case HOME:
				case WELCOME:
					return null;
				case CHAT:
					return CHATS;
				case CONNECT:
					return SETTINGS;
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
	BuddySidebar(Function<HomeView.Actions, HomeView> homeFactory, ProgressView progress, ActivityView activity, ChatSessions sessions,
		SettingsView settings, int initialWidth, IntConsumer onWidthChosen)
	{
		super(false);
		this.sessions = sessions;
		this.activity = activity;
		this.settings = settings;
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
		});
		this.panelWidth = clamp(initialWidth);
		this.onWidthChosen = onWidthChosen;

		setLayout(new BorderLayout(0, 4));
		setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		setBackground(ChatComponents.BASE_BG);
		installResizeGrip();

		newChatButton.addActionListener(e ->
		{
			sessions.newChat();
			show(Page.CHAT);
		});
		chatMenuButton.addActionListener(e -> showChatMenu());
		add(nav, BorderLayout.NORTH);

		body.setOpaque(false);
		body.add(home, Page.HOME.name());
		body.add(progress, Page.PROGRESS.name());
		body.add(activity, Page.ACTIVITY.name());
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
	 * Before the player turns RS Buddy on, only the Welcome page shows (nothing is sent until then); after, Home.
	 */
	void setTurnedOn(boolean on, JComponent welcome)
	{
		if (welcome != null && welcome.getParent() != body)
		{
			body.add(welcome, Page.WELCOME.name());
		}
		show(on ? Page.HOME : Page.WELCOME);
	}

	private ConnectView connect;

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
		page = next;
		cards.show(body, next.name());
		nav.setVisible(next != Page.HOME && next != Page.WELCOME);
		List<NavBar.Crumb> ancestors = new ArrayList<>();
		for (Page p = next.parent(); p != null; p = p.parent())
		{
			Page target = p;
			ancestors.add(new NavBar.Crumb(p == Page.HOME ? "Home" : p.title, () -> show(target)));
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
		ask.setVisible(next != Page.CHAT && next != Page.CHATS && next != Page.WELCOME && next != Page.CONNECT);

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
		else if (next == Page.ACTIVITY)
		{
			activity.refresh();
		}
		else if (next == Page.SETTINGS)
		{
			settings.onShown();
		}
		else if (next == Page.CONNECT && connect != null)
		{
			connect.onShown();
		}
		revalidate();
		repaint();
	}

	private String titleOf(Page p)
	{
		return p == Page.CHAT ? sessions.current().title() : p.title;
	}

	/**
	 * Send from the floating composer: start a new chat, switch to it, and let the floating box glide down into the
	 * chat's composer.
	 */
	private void askFromFloating(String text)
	{
		Rectangle from = ask.isDisplayable() && ask.isVisible() ? ask.boxBoundsIn(this) : null;
		ask.clear();
		sessions.startWith(text);
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
	}

	HomeView home()
	{
		return home;
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
