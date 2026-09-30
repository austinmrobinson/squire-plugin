package com.squire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;

/**
 * Observes a stretch of play for Squire to review afterwards: damage taken (from what, with which protection prayer
 * up), damage dealt, attacks by weapon, supplies used, kills, deaths, HP and time. It only counts; it never shows
 * anything during play (Jagex's rules forbid live boss coaching), and the summary is uploaded when it stops.
 * All methods run on the client thread.
 */
class SessionRecorder
{
	private static final int MAX_SECONDS = 60 * 60;
	private static final int MAX_HITS = 400;
	/** Ticks inside an instance (about 30 seconds) before leaving it counts as the run ending. */
	private static final int MIN_INSTANCE_TICKS = 50;
	private static final Pattern DOSE = Pattern.compile("\\s*\\(\\d\\)$");
	private static final Pattern FOOD = Pattern.compile("(?i)^(shark|paddlefish|manta ray|anglerfish|dark crab|sea turtle|tuna potato|"
		+ "cooked karambwan|karambwan|monkfish|lobster|swordfish|pineapple pizza|.*pie|.*pizza|cooked .*|.*potato.*|crystal paddlefish|"
		+ "corrupted paddlefish|blighted .*|purple sweets|saradomin brew|.*brew|jug of wine|cake|chocolate cake|.* fish)$");

	private final Client client;
	private final IntFunction<String> itemName;
	private final Consumer<Map<String, Object>> onFinished;

	private String label;
	private long startedAt;
	private int ticks;
	private final List<Map<String, Object>> areas = new ArrayList<>();
	private final List<Map<String, Object>> kills = new ArrayList<>();
	private final List<Map<String, Object>> hits = new ArrayList<>();
	private final Map<String, Integer> takenBySource = new HashMap<>();
	private final Map<String, Integer> dealtByTarget = new HashMap<>();
	private final Map<String, Integer> attacksByWeapon = new HashMap<>();
	private final Map<String, Integer> supplies = new HashMap<>();
	private final Map<Skill, Integer> startXp = new HashMap<>();
	private final Map<Integer, Integer> lastInventory = new HashMap<>();
	private int taken, whileProtected, unprotected, dealt, hitCount, zeros, maxHit, attacks, deaths, prayerSwitches, gearSwitches;
	private int hpMin = Integer.MAX_VALUE, hpStart = -1, hpEnd = -1;
	private long hpSum;
	private int lastRegion = -1, lastWeapon = -2, instanceTicks;
	private String lastPrayer;

	SessionRecorder(Client client, IntFunction<String> itemName, Consumer<Map<String, Object>> onFinished)
	{
		this.client = client;
		this.itemName = itemName;
		this.onFinished = onFinished;
	}

	boolean recording()
	{
		return label != null;
	}

	String label()
	{
		return label;
	}

	long startedAt()
	{
		return startedAt;
	}

	void start(String what)
	{
		reset();
		label = what == null || what.isBlank() ? "Session" : what.trim();
		startedAt = System.currentTimeMillis();
		for (Skill s : Skill.values())
		{
			startXp.put(s, client.getSkillExperience(s));
		}
		hpStart = client.getBoostedSkillLevel(Skill.HITPOINTS);
		snapshotInventory();
	}

	/** Stop and hand the summary to onFinished. */
	void stop(String reason)
	{
		if (label == null)
		{
			return;
		}
		Map<String, Object> summary = summary(reason);
		label = null;
		onFinished.accept(summary);
	}

	void cancel()
	{
		label = null;
	}

	// ---- Events

	void onTick()
	{
		if (label == null)
		{
			return;
		}
		ticks++;
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		hpMin = Math.min(hpMin, hp);
		hpSum += hp;
		hpEnd = hp;

		WorldPoint wp = WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		int region = wp.getRegionID();
		if (region != lastRegion)
		{
			lastRegion = region;
			if (areas.size() < 100)
			{
				areas.add(Map.of("at", seconds(), "name", (client.getTopLevelWorldView().isInstance() ? "Instance, region " : "Region ") + region));
			}
		}
		String prayer = protection();
		if (lastPrayer != null && !String.valueOf(prayer).equals(lastPrayer))
		{
			prayerSwitches++;
		}
		lastPrayer = String.valueOf(prayer);
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item weapon = worn == null ? null : worn.getItem(net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx());
		int weaponId = weapon == null ? -1 : weapon.getId();
		if (lastWeapon != -2 && weaponId != lastWeapon)
		{
			gearSwitches++;
		}
		lastWeapon = weaponId;
		// Leaving the instance the fight happened in (the Gauntlet, Vorkath, a raid) ends the run
		boolean instanced = client.getTopLevelWorldView().isInstance();
		if (instanced)
		{
			instanceTicks++;
		}
		else if (instanceTicks >= MIN_INSTANCE_TICKS)
		{
			stop("left the instance");
			return;
		}
		if (seconds() >= MAX_SECONDS)
		{
			stop("an hour passed");
		}
	}

