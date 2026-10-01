package com.squire;

import com.squire.ChatComponents.Align;
import com.squire.ChatComponents.MessageList;
import com.squire.ChatComponents.Surface;
import com.squire.WelcomeView.Wrapped;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * Connect another AI app (Claude, ChatGPT, Cursor, ...) to Squire's MCP server: the connector URL to add, and a
 * one-time pairing code to type when the app asks you to sign in. Watches for the new connection while it's open.
 */
class ConnectView extends JPanel
{
	/** Fetches a fresh code and polls for connected apps; the plugin talks to the server. */
	interface Source
	{
		/** Calls back (any thread) with a code, or an error. */
		void newCode(PairingCallback callback);

		/** Calls back (any thread) with the connected apps' names, or null if unknown. */
		void connectedApps(java.util.function.Consumer<java.util.List<String>> callback);
	}

	interface PairingCallback
	{
		void done(String code, Instant expiresAt, String mcpUrl, String error);
	}

	private static final Color SUCCESS = Tokens.COLOR_STATUS_DONE;

	private final Source source;
	private final JLabel url = new JLabel(" ");
	private final JLabel code = new JLabel("····-····", SwingConstants.CENTER);
	private final JLabel expiry = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
	private final ExportCards.PillButton copyUrl = new ExportCards.PillButton("Copy");
	private final ExportCards.PillButton copyCode = new ExportCards.PillButton("Copy");
	private final JLabel newCode = link("Get a new code");
	private final Timer tick = new Timer(1000, e -> onTick());
	private Instant expiresAt;
	private java.util.List<String> appsAtStart;
	private int ticks;

