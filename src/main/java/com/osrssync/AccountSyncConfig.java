package com.osrssync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(AccountSyncConfig.GROUP)
public interface AccountSyncConfig extends Config
{
	String GROUP = "accountsync";

	@ConfigSection(
		name = "In-game chat",
		description = "Ask RS Buddy from the game's chatbox; replies appear there, visible only to you",
		position = 10
	)
	String inGameSection = "inGame";

	/** What the Ask RS Buddy shortcut does. */
	enum AskShortcut
	{
		CHATBOX("Ask in the chatbox"),
		PANEL("Open the RS Buddy panel");

		private final String label;

		AskShortcut(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "chatCommand",
		name = "::buddy command",
		description = "Type ::buddy followed by a question in the chatbox to ask RS Buddy. Nothing is sent to the game; "
			+ "the reply shows in your chatbox and in the RS Buddy panel.",
		section = inGameSection,
		position = 11
	)
	default boolean chatCommand()
	{
		return true;
	}

	@ConfigItem(
		keyName = "askHotkey",
		name = "Ask shortcut",
		description = "Opens an \"Ask RS Buddy\" prompt in the chatbox (or the panel, below). Ignored while you're typing a message.",
		section = inGameSection,
		position = 12
	)
	default Keybind askHotkey()
	{
		return new Keybind(java.awt.event.KeyEvent.VK_B, java.awt.event.InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "askHotkeyOpens",
		name = "Shortcut opens",
		description = "Ask in the chatbox, or open the RS Buddy panel with the cursor in its chat",
		section = inGameSection,
		position = 13
	)
	default AskShortcut askHotkeyOpens()
	{
		return AskShortcut.CHATBOX;
	}

	@ConfigItem(
		keyName = "endpoint",
		name = "Server URL",
		description = "Base URL of your RS Buddy server, e.g. https://my-rs-buddy.vercel.app",
		position = 1
	)
	default String endpoint()
	{
		return "";
	}

	@ConfigItem(
		keyName = "token",
		name = "Ingest token",
		description = "Must match INGEST_TOKEN on the server",
		secret = true,
		position = 2
	)
	default String token()
	{
		return "";
	}

	@Range(min = 1, max = 120)
	@ConfigItem(
		keyName = "syncDelay",
		name = "Sync delay (seconds)",
		description = "How long routine changes (XP drops, inventory) are batched before syncing. Level-ups, loot, kill counts, "
			+ "quests, gear and bank changes sync right away, and so does anything you ask the chat.",
		position = 3
	)
	default int syncDelay()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "writeAccountMd",
		name = "Write local ACCOUNT.md",
		description = "Save the server-generated account summary to ~/.runelite/account-sync/<name>/ACCOUNT.md after each sync",
		position = 4
	)
	default boolean writeAccountMd()
	{
		return true;
	}

	@ConfigItem(
		keyName = "panelWidth",
		name = "Panel width",
		description = "Width of the RS Buddy panel; drag its left edge to change",
		hidden = true
	)
	default int panelWidth()
	{
		return BuddySidebar.DEFAULT_WIDTH;
	}

	@ConfigItem(
		keyName = "model",
		name = "Chat model",
		description = "Model RS Buddy uses; pick it from the chat's model menu",
		hidden = true
	)
	default String model()
	{
		return ChatView.AUTO_MODEL;
	}
}