	void onHitsplat(Actor target, int amount, boolean mine)
	{
		if (label == null)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (target == local)
		{
			if (amount <= 0)
			{
				return;
			}
			String source = attackerName(local);
			String prayer = protection();
			taken += amount;
			takenBySource.merge(source, amount, Integer::sum);
			if (prayer != null)
			{
				whileProtected += amount;
			}
			else
			{
				unprotected += amount;
			}
			if (hits.size() < MAX_HITS)
			{
				Map<String, Object> h = new LinkedHashMap<>();
				h.put("at", seconds());
				h.put("source", source);
				h.put("damage", amount);
				h.put("prayer", prayer);
				h.put("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
				hits.add(h);
			}
		}
		else if (mine && target instanceof NPC)
		{
			String name = ((NPC) target).getName();
			dealt += amount;
			hitCount++;
			if (amount == 0)
			{
				zeros++;
			}
			maxHit = Math.max(maxHit, amount);
			dealtByTarget.merge(name == null ? "Unknown" : name, amount, Integer::sum);
		}
	}

	/** The player started an attack animation while fighting an NPC. */
	void onPlayerAnimation(Player player)
	{
		if (label == null || player != client.getLocalPlayer() || player.getAnimation() == -1 || !(player.getInteracting() instanceof NPC))
		{
			return;
		}
		attacks++;
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item weapon = worn == null ? null : worn.getItem(net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx());
		attacksByWeapon.merge(weapon == null ? "Unarmed" : itemName.apply(weapon.getId()), 1, Integer::sum);
	}

	void onDeath(Actor actor)
	{
		if (label == null)
		{
			return;
		}
		if (actor == client.getLocalPlayer())
		{
			deaths++;
			stop("died");
		}
		else if (actor instanceof NPC && dealtByTarget.containsKey(((NPC) actor).getName()))
		{
			if (kills.size() < 500)
			{
				kills.add(Map.of("at", seconds(), "npc", String.valueOf(((NPC) actor).getName())));
			}
		}
	}

	/** Inventory changed: count food eaten and potion doses drunk. */
	void onInventoryChanged(ItemContainer inv)
	{
		if (label == null)
		{
			return;
		}
		Map<Integer, Integer> now = counts(inv);
		for (Map.Entry<Integer, Integer> e : lastInventory.entrySet())
		{
			int before = e.getValue(), after = now.getOrDefault(e.getKey(), 0);
			if (after < before)
			{
				String name = itemName.apply(e.getKey());
				if (DOSE.matcher(name).find())
				{
					supplies.merge(DOSE.matcher(name).replaceAll("") + " (doses)", before - after, Integer::sum);
				}
				else if (FOOD.matcher(name).matches())
				{
					supplies.merge(name, before - after, Integer::sum);
				}
			}
		}
		lastInventory.clear();
		lastInventory.putAll(now);
	}

	// ---- Helpers

	private int seconds()
	{
		return (int) ((System.currentTimeMillis() - startedAt) / 1000);
	}

	/** The protection prayer up right now, or null. */
	private String protection()
	{
		if (client.isPrayerActive(Prayer.PROTECT_FROM_MAGIC))
		{
			return "Protect from Magic";
		}
		if (client.isPrayerActive(Prayer.PROTECT_FROM_MISSILES))
		{
			return "Protect from Missiles";
		}
		if (client.isPrayerActive(Prayer.PROTECT_FROM_MELEE))
		{
			return "Protect from Melee";
		}
		return null;
	}

	/** Who's hitting the player: an NPC targeting them, else whatever they're fighting. */
	private String attackerName(Player local)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null && npc.getInteracting() == local && npc.getName() != null)
			{
				return npc.getName();
			}
		}
		Actor target = local.getInteracting();
		return target != null && target.getName() != null ? target.getName() : "Unknown";
	}

	private void snapshotInventory()
	{
		lastInventory.clear();
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv != null)
		{
			lastInventory.putAll(counts(inv));
		}
	}

	private static Map<Integer, Integer> counts(ItemContainer inv)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (Item i : inv.getItems())
		{
			if (i != null && i.getId() > 0)
			{
				out.merge(i.getId(), Math.max(1, i.getQuantity()), Integer::sum);
			}
		}
		return out;
	}

	private Map<String, Object> summary(String reason)
	{
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("label", label);
		s.put("startedAt", startedAt);
		s.put("durationSeconds", seconds());
		s.put("endedBy", reason);
		s.put("areas", areas);
		s.put("kills", kills);
		s.put("deaths", deaths);
		s.put("damageTaken", Map.of("total", taken, "bySource", takenBySource, "whileProtected", whileProtected, "unprotected", unprotected, "hits", hits));
		s.put("damageDealt", Map.of("total", dealt, "byTarget", dealtByTarget, "hits", hitCount, "zeros", zeros, "maxHit", maxHit));
		s.put("attacks", Map.of("total", attacks, "byWeapon", attacksByWeapon));
		s.put("supplies", supplies);
		Map<String, Object> hp = new LinkedHashMap<>();
		hp.put("min", hpMin == Integer.MAX_VALUE ? null : hpMin);
		hp.put("average", ticks == 0 ? null : Math.round(hpSum / (double) ticks));
		hp.put("start", hpStart < 0 ? null : hpStart);
		hp.put("end", hpEnd < 0 ? null : hpEnd);
		s.put("hitpoints", hp);
		s.put("prayerSwitches", prayerSwitches);
		s.put("gearSwitches", gearSwitches);
		Map<String, Integer> xp = new HashMap<>();
		for (Skill sk : Skill.values())
		{
			int gained = client.getSkillExperience(sk) - startXp.getOrDefault(sk, client.getSkillExperience(sk));
			if (gained > 0)
			{
				xp.put(sk.getName(), gained);
			}
		}
		s.put("xp", xp);
		return s;
	}

	private void reset()
	{
		ticks = 0;
		areas.clear();
		kills.clear();
		hits.clear();
		takenBySource.clear();
		dealtByTarget.clear();
		attacksByWeapon.clear();
		supplies.clear();
		startXp.clear();
		lastInventory.clear();
		taken = whileProtected = unprotected = dealt = hitCount = zeros = maxHit = attacks = deaths = prayerSwitches = gearSwitches = 0;
		hpMin = Integer.MAX_VALUE;
		hpStart = hpEnd = -1;
		hpSum = 0;
		lastRegion = -1;
		lastWeapon = -2;
		instanceTicks = 0;
		lastPrayer = null;
	}
}
