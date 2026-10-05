package com.squire;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;

/**
 * Works out what the player is doing on every game tick, for the Activity page, and sends one record per minute.
 * A tick goes to the monster they're fighting, else the skill they're training (one that gained XP in the last few
 * ticks), else "Other" when they're active (questing, banking, running around); idle ticks aren't counted. The minute
 * keeps the count of ticks for each, so time is split accurately (a minute of a boss fight with a walk at the end is
 * mostly the boss), and is labelled by the biggest. It also notes where the player was (the map region they spent most
 * of the minute in) and the XP per skill, so the server can group minutes into runs. Client thread only.
 */
class ActivityTracker
{
	/** A skill that gained XP this many ticks ago or less is what the player is doing (skilling XP comes in bursts). */
	private static final int SKILL_TICKS = 8;
	/** 5 minutes, in client ticks (20ms). */
	private static final int IDLE_CLIENT_TICKS = 15_000;
	/** XP from these without a fight (a cannon, an NPC we couldn't see) is "Combat"; alongside a gathering skill it's that skill. */
	private static final Set<Skill> COMBAT_SKILLS = Set.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.HITPOINTS);
	/** A monster needs at least this many ticks in a minute to name it (one stray click isn't a fight). */
	private static final int FIGHT_TICKS = 5;

	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> lastXpTick = new EnumMap<>(Skill.class);
	private int tickCount;
	private long minute = -1;
	private final Map<String, Integer> labelTicks = new HashMap<>();
	private final Map<String, String> labelCategory = new HashMap<>();
	// Ticks spent fighting with each weapon this minute, for the favourite weapon on the Progress page
	private final Map<String, Integer> weaponTicks = new HashMap<>();
	private final Map<Integer, Integer> regionTicks = new HashMap<>();
	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
	// Grimy herbs in the inventory, to tell a herb run from other Farming (-1 until first seen)
	private int grimyHerbs = -1;
	private boolean pickedHerbs;

	/** Call on every game tick while logged in; returns the finished minute's record when a minute rolls over. */
	Map<String, Object> tick(Client client)
	{
		tickCount++;
		long now = System.currentTimeMillis() / 60_000;
		Map<String, Object> finished = null;
		if (now != minute)
		{
			finished = minute >= 0 ? classify(minute) : null;
			minute = now;
			labelTicks.clear();
			labelCategory.clear();
			weaponTicks.clear();
			regionTicks.clear();
			xp.clear();
			pickedHerbs = false;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return finished;
		}
		Integer region = regionOf(client, local);
		if (region != null)
		{
			regionTicks.merge(region, 1, Integer::sum);
		}

		// This tick: the fight, else the skill being trained, else Other if they're at the keyboard
		Actor target = local.getInteracting();
		if (target instanceof NPC && ((NPC) target).getCombatLevel() > 0 && target.getName() != null)
		{
			count(target.getName(), "combat");
			String weapon = wieldedWeapon(client);
			if (weapon != null)
			{
				weaponTicks.merge(weapon, 1, Integer::sum);
			}
			return finished;
		}
		Skill skill = recentSkill();
		if (skill != null)
		{
			if (COMBAT_SKILLS.contains(skill))
			{
				count("Combat", "combat");
			}
			else
			{
				count(skill.getName(), "skilling");
			}
			return finished;
		}
		if (Math.min(client.getKeyboardIdleTicks(), client.getMouseIdleTicks()) < IDLE_CLIENT_TICKS)
		{
			count("Other", "other");
		}
		return finished;
	}

	private void count(String label, String category)
	{
		labelTicks.merge(label, 1, Integer::sum);
		labelCategory.putIfAbsent(label, category);
	}

	/**
	 * The skill that gained XP most recently, within the last few ticks. A gathering or production skill beats combat
	 * skills gained at the same time (Barbarian fishing gives Strength too, and is fishing).
	 */
	private Skill recentSkill()
	{
		Skill best = null;
		int bestTick = Integer.MIN_VALUE;
		Skill combat = null;
		for (Map.Entry<Skill, Integer> e : lastXpTick.entrySet())
		{
			if (tickCount - e.getValue() > SKILL_TICKS)
			{
				continue;
			}
			if (COMBAT_SKILLS.contains(e.getKey()))
			{
				combat = e.getKey();
				continue;
			}
			if (e.getValue() > bestTick)
			{
				best = e.getKey();
				bestTick = e.getValue();
			}
		}
		return best != null ? best : combat;
	}

	/** The map region the player is in; inside an instance, the region it was copied from (a raid room, a boss arena). */
	private static Integer regionOf(Client client, Player local)
	{
		LocalPoint lp = local.getLocalLocation();
		if (lp == null)
		{
			return null;
		}
		WorldPoint wp = client.getTopLevelWorldView().isInstance() ? WorldPoint.fromLocalInstance(client, lp) : local.getWorldLocation();
		return wp == null ? null : wp.getRegionID();
	}

	/** The weapon in the weapon slot, by name, or null when unarmed. */
	private static String wieldedWeapon(Client client)
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item weapon = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null || weapon.getId() <= 0)
		{
			return null;
		}
		String name = client.getItemDefinition(weapon.getId()).getName();
		return name == null || name.isEmpty() || "null".equals(name) ? null : name;
	}

	void onXp(Skill skill, int total)
	{
		Integer previous = lastXp.put(skill, total);
		if (previous != null && total > previous)
		{
			xp.merge(skill, total - previous, Integer::sum);
			lastXpTick.put(skill, tickCount);
		}
	}

	/** Inventory changes: more grimy herbs than before means herbs were picked (a herb run, not other Farming). */
	void onInventory(Client client, ItemContainer inventory)
	{
		int count = 0;
		for (Item item : inventory.getItems())
		{
			if (item.getId() > 0)
			{
				String name = client.getItemDefinition(item.getId()).getName();
				if (name != null && name.startsWith("Grimy "))
				{
					count += item.getQuantity();
				}
			}
		}
		if (grimyHerbs >= 0 && count > grimyHerbs && tickCount - lastXpTick.getOrDefault(Skill.FARMING, Integer.MIN_VALUE / 2) <= SKILL_TICKS)
		{
			pickedHerbs = true;
		}
		grimyHerbs = count;
	}

	/** Forget XP baselines and the partial minute (e.g. on logout or hopping accounts). */
	void reset()
	{
		lastXp.clear();
		lastXpTick.clear();
		minute = -1;
		labelTicks.clear();
		labelCategory.clear();
		weaponTicks.clear();
		regionTicks.clear();
		xp.clear();
		grimyHerbs = -1;
		pickedHerbs = false;
	}

	/** What this minute looks like so far ("Vorkath", "Woodcutting", "Other"), or null if idle. */
	String current()
	{
		Map<String, Object> m = minute >= 0 ? classify(minute) : null;
		return m == null ? null : (String) m.get("activity");
	}

	private Map<String, Object> classify(long minuteIndex)
	{
		if (labelTicks.isEmpty())
		{
			return null; // idle
		}
		// The label: the biggest share, but a real fight or skill beats Other (a fight with a walk after it is the fight)
		Map.Entry<String, Integer> top = labelTicks.entrySet().stream()
			.filter(e -> !"Other".equals(e.getKey()) && (!"combat".equals(labelCategory.get(e.getKey())) || e.getValue() >= FIGHT_TICKS))
			.max(Map.Entry.comparingByValue())
			.orElse(null);
		String activity = top != null ? top.getKey() : "Other";
		String category = top != null ? labelCategory.get(activity) : "other";

		Map<String, Object> record = new LinkedHashMap<>();
		record.put("ts", minuteIndex * 60_000);
		record.put("activity", activity);
		record.put("category", category);
		record.put("xp", xp.values().stream().mapToInt(Integer::intValue).sum());
		// The minute split by what each tick was, for accurate time (and the parts of a run)
		record.put("ticks", new LinkedHashMap<>(labelTicks));
		Map<String, Integer> skills = new LinkedHashMap<>();
		xp.forEach((s, v) -> skills.put(s.getName(), v));
		if (!skills.isEmpty())
		{
			record.put("skills", skills);
		}
		regionTicks.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(r -> record.put("region", r.getKey()));
		if ("Farming".equals(activity) && pickedHerbs)
		{
			record.put("detail", "herbs");
		}
		if ("combat".equals(category))
		{
			weaponTicks.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(w -> record.put("weapon", w.getKey()));
		}
		return record;
	}
}
