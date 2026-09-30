package com.squire;

import com.google.gson.JsonObject;
import com.squire.ChatComponents.Align;
import com.squire.ChatComponents.MessageList;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * Set up, straight after Continue: one card for what syncs by itself (the account, then its Wise Old Man history),
 * turning green when it's done, then what needs the player: opening the bank and the collection log. It checks the
 * server every few seconds while it's on screen. "Finish later" leaves it for Home, which keeps a way back.
 */
class SetupView extends JPanel
{
	interface Controller
	{
		/** The server's view: {synced, history: waiting|importing|done|none, bank, collectionLog}, or an error. */
		void fetch(Consumer<AccountApi.Result> callback);

		boolean loggedIn();

		/** The page came on screen or left it (the plugin captures the collection log as soon as it opens meanwhile). */
		void visible(boolean visible);

		/** Everything is done: Home stops offering the page. */
		void completed();

		/** Finish later, or Done: back to Home. */
		void leave();
	}

	static final Color DONE = new Color(0x3FA33F);
	private static final Color EDGE = new Color(0x0F0F0F);
	private static final int POLL_MS = 3000;

	private final Controller controller;
	private final MessageList list = new MessageList(null, 0);
	private final ChatComponents.AccentButton later = new ChatComponents.AccentButton("Finish later", true);
	private final ChatComponents.AccentButton done = new ChatComponents.AccentButton("Done");
	private final JPanel bottom = new JPanel(new BorderLayout());
	private final Timer poll;
	private final Timer spin;
	private JsonObject state;
	private int spinStep;
	private boolean reportedComplete;

	SetupView(Controller controller)
	{
		super(new BorderLayout());
		this.controller = controller;
		setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, 12, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll);

		later.addActionListener(e -> controller.leave());
		done.addActionListener(e -> controller.leave());
		bottom.setOpaque(false);
		bottom.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		add(bottom, BorderLayout.SOUTH);

