package com.osrssync;

import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The Chats page: every conversation, newest first. Ones still answering show the Leap spinner; ones that
 * finished while you were elsewhere show a dot. Click to open; hover for the delete button (click it twice).
 */
class ChatHistoryView extends JPanel
{
	private final ChatSessions sessions;
	private final Consumer<String> open;
	private final Runnable newChat;
	private final MessageList list = new MessageList(null, 0);
	private String renderedKey = "";

	ChatHistoryView(ChatSessions sessions, Consumer<String> open, Runnable newChat)
	{
		super(new BorderLayout());
		this.sessions = sessions;
		this.open = open;
		this.newChat = newChat;
		setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(1, 1, 8, 1));
		JScrollPane scroll = new JScrollPane(list);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll);
		sessions.addListener(() ->
		{
			if (isShowing())
			{
				render();
			}
		});
		render();
	}

	/** Rebuild if anything the list shows changed (cheap to call on every show). */
	void onShown()
	{
		render();
	}

	private void render()
	{
		List<ChatSessions.Chat> chats = sessions.chats();
		StringBuilder key = new StringBuilder();
		long now = System.currentTimeMillis();
		for (ChatSessions.Chat c : chats)
		{
			key.append(c.id).append(c.title).append(c.updatedAt).append(c.running()).append(c.unread).append(ago(c.updatedAt, now)).append('|');
		}
		if (key.toString().equals(renderedKey))
		{
			return;
		}
		renderedKey = key.toString();

		list.removeAll();
		Surface top = HomeView.listCard();
		top.add(HomeView.listRow(SvgIcon.load("new-chat", 16, null), "New chat", null, newChat));
		list.add(ChatComponents.place(top, Align.FILL, 0));

		if (chats.isEmpty())
		{
			JLabel none = Ui.small("Your conversations will be listed here.");
			none.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 0));
			list.add(ChatComponents.place(none, Align.LEFT, 4));
		}
		else
		{
			Surface card = HomeView.listCard();
			for (int i = 0; i < chats.size(); i++)
			{
				if (i > 0)
				{
					card.add(HomeView.divider());
				}
				card.add(row(chats.get(i), now));
			}
			list.add(ChatComponents.place(Ui.sectionLabel("Chats"), Align.LEFT, 12));
			list.add(ChatComponents.place(card, Align.FILL, 4));
		}
		list.revalidate();
		list.repaint();
	}

	private JComponent row(ChatSessions.Chat chat, long now)
	{
		Surface r = new Surface(null, 0, false);
		r.setLayout(new BorderLayout(8, 0));
		r.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

		JPanel words = new JPanel();
		words.setOpaque(false);
		words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
		JLabel title = new JLabel(chat.title);
		title.setFont(chat.unread ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		title.setForeground(chat.unread ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		JLabel preview = Ui.small(chat.running() ? "Working..." : chat.preview);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		preview.setAlignmentX(Component.LEFT_ALIGNMENT);
		words.add(title);
		words.add(preview);
		r.add(words, BorderLayout.CENTER);

		// Right: spinner while working, a dot when there's an unseen answer, else how long ago; delete on hover
		JPanel right = new JPanel(new BorderLayout());
		right.setOpaque(false);
		JComponent status = chat.running() ? ChatTraceViews.spinner(ChatComponents.ACCENT)
			: chat.unread ? unreadDot() : Ui.small(ago(chat.updatedAt, now));
		JLabel delete = Ui.small("x");
		delete.setToolTipText("Delete this chat");
		delete.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
		delete.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		delete.setVisible(false);
		JPanel stack = new JPanel(new java.awt.GridBagLayout());
		stack.setOpaque(false);
		stack.add(status);
		stack.add(delete);
		right.add(stack, BorderLayout.CENTER);
		r.add(right, BorderLayout.EAST);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.getPreferredSize().height));
		r.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				open.accept(chat.id);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				r.setFill(ChatComponents.HOVER_BG);
				delete.setVisible(!chat.running());
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!r.contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), r)))
				{
					r.setFill(null);
					delete.setVisible(false);
					delete.setText("x");
				}
			}
		};
		for (Component c : new Component[]{r, words, title, preview, right, stack, status})
		{
			c.addMouseListener(hover);
		}
		delete.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Two clicks: "x" becomes "Delete?", then it goes
				if ("x".equals(delete.getText()))
				{
					delete.setText("Delete?");
					delete.setForeground(new Color(0xFF8A80));
				}
				else
				{
					sessions.delete(chat.id);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover.mouseExited(e);
			}
		});
		return r;
	}

	private static JComponent unreadDot()
	{
		JComponent d = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(ChatComponents.ACCENT);
				Pixel.fill(g2, (getWidth() - 8) / 2, (getHeight() - 8) / 2, 8, 8, 2);
				g2.dispose();
			}
		};
		d.setPreferredSize(new Dimension(16, 16));
		d.setToolTipText("New reply");
		return d;
	}

	/** "now", "5m", "3h", "2d", "4w". */
	static String ago(long at, long now)
	{
		long s = Math.max(0, (now - at) / 1000);
		if (s < 60)
		{
			return "now";
		}
		if (s < 3600)
		{
			return (s / 60) + "m";
		}
		if (s < 86400)
		{
			return (s / 3600) + "h";
		}
		if (s < 86400 * 14)
		{
			return (s / 86400) + "d";
		}
		return (s / (86400 * 7)) + "w";
	}
}
