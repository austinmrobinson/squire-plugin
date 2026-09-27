package com.osrssync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.File;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreManager;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@PluginDescriptor(
	name = "RS Buddy",
	description = "Chat with an AI sidekick that knows your account: stats, gear, quests, diaries, clog and KC",
	tags = {"sync", "database", "export", "api", "mcp"}
)
public class AccountSyncPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int MAX_PENDING_EVENTS = 2000;
	private static final int MAX_PENDING_ACTIVITY = 10_000;
	// Wait a couple of ticks after login so varps/varbits are populated before reading quests/diaries
	private static final int PROGRESS_READ_DELAY_TICKS = 3;
	/** At least this long between syncs, so a burst of changes goes in one request (2 ticks, 1.2s). */
	private static final int MIN_SYNC_GAP_TICKS = 2;
	/** Sync the profile (world, levels) at least this often even when nothing else changes (5 minutes). */
	private static final int HEARTBEAT_TICKS = 500;
	/** The server regenerates ACCOUNT.md on request; ask at most this often. */
	private static final long ACCOUNT_MD_EVERY_MS = 60_000;
	/** Home and Activity reload after a sync at most this often, unless something notable happened. */
	private static final long REFRESH_EVERY_MS = 15_000;

	private static final Map<Integer, String> CONTAINERS = Map.of(
		InventoryID.INV, "inventory",
		InventoryID.WORN, "equipment",
		InventoryID.BANK, "bank",
		InventoryID.SEED_VAULT, "seed_vault",
		InventoryID.LOOTING_BAG, "looting_bag",
		InventoryID.INV_GROUP_TEMP, "group_storage"
	);

	private static final String[] DIARY_AREAS = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock", "Western Provinces", "Wilderness"
	};
	private static final String[] DIARY_TIERS = {"easy", "medium", "hard", "elite"};
	// Rows follow DIARY_AREAS, columns follow DIARY_TIERS
	private static final int[][] DIARY_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};

	private static final String[] CA_TIERS = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	private static final int[] CA_TIER_VARBITS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM, VarbitID.CA_TIER_STATUS_HARD,
		VarbitID.CA_TIER_STATUS_ELITE, VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER
	};

	private static final String[] ACCOUNT_TYPES = {
		"normal", "ironman", "ultimate_ironman", "hardcore_ironman", "group_ironman", "hardcore_group_ironman", "unranked_group_ironman"
	};

	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completed |subdued )?(.+?) (?:kill |chest |harvest |lap |completion |success |rescue )?count is: ([\\d,]+)");
	private static final Pattern CLUE_COUNT = Pattern.compile("^You have completed ([\\d,]+) (\\w+) Treasure Trails?");
	private static final Pattern COLLECTION_LOG = Pattern.compile("^New item added to your collection log: (.+)$");
	private static final Pattern COMBAT_TASK = Pattern.compile("^Congratulations, you've completed an? (\\w+) combat task: (.+?)(?: \\(\\d+ points?\\))?\\.?$");
	// Collection log: 7797 builds the interface, 2240 runs its search, and 4100 fires once per obtained item while it does
	private static final int SCRIPT_COLLECTION_LOG_SETUP = 7797;
	private static final int SCRIPT_COLLECTION_LOG_SEARCH = 2240;
	private static final int SCRIPT_COLLECTION_LOG_ITEM = 4100;
	private static final int CLOG_CAPTURE_SETTLE_TICKS = 3;
	private static final int CLOG_CAPTURE_TIMEOUT_TICKS = 10;

	private static final Pattern PET = Pattern.compile("^You (?:have a funny feeling like you|feel something weird sneaking into your backpack)");

	@Inject
	private Client client;

	@Inject
	private AccountSyncConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private HiscoreManager hiscoreManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private net.runelite.client.input.KeyManager keyManager;

	@Inject
	private net.runelite.client.chat.ChatMessageManager chatMessageManager;

	@Inject
	private net.runelite.client.game.chatbox.ChatboxPanelManager chatboxPanelManager;

	private InGameChat inGameChat;

	/** The Ask RS Buddy shortcut: a chatbox prompt, or the panel with the cursor in its chat. */
	private final net.runelite.client.util.HotkeyListener askHotkey = new net.runelite.client.util.HotkeyListener(() -> config.askHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			if (config.askHotkeyOpens() == AccountSyncConfig.AskShortcut.PANEL)
			{
				SwingUtilities.invokeLater(() ->
				{
					clientToolbar.openPanel(navButton);
					sidebar.showPage("chat");
				});
			}
			else if (inGameChat != null)
			{
				clientThread.invoke(inGameChat::openPrompt);
			}
		}
	};

	private SettingsView panel;
	private BuddySidebar sidebar;
	private NavigationButton navButton;
	private ChatSessions sessions;
	/** Conversation starters for what the player is doing now; recomputed every few ticks. */
	private volatile List<Scenarios.Prompt> livePrompts = List.of();
	// What just happened, for the starters (client thread)
	private String lastLevelledSkill;
	private int lastLevelledTo;
	private long lastLevelledAt;
	private String lastLootItem;
	private String lastLootSource;
	private long lastLootValue;
	private long lastLootAt;
	private boolean lastLootClog;
	private String lastQuestStarted;
	/** Drops worth a "what do I do with this?" starter. */
	private static final long NOTABLE_DROP_GP = 250_000;
	// Where the player is right now, sent with each chat message; refreshed on game ticks
	private volatile Map<String, Object> chatContext = Map.of();

	private final Object lock = new Object();

	// Pending state, guarded by lock
	/** Ticks since the oldest unsynced change, or -1 when everything is synced. */
	private int dirtyTicks = -1;
	/** Something worth syncing right away is pending (level-up, loot, gear, bank, progress). */
	private boolean urgentChange;
	private boolean skillsDirty;
	private boolean progressDirty;
	private final Map<String, List<Map<String, Object>>> pendingContainers = new HashMap<>();
	private final Map<String, Integer> pendingKillCounts = new HashMap<>();
	private final List<Map<String, Object>> pendingEvents = new ArrayList<>();
	private final List<Map<String, Object>> pendingActivity = new ArrayList<>();
	private final Map<String, Double> pendingPersonalBests = new HashMap<>();
	private List<Map<String, Object>> pendingCollectionLog;

	// Client-thread only
	private final Map<Skill, Integer> lastLevels = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private volatile long lastAccountMdAt;
	private volatile long lastRefreshAt;
	private final ActivityTracker activityTracker = new ActivityTracker();
	private final Map<String, QuestState> lastQuestStates = new HashMap<>();
	private int ticksSinceSync;
	private int ticksSinceLogin = -1;
	private volatile String accountHash;
	private volatile boolean inFlight;
	private volatile String playerName;
	private String chatheadKey;
	private String pendingPortraitKey;
	private long pendingPortraitSignature;
	/** The account whose saved portrait is showing, so it's only loaded once per login. */
	private String cachedPortraitFor;
	private boolean syncSoon;
	// Collection log capture: requested by the Update button, fulfilled when the log is opened
	private boolean clogRequested;
	private Map<Integer, Integer> clogCapture;
	private int clogCaptureStartTick;
	private int clogLastItemTick;

	@Provides
	AccountSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountSyncConfig.class);
	}

	@Override
	protected void startUp()
	{
		ModelCatalog models = new ModelCatalog(okHttpClient, gson, config::endpoint, config::token);
		// Export cards can open an imported setup in Inventory Setups (its "view" message, like picking it in its panel)
		ExportCards.openSetup = name -> eventBus.post(new net.runelite.client.events.PluginMessage(
			"inventory-setups", "view", new HashMap<>(Map.of("setup", name))));
		// Every conversation gets its own view and connection, so several can answer at once; history is on disk
		sessions = new ChatSessions(() ->
		{
			ChatClient client = new ChatClient(okHttpClient, gson, config::endpoint, config::token, config::model);
			client.setAccount(() -> accountHash);
			ChatView view = new ChatView(client, this::chatContextForMessage, models, config::model,
				id -> configManager.setConfiguration(AccountSyncConfig.GROUP, "model", id));
			view.setSuggestions(() -> livePrompts);
			return view;
		}, new ChatStore(new File(RuneLite.RUNELITE_DIR, "account-sync/chats"), gson));
		sessions.load();
		inGameChat = new InGameChat(client, chatMessageManager, chatboxPanelManager, () -> sessions);
		keyManager.registerKeyListener(askHotkey);
		panel = new SettingsView(this::requestFullUpdate, () -> List.of(
			new String[]{"::buddy command", config.chatCommand() ? "On" : "Off"},
			new String[]{"Ask shortcut", config.askHotkey().toString()},
			new String[]{"Shortcut opens", config.askHotkeyOpens() == AccountSyncConfig.AskShortcut.PANEL ? "Panel" : "Chatbox"}));
		Crest.setIconSource(itemManager::getImage);
		WikiCards.install(okHttpClient);
		AccountApi api = new AccountApi(okHttpClient, gson, config::endpoint, config::token, () -> playerName);
		ProgressView progressView = new ProgressView(skill -> skillIconManager.getSkillImage(skill, true), new WikiImages(okHttpClient));
		ActivityView activityView = new ActivityView(api);
		sidebar = new BuddySidebar(
			actions -> new HomeView(api, progressView, actions, sessions, () -> livePrompts),
			progressView, activityView, sessions, panel, config.panelWidth(),
			w -> configManager.setConfiguration(AccountSyncConfig.GROUP, "panelWidth", w));
		models.refresh(options -> SwingUtilities.invokeLater(() -> sessions.forEachView(ChatView::refreshModelLabel)));
		navButton = NavigationButton.builder()
			.tooltip("RS Buddy")
			.icon(BuddyIcon.create())
			.priority(10)
			.panel(sidebar)
			.build();
		clientToolbar.addNavigation(navButton);
		// Show the last portrait we drew straight away; the live one replaces it once the player is idle in game
		loadCachedPortrait(null);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			onLogin();
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		keyManager.unregisterKeyListener(askHotkey);
		inGameChat = null;
		if (sessions != null)
		{
			sessions.shutdown();
			sessions = null;
		}
		flush();
		resetAccountState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN && ticksSinceLogin < 0)
		{
			onLogin();
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			flush();
			resetAccountState();
		}
	}

	private void onLogin()
	{
		ticksSinceLogin = 0;
		ticksSinceSync = 0;
		synchronized (lock)
		{
			skillsDirty = true;
			progressDirty = true;
		}
		markChanged(true);
	}

	private void resetAccountState()
	{
		ticksSinceLogin = -1;
		accountHash = null;
		playerName = null;
		lastLevels.clear();
		lastXp.clear();
		lastLevelledSkill = null;
		lastLootItem = null;
		lastQuestStarted = null;
		livePrompts = List.of();
		lastQuestStates.clear();
		chatheadKey = null;
		cachedPortraitFor = null;
		pendingPortraitKey = null;
		activityTracker.reset();
		clogRequested = false;
		clogCapture = null;
		syncSoon = false;
		synchronized (lock)
		{
			skillsDirty = false;
			progressDirty = false;
			dirtyTicks = -1;
			urgentChange = false;
			pendingContainers.clear();
			pendingActivity.clear();
			pendingKillCounts.clear();
			pendingEvents.clear();
			pendingPersonalBests.clear();
			pendingCollectionLog = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (ticksSinceLogin < 0)
		{
			return;
		}
		ticksSinceLogin++;
		ticksSinceSync++;

		if (accountHash == null && client.getAccountHash() != -1)
		{
			accountHash = Long.toString(client.getAccountHash());
		}

		finishCollectionLogCaptureIfDone();
		Map<String, Object> minute = activityTracker.tick(client);
		if (minute != null)
		{
			synchronized (lock)
			{
				if (pendingActivity.size() < MAX_PENDING_ACTIVITY)
				{
					pendingActivity.add(minute);
				}
			}
			markChanged(false);
		}
		if (ticksSinceLogin % 5 == 1)
		{
			chatContext = readChatContext();
			updateLivePrompts();
			updateChathead();
		}

		// Changes sync as they happen: notable ones on the next tick, routine ones (XP drops, inventory) batched for
		// the configured delay. A heartbeat keeps the profile fresh when nothing changes.
		int routineTicks = Math.max(1, (int) Math.ceil(config.syncDelay() / 0.6));
		int waited;
		boolean urgent;
		synchronized (lock)
		{
			if (dirtyTicks >= 0)
			{
				dirtyTicks++;
			}
			waited = dirtyTicks;
			urgent = urgentChange;
		}
		boolean due = ticksSinceLogin == PROGRESS_READ_DELAY_TICKS || syncSoon || ticksSinceSync >= HEARTBEAT_TICKS
			|| (waited >= 0 && ticksSinceSync >= MIN_SYNC_GAP_TICKS && (urgent || waited >= routineTicks));
		if (due && ticksSinceLogin >= PROGRESS_READ_DELAY_TICKS && sync())
		{
			ticksSinceSync = 0;
			syncSoon = false;
		}
	}

	/** Note that something changed; urgent changes sync on the next tick, others after the sync delay. */
	private void markChanged(boolean urgent)
	{
		synchronized (lock)
		{
			if (dirtyTicks < 0)
			{
				dirtyTicks = 0;
			}
			urgentChange |= urgent;
		}
	}

	/** The live context for a chat message; also syncs right away so the agent's tools see the latest state. */
	private Map<String, Object> chatContextForMessage()
	{
		clientThread.invokeLater(() -> markChanged(true));
		return chatContext;
	}

	/** "::buddy <question>" in the chatbox asks RS Buddy; "::buddy" alone opens the prompt. Never sent to the game. */
	@Subscribe
	public void onCommandExecuted(net.runelite.api.events.CommandExecuted event)
	{
		if (!config.chatCommand() || inGameChat == null || !"buddy".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		String question = String.join(" ", event.getArguments()).trim();
		if (question.isEmpty())
		{
			inGameChat.openPrompt();
		}
		else
		{
			inGameChat.ask(question);
		}
	}

	/** Inventory Setups announces its setup list; export cards use it to notice an imported setup. */
	@Subscribe
	public void onPluginMessage(net.runelite.client.events.PluginMessage message)
	{
		if ("inventory-setups".equals(message.getNamespace()) && "setups-changed".equals(message.getName()))
		{
			Object setups = message.getData().get("setups");
			if (setups instanceof java.util.Collection)
			{
				List<String> names = new ArrayList<>();
				for (Object o : (java.util.Collection<?>) setups)
				{
					names.add(String.valueOf(o));
				}
				ExportCards.setupsChanged(names);
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int level = event.getLevel();
		activityTracker.onXp(skill, event.getXp());
		// Boosts and drains also fire StatChanged; only XP changes need syncing
		Integer previousXp = lastXp.put(skill, event.getXp());
		boolean xpChanged = previousXp == null || previousXp != event.getXp();
		Integer previous = lastLevels.put(skill, level);
		if (previous != null && level > previous)
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("skill", skill.getName());
			data.put("level", level);
			data.put("xp", event.getXp());
			addEvent("level_up", skill.getName() + " level " + level, data);
			lastLevelledSkill = skill.getName();
			lastLevelledTo = level;
			lastLevelledAt = System.currentTimeMillis();
		}
		if (xpChanged)
		{
			synchronized (lock)
			{
				skillsDirty = true;
			}
			markChanged(false);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		String name = CONTAINERS.get(event.getContainerId());
		if (name == null)
		{
			return;
		}
		List<Map<String, Object>> items = snapshotItems(event.getItemContainer());
		synchronized (lock)
		{
			pendingContainers.put(name, items);
		}
		// Inventory churns while skilling; gear, bank and storage changes matter right away
		markChanged(!"inventory".equals(name));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == VarPlayerID.QP
			|| event.getVarpId() == VarPlayerID.COLLECTION_COUNT
			|| event.getVarpId() == VarPlayerID.COLLECTION_COUNT_MAX
			|| CombatAchievements.isCompletionVarp(event.getVarpId())
			|| event.getVarbitId() == VarbitID.CA_POINTS
			|| event.getVarbitId() == VarbitID.SLAYER_TASKS_COMPLETED)
		{
			synchronized (lock)
			{
				progressDirty = true;
			}
			markChanged(true);
			return;
		}
		for (int[] row : DIARY_VARBITS)
		{
			for (int varbit : row)
			{
				if (event.getVarbitId() == varbit)
				{
					synchronized (lock)
					{
						progressDirty = true;
					}
					markChanged(true);
					return;
				}
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());

		Matcher m = KILL_COUNT.matcher(message);
		if (m.find())
		{
			recordKillCount(m.group(1), parseCount(m.group(2)));
			return;
		}

		m = CLUE_COUNT.matcher(message);
		if (m.find())
		{
			String tier = m.group(2).toLowerCase();
			recordKillCount("Clue scrolls (" + tier + ")", parseCount(m.group(1)));
			return;
		}

		m = COLLECTION_LOG.matcher(message);
		if (m.find())
		{
			addEvent("collection_log", m.group(1), Map.of("item", m.group(1)));
			lastLootItem = m.group(1);
			lastLootSource = null;
			lastLootClog = true;
			lastLootAt = System.currentTimeMillis();
			return;
		}

		m = COMBAT_TASK.matcher(message);
		if (m.find())
		{
			addEvent("combat_achievement", m.group(2), Map.of("tier", m.group(1).toLowerCase(), "task", m.group(2)));
			synchronized (lock)
			{
				progressDirty = true;
			}
			return;
		}

		if (PET.matcher(message).find())
		{
			addEvent("pet", message, Map.of("message", message));
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		long total = 0;
		for (ItemStack stack : event.getItems())
		{
			int id = itemManager.canonicalize(stack.getId());
			ItemComposition comp = itemManager.getItemComposition(id);
			int price = itemManager.getItemPrice(id);
			total += (long) price * stack.getQuantity();

			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", id);
			item.put("name", comp.getMembersName());
			item.put("quantity", stack.getQuantity());
			item.put("gePrice", price);
			items.add(item);
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("source", event.getName());
		data.put("kind", event.getType().name().toLowerCase());
		data.put("combatLevel", event.getCombatLevel());
		data.put("amount", event.getAmount());
		data.put("totalValue", total);
		data.put("items", items);
		addEvent("loot", event.getName() + " (" + total + " gp)", data);
		// The best item in a notable drop becomes a "what do I do with this?" starter
		Map<String, Object> best = null;
		long bestValue = 0;
		for (Map<String, Object> item : items)
		{
			long value = ((Number) item.get("gePrice")).longValue() * ((Number) item.get("quantity")).longValue();
			if (value > bestValue)
			{
				bestValue = value;
				best = item;
			}
		}
		if (best != null && bestValue >= NOTABLE_DROP_GP)
		{
			lastLootItem = String.valueOf(best.get("name"));
			lastLootSource = event.getName();
			lastLootValue = bestValue;
			lastLootClog = false;
			lastLootAt = System.currentTimeMillis();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local)
		{
			return;
		}
		WorldPoint wp = local.getWorldLocation();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("x", wp.getX());
		data.put("y", wp.getY());
		data.put("plane", wp.getPlane());
		data.put("regionId", wp.getRegionID());
		addEvent("death", "Died at region " + wp.getRegionID(), data);
	}

	private void recordKillCount(String name, int count)
	{
		if (!KillCountNames.isKillCount(name))
		{
			return;
		}
		synchronized (lock)
		{
			pendingKillCounts.merge(KillCountNames.canonicalize(name), count, Math::max);
		}
		markChanged(true);
	}

	/**
	 * Update button: re-read everything and backfill kill counts, PBs and (once opened) the collection log.
	 * Called from the Swing thread.
	 */
	private void requestFullUpdate()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			{
				panel.setStatus("Log in first, then press Update.");
				return;
			}
			if (!isConfigured())
			{
				panel.setStatus("Set the server URL and ingest token in the plugin settings first.");
				return;
			}
			synchronized (lock)
			{
				skillsDirty = true;
				progressDirty = true;
			}
			importChatCommandsData();
			lookupHiscores(client.getLocalPlayer().getName());

			clogRequested = true;
			if (client.getWidget(InterfaceID.Collection.UNIVERSE) != null)
			{
				startCollectionLogCapture();
				panel.setStatus("Syncing, including your collection log...");
			}
			else
			{
				panel.setStatus("Syncing... Open your collection log to include it.");
			}
			syncSoon = true;
		});
	}

	/**
	 * RuneLite's Chat Commands plugin remembers every kill count and personal best it has seen.
	 */
	private void importChatCommandsData()
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return;
		}
		for (String key : configManager.getRSProfileConfigurationKeys("killcount", profile, ""))
		{
			String name = stripGroupPrefix(key, "killcount");
			Integer count = parseInt(configManager.getRSProfileConfiguration("killcount", name));
			if (count != null && count > 0)
			{
				recordKillCount(name, count);
			}
		}
		for (String key : configManager.getRSProfileConfigurationKeys("personalbest", profile, ""))
		{
			String name = stripGroupPrefix(key, "personalbest");
			String value = configManager.getRSProfileConfiguration("personalbest", name);
			try
			{
				double seconds = Double.parseDouble(value);
				if (seconds > 0)
				{
					synchronized (lock)
					{
						pendingPersonalBests.put(KillCountNames.canonicalize(name), seconds);
					}
				}
			}
			catch (NumberFormatException | NullPointerException ignored)
			{
			}
		}
	}

	private static String stripGroupPrefix(String key, String group)
	{
		// Keys may come back as "group.rsprofile.<id>.name" or just "name" depending on client version
		int idx = key.lastIndexOf('.');
		return key.startsWith(group + ".") && idx >= 0 ? key.substring(idx + 1) : key;
	}

	private static Integer parseInt(String s)
	{
		try
		{
			return s == null ? null : Integer.parseInt(s.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private void lookupHiscores(String playerName)
	{
		executor.execute(() ->
		{
			try
			{
				// Every account type appears on the main hiscores
				HiscoreResult result = hiscoreManager.lookup(playerName, HiscoreEndpoint.NORMAL);
				if (result == null)
				{
					return;
				}
				int imported = 0;
				for (Map.Entry<HiscoreSkill, net.runelite.client.hiscore.Skill> e : result.getSkills().entrySet())
				{
					if (e.getKey().getType() != HiscoreSkillType.SKILL && e.getValue().getLevel() > 0)
					{
						recordKillCount(e.getKey().getName(), e.getValue().getLevel());
						imported++;
					}
				}
				log.debug("Account Sync: imported {} activity scores from hiscores", imported);
				clientThread.invokeLater(() -> syncSoon = true);
			}
			catch (IOException e)
			{
				log.warn("Account Sync: hiscore lookup failed", e);
				panel.setStatus("Hiscore lookup failed; other data still synced.");
			}
		});
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_COLLECTION_LOG_SETUP && clogRequested && clogCapture == null)
		{
			// Let the interface finish building before triggering its search
			clientThread.invokeLater(this::startCollectionLogCapture);
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (event.getActionParam1() == InterfaceID.Collection.SEARCH_TOGGLE && "Search".equals(event.getOption()))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption("Sync to database")
				.setTarget("")
				.setType(MenuAction.RUNELITE)
				.onClick(e ->
				{
					clogRequested = true;
					startCollectionLogCapture();
				});
		}
	}

	private void startCollectionLogCapture()
	{
		if (client.getWidget(InterfaceID.Collection.UNIVERSE) == null)
		{
			return;
		}
		clogCapture = new HashMap<>();
		clogCaptureStartTick = client.getTickCount();
		clogLastItemTick = clogCaptureStartTick;
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(SCRIPT_COLLECTION_LOG_SEARCH);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (clogCapture == null || event.getScriptId() != SCRIPT_COLLECTION_LOG_ITEM)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		int itemId = (Integer) args[1];
		int quantity = (Integer) args[2];
		clogCapture.put(itemId, quantity);
		clogLastItemTick = client.getTickCount();
	}

	private void finishCollectionLogCaptureIfDone()
	{
		if (clogCapture == null)
		{
			return;
		}
		int now = client.getTickCount();
		if (clogCapture.isEmpty())
		{
			if (now - clogCaptureStartTick > CLOG_CAPTURE_TIMEOUT_TICKS)
			{
				clogCapture = null;
				panel.setStatus("Couldn't read the collection log. Try right-clicking its Search button → Sync to database.");
			}
			return;
		}
		if (now - clogLastItemTick < CLOG_CAPTURE_SETTLE_TICKS)
		{
			return;
		}

		List<Map<String, Object>> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : clogCapture.entrySet())
		{
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", e.getKey());
			item.put("name", itemManager.getItemComposition(e.getKey()).getMembersName());
			item.put("quantity", e.getValue());
			items.add(item);
		}
		synchronized (lock)
		{
			pendingCollectionLog = items;
		}
		clogCapture = null;
		clogRequested = false;
		syncSoon = true;
		client.addChatMessage(ChatMessageType.CONSOLE, "", "Account Sync: captured " + items.size() + " collection log items.", "");
	}

	private static int parseCount(String s)
	{
		return Integer.parseInt(s.replace(",", ""));
	}

	private void addEvent(String type, String summary, Map<String, Object> data)
	{
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", type);
		event.put("ts", System.currentTimeMillis());
		event.put("summary", summary);
		event.put("data", data);
		synchronized (lock)
		{
			if (pendingEvents.size() < MAX_PENDING_EVENTS)
			{
				pendingEvents.add(event);
			}
		}
		markChanged(true);
	}

	private List<Map<String, Object>> snapshotItems(ItemContainer container)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		if (container == null)
		{
			return out;
		}
		Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition comp = itemManager.getItemComposition(item.getId());
			if (comp.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			int id = itemManager.canonicalize(item.getId());
			ItemComposition canonical = id == item.getId() ? comp : itemManager.getItemComposition(id);

			Map<String, Object> row = new LinkedHashMap<>();
			row.put("slot", slot);
			row.put("id", id);
			row.put("name", canonical.getMembersName());
			row.put("quantity", item.getQuantity());
			row.put("gePrice", itemManager.getItemPrice(id));
			row.put("haPrice", canonical.getHaPrice());
			Map<String, Object> equip = equipmentStats(id);
			if (equip != null)
			{
				row.put("equip", equip);
			}
			out.add(row);
		}
		return out;
	}

	/**
	 * Combat stats for equippable items, used server-side to work out best-in-slot gear.
	 */
	private Map<String, Object> equipmentStats(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		if (stats == null || !stats.isEquipable() || stats.getEquipment() == null)
		{
			return null;
		}
		ItemEquipmentStats e = stats.getEquipment();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("slot", e.getSlot());
		m.put("twoHanded", e.isTwoHanded());
		m.put("astab", e.getAstab());
		m.put("aslash", e.getAslash());
		m.put("acrush", e.getAcrush());
		m.put("amagic", e.getAmagic());
		m.put("arange", e.getArange());
		m.put("dstab", e.getDstab());
		m.put("dslash", e.getDslash());
		m.put("dcrush", e.getDcrush());
		m.put("dmagic", e.getDmagic());
		m.put("drange", e.getDrange());
		m.put("str", e.getStr());
		m.put("rstr", e.getRstr());
		m.put("mdmg", e.getMdmg());
		m.put("prayer", e.getPrayer());
		m.put("aspeed", e.getAspeed());
		return m;
	}

	private Map<String, Object> readChatContext()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return Map.of();
		}
		// Inside instances (Vorkath, raids), the real map position rather than the instance copy's
		WorldPoint wp = WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("character", local.getName());
		c.put("world", client.getWorld());
		c.put("location", Map.of("x", wp.getX(), "y", wp.getY(), "plane", wp.getPlane(), "regionId", wp.getRegionID()));
		c.put("hitpoints", client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS));
		c.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER) + "/" + client.getRealSkillLevel(Skill.PRAYER));
		// Boosted or drained stats, e.g. "Strength 118/99"
		List<String> boosted = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			int real = client.getRealSkillLevel(skill), now = client.getBoostedSkillLevel(skill);
			if (now != real && skill != Skill.HITPOINTS && skill != Skill.PRAYER)
			{
				boosted.add(skill.getName() + " " + now + "/" + real);
			}
		}
		if (!boosted.isEmpty())
		{
			c.put("boostedStats", boosted);
		}
		int spellbook = client.getVarbitValue(VarbitID.SPELLBOOK);
		c.put("spellbook", spellbook >= 0 && spellbook < SPELLBOOKS.length ? SPELLBOOKS[spellbook] : "unknown");
		c.put("equipped", itemNames(client.getItemContainer(InventoryID.WORN), false));
		c.put("inventory", itemNames(client.getItemContainer(InventoryID.INV), true));
		String doing = activityTracker.current();
		if (doing != null)
		{
			c.put("doingThisMinute", doing);
		}
		net.runelite.api.Actor target = local.getInteracting();
		if (target != null && target.getName() != null)
		{
			c.put("interactingWith", Text.removeTags(target.getName()));
		}
		List<String> setups = inventorySetupNames();
		if (!setups.isEmpty())
		{
			c.put("inventorySetups", setups);
			ExportCards.knownSetups(setups);
		}
		c.put("note", "Live state from the player's RuneLite client at the time of this message; fresher than the synced data.");
		return c;
	}

	private static final String[] SPELLBOOKS = {"standard", "ancient", "lunar", "arceuus"};

	/** Recompute the conversation starters from what's happening in game; tell the panel if they changed. */
	private void updateLivePrompts()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		Scenarios.Snapshot s = new Scenarios.Snapshot();
		s.accountType = String.valueOf(readProfile().get("accountType"));
		net.runelite.api.Actor target = local.getInteracting();
		if (target instanceof net.runelite.api.NPC && ((net.runelite.api.NPC) target).getCombatLevel() > 0 && target.getName() != null)
		{
			s.fighting = Text.removeTags(target.getName());
		}
		s.activity = activityTracker.current();
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equals(s.activity))
			{
				s.activityLevel = client.getRealSkillLevel(skill);
			}
		}
		s.levelledSkill = lastLevelledSkill;
		s.levelledTo = lastLevelledTo;
		s.levelledAt = lastLevelledAt;
		s.lootItem = lastLootItem;
		s.lootSource = lastLootSource;
		s.lootValue = lastLootValue;
		s.lootAt = lastLootAt;
		s.newCollectionLog = lastLootClog;
		readSlayerTask(s);
		s.clueTier = clueTier();
		if (client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1)
		{
			WorldPoint wp = local.getWorldLocation();
			int y = wp.getY() >= 9920 ? wp.getY() - 9920 : wp.getY() - 3520;
			s.wildernessLevel = Math.max(1, y / 8 + 1);
		}
		net.runelite.api.widgets.Widget bank = client.getWidget(net.runelite.api.gameval.InterfaceID.Bankmain.ITEMS);
		s.bankOpen = bank != null && !bank.isHidden();
		if (lastQuestStarted != null && lastQuestStates.get(lastQuestStarted) == QuestState.IN_PROGRESS)
		{
			s.questInProgress = lastQuestStarted;
		}
		int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
		s.hitpoints = maxHp > 0 ? client.getBoostedSkillLevel(Skill.HITPOINTS) / (double) maxHp : 1;

		List<Scenarios.Prompt> next = Scenarios.suggest(s);
		if (!next.equals(livePrompts))
		{
			livePrompts = next;
			SwingUtilities.invokeLater(() ->
			{
				if (sidebar != null)
				{
					sidebar.home().chatMaybeChanged();
				}
				if (sessions != null)
				{
					sessions.forEachView(ChatView::refreshSuggestions);
				}
			});
		}
	}

	/** The current slayer task and (for Konar) its area, as RuneLite's Slayer plugin reads them. */
	private void readSlayerTask(Scenarios.Snapshot s)
	{
		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (amount <= 0)
		{
			return;
		}
		try
		{
			int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
			int taskRow;
			if (taskId == 98) // bosses
			{
				var rows = client.getDBRowsByValue(net.runelite.api.gameval.DBTableID.SlayerTaskSublist.ID,
					net.runelite.api.gameval.DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
				if (rows.isEmpty())
				{
					return;
				}
				taskRow = (Integer) client.getDBTableField(rows.get(0), net.runelite.api.gameval.DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
			}
			else
			{
				var rows = client.getDBRowsByValue(net.runelite.api.gameval.DBTableID.SlayerTask.ID, net.runelite.api.gameval.DBTableID.SlayerTask.COL_ID, 0, taskId);
				if (rows.isEmpty())
				{
					return;
				}
				taskRow = rows.get(0);
			}
			s.slayerTask = (String) client.getDBTableField(taskRow, net.runelite.api.gameval.DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
			s.slayerLeft = amount;
			int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
			if (areaId > 0)
			{
				var areas = client.getDBRowsByValue(net.runelite.api.gameval.DBTableID.SlayerArea.ID, net.runelite.api.gameval.DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
				if (!areas.isEmpty())
				{
					s.slayerArea = (String) client.getDBTableField(areas.get(0), net.runelite.api.gameval.DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Couldn't read the slayer task", e);
		}
	}

	/** "easy", "hard", ... if a clue scroll is in the inventory. */
	private String clueTier()
	{
		net.runelite.api.ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			return null;
		}
		for (net.runelite.api.Item item : inv.getItems())
		{
			if (item.getId() <= 0)
			{
				continue;
			}
			String name = itemManager.getItemComposition(item.getId()).getMembersName();
			Matcher m = CLUE_ITEM.matcher(name);
			if (m.find())
			{
				return m.group(1).toLowerCase();
			}
		}
		return null;
	}

	private static final Pattern CLUE_ITEM = Pattern.compile("^Clue scroll \\((beginner|easy|medium|hard|elite|master)\\)");

	/** Item names in a container, e.g. ["Abyssal whip", "Shark x12"]; stacks merged when asked. */
	private List<String> itemNames(net.runelite.api.ItemContainer container, boolean merge)
	{
		Map<String, Integer> counts = new LinkedHashMap<>();
		if (container != null)
		{
			for (net.runelite.api.Item item : container.getItems())
			{
				if (item.getId() <= 0 || item.getQuantity() <= 0)
				{
					continue;
				}
				String name = itemManager.getItemComposition(item.getId()).getMembersName();
				counts.merge(merge ? name : name + "#" + counts.size(), item.getQuantity(), Integer::sum);
			}
		}
		List<String> out = new ArrayList<>();
		counts.forEach((name, qty) ->
		{
			String n = merge ? name : name.substring(0, name.lastIndexOf('#'));
			out.add(qty > 1 ? n + " x" + qty : n);
		});
		return out;
	}

	/**
	 * The player's Inventory Setups, if that plugin is on, through its PluginMessage API
	 * (posting is synchronous, so the list is filled on return).
	 */
	private List<String> inventorySetupNames()
	{
		List<String> names = new ArrayList<>();
		Map<String, Object> data = new HashMap<>();
		data.put("setups", names);
		try
		{
			eventBus.post(new net.runelite.client.events.PluginMessage("inventory-setups", "get-setups", data));
		}
		catch (RuntimeException e)
		{
			log.debug("Inventory Setups didn't answer", e);
		}
		return names;
	}

	private Map<String, Object> readProfile()
	{
		Player local = client.getLocalPlayer();
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("name", local != null ? local.getName() : null);
		if (local != null && local.getName() != null)
		{
			playerName = local.getName();
		}
		int type = client.getVarbitValue(VarbitID.IRONMAN);
		p.put("accountType", type >= 0 && type < ACCOUNT_TYPES.length ? ACCOUNT_TYPES[type] : "unknown_" + type);
		p.put("world", client.getWorld());
		p.put("combatLevel", local != null ? local.getCombatLevel() : null);
		p.put("totalLevel", client.getTotalLevel());
		p.put("totalXp", client.getOverallExperience());
		p.put("questPoints", client.getVarpValue(VarPlayerID.QP));
		if (local != null)
		{
			WorldPoint wp = local.getWorldLocation();
			p.put("x", wp.getX());
			p.put("y", wp.getY());
			p.put("plane", wp.getPlane());
			p.put("regionId", wp.getRegionID());
		}
		return p;
	}

	private List<Map<String, Object>> readSkills()
	{
		List<Map<String, Object>> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			if (skill.name().equals("OVERALL"))
			{
				continue;
			}
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("skill", skill.getName());
			s.put("level", client.getRealSkillLevel(skill));
			s.put("boostedLevel", client.getBoostedSkillLevel(skill));
			s.put("xp", client.getSkillExperience(skill));
			skills.add(s);
		}
		return skills;
	}

	private Map<String, Object> readProgress()
	{
		List<Map<String, Object>> quests = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			QuestState state;
			try
			{
				state = quest.getState(client);
			}
			catch (Exception e)
			{
				log.debug("Unable to read state for quest {}", quest.getName(), e);
				continue;
			}
			QuestState previous = lastQuestStates.put(quest.getName(), state);
			if (previous != null && previous != state && state == QuestState.IN_PROGRESS)
			{
				lastQuestStarted = quest.getName();
			}
			if (previous != null && previous != QuestState.FINISHED && state == QuestState.FINISHED)
			{
				addEvent("quest_complete", quest.getName(), Map.of("quest", quest.getName()));
			}
			Map<String, Object> q = new LinkedHashMap<>();
			q.put("quest", quest.getName());
			q.put("state", state.name().toLowerCase());
			quests.add(q);
		}

		List<Map<String, Object>> diaries = new ArrayList<>();
		for (int a = 0; a < DIARY_AREAS.length; a++)
		{
			for (int t = 0; t < DIARY_TIERS.length; t++)
			{
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("area", DIARY_AREAS[a]);
				d.put("tier", DIARY_TIERS[t]);
				d.put("complete", client.getVarbitValue(DIARY_VARBITS[a][t]) > 0);
				diaries.add(d);
			}
		}

		Map<String, Object> combatAchievements = new LinkedHashMap<>();
		combatAchievements.put("points", client.getVarbitValue(VarbitID.CA_POINTS));
		List<String> tiersComplete = new ArrayList<>();
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			if (client.getVarbitValue(CA_TIER_VARBITS[i]) > 0)
			{
				tiersComplete.add(CA_TIERS[i]);
			}
		}
		combatAchievements.put("tiersComplete", tiersComplete);

		Map<String, Object> slayer = new LinkedHashMap<>();
		slayer.put("points", client.getVarbitValue(VarbitID.SLAYER_POINTS));
		slayer.put("streak", client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED));
		slayer.put("taskRemaining", client.getVarpValue(VarPlayerID.SLAYER_COUNT));

		Map<String, Object> progress = new LinkedHashMap<>();
		progress.put("quests", quests);
		progress.put("diaries", diaries);
		progress.put("combatAchievements", combatAchievements);
		progress.put("combatAchievementTasks", CombatAchievements.read(client));
		progress.put("slayer", slayer);
		// The collection log's own counters; zero until the game has sent them (e.g. the log was opened)
		int clogTotal = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		if (clogTotal > 0)
		{
			progress.put("collectionLogCounts", Map.of("obtained", client.getVarpValue(VarPlayerID.COLLECTION_COUNT), "total", clogTotal));
		}
		return progress;
	}

	/**
	 * Builds a payload from pending state and current client data. Must run on the client thread.
	 */
	private boolean sync()
	{
		if (accountHash == null || inFlight || !isConfigured())
		{
			return false;
		}

		boolean readSkills;
		boolean readProgress;
		synchronized (lock)
		{
			readSkills = skillsDirty;
			readProgress = progressDirty;
			skillsDirty = false;
			progressDirty = false;
			dirtyTicks = -1;
			urgentChange = false;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("accountHash", accountHash);
		payload.put("sentAt", System.currentTimeMillis());
		payload.put("wantAccountMd", wantAccountMd());
		payload.put("profile", readProfile());
		if (readSkills)
		{
			payload.put("skills", readSkills());
		}
		if (readProgress)
		{
			payload.putAll(readProgress());
		}
		drainPendingInto(payload);
		send(payload);
		return true;
	}

	/** Whether to have the server regenerate and return ACCOUNT.md with this sync (for the local copy). */
	private boolean wantAccountMd()
	{
		long now = System.currentTimeMillis();
		if (!config.writeAccountMd() || now - lastAccountMdAt < ACCOUNT_MD_EVERY_MS)
		{
			return false;
		}
		lastAccountMdAt = now;
		return true;
	}

	/**
	 * Sends whatever is already queued without touching client state. Safe to call off the client thread.
	 */
	private void flush()
	{
		if (accountHash == null || !isConfigured())
		{
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("accountHash", accountHash);
		payload.put("sentAt", System.currentTimeMillis());
		payload.put("wantAccountMd", false);
		if (drainPendingInto(payload))
		{
			send(payload);
		}
	}

	private boolean drainPendingInto(Map<String, Object> payload)
	{
		synchronized (lock)
		{
			boolean any = false;
			if (!pendingContainers.isEmpty())
			{
				payload.put("containers", new HashMap<>(pendingContainers));
				pendingContainers.clear();
				any = true;
			}
			if (!pendingKillCounts.isEmpty())
			{
				payload.put("killCounts", new HashMap<>(pendingKillCounts));
				pendingKillCounts.clear();
				any = true;
			}
			if (!pendingEvents.isEmpty())
			{
				payload.put("events", new ArrayList<>(pendingEvents));
				pendingEvents.clear();
				any = true;
			}
			if (!pendingPersonalBests.isEmpty())
			{
				payload.put("personalBests", new HashMap<>(pendingPersonalBests));
				pendingPersonalBests.clear();
				any = true;
			}
			if (!pendingActivity.isEmpty())
			{
				payload.put("activity", new ArrayList<>(pendingActivity));
				pendingActivity.clear();
				any = true;
			}
			if (pendingCollectionLog != null)
			{
				payload.put("collectionLog", pendingCollectionLog);
				pendingCollectionLog = null;
				any = true;
			}
			return any;
		}
	}

	@SuppressWarnings("unchecked")
	private void requeue(Map<String, Object> payload)
	{
		markChanged(false);
		synchronized (lock)
		{
			if (payload.containsKey("skills"))
			{
				skillsDirty = true;
			}
			if (payload.containsKey("quests"))
			{
				progressDirty = true;
			}
			Map<String, List<Map<String, Object>>> containers = (Map<String, List<Map<String, Object>>>) payload.get("containers");
			if (containers != null)
			{
				// Anything captured since is newer, so only restore what hasn't been replaced
				containers.forEach(pendingContainers::putIfAbsent);
			}
			Map<String, Integer> kcs = (Map<String, Integer>) payload.get("killCounts");
			if (kcs != null)
			{
				kcs.forEach((k, v) -> pendingKillCounts.merge(k, v, Math::max));
			}
			Map<String, Double> pbs = (Map<String, Double>) payload.get("personalBests");
			if (pbs != null)
			{
				pbs.forEach(pendingPersonalBests::putIfAbsent);
			}
			List<Map<String, Object>> clog = (List<Map<String, Object>>) payload.get("collectionLog");
			if (clog != null && pendingCollectionLog == null)
			{
				pendingCollectionLog = clog;
			}
			List<Map<String, Object>> activity = (List<Map<String, Object>>) payload.get("activity");
			if (activity != null)
			{
				List<Map<String, Object>> merged = new ArrayList<>(activity);
				merged.addAll(pendingActivity);
				pendingActivity.clear();
				pendingActivity.addAll(merged.subList(0, Math.min(merged.size(), MAX_PENDING_ACTIVITY)));
			}
			List<Map<String, Object>> events = (List<Map<String, Object>>) payload.get("events");
			if (events != null)
			{
				List<Map<String, Object>> merged = new ArrayList<>(events);
				merged.addAll(pendingEvents);
				pendingEvents.clear();
				pendingEvents.addAll(merged.subList(0, Math.min(merged.size(), MAX_PENDING_EVENTS)));
			}
		}
	}

	/**
	 * Re-draw the Home portrait when the player's look changes (gear, colours). Waits until they're standing still,
	 * so the portrait always uses the idle pose.
	 */
	private void updateChathead()
	{
		Player player = client.getLocalPlayer();
		String name = player == null ? null : player.getName();
		if (name != null && !name.equals(cachedPortraitFor))
		{
			cachedPortraitFor = name;
			loadCachedPortrait(name);
		}
		String key = PlayerPortrait.appearanceKey(player);
		if (key == null || key.equals(chatheadKey) || !PlayerPortrait.isIdle(player))
		{
			return;
		}
		PlayerPortrait.Mesh mesh = PlayerPortrait.capture(client);
		if (mesh == null)
		{
			return;
		}
		// Only draw once the model is the same as at the last check (items finished loading)
		long signature = PlayerPortrait.signature(mesh);
		if (!key.equals(pendingPortraitKey) || signature != pendingPortraitSignature)
		{
			pendingPortraitKey = key;
			pendingPortraitSignature = signature;
			return;
		}
		chatheadKey = key;
		try
		{
			java.awt.image.BufferedImage image = PlayerPortrait.draw(mesh, 128, PlayerPortrait.DEFAULT_FRAMING);
			javax.swing.SwingUtilities.invokeLater(() -> sidebar.home().setChathead(image));
			if (name != null)
			{
				Path dir = accountDir(name);
				Files.createDirectories(dir);
				javax.imageio.ImageIO.write(image, "png", dir.resolve("portrait.png").toFile());
				PlayerPortrait.dump(mesh, dir.resolve("portrait-model.json").toFile());
			}
		}
		catch (Exception e)
		{
			log.warn("Couldn't draw the portrait", e);
		}
	}

	private static Path accountDir(String name)
	{
		return new File(RuneLite.RUNELITE_DIR, "account-sync").toPath().resolve(name.replaceAll("[^A-Za-z0-9 _-]", "_"));
	}

	/** Show a portrait saved by an earlier session: this account's, or with no name the most recent one. */
	private void loadCachedPortrait(String name)
	{
		try
		{
			Path file = null;
			if (name != null)
			{
				file = accountDir(name).resolve("portrait.png");
			}
			else
			{
				Path root = new File(RuneLite.RUNELITE_DIR, "account-sync").toPath();
				if (Files.isDirectory(root))
				{
					try (java.util.stream.Stream<Path> dirs = Files.list(root))
					{
						file = dirs.map(d -> d.resolve("portrait.png"))
							.filter(Files::isRegularFile)
							.max(java.util.Comparator.comparingLong(f -> f.toFile().lastModified()))
							.orElse(null);
					}
				}
			}
			if (file == null || !Files.isRegularFile(file))
			{
				return;
			}
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
			if (image != null)
			{
				javax.swing.SwingUtilities.invokeLater(() -> sidebar.home().setChathead(image));
			}
		}
		catch (Exception e)
		{
			log.debug("No saved portrait to show", e);
		}
	}

	private void writeAccountMd(ResponseBody body)
	{
		String name = playerName;
		if (!config.writeAccountMd() || body == null || name == null)
		{
			return;
		}
		try
		{
			JsonElement md = gson.fromJson(body.string(), JsonObject.class).get("accountMd");
			if (md == null || md.isJsonNull())
			{
				return;
			}
			Path dir = new File(RuneLite.RUNELITE_DIR, "account-sync").toPath().resolve(name.replaceAll("[^A-Za-z0-9 _-]", "_"));
			Files.createDirectories(dir);
			// Write then rename so readers never see a half-written file
			Path tmp = dir.resolve("ACCOUNT.md.tmp");
			Files.write(tmp, md.getAsString().getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, dir.resolve("ACCOUNT.md"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Exception e)
		{
			log.warn("Account Sync: couldn't write ACCOUNT.md", e);
		}
	}

	private static String summarize(Map<String, Object> payload)
	{
		StringBuilder sb = new StringBuilder();
		if (payload.containsKey("collectionLog"))
		{
			sb.append("<li>Collection log: ").append(((List<?>) payload.get("collectionLog")).size()).append(" items</li>");
		}
		if (payload.containsKey("combatAchievementTasks"))
		{
			long done = ((List<?>) payload.get("combatAchievementTasks")).stream()
				.filter(t -> Boolean.TRUE.equals(((Map<?, ?>) t).get("complete"))).count();
			sb.append("<li>Combat achievements: ").append(done).append(" tasks done</li>");
		}
		if (payload.containsKey("killCounts"))
		{
			sb.append("<li>Kill counts: ").append(((Map<?, ?>) payload.get("killCounts")).size()).append("</li>");
		}
		return sb.length() == 0 ? "" : "<ul>" + sb + "</ul>";
	}

	private boolean isConfigured()
	{
		return !config.endpoint().isBlank() && !config.token().isBlank();
	}

	private void send(Map<String, Object> payload)
	{
		HttpUrl base = HttpUrl.parse(config.endpoint().trim());
		if (base == null)
		{
			log.warn("Account Sync: invalid server URL {}", config.endpoint());
			return;
		}
		HttpUrl url = base.newBuilder().addPathSegments("api/ingest").build();
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + config.token().trim())
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		inFlight = true;
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight = false;
				log.warn("Account Sync: failed to reach server", e);
				panel.setStatus("Couldn't reach the server; will retry.");
				requeue(payload);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.isSuccessful())
					{
						writeAccountMd(response.body());
						panel.setStatus("Last synced " + java.time.LocalTime.now().withNano(0) + summarize(payload));
						// Syncs are frequent now; reload the pages when something notable changed, else now and then
						long now = System.currentTimeMillis();
						boolean notable = payload.containsKey("events") || payload.containsKey("killCounts") || payload.containsKey("quests");
						if (notable || now - lastRefreshAt >= REFRESH_EVERY_MS)
						{
							lastRefreshAt = now;
							sidebar.refreshData();
						}
					}
					else
					{
						log.warn("Account Sync: server returned {}", response.code());
						panel.setStatus("Server returned " + response.code() + (response.code() == 401 ? " (check your ingest token)" : ""));
						if (response.code() >= 500)
						{
							requeue(payload);
						}
					}
				}
				finally
				{
					inFlight = false;
				}
			}
		});
	}
}
