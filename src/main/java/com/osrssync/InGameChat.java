package com.osrssync;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * Talking to RS Buddy from the game's chatbox: "::buddy <question>" or the Ask shortcut's prompt. Nothing is sent
 * to the game: RuneLite keeps "::" commands to itself, and replies are local console lines only this player sees.
 * The questions and replies also live in an "In-game" conversation in the panel, where sources, hover cards and
 * setups to copy are shown in full.
 */
class InGameChat
{
	private static final Color NAME = new Color(0xDC8A00);
	private static final Color MUTED = new Color(0x9A9A9A);
	/** A chatbox answer longer than this many lines is cut short with a pointer to the panel. */
	private static final int MAX_LINES = 5;
	/** Longer paragraphs are split between sentences into lines about this long. */
	private static final int LINE_CHARS = 180;

	private final Client client;
	private final ChatMessageManager chatMessages;
	private final ChatboxPanelManager chatbox;
	private final Supplier<ChatSessions> sessions;

	InGameChat(Client client, ChatMessageManager chatMessages, ChatboxPanelManager chatbox, Supplier<ChatSessions> sessions)
	{
		this.client = client;
		this.chatMessages = chatMessages;
		this.chatbox = chatbox;
		this.sessions = sessions;
	}

	/** The shortcut: open an "Ask RS Buddy" prompt in the chatbox. Client thread. */
	void openPrompt()
	{
		if (client.getGameState() != GameState.LOGGED_IN || chatbox.getCurrentInput() != null)
		{
			return;
		}
		// Don't take over while the player is typing a message
		String typing = client.getVarcStrValue(VarClientID.CHATINPUT);
		if (typing != null && !typing.isEmpty())
		{
			return;
		}
		chatbox.openTextInput("Ask RS Buddy:")
			.onDone((Consumer<String>) this::ask)
			.build();
	}

	/** Ask a question from the chatbox. Any thread. */
	void ask(String question)
	{
		String q = question == null ? "" : question.trim();
		if (q.isEmpty())
		{
			return;
		}
		print(ColorUtil.wrapWithColorTag("You asked RS Buddy: ", MUTED) + Text.escapeJagex(q));
		SwingUtilities.invokeLater(() ->
		{
			ChatSessions s = sessions.get();
			if (s == null)
			{
				return;
			}
			ChatView view = s.inGame(v -> v.setInGame(
				Map.of("replyIn", "game chatbox",
					"replyGuide", "The player asked in the game's chatbox; the answer is shown there as plain text lines. "
						+ "Answer in 1-3 short sentences with no lists, headings or markdown. The full reply is also in their RS Buddy panel."),
				new ChatView.ReplyListener()
				{
					@Override
					public void reply(String markdown, boolean more)
					{
						printReply(markdown, more);
					}

					@Override
					public void error(String message)
					{
						print(ColorUtil.wrapWithColorTag("RS Buddy: ", NAME) + ColorUtil.wrapWithColorTag(Text.escapeJagex(message), MUTED));
					}
				}));
			if (view.isBusy())
			{
				print(ColorUtil.wrapWithColorTag("RS Buddy is still answering your last question.", MUTED));
				return;
			}
			print(ColorUtil.wrapWithColorTag("RS Buddy is thinking...", MUTED));
			view.sendMessage(q);
		});
	}

	private void printReply(String markdown, boolean more)
	{
		List<String> lines = toChatLines(markdown);
		boolean cut = lines.size() > MAX_LINES;
		List<String> shown = cut ? lines.subList(0, MAX_LINES) : lines;
		for (int i = 0; i < shown.size(); i++)
		{
			String line = Text.escapeJagex(shown.get(i));
			print(i == 0 ? ColorUtil.wrapWithColorTag("RS Buddy: ", NAME) + line : line);
		}
		if (shown.isEmpty())
		{
			print(ColorUtil.wrapWithColorTag("RS Buddy: ", NAME) + ColorUtil.wrapWithColorTag("(no reply)", MUTED));
		}
		if (cut || more)
		{
			print(ColorUtil.wrapWithColorTag("Full answer in the RS Buddy panel.", MUTED));
		}
	}

	/** Markdown to plain chat lines: wiki links and links become their text, emphasis goes, one line per paragraph or bullet. */
	static List<String> toChatLines(String markdown)
	{
		String text = MarkdownLite.normalize(markdown)
			.replaceAll("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]", "$2")
			.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1")
			.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "$1")
			.replaceAll("(\\*\\*|__|`)", "")
			.replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])", "$1")
			.replaceAll("(?m)^#{1,6}\\s*", "")
			.replaceAll("(?m)^\\s*[*+]\\s+", "- ");
		List<String> out = new ArrayList<>();
		for (String line : text.split("\\n"))
		{
			String l = line.replaceAll("\\s+", " ").trim();
			if (l.isEmpty() || l.matches("^[-|: ]+$"))
			{
				continue;
			}
			// Group sentences into lines of a readable length (the chatbox wraps each one)
			StringBuilder current = new StringBuilder();
			for (String sentence : l.split("(?<=[.!?])\\s+(?=[A-Z0-9\"'(])"))
			{
				if (current.length() > 0 && current.length() + sentence.length() + 1 > LINE_CHARS)
				{
					out.add(current.toString());
					current.setLength(0);
				}
				current.append(current.length() > 0 ? " " : "").append(sentence);
			}
			if (current.length() > 0)
			{
				out.add(current.toString());
			}
		}
		return out;
	}

	private void print(String formatted)
	{
		// Console lines: shown only in this client, and not "game messages" that other plugins parse
		chatMessages.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}
}
