package com.squire;

import com.squire.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.FontManager;

/**
 * The "Ask anything..." composer docked at the bottom of the non-chat pages: a quick way into a new chat, with the
 * menu button beside it (the way to every page). It spans the panel and wraps onto more lines upward as the text
 * grows, like the chat's own composer. Enter sends (Shift+Enter for a new line); with nothing typed it opens the chat.
 */
class FloatingAsk extends JPanel
{
	static final int MIN_WIDTH = 200;
	static final int BOTTOM = 0;
	static final int SIDE = 0;
	/** Bottom padding under pages' last content (the composer is docked below them, not over them). */
	static final int CLEARANCE = 8;
	private static final int ROW_HEIGHT_MIN = 40;
	private static final int MAX_ROWS = 6;
	/** Margin around the box. */
	static final int PAD = 0;
	private final JComponent menu = Ui.menuButton(ChatComponents.CARD_BG);

	private final Surface box = new Surface(ChatComponents.CARD_BG, 8, false).border(ChatComponents.BORDER).sunken();
	private final ChatView.PlaceholderTextArea input = new ChatView.PlaceholderTextArea("Ask anything...");
	private final JScrollPane inputScroll = new JScrollPane(input);
	private final ChatComponents.SendButton send = new ChatComponents.SendButton(true);

	FloatingAsk(Consumer<String> onSend, Runnable onEmpty)
	{
		super(new BorderLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, PAD, PAD, PAD));

		box.setLayout(new BorderLayout(8, 0));
		box.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 4));
		input.setFont(FontManager.getRunescapeFont());
		input.setForeground(Color.WHITE);
		input.setCaretColor(Color.WHITE);
		input.setOpaque(false);
		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setRows(1);
		input.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		inputScroll.setOpaque(false);
		inputScroll.getViewport().setOpaque(false);
		inputScroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 7, 0));
		inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		inputScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
		box.add(inputScroll, BorderLayout.CENTER);
		JPanel sendWrap = new JPanel(new BorderLayout());
		sendWrap.setOpaque(false);
		sendWrap.add(send, BorderLayout.SOUTH);
		box.add(sendWrap, BorderLayout.EAST);
		add(box, BorderLayout.CENTER);
		JPanel menuWrap = new JPanel(new BorderLayout());
		menuWrap.setOpaque(false);
		menuWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
		menuWrap.add(menu, BorderLayout.SOUTH);
		add(menuWrap, BorderLayout.WEST);

		Runnable submit = () ->
		{
			String text = input.getText().trim();
			if (text.isEmpty())
			{
				onEmpty.run();
			}
			else
			{
				onSend.accept(text);
			}
		};
		input.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown())
				{
					e.consume();
					submit.run();
				}
			}
		});
		send.addActionListener(e -> submit.run());
		this.submit = submit;
		input.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				resized();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				resized();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
			}
		});
	}

	private final Runnable submit;

	/** Send what's typed, as Enter would (tests). */
	void submit()
	{
		submit.run();
	}

	String text()
	{
		return input.getText().trim();
	}

	/** Put text in it (previews). */
	void setDraft(String text)
	{
		input.setText(text);
	}

	void clear()
	{
		input.setText("");
	}

	/** The composer box itself (not the shadow margin), in another component's coordinates. */
	Rectangle boxBoundsIn(Component other)
	{
		return SwingUtilities.convertRectangle(this, box.getBounds(), other);
	}

	private void resized()
	{
		Container parent = getParent();
		if (parent != null)
		{
			parent.revalidate();
			parent.repaint();
		}
	}

	/** Chrome around the text: padding, the gap and the send button. */
	private int chrome()
	{
		Insets b = box.getInsets();
		return b.left + b.right + 8 + send.getPreferredSize().width + 4 + menu.getPreferredSize().width + 4;
	}

	/** What the box says when empty; it changes with the page ("Ask about or change this plan..."). */
	void setPlaceholder(String text)
	{
		if (!text.equals(input.placeholder()))
		{
			input.setPlaceholder(text);
			revalidate();
			repaint();
		}
	}

	/** Width for the current text: compact, growing with the text up to {@code max}. */
	private int boxWidth(int max)
	{
		String text = input.getText();
		FontMetrics fm = input.getFontMetrics(input.getFont());
		// Empty, it fits its hint
		int longest = text.isEmpty() ? fm.stringWidth(input.placeholder()) : 0;
		for (String line : text.split("\n", -1))
		{
			longest = Math.max(longest, fm.stringWidth(line));
		}
		// Docked: always the panel's width
		return max;
	}

	/** Height for the text at this width: one line, then more as it wraps, up to MAX_ROWS (then it scrolls). */
	private int boxHeight(int width)
	{
		FontMetrics fm = input.getFontMetrics(input.getFont());
		int inner = Math.max(20, width - chrome());
		javax.swing.text.View root = input.getUI().getRootView(input);
		root.setSize(inner, Integer.MAX_VALUE);
		int rows = Math.max(1, (int) Math.ceil(root.getPreferredSpan(javax.swing.text.View.Y_AXIS) / fm.getHeight()));
		boolean capped = rows > MAX_ROWS;
		rows = Math.min(rows, MAX_ROWS);
		inputScroll.setVerticalScrollBarPolicy(capped ? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
		if (input.getRows() != rows)
		{
			input.setRows(rows);
		}
		Insets s = inputScroll.getInsets();
		Insets b = box.getInsets();
		return Math.max(ROW_HEIGHT_MIN, rows * fm.getHeight() + s.top + s.bottom + b.top + b.bottom);
	}

	/** Size for the space available (the parent's width), shadow margin included. */
	Dimension sizeFor(int available)
	{
		int max = Math.max(MIN_WIDTH, available - 2 * SIDE);
		int w = boxWidth(max);
		int h = boxHeight(w);
		return new Dimension(w + 2 * PAD, h + PAD);
	}

	void focusInput()
	{
		input.requestFocusInWindow();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		// Docked: no shadow, it sits on the panel
	}

	/**
	 * Lays a page out above the composer, which is docked along the bottom. When the composer isn't wanted (the chat
	 * pages have their own) the page gets the whole area.
	 */
	static final class Overlay implements LayoutManager
	{
		private final JComponent back;
		private final FloatingAsk front;

		Overlay(JComponent back, FloatingAsk front)
		{
			this.back = back;
			this.front = front;
		}

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container parent)
		{
			return back.getPreferredSize();
		}

		@Override
		public Dimension minimumLayoutSize(Container parent)
		{
			return new Dimension(0, 0);
		}

		@Override
		public void layoutContainer(Container parent)
		{
			int w = parent.getWidth(), h = parent.getHeight();
			if (!front.isVisible())
			{
				back.setBounds(0, 0, w, h);
				return;
			}
			Dimension f = front.sizeFor(w);
			int gap = 4;
			back.setBounds(0, 0, w, Math.max(0, h - f.height - gap));
			front.setBounds(0, h - f.height, w, f.height);
		}
	}
}
