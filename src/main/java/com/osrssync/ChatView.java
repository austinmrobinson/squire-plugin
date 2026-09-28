package com.osrssync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.osrssync.ChatComponents.Align;
import com.osrssync.ChatComponents.Bubble;
import com.osrssync.ChatComponents.MessageList;
import com.osrssync.ChatComponents.SendButton;
import com.osrssync.ChatComponents.Surface;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Squire chat: messages in an inset card, a grooved typing row, and a composer with the
 * model picker and a beveled send button.
 */
class ChatView extends JPanel
{
	static final String AUTO_MODEL = "auto";
	private static final String[] SUGGESTIONS = {
		"What's my best ranged setup right now?",
		"Which combat achievements am I closest to?",
		"What should I work on next?",
	};
	private static final int MAX_INPUT_ROWS = 8;
	private static final int TITLE_CHARS = 26;
	private static final int MAX_ATTACHMENTS = 5;

	private final ChatClient client;
	private final Supplier<Map<String, Object>> contextSupplier;
	private final ModelCatalog models;
	private final Supplier<String> currentModel;
	private final Consumer<String> onModelChange;
	private Consumer<String> onTitle = t -> {};

	// User messages sit in from the right by the same inset replies, steps and sources use on the left
	private final MessageList list = new MessageList(null, ChatTraceViews.INSET);
	private final JScrollPane scroll = new JScrollPane(list);
	private final JTextArea input = new PlaceholderTextArea("Ask anything...");
	private final SendButton sendButton = new SendButton();
	private final JLabel modelPicker = new JLabel();
	private final Surface composer = new Surface(ChatComponents.PANEL_BG, 8, false).border(ChatComponents.BORDER).sunken();
	private final JScrollPane inputScroll = new JScrollPane(input);
	private boolean multiline;
	private final JLabel attachButton = new JLabel(SvgIcon.load("paperclip", 16, null));
	private final List<Attachment> attachments = new ArrayList<>();
	private final JPanel attachmentRow = new JPanel(new BorderLayout());
	/** Images are scaled and encoded off the Swing thread. */
	private final ExecutorService attachWorker = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "rs-buddy-attachments");
		t.setDaemon(true);
		return t;
	});

	private final JPanel emptyState;
	private final TypingRow typing = new TypingRow();
	private Bubble currentReply;
	private ChatTrace trace;
	private ChatTraceViews.StepsLine steps;
	private final StringBuilder currentText = new StringBuilder();
	private boolean busy;
	private String title = "New chat";
	private boolean titled;
	private final Timer renderTimer;

	// ---- The conversation as saved in the chat history
	private String id = java.util.UUID.randomUUID().toString();
	private long createdAt = System.currentTimeMillis();
	private long updatedAt = createdAt;
	/** One JSON object per message: {role: user|assistant|error, text, at, attachments?, trace?}. */
	private final List<com.google.gson.JsonObject> turns = new ArrayList<>();
	/** Told when the conversation changes (a message sent or answered) or starts/stops working. */
	private Runnable onChanged = () -> {};
	/** Started from the game's chatbox (::squire or the shortcut); its replies are echoed there. */
	private boolean inGame;
	private Map<String, Object> extraContext = Map.of();
	private ReplyListener replyListener;
	/** Where each message block of the current reply starts, so the final answer can be told from interim narration. */
	private final List<Integer> blockStarts = new ArrayList<>();

	/** Told when a reply finishes or fails (the in-game chat prints it in the chatbox). */
	interface ReplyListener
	{
		/** {@code more}: the reply has things the chatbox can't show (sources, setups to copy). */
		void reply(String markdown, boolean more);

		void error(String message);
	}
	private static final int RECAP_TURNS = 12;
	/** A reopened chat shows its latest messages first; older ones load on request, a window at a time. */
	private static final int RESTORE_WINDOW = 20;
	private int renderedFrom;
	private static final int RECAP_CHARS = 800;

	ChatView(ChatClient client, Supplier<Map<String, Object>> contextSupplier, ModelCatalog models, Supplier<String> currentModel, Consumer<String> onModelChange)
	{
		this.client = client;
		this.contextSupplier = contextSupplier;
		this.models = models;
		this.currentModel = currentModel;
		this.onModelChange = onModelChange;
		setLayout(new BorderLayout(0, 4));
		setOpaque(false);

		// Messages sit inside an inset card
		Surface card = new Surface(ChatComponents.CARD_BG, 8, true).border(ChatComponents.PANEL_BG);
		card.setLayout(new BorderLayout());
		card.setBorder(BorderFactory.createEmptyBorder(1, 1, 9, 1));
		list.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		card.add(scroll, BorderLayout.CENTER);
		add(card, BorderLayout.CENTER);

		emptyState = buildEmptyState();
		list.add(ChatComponents.place(emptyState, Align.FILL, 0));

		add(buildComposer(), BorderLayout.SOUTH);

		// Coalesce streaming deltas into ~12 renders per second
		renderTimer = new Timer(80, e -> renderReply());
		renderTimer.setRepeats(false);
		refreshModelLabel();
	}

	void setOnTitle(Consumer<String> onTitle)
	{
		this.onTitle = onTitle;
	}

	void setOnChanged(Runnable onChanged)
	{
		this.onChanged = onChanged;
	}

	String id()
	{
		return id;
	}

	long updatedAt()
	{
		return updatedAt;
	}

	boolean isBusy()
	{
		return busy;
	}

	boolean isInGame()
	{
		return inGame;
	}

	/** Mark this as the in-game conversation: replies are kept short and echoed to the chatbox. */
	void setInGame(Map<String, Object> extraContext, ReplyListener listener)
	{
		this.inGame = true;
		this.extraContext = extraContext;
		this.replyListener = listener;
	}

	/** Stop any reply in progress and release the connection (the chat is being closed or deleted). */
	void close()
	{
		if (busy)
		{
			client.cancel();
		}
		client.shutdown();
		attachWorker.shutdownNow();
	}

	/** The conversation for the chat history file. */
	com.google.gson.JsonObject toRecord()
	{
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("version", 1);
		o.addProperty("id", id);
		o.addProperty("title", title);
		o.addProperty("inGame", inGame);
		o.addProperty("createdAt", createdAt);
		o.addProperty("updatedAt", updatedAt);
		o.addProperty("sessionId", client.sessionId());
		o.addProperty("streamIndex", client.streamIndex());
		JsonArray list = new JsonArray();
		turns.forEach(list::add);
		o.add("turns", list);
		return o;
	}

	/** Rebuild a saved conversation: its messages, steps, sources and export cards, and its server session. */
	void restore(com.google.gson.JsonObject record)
	{
		id = record.get("id").getAsString();
		createdAt = record.has("createdAt") ? record.get("createdAt").getAsLong() : createdAt;
		updatedAt = record.has("updatedAt") ? record.get("updatedAt").getAsLong() : createdAt;
		title = record.has("title") ? record.get("title").getAsString() : "Chat";
		inGame = record.has("inGame") && record.get("inGame").getAsBoolean();
		titled = true;
		if (record.has("sessionId") && !record.get("sessionId").isJsonNull())
		{
			client.resume(record.get("sessionId").getAsString(), record.has("streamIndex") ? record.get("streamIndex").getAsInt() : 0);
		}
		turns.clear();
		JsonArray saved = record.has("turns") ? record.getAsJsonArray("turns") : new JsonArray();
		saved.forEach(t -> turns.add(t.getAsJsonObject()));
		renderTurnsFrom(Math.max(0, turns.size() - RESTORE_WINDOW));
	}

	/** Show the saved messages from this index on, with a "Show earlier" row if there are more before it. */
	private void renderTurnsFrom(int from)
	{
		// Start on a message the player sent, so a reply is never shown without its question
		while (from > 0 && !"user".equals(role(turns.get(from))))
		{
			from--;
		}
		renderedFrom = from;
		list.removeAll();
		if (from > 0)
		{
			JLabel earlier = note("Show earlier messages (" + from + ")");
			earlier.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			earlier.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					renderTurnsFrom(Math.max(0, renderedFrom - RESTORE_WINDOW));
					SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
				}
			});
			list.add(ChatComponents.place(earlier, Align.LEFT, 8));
		}
		for (com.google.gson.JsonObject t : turns.subList(from, turns.size()))
		{
			renderSavedTurn(t);
		}
		if (turns.isEmpty())
		{
			list.add(emptyState);
		}
		currentReply = null;
		refresh(from == Math.max(0, turns.size() - RESTORE_WINDOW) || from == 0 && turns.size() <= RESTORE_WINDOW);
	}

	private static String role(com.google.gson.JsonObject t)
	{
		return t.has("role") ? t.get("role").getAsString() : "";
	}

	private void renderSavedTurn(com.google.gson.JsonObject t)
	{
		String role = role(t);
		String text = t.has("text") ? t.get("text").getAsString() : "";
		if ("user".equals(role))
		{
			List<Attachment> sent = new ArrayList<>();
			if (t.has("attachments"))
			{
				t.getAsJsonArray("attachments").forEach(a -> sent.add(Attachment.fromHistory(a.getAsJsonObject())));
			}
			addUser(text, sent);
		}
		else if ("assistant".equals(role))
		{
			trace = t.has("trace") ? ChatTrace.fromJson(t.getAsJsonObject("trace")) : new ChatTrace();
			steps = new ChatTraceViews.StepsLine();
			list.add(ChatComponents.place(steps, Align.FILL, 8));
			currentText.setLength(0);
			currentText.append(text);
			currentReply = null;
			if (!text.isEmpty())
			{
				currentReply = new Bubble(null, Color.WHITE, false, false);
				list.add(ChatComponents.place(currentReply, Align.FILL, 8));
				renderReply();
			}
			finishTurnViews();
		}
		else if ("error".equals(role))
		{
			showError(text);
		}
	}

	/** The whole conversation as markdown, for "Copy conversation". */
	String transcript()
	{
		StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n");
		for (com.google.gson.JsonObject t : turns)
		{
			String role = t.has("role") ? t.get("role").getAsString() : "";
			String text = t.has("text") ? t.get("text").getAsString() : "";
			sb.append("user".equals(role) ? "**You:** " : "error".equals(role) ? "**Error:** " : "**Squire:** ").append(text).append("\n\n");
		}
		return sb.toString().trim();
	}

	/** The conversation so far as plain text, for a new server session (see ChatClient.send). */
	private String recap()
	{
		StringBuilder sb = new StringBuilder();
		for (com.google.gson.JsonObject t : turns.subList(Math.max(0, turns.size() - RECAP_TURNS), turns.size()))
		{
			String role = t.has("role") ? t.get("role").getAsString() : "";
			if ("error".equals(role))
			{
				continue;
			}
			String text = t.has("text") ? t.get("text").getAsString() : "";
			if (text.length() > RECAP_CHARS)
			{
				text = text.substring(0, RECAP_CHARS) + " [...]";
			}
			sb.append("user".equals(role) ? "Player: " : "Squire: ").append(text).append("\n\n");
		}
		return sb.toString().trim();
	}

	private void changed()
	{
		updatedAt = System.currentTimeMillis();
		onChanged.run();
	}

	void newChat()
	{
		if (busy)
		{
			client.cancel();
		}
		client.reset();
		list.removeAll();
		list.add(emptyState);
		currentReply = null;
		turns.clear();
		createdAt = updatedAt = System.currentTimeMillis();
		titled = false;
		title = "New chat";
		onTitle.accept(title);
		setBusy(false);
		refresh(true);
		focusInput();
	}

	/** Start a new conversation with this message (from Home's suggestions or its input). */
	void startWith(String text)
	{
		newChat();
		send(text);
	}

	/** Send a message in this conversation, as if typed into the composer. */
	void sendMessage(String text)
	{
		send(text);
	}

	/** The current conversation's title (its first message, shortened). */
	String title()
	{
		return title;
	}

	/** Whether the current conversation has any messages. */
	boolean hasConversation()
	{
		return titled;
	}

	/** Put text in the composer (previews). */
	void setDraft(String text)
	{
		input.setText(text);
		fitComposer();
	}

	void focusInput()
	{
		SwingUtilities.invokeLater(input::requestFocusInWindow);
	}

	/** Update the picker after the model list or the saved choice changes. */
	void refreshModelLabel()
	{
		String id = currentModel.get();
		String label = AUTO_MODEL.equals(id) ? "Auto" : models.find(id).label;
		Runnable apply = () ->
		{
			modelPicker.setText(label.length() > 14 ? label.substring(0, 13) + "…" : label);
			modelPicker.setToolTipText("Model: " + label + " (click to change)");
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			apply.run();
		}
		else
		{
			SwingUtilities.invokeLater(apply);
		}
	}

	// ---- Building the UI

	/** Starters for an empty chat: what the player is doing right now first, then general ones. */
	void setSuggestions(Supplier<List<Scenarios.Prompt>> suggestions)
	{
		this.suggestions = suggestions;
		refreshSuggestions();
	}

	/** Rebuild the starters (the live ones changed). Only an empty chat shows them. */
	void refreshSuggestions()
	{
		if (!titled)
		{
			fillSuggestions();
			suggestionChips.revalidate();
			suggestionChips.repaint();
		}
	}

	private void fillSuggestions()
	{
		suggestionChips.removeAll();
		List<String[]> prompts = new ArrayList<>();
		for (Scenarios.Prompt p : suggestions.get())
		{
			prompts.add(new String[]{p.title, p.prompt});
		}
		for (String s : SUGGESTIONS)
		{
			if (prompts.size() < 3)
			{
				prompts.add(new String[]{s, s});
			}
		}
		for (String[] p : prompts)
		{
			javax.swing.JComponent chip = ChatComponents.suggestion(p[0], () -> send(p[1]));
			chip.setAlignmentX(Component.CENTER_ALIGNMENT);
			chip.setMaximumSize(new Dimension(180, 60));
			suggestionChips.add(chip);
			suggestionChips.add(Box.createVerticalStrut(6));
		}
	}

	private Supplier<List<Scenarios.Prompt>> suggestions = List::of;
	private final JPanel suggestionChips = new JPanel();

	private JPanel buildEmptyState()
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		JPanel col = new JPanel();
		col.setOpaque(false);
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

		JLabel icon = new JLabel(new ImageIcon(SquireIcon.create(32)));
		icon.setAlignmentX(Component.CENTER_ALIGNMENT);
		col.add(icon);
		col.add(Box.createVerticalStrut(10));

		JLabel title = new JLabel("Ask Squire");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);
		col.add(title);
		col.add(Box.createVerticalStrut(4));

		JLabel subtitle = new JLabel("<html><div style='text-align:center;width:160px'>It can see your stats, bank, gear, quests, diaries, clog and KC.</div></html>");
		subtitle.setForeground(ChatComponents.MUTED);
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
		col.add(subtitle);
		col.add(Box.createVerticalStrut(14));

		suggestionChips.setOpaque(false);
		suggestionChips.setLayout(new BoxLayout(suggestionChips, BoxLayout.Y_AXIS));
		suggestionChips.setAlignmentX(Component.CENTER_ALIGNMENT);
		col.add(suggestionChips);
		fillSuggestions();

		panel.add(col, new GridBagConstraints());
		panel.setBorder(BorderFactory.createEmptyBorder(24, 8, 8, 8));
		return panel;
	}

	private JPanel buildComposer()
	{
		composer.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		input.setRows(1);
		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setOpaque(false);
		input.setForeground(Color.WHITE);
		input.setCaretColor(Color.WHITE);
		input.setFont(FontManager.getRunescapeFont());
		input.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown())
				{
					e.consume();
					sendOrStop();
				}
			}
		});
		// One row until the text would wrap, then the input takes the full width and grows
		input.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				SwingUtilities.invokeLater(ChatView.this::fitComposer);
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				SwingUtilities.invokeLater(ChatView.this::fitComposer);
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
			}
		});
		composer.addComponentListener(new java.awt.event.ComponentAdapter()
		{
			@Override
			public void componentResized(java.awt.event.ComponentEvent e)
			{
				fitComposer();
			}
		});
		// Paste or drop screenshots and files (and long text) as attachments
		AttachmentTransfer transfer = new AttachmentTransfer(input.getTransferHandler());
		input.setTransferHandler(transfer);
		composer.setTransferHandler(transfer);
		attachmentRow.setOpaque(false);
		attachmentRow.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 0));
		attachmentRow.setVisible(false);

		inputScroll.setOpaque(false);
		inputScroll.getViewport().setOpaque(false);
		inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		// Model picker ("Auto ▾") and send button
		modelPicker.setFont(FontManager.getRunescapeFont());
		modelPicker.setForeground(ChatComponents.MUTED);
		modelPicker.setIcon(SvgIcon.load("chevron-down", 16, null));
		modelPicker.setHorizontalTextPosition(JLabel.LEFT);
		modelPicker.setIconTextGap(0);
		modelPicker.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 6));
		modelPicker.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		modelPicker.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showModelMenu();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				modelPicker.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				modelPicker.setForeground(ChatComponents.MUTED);
			}
		});
		sendButton.addActionListener(e -> sendOrStop());

		attachButton.setToolTipText("Attach images or text files (or paste them)");
		attachButton.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
		attachButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		attachButton.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				chooseFiles();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				attachButton.setIcon(SvgIcon.load("paperclip", 16, Color.WHITE));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				attachButton.setIcon(SvgIcon.load("paperclip", 16, null));
			}
		});

		layoutComposer(false);

		// Hidden (not painted, but still laid out) while the floating composer animates into its place
		JPanel wrap = new JPanel(new BorderLayout())
		{
			@Override
			public void paint(Graphics g)
			{
				if (!composerHidden)
				{
					super.paint(g);
				}
			}
		};
		wrap.setOpaque(false);
		wrap.add(composer, BorderLayout.CENTER);
		return wrap;
	}

	private boolean composerHidden;

	/** The composer box, in another component's coordinates (where the floating composer lands). */
	java.awt.Rectangle composerBoundsIn(java.awt.Component other)
	{
		return SwingUtilities.convertRectangle(composer.getParent(), composer.getBounds(), other);
	}

	void setComposerHidden(boolean hidden)
	{
		composerHidden = hidden;
		composer.getParent().repaint();
	}

	/**
	 * Single line: [attach] input [model] [send]. Multiline: the input across the top, the controls in a row
	 * underneath ([attach] [model] on the left, [send] on the right). Attachments sit above either.
	 */
	private void layoutComposer(boolean multi)
	{
		boolean focused = input.isFocusOwner();
		multiline = multi;
		composer.removeAll();
		composer.setLayout(new BorderLayout(4, 0));
		composer.add(attachmentRow, BorderLayout.NORTH);
		if (!multi)
		{
			input.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
			inputScroll.setBorder(BorderFactory.createEmptyBorder(8, 0, 7, 0));
			composer.add(bottom(attachButton), BorderLayout.WEST);
			composer.add(inputScroll, BorderLayout.CENTER);
			JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			controls.setOpaque(false);
			controls.add(modelPicker);
			controls.add(sendButton);
			composer.add(bottom(controls), BorderLayout.EAST);
		}
		else
		{
			input.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
			inputScroll.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
			composer.add(inputScroll, BorderLayout.CENTER);
			JPanel row = new JPanel(new BorderLayout());
			row.setOpaque(false);
			JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			left.setOpaque(false);
			left.add(attachButton);
			left.add(modelPicker);
			row.add(left, BorderLayout.WEST);
			row.add(bottom(sendButton), BorderLayout.EAST);
			composer.add(row, BorderLayout.SOUTH);
		}
		composer.revalidate();
		composer.repaint();
		if (focused)
		{
			input.requestFocusInWindow();
		}
	}

	private static JPanel bottom(java.awt.Component c)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(c, BorderLayout.SOUTH);
		return p;
	}

	/** Switch between the one-line and multiline composer, and size the input to its wrapped text. */
	private void fitComposer()
	{
		int width = composer.getWidth();
		if (width <= 0)
		{
			return;
		}
		Insets box = composer.getInsets();
		String text = input.getText();
		FontMetrics fm = input.getFontMetrics(input.getFont());
		// Room for text on one line: everything but the attach button, the model picker and send
		int oneLine = width - box.left - box.right - attachButton.getPreferredSize().width - modelPicker.getPreferredSize().width
			- sendButton.getPreferredSize().width - 8 - 2 - 2;
		boolean multi = text.indexOf('\n') >= 0 || fm.stringWidth(text) > oneLine;
		if (multi != multiline)
		{
			layoutComposer(multi);
		}

		// Wrapped rows at the input's width in this layout, up to the cap (then it scrolls)
		int inputWidth = multi ? width - box.left - box.right - 8 : oneLine;
		javax.swing.text.View root = input.getUI().getRootView(input);
		root.setSize(Math.max(1, inputWidth), Integer.MAX_VALUE);
		int rows = (int) Math.ceil(root.getPreferredSpan(javax.swing.text.View.Y_AXIS) / fm.getHeight());
		rows = Math.max(1, Math.min(MAX_INPUT_ROWS, rows));
		// Scroll only past the cap; below it the input always fits (this also avoids a stale scrollbar
		// measured at the other layout's width)
		inputScroll.setVerticalScrollBarPolicy(rows >= MAX_INPUT_ROWS
			? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
		if (rows != input.getRows())
		{
			input.setRows(rows);
			revalidate();
		}
	}

	// ---- Attachments

	private void chooseFiles()
	{
		JFileChooser chooser = new JFileChooser();
		chooser.setMultiSelectionEnabled(true);
		chooser.setDialogTitle("Attach to message");
		List<String> exts = new ArrayList<>(Attachment.IMAGE_TYPES);
		exts.addAll(Attachment.TEXT_TYPES);
		chooser.setFileFilter(new FileNameExtensionFilter("Images and text files", exts.toArray(new String[0])));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
		{
			addFiles(List.of(chooser.getSelectedFiles()));
		}
		focusInput();
	}

	private void addFiles(List<File> files)
	{
		for (File f : files)
		{
			attachWorker.execute(() ->
			{
				try
				{
					Attachment a = Attachment.fromFile(f);
					if (a != null)
					{
						SwingUtilities.invokeLater(() -> addAttachment(a));
					}
				}
				catch (Exception e)
				{
					// unreadable file: skip it
				}
			});
		}
	}

	private void addImage(BufferedImage image)
	{
		attachWorker.execute(() ->
		{
			try
			{
				Attachment a = Attachment.image(image, null);
				SwingUtilities.invokeLater(() -> addAttachment(a));
			}
			catch (Exception e)
			{
				// couldn't encode it: skip
			}
		});
	}

	void addAttachment(Attachment a)
	{
		if (attachments.size() >= MAX_ATTACHMENTS)
		{
			return;
		}
		attachments.add(a);
		refreshAttachments();
	}

	private void removeAttachment(Attachment a)
	{
		attachments.remove(a);
		refreshAttachments();
		focusInput();
	}

	private void refreshAttachments()
	{
		attachmentRow.removeAll();
		if (!attachments.isEmpty())
		{
			attachmentRow.add(AttachmentViews.strip(attachments, false, this::removeAttachment), BorderLayout.WEST);
		}
		attachmentRow.setVisible(!attachments.isEmpty());
		revalidate();
		repaint();
	}

	/**
	 * The input's paste/drop handling: files and images become attachments, long text becomes a text
	 * attachment, and everything else goes to the text area's own handler.
	 */
	private final class AttachmentTransfer extends TransferHandler
	{
		private final TransferHandler text;

		AttachmentTransfer(TransferHandler text)
		{
			this.text = text;
		}

		@Override
		public boolean canImport(TransferSupport support)
		{
			return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
				|| support.isDataFlavorSupported(DataFlavor.imageFlavor)
				|| (support.getComponent() == input && text.canImport(support))
				|| support.isDataFlavorSupported(DataFlavor.stringFlavor);
		}

		@Override
		@SuppressWarnings("unchecked")
		public boolean importData(TransferSupport support)
		{
			Transferable t = support.getTransferable();
			try
			{
				if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
				{
					addFiles((List<File>) t.getTransferData(DataFlavor.javaFileListFlavor));
					return true;
				}
				String pasted = t.isDataFlavorSupported(DataFlavor.stringFlavor) ? (String) t.getTransferData(DataFlavor.stringFlavor) : null;
				if (pasted != null && !pasted.isBlank())
				{
					if (Attachment.isLongPaste(pasted))
					{
						addAttachment(Attachment.text(pasted, null));
						return true;
					}
					if (support.getComponent() != input)
					{
						input.replaceSelection(pasted);
						return true;
					}
					return text.importData(support);
				}
				if (t.isDataFlavorSupported(DataFlavor.imageFlavor))
				{
					Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
					addImage(toBuffered(img));
					return true;
				}
			}
			catch (Exception e)
			{
				return false;
			}
			return support.getComponent() == input && text.importData(support);
		}

		@Override
		public int getSourceActions(JComponent c)
		{
			return text.getSourceActions(c);
		}

		@Override
		public void exportToClipboard(JComponent comp, Clipboard clip, int action)
		{
			text.exportToClipboard(comp, clip, action);
		}

		@Override
		public void exportAsDrag(JComponent comp, java.awt.event.InputEvent e, int action)
		{
			text.exportAsDrag(comp, e, action);
		}
	}

	private static BufferedImage toBuffered(Image img)
	{
		if (img instanceof BufferedImage)
		{
			return (BufferedImage) img;
		}
		javax.swing.ImageIcon loaded = new javax.swing.ImageIcon(img);
		BufferedImage out = new BufferedImage(loaded.getIconWidth(), loaded.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(loaded.getImage(), 0, 0, null);
		g.dispose();
		return out;
	}

	private void showModelMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		String current = currentModel.get();
		JCheckBoxMenuItem auto = new JCheckBoxMenuItem("Auto (server default)", AUTO_MODEL.equals(current));
		auto.addActionListener(e -> pickModel(AUTO_MODEL));
		menu.add(auto);
		menu.addSeparator();
		models.refresh(o -> refreshModelLabel());
		for (ModelCatalog.Option o : models.options())
		{
			JCheckBoxMenuItem item = new JCheckBoxMenuItem("<html>" + o.label + " <font color='#9a9a9a'>" + o.blurb + "</font></html>", o.id.equals(current));
			item.addActionListener(e -> pickModel(o.id));
			menu.add(item);
		}
		menu.show(modelPicker, 0, -menu.getPreferredSize().height);
	}

	private void pickModel(String id)
	{
		onModelChange.accept(id);
		refreshModelLabel();
	}

	// ---- Conversation

	private void sendOrStop()
	{
		if (busy)
		{
			client.cancel();
			if (steps != null)
			{
				steps.setStatus("Stopping");
			}
			return;
		}
		String text = input.getText().trim();
		if (!text.isEmpty() || !attachments.isEmpty())
		{
			List<Attachment> sending = new ArrayList<>(attachments);
			input.setText("");
			attachments.clear();
			refreshAttachments();
			send(text, sending);
		}
	}

	private void send(String text)
	{
		send(text, List.of());
	}

	private void send(String text, List<Attachment> sending)
	{
		if (busy)
		{
			return;
		}
		if (!titled)
		{
			titled = true;
			String name = (inGame ? "In-game: " : "") + (text.isEmpty() ? sending.get(0).name : text);
			title = name.length() > TITLE_CHARS ? name.substring(0, TITLE_CHARS - 1).trim() + "…" : name;
			onTitle.accept(title);
		}
		String recap = recap();
		com.google.gson.JsonObject turn = new com.google.gson.JsonObject();
		turn.addProperty("role", "user");
		turn.addProperty("text", text);
		turn.addProperty("at", System.currentTimeMillis());
		if (!sending.isEmpty())
		{
			JsonArray saved = new JsonArray();
			sending.forEach(a -> saved.add(a.toHistory()));
			turn.add("attachments", saved);
		}
		turns.add(turn);
		addUser(text, sending);
		beginAssistant();
		changed();

		Map<String, Object> context = contextSupplier.get();
		if (!extraContext.isEmpty())
		{
			context = new java.util.LinkedHashMap<>(context);
			context.putAll(extraContext);
		}
		client.send(text, sending, context, new ChatClient.Listener()
		{
			@Override
			public void onDelta(String delta)
			{
				SwingUtilities.invokeLater(() -> appendAssistant(delta));
			}

			@Override
			public void onBlockCompleted()
			{
				// Separates interim narration ("Let me check your bank…") from the answer
				SwingUtilities.invokeLater(() ->
				{
					blockStarts.add(currentText.length() + 2);
					appendAssistant("\n\n");
				});
			}

			@Override
			public void onToolUse(String description)
			{
				// The Steps panel describes each tool; the typing row mirrors the current one
			}

			@Override
			public void onReasoning(String delta)
			{
				SwingUtilities.invokeLater(() -> traceReasoning(delta));
			}

			@Override
			public void onReasoningDone()
			{
				SwingUtilities.invokeLater(() -> trace.reasoningDone());
			}

			@Override
			public void onActions(JsonArray actions)
			{
				SwingUtilities.invokeLater(() -> traceActions(actions));
			}

			@Override
			public void onActionResult(String callId, JsonElement output, boolean ok)
			{
				SwingUtilities.invokeLater(() -> traceResult(callId, output, ok));
			}

			@Override
			public void onError(String message)
			{
				SwingUtilities.invokeLater(() -> addError(friendlyError(message)));
			}

			@Override
			public void onDone()
			{
				SwingUtilities.invokeLater(() -> endAssistant());
			}
		}, recap.isEmpty() ? null : recap);
	}

	void addUser(String text)
	{
		addUser(text, List.of());
	}

	void addUser(String text, List<Attachment> sent)
	{
		list.remove(emptyState);
		if (!sent.isEmpty())
		{
			list.add(ChatComponents.place(AttachmentViews.strip(sent, true, null), Align.RIGHT, 8));
			if (text.isEmpty())
			{
				refresh(true);
				return;
			}
		}
		Bubble bubble = new Bubble(ChatComponents.USER_BG, Color.WHITE, true, true, Bubble.MESSAGE_PAD).minHeight(Bubble.MESSAGE_MIN_HEIGHT);
		bubble.setHtml(MarkdownLite.escape(text).replace("\n", "<br>"));
		list.add(ChatComponents.place(bubble, Align.RIGHT, sent.isEmpty() ? 8 : 4));
		refresh(true);
	}

	void beginAssistant()
	{
		setBusy(true);
		currentReply = null;
		currentText.setLength(0);
		blockStarts.clear();
		trace = new ChatTrace();
		// The steps line doubles as the working indicator: animated while the agent works, then a summary
		steps = new ChatTraceViews.StepsLine();
		steps.update(trace, true);
		list.add(ChatComponents.place(steps, Align.FILL, 8));
		refresh(true);
	}

	void appendAssistant(String delta)
	{
		if (currentReply == null)
		{
			currentReply = new Bubble(null, Color.WHITE, false, false);
			list.add(ChatComponents.place(currentReply, Align.FILL, 8), list.getComponentZOrder(typing));
		}
		currentText.append(delta);
		typing.setVisible(false);
		renderTimer.restart();
	}

	void showActivity(String activity)
	{
		typing.setActivity(activity);
		typing.setVisible(true);
		// Keep the indicator after any text already streamed
		list.remove(typing);
		list.add(ChatComponents.place(typing, Align.FILL, 8));
		refresh(false);
	}

	void endAssistant()
	{
		renderReply();
		list.remove(typing);
		if (trace != null)
		{
			trace.finish();
			finishTurnViews();
		}
		if (currentReply == null && busy)
		{
			// The turn ended without text (e.g. stopped before answering)
			list.add(ChatComponents.place(note("No reply."), Align.LEFT, 8));
		}
		if (busy && (currentText.length() > 0 || (trace != null && !trace.entries.isEmpty())))
		{
			com.google.gson.JsonObject turn = new com.google.gson.JsonObject();
			turn.addProperty("role", "assistant");
			turn.addProperty("text", currentText.toString().trim());
			turn.addProperty("at", System.currentTimeMillis());
			if (trace != null)
			{
				turn.add("trace", trace.toJson());
			}
			turns.add(turn);
			if (replyListener != null)
			{
				boolean more = trace != null && (!trace.exports.isEmpty() || !trace.sources.isEmpty());
				replyListener.reply(finalBlock(), more);
			}
		}
		setBusy(false);
		refresh(false);
		changed();
	}

	/** The last message block of the reply: the answer itself, without "Let me check..." narration before it. */
	private String finalBlock()
	{
		String all = currentText.toString();
		for (int i = blockStarts.size() - 1; i >= -1; i--)
		{
			int start = i < 0 ? 0 : Math.min(blockStarts.get(i), all.length());
			String block = all.substring(start).trim();
			if (!block.isEmpty())
			{
				return block;
			}
			all = all.substring(0, start);
		}
		return all.trim();
	}

	/** Under a finished reply: the folded steps line (if any), sources and export cards. */
	private void finishTurnViews()
	{
		steps.update(trace, false);
		if (!steps.hasSteps())
		{
			list.remove(steps); // nothing to show behind it
		}
		if (currentReply != null && !trace.sources.isEmpty())
		{
			list.add(ChatComponents.place(new ChatTraceViews.SourcesRow(trace), Align.FILL, 6));
		}
		// Setups, tag tabs and markers the agent made, each ready to copy into its plugin
		for (ExportCards.Export export : trace.exports)
		{
			list.add(ChatComponents.place(ExportCards.row(export, ChatTraceViews.INSET), Align.FILL, 8));
		}
	}

	/** The latest reply's Steps panel (previews). */
	ChatTraceViews.StepsLine lastSteps()
	{
		return steps;
	}

	void traceReasoning(String delta)
	{
		trace.reasoning(delta);
		refreshSteps();
	}

	void traceActions(JsonArray actions)
	{
		trace.actions(actions);
		refreshSteps();
	}

	void traceResult(String callId, JsonElement output, boolean ok)
	{
		trace.result(callId, output, ok);
		refreshSteps();
	}

	private void refreshSteps()
	{
		if (steps != null && trace != null)
		{
			steps.update(trace, busy);
			refresh(false);
		}
	}

	void addError(String message)
	{
		com.google.gson.JsonObject turn = new com.google.gson.JsonObject();
		turn.addProperty("role", "error");
		turn.addProperty("text", message);
		turn.addProperty("at", System.currentTimeMillis());
		turns.add(turn);
		if (replyListener != null)
		{
			replyListener.error(message);
		}
		// A failed request never reaches "done", so settle the working line here
		if (trace != null && steps != null && busy)
		{
			trace.finish();
			steps.update(trace, false);
			if (!steps.hasSteps())
			{
				list.remove(steps);
			}
		}
		showError(message);
		setBusy(false);
		refresh(true);
		changed();
	}

	private void showError(String message)
	{
		list.remove(typing);
		Bubble bubble = new Bubble(ChatComponents.ERROR_BG, new Color(0xFFB4B4), false, true, Bubble.MESSAGE_PAD).minHeight(Bubble.MESSAGE_MIN_HEIGHT);
		bubble.setHtml(MarkdownLite.escape(message));
		list.add(ChatComponents.place(bubble, Align.RIGHT, 8));
	}

	/** Invalidate the whole message list (for timing tests). */
	void relayoutForTest()
	{
		list.invalidate();
		for (java.awt.Component c : list.getComponents())
		{
			c.invalidate();
		}
	}

	/** Render the streamed reply now instead of on the coalescing timer (for timing tests). */
	void flushRender()
	{
		renderTimer.stop();
		renderReply();
	}

	private void renderReply()
	{
		if (currentReply == null)
		{
			return;
		}
		// Wiki names in the reply are links with hover cards. Links elsewhere (news, Reddit, X, other sites)
		// are what the agent is citing, so they join the sources.
		ChatTrace t = trace;
		currentReply.setHtml(MarkdownLite.toHtml(currentText.toString().trim(), t == null ? null : (url, text) ->
		{
			if (WikiCards.titleOf(url) == null)
			{
				t.cite(url, text);
			}
			return 0;
		}));
		refresh(false);
	}

	private void setBusy(boolean value)
	{
		boolean was = busy;
		busy = value;
		if (was != value)
		{
			onChanged.run();
		}
		sendButton.setStop(value);
		typing.setAnimating(value);
	}

	/** Relayout and keep the view pinned to the bottom if the reader was already there. */
	private void refresh(boolean forceBottom)
	{
		javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
		boolean atBottom = forceBottom || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
		list.revalidate();
		list.repaint();
		if (atBottom)
		{
			SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
		}
	}

	private static JLabel note(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ChatComponents.MUTED);
		label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
		return label;
	}

	private static String friendlyError(String message)
	{
		if (message != null && message.contains("model connection is unavailable"))
		{
			return "The server has no model credentials. Add AI_GATEWAY_API_KEY to server/.env.local (or deploy to Vercel) and restart it.";
		}
		if (message != null && message.contains("Free tier users do not have access"))
		{
			return "Your AI Gateway account is on the free tier, which can't use this model. Pick another model, or add credits in Vercel.";
		}
		return message == null || message.isBlank() ? "Something went wrong." : message;
	}

	/** Full-width row between grooved dividers: animated dots plus what Squire is doing. */
	static class TypingRow extends JPanel
	{
		private static final float[][] FRAMES = {{1f, 0.6f, 0.3f}, {0.3f, 1f, 0.6f}, {0.6f, 0.3f, 1f}};
		private final ImageIcon[] dots = new ImageIcon[FRAMES.length];
		private final JLabel dotLabel = new JLabel();
		private final JLabel activity = new JLabel();
		private final Timer timer;
		private int frame;

		TypingRow()
		{
			setOpaque(false);
			setLayout(new BorderLayout(8, 0));
			setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
			for (int i = 0; i < FRAMES.length; i++)
			{
				dots[i] = new ImageIcon(squares(FRAMES[i]));
			}
			dotLabel.setIcon(dots[0]);
			activity.setFont(FontManager.getRunescapeFont());
			activity.setForeground(ChatComponents.MUTED);
			add(dotLabel, BorderLayout.WEST);
			add(activity, BorderLayout.CENTER);
			timer = new Timer(300, e -> dotLabel.setIcon(dots[++frame % dots.length]));
		}

		/** Three 4px accent squares, each at its own opacity (one frame of the animation). */
		private static java.awt.image.BufferedImage squares(float[] opacities)
		{
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(18, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = img.createGraphics();
			for (int i = 0; i < 3 && i < opacities.length; i++)
			{
				g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacities[i]));
				g.setColor(ChatComponents.ACCENT);
				g.fillRect(i * 7, 0, 4, 4);
			}
			g.dispose();
			return img;
		}

		void setActivity(String text)
		{
			activity.setText(text + "...");
		}

		void setAnimating(boolean on)
		{
			if (on)
			{
				timer.start();
			}
			else
			{
				timer.stop();
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(super.getPreferredSize().width, 40);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			ChatComponents.groove(g, 0, getWidth());
			ChatComponents.groove(g, getHeight() - 2, getWidth());
		}
	}

	/** Text area that draws a hint while it's empty. */
	static class PlaceholderTextArea extends JTextArea
	{
		private final String placeholder;

		PlaceholderTextArea(String placeholder)
		{
			this.placeholder = placeholder;
		}

		/** Room for the visible rows plus the padding, so a full view never scrolls. */
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			Dimension d = super.getPreferredScrollableViewportSize();
			Insets in = getInsets();
			d.height = Math.max(1, getRows()) * getRowHeight() + in.top + in.bottom;
			return d;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (getText().isEmpty())
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setColor(ChatComponents.MUTED);
				g2.setFont(getFont());
				FontMetrics fm = g2.getFontMetrics();
				Insets in = getInsets();
				g2.drawString(placeholder, in.left, in.top + fm.getAscent());
				g2.dispose();
			}
		}
	}
}
