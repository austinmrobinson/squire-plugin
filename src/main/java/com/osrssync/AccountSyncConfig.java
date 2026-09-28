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
		description = "Ask Squire from the game's chatbox; replies appear there, visible only to you",
		position = 10
	)
	String inGameSection = "inGame";

	/** What the Ask Squire shortcut does. */
	enum AskShortcut
	{
		CHATBOX("Ask in the chatbox"),
		PANEL("Open the Squire panel");

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
		name = "::squire command",
		description = "Type ::squire followed by a question in the chatbox to ask Squire. Nothing is sent to the game; "
			+ "the reply shows in your chatbox and in the Squire panel.",
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
		description = "Opens an \"Ask Squire\" prompt in the chatbox (or the panel, below). Ignored while you're typing a message.",
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
		description = "Ask in the chatbox, or open the Squire panel with the cursor in its chat",
		section = inGameSection,
		position = 13
	)
	default AskShortcut askHotkeyOpens()
	{
		return AskShortcut.CHATBOX;
	}

	/** The public Squire server. Self-hosters set their own in Advanced. */
	String DEFAULT_SERVER = "https://rs-buddy.vercel.app";

	@ConfigSection(
		name = "Advanced",
		description = "For running your own Squire server",
		position = 90,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "enabled",
		name = "Squire is on",
		description = "Set when you turn Squire on from its Home page; nothing is sent before that",
		hidden = true
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "gatewayKey",
		name = "Your AI Gateway key",
		description = "Optional: your own Vercel AI Gateway key for unlimited chat (the free tier has a daily limit). "
			+ "It's sent to the server once, stored encrypted there, and cleared from here.",
		secret = true,
		position = 14,
		section = inGameSection
	)
	default String gatewayKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "endpoint",
		name = "Server URL",
		description = "Leave blank for the public Squire server, or set your own (see the project's README)",
		section = advancedSection,
		position = 91
	)
	default String endpoint()
	{
		return "";
	}

	@ConfigItem(
		keyName = "token",
		name = "Access token",
		description = "Filled in automatically when you turn Squire on. On your own server this can be its INGEST_TOKEN.",
		secret = true,
		section = advancedSection,
		position = 92
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
		description = "Width of the Squire panel; drag its left edge to change",
		hidden = true
	)
	default int panelWidth()
	{
		return BuddySidebar.DEFAULT_WIDTH;
	}

	@ConfigItem(
		keyName = "model",
		name = "Chat model",
		description = "Model Squire uses; pick it from the chat's model menu",
		hidden = true
	)
	default String model()
	{
		return ChatView.AUTO_MODEL;
	}

	@ConfigSection(
		name = "What's synced",
		description = "Choose what Squire syncs. Turning something off also removes what the server already has.",
		position = 20,
		closedByDefault = true
	)
	String syncSection = "sync";

	@ConfigItem(
		keyName = "syncBank",
		name = "Bank",
		description = "Your bank's items and value",
		section = syncSection,
		position = 1
	)
	default boolean syncBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncWorn",
		name = "Inventory and equipment",
		description = "What you're carrying and wearing",
		section = syncSection,
		position = 2
	)
	default boolean syncWorn()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncStorage",
		name = "Other storage",
		description = "Looting bag, rune pouch, seed vault and house storage",
		section = syncSection,
		position = 3
	)
	default boolean syncStorage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncLocation",
		name = "Location and world",
		description = "Where you are, for location-aware answers",
		section = syncSection,
		position = 4
	)
	default boolean syncLocation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncLoot",
		name = "Loot drops",
		description = "Notable drops, for loot history and drop luck",
		section = syncSection,
		position = 5
	)
	default boolean syncLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncActivity",
		name = "What you're doing",
		description = "The monster or skill you're on each minute, for time played",
		section = syncSection,
		position = 6
	)
	default boolean syncActivity()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncClog",
		name = "Collection log",
		description = "Your collection log slots",
		section = syncSection,
		position = 7
	)
	default boolean syncClog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hiddenItems",
		name = "Hidden items",
		description = "Items never synced, comma-separated (e.g. a rare you'd rather keep private)",
		section = syncSection,
		position = 8
	)
	default String hiddenItems()
	{
		return "";
	}
}
