package com.osrssync;

import com.osrssync.ChatComponents.Surface;
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
 * The floating "Ask anything..." composer over the non-chat pages: a quick way into a new chat. It starts compact,
 * grows sideways as the text gets longer until it reaches the panel's width, then wraps onto more lines upward, like
 * the chat's own composer. Enter sends (Shift+Enter for a new line); with nothing typed it opens the chat.
 */
class FloatingAsk extends JPanel
{
	static final int MIN_WIDTH = 200;
	static final int BOTTOM = 20;
	static final int SIDE = 12;
	/** Bottom padding pages need so their last content can scroll clear of the composer. */
	static final int CLEARANCE = 40 + BOTTOM + 12;
	private static final int ROW_HEIGHT_MIN = 40;
	private static final int MAX_ROWS = 6;
	/** Shadow margin around the box (the shadow paints outside it). */
	static final int PAD = 16;

	private final Surface box = new Surface(ChatComponents.CARD_BG, 8, false).border(ChatComponents.BORDER);
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
		return b.left + b.right + 8 + send.getPreferredSize().width + 4;
	}

	/** Width for the current text: compact, growing with the text up to {@code max}. */
	private int boxWidth(int max)
	{
		String text = input.getText();
		FontMetrics fm = input.getFontMetrics(input.getFont());
		int longest = 0;
		for (String line : text.split("\n", -1))
		{
			longest = Math.max(longest, fm.stringWidth(line));
		}
		return Math.max(Math.min(MIN_WIDTH, max), Math.min(max, longest + chrome()));
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
		// A soft layered drop shadow under the box
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int x = PAD, w = getWidth() - PAD * 2, h = getHeight() - PAD;
		for (int i = 1; i <= 6; i++)
		{
			g2.setColor(new Color(0, 0, 0, 22));
			int grow = i - 2;
			g2.fillRoundRect(x - grow, i * 2 - grow, w + grow * 2, h + grow * 2, 16 + grow * 2, 16 + grow * 2);
		}
		g2.dispose();
	}

	/**
	 * Lays a page out full size with the composer floating centred near its bottom. Hides the composer (and gives
	 * the page the whole area) when it isn't wanted.
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
			back.setBounds(0, 0, w, h);
			Dimension f = front.sizeFor(w);
			int fw = Math.min(f.width, w + 2 * PAD);
			front.setBounds((w - fw) / 2, h - BOTTOM - f.height + PAD, fw, f.height);
		}
	}
}