		poll = new Timer(POLL_MS, e -> fetch());
		spin = new Timer(100, e ->
		{
			spinStep = (spinStep + 1) % 8;
			list.repaint();
		});
		render();
	}

	/** The page came on screen: check now, then every few seconds until it leaves. */
	void onShown()
	{
		controller.visible(true);
		fetch();
		poll.start();
		spin.start();
	}

	void onHidden()
	{
		controller.visible(false);
		poll.stop();
		spin.stop();
	}

	/** For previews: show this state without fetching. */
	void show(JsonObject state)
	{
		this.state = state;
		render();
	}

	private void fetch()
	{
		if (!isShowing() && poll.isRunning())
		{
			onHidden();
			return;
		}
		controller.fetch(result -> SwingUtilities.invokeLater(() ->
		{
			if (result.json != null)
			{
				state = result.json;
				render();
			}
		}));
	}

	private boolean flag(String key)
	{
		return state != null && state.has(key) && state.get(key).getAsBoolean();
	}

	private String history()
	{
		return state != null && state.has("history") ? state.get("history").getAsString() : "waiting";
	}

	private void render()
	{
		boolean synced = flag("synced");
		String history = history();
		boolean dataDone = synced && ("done".equals(history) || "none".equals(history));
		boolean bank = flag("bank"), clog = flag("collectionLog");
		boolean complete = dataDone && bank && clog;

		list.removeAll();
		list.add(ChatComponents.place(syncCard(synced, history, dataDone), Align.FILL, 0));
		list.add(ChatComponents.place(groupTitle("Needs you"), Align.FILL, 4));
		list.add(ChatComponents.place(new Task("bank.png", "Open your bank", "Needed for gear, setups and plans",
			"Bank synced", bank), Align.FILL, 4));
		list.add(ChatComponents.place(new Task("collection-log.png", "Open your collection log", "Needed for log goals and your score",
			"Collection log synced", clog), Align.FILL, 4));
		list.revalidate();
		list.repaint();

		bottom.removeAll();
		bottom.add(complete ? done : later);
		bottom.revalidate();
		bottom.repaint();

		if (complete && !reportedComplete)
		{
			reportedComplete = true;
			controller.completed();
		}
	}

	private JComponent syncCard(boolean synced, String history, boolean finished)
	{
		String title, detail;
		double progress;
		if (finished)
		{
			title = "Your data is synced";
			detail = "none".equals(history) ? "Stats, quests and diaries" : "Stats, quests, diaries and your history";
			progress = 1;
		}
		else if (synced)
		{
			title = "Syncing your data";
			detail = "Importing your history from Wise Old Man...";
			progress = 0.75;
		}
		else if (controller.loggedIn())
		{
			title = "Syncing your data";
			detail = "Sending your stats, quests and diaries...";
			progress = 0.35;
		}
		else
		{
			title = "Syncing your data";
			detail = "Log in to the game to start";
			progress = 0.05;
		}
		return new SyncCard(title, detail, progress, finished);
	}

	private static JComponent groupTitle(String text)
	{
		JLabel l = Ui.bold(text);
		l.setBorder(BorderFactory.createEmptyBorder(10, 11, 2, 0));
		return l;
	}

	/** Two lines of text: a bold title and a small muted detail. */
	private static JPanel textBlock(String title, String detail)
	{
		JPanel text = new JPanel();
		text.setOpaque(false);
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		JLabel t = Ui.bold(title);
		JLabel d = Ui.small(detail);
		d.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
		text.add(t);
		text.add(d);
		return text;
	}

	/** A card in the design's frame: stepped corners, a 1px edge and the game's bevel. */
	private static void frame(Graphics2D g2, int w, int h, Color fill, Color edge)
	{
		g2.setColor(fill);
		Pixel.fill(g2, 0, 0, w, h, 4);
		g2.setColor(edge);
		Pixel.draw(g2, 0, 0, w, h, 4);
		Pixel.bevel(g2, 1, 1, w - 2, h - 2, 3, ChatComponents.CARD_LIGHT, ChatComponents.CARD_DARK);
	}

	/** What syncs by itself: a progress bar along the top, then a status and two lines of text. Green when done. */
	private final class SyncCard extends JPanel
	{
		private final double progress;
		private final boolean finished;

		SyncCard(String title, String detail, double progress, boolean finished)
		{
			super(new BorderLayout(8, 0));
			this.progress = progress;
			this.finished = finished;
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(7 + 8, 10, 8, 10));
			JComponent status = new Status(finished ? Status.DONE : Status.LOADING);
			JPanel west = new JPanel(new BorderLayout());
			west.setOpaque(false);
			west.add(status, BorderLayout.NORTH);
			add(west, BorderLayout.WEST);
			add(textBlock(title, detail));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			int w = getWidth(), h = getHeight();
			frame(g2, w, h, ChatComponents.CARD_BG, finished ? DONE : EDGE);
			// The bar runs edge to edge just inside the frame
			int bw = w - 2;
			g2.setColor(ChatComponents.BASE_BG);
			g2.fillRect(1, 1, bw, 6);
			g2.setColor(finished ? DONE : ChatComponents.ACCENT);
			g2.fillRect(1, 1, (int) Math.round(bw * progress), 6);
			g2.dispose();
		}
	}

	/** Something the player does in game: its icon, what to do and why, and a status on the right. */
	private final class Task extends JPanel
	{
		Task(String iconFile, String title, String detail, String doneDetail, boolean finished)
		{
			super(new BorderLayout(8, 0));
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			JLabel icon = new JLabel(icon(iconFile));
			icon.setPreferredSize(new Dimension(32, 32));
			add(icon, BorderLayout.WEST);
			add(textBlock(title, finished ? doneDetail : detail));
			JPanel east = new JPanel(new java.awt.GridBagLayout());
			east.setOpaque(false);
			east.add(new Status(finished ? Status.DONE : Status.WAITING));
			add(east, BorderLayout.EAST);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			frame(g2, getWidth(), getHeight(), ChatComponents.CARD_BG, EDGE);
			g2.dispose();
		}
	}

	/** The wiki's small game icons, scaled in whole steps so the pixels stay crisp, centred in 32px. */
	private static javax.swing.Icon icon(String file)
	{
		BufferedImage img;
		try (java.io.InputStream in = SetupView.class.getResourceAsStream("welcome/" + file))
		{
			img = in == null ? null : ImageIO.read(in);
		}
		catch (java.io.IOException e)
		{
			img = null;
		}
		if (img == null)
		{
			return null;
		}
		int scale = Math.max(1, 32 / Math.max(img.getWidth(), img.getHeight()));
		Image scaled = img.getScaledInstance(img.getWidth() * scale, img.getHeight() * scale, Image.SCALE_REPLICATE);
		BufferedImage out = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(scaled, (32 - img.getWidth() * scale) / 2, (32 - img.getHeight() * scale) / 2, null);
		g.dispose();
		return new javax.swing.ImageIcon(out);
	}

	/** The design's 12px pixel statuses: a spinner, a green tick, or an empty box. */
	private final class Status extends JComponent
	{
		static final int LOADING = 0, DONE = 1, WAITING = 2;
		private final int kind;

		Status(int kind)
		{
			this.kind = kind;
			Dimension d = new Dimension(12, 17);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			int y = 2;
			switch (kind)
			{
				case LOADING:
				{
					// Eight 2px dots in a ring; the bright one walks round
					int[][] dots = {{5, 0}, {9, 1}, {10, 5}, {9, 9}, {5, 10}, {1, 9}, {0, 5}, {1, 1}};
					int[] shades = {0xFFFFFF, 0xBBBBBB, 0x888888, 0x555555, 0x555555, 0x555555, 0x555555, 0x555555};
					for (int i = 0; i < dots.length; i++)
					{
						g2.setColor(new Color(shades[(i - spinStep + 8) % 8]));
						g2.fillRect(dots[i][0], y + dots[i][1], 2, 2);
					}
					break;
				}
				case DONE:
				{
					g2.setColor(SetupView.DONE);
					// A stepped tick
					int[][] tick = {{0, 5}, {2, 7}, {4, 9}, {6, 7}, {8, 5}, {10, 3}, {10, 1}};
					for (int[] p : tick)
					{
						g2.fillRect(p[0], y + p[1], 2, 2);
					}
					break;
				}
				default:
					g2.setColor(ChatComponents.BORDER);
					g2.drawRect(0, y, 11, 11);
			}
			g2.dispose();
		}
	}
}