	ConnectView(Source source)
	{
		super(new BorderLayout());
		this.source = source;
		setOpaque(false);
		MessageList list = new MessageList(null, 0);
		list.setBorder(BorderFactory.createEmptyBorder(8, 4, 16, 4));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		add(scroll);

		list.add(ChatComponents.place(new Wrapped(
			"Use your account in Claude, ChatGPT, Cursor or any AI app that supports MCP connectors.", ChatComponents.MUTED, false),
			Align.FILL, 4));

		// Step 1: the connector URL
		list.add(ChatComponents.place(step("1", "Add a connector with this URL"), Align.FILL, 16));
		Surface urlCard = HomeView.listCard();
		urlCard.setLayout(new BorderLayout(8, 0));
		urlCard.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 8));
		url.setFont(FontManager.getRunescapeSmallFont());
		url.setForeground(Color.WHITE);
		urlCard.add(url, BorderLayout.CENTER);
		urlCard.add(copyUrl, BorderLayout.EAST);
		copyUrl.addActionListener(e -> copy(url.getToolTipText(), copyUrl));
		list.add(ChatComponents.place(urlCard, Align.FILL, 6));

		// Step 2: the pairing code
		list.add(ChatComponents.place(step("2", "Sign in with this code"), Align.FILL, 16));
		Surface codeCard = HomeView.listCard();
		codeCard.setLayout(new BorderLayout(0, 2));
		codeCard.setBorder(BorderFactory.createEmptyBorder(12, 12, 10, 12));
		code.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.PLAIN, 32f));
		code.setForeground(Color.WHITE);
		codeCard.add(code, BorderLayout.CENTER);
		JPanel under = new JPanel(new BorderLayout(8, 0));
		under.setOpaque(false);
		expiry.setFont(FontManager.getRunescapeSmallFont());
		expiry.setForeground(ChatComponents.MUTED);
		expiry.setHorizontalAlignment(SwingConstants.LEFT);
		under.add(expiry, BorderLayout.CENTER);
		under.add(copyCode, BorderLayout.EAST);
		copyCode.addActionListener(e -> copy(code.getText(), copyCode));
		codeCard.add(under, BorderLayout.SOUTH);
		list.add(ChatComponents.place(codeCard, Align.FILL, 6));
		newCode.setVisible(false);
		newCode.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseReleased(MouseEvent e)
			{
				// On release rather than click: Swing drops a click if the pointer moves a pixel while pressed
				if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !e.getComponent().contains(e.getPoint()))
				{
					return;
				}
				requestCode();
			}
		});
		list.add(ChatComponents.place(newCode, Align.FILL, 8));

		status.setFont(FontManager.getRunescapeFont());
		status.setForeground(ChatComponents.MUTED);
		list.add(ChatComponents.place(status, Align.FILL, 14));

		list.add(ChatComponents.place(new Wrapped(
			"The app can read your synced account and use Squire's OSRS tools. It can't do anything in your game. "
				+ "Disconnect it any time in Settings.", ChatComponents.MUTED, false), Align.FILL, 14));
	}

	/** Page opened: get a fresh code and start watching for the connection. */
	void onShown()
	{
		appsAtStart = null;
		source.connectedApps(apps -> onEdt(() -> appsAtStart = apps));
		requestCode();
		ticks = 0;
		tick.start();
	}

	void onHidden()
	{
		tick.stop();
	}

	private void requestCode()
	{
		newCode.setVisible(false);
		code.setText("····-····");
		code.setForeground(ChatComponents.MUTED);
		expiry.setText("Getting a code...");
		copyCode.setEnabled(false);
		status.setText(" ");
		source.newCode((c, expires, mcpUrl, error) -> onEdt(() ->
		{
			if (error != null)
			{
				expiry.setText(error);
				newCode.setVisible(true);
				return;
			}
			code.setText(c);
			code.setForeground(Color.WHITE);
			copyCode.setEnabled(true);
			expiresAt = expires;
			url.setText(mcpUrl);
			url.setToolTipText(mcpUrl);
			status.setForeground(ChatComponents.MUTED);
			status.setText("Waiting for your app...");
			onTick();
		}));
	}

	private void onTick()
	{
		if (expiresAt != null)
		{
			long left = Duration.between(Instant.now(), expiresAt).getSeconds();
			if (left <= 0)
			{
				expiry.setText("Code expired");
				code.setForeground(ChatComponents.MUTED);
				copyCode.setEnabled(false);
				newCode.setVisible(true);
				expiresAt = null;
			}
			else
			{
				expiry.setText(String.format("Expires in %d:%02d", left / 60, left % 60));
			}
		}
		// Every 3 seconds, look for a newly connected app
		if (++ticks % 3 == 0 && appsAtStart != null)
		{
			source.connectedApps(apps -> onEdt(() ->
			{
				if (apps == null || appsAtStart == null)
				{
					return;
				}
				if (apps.size() <= appsAtStart.size())
				{
					return;
				}
				String app = apps.stream().filter(a -> !appsAtStart.contains(a)).findFirst().orElse(apps.get(apps.size() - 1));
				status.setForeground(SUCCESS);
				status.setText("Connected to " + app);
				code.setForeground(ChatComponents.MUTED);
				copyCode.setEnabled(false);
				expiresAt = null;
				expiry.setText("Code used");
				appsAtStart = apps;
			}));
		}
	}

	private static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}

	private static void copy(String text, ExportCards.PillButton button)
	{
		if (text == null || text.isBlank())
		{
			return;
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		button.setText("Copied");
		Timer back = new Timer(1500, e -> button.setText("Copy"));
		back.setRepeats(false);
		back.start();
	}

	private static JPanel step(String n, String text)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setOpaque(false);
		JLabel num = new JLabel(n + ".");
		num.setFont(FontManager.getRunescapeBoldFont());
		num.setForeground(ChatComponents.ACCENT);
		JLabel t = new JLabel(text);
		t.setFont(FontManager.getRunescapeFont());
		t.setForeground(Color.WHITE);
		p.add(num, BorderLayout.WEST);
		p.add(t, BorderLayout.CENTER);
		return p;
	}

	private static JLabel link(String text)
	{
		JLabel l = new JLabel("<html><u>" + text + "</u></html>", SwingConstants.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ChatComponents.ACCENT);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return l;
	}
}
