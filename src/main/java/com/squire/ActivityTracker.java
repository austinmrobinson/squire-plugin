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
import net.runelite.api.gameval.InventoryID;

/**
 * Works out what the player spent each minute doing, for the Activity page.
 * A minute goes to the monster they were fighting, else the skill that gained the most XP, else "Other"
 * (questing, banking, running around). Idle minutes aren't counted. Client thread only.
 */
class ActivityTracker
{
	/** Minutes with any NPC fighting at least this many ticks (of 100) count as fighting it. */
	private static final int FIGHT_TICKS = 5;
	/** Minutes with input on at least this many ticks count as "Other" when nothing else happened. */
	private static final int ACTIVE_TICKS = 10;
	/** 5 minutes, in client ticks (20ms). */
	private static final int IDLE_CLIENT_TICKS = 15_000;
	private static final Set<Skill> MELEE_RANGED = Set.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED);

	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private long minute = -1;
	private final Map<String, Integer> npcTicks = new HashMap<>();
	// Ticks spent fighting with each weapon this minute, for the favourite weapon on the Progress page
	private final Map<String, Integer> weaponTicks = new HashMap<>();
	private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
	private int activeTicks;

	/** Call on every game tick while logged in; returns the finished minute's record when a minute rolls over. */
	Map<String, Object> tick(Client client)
	{
		long now = System.currentTimeMillis() / 60_000;
		Map<String, Object> finished = null;
		if (now != minute)
		{
			finished = minute >= 0 ? classify(minute) : null;
			minute = now;
			npcTicks.clear();
			weaponTicks.clear();
			xp.clear();
			activeTicks = 0;
		}

		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();
		if (target instanceof NPC && ((NPC) target).getCombatLevel() > 0 && target.getName() != null)
		{
			npcTicks.merge(target.getName(), 1, Integer::sum);
			String weapon = wieldedWeapon(client);
			if (weapon != null)
			{
				weaponTicks.merge(weapon, 1, Integer::sum);
			}
		}
		if (Math.min(client.getKeyboardIdleTicks(), client.getMouseIdleTicks()) < IDLE_CLIENT_TICKS)
		{
			activeTicks++;
		}
		return finished;
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
		}
	}

	/** Forget XP baselines and the partial minute (e.g. on logout or hopping accounts). */
	void reset()
	{
		lastXp.clear();
		minute = -1;
		npcTicks.clear();
		weaponTicks.clear();
		xp.clear();
		activeTicks = 0;
	}

	/** What this minute looks like so far ("Vorkath", "Woodcutting", "Other"), or null if idle. */
	String current()
	{
		Map<String, Object> m = minute >= 0 ? classify(minute) : null;
		return m == null ? null : (String) m.get("activity");
	}

	private Map<String, Object> classify(long minuteIndex)
	{
		int totalXp = xp.values().stream().mapToInt(Integer::intValue).sum();
		String activity;
		String category;

		Map.Entry<String, Integer> npc = npcTicks.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
		Map.Entry<Skill, Integer> skill = xp.entrySet().stream()
			.filter(e -> e.getKey() != Skill.HITPOINTS)
			.max(Map.Entry.comparingByValue())
			.orElse(null);
		if (npc != null && npc.getValue() >= FIGHT_TICKS)
		{
			activity = npc.getKey();
			category = "combat";
		}
		else if (skill != null && MELEE_RANGED.contains(skill.getKey()))
		{
			activity = "Combat";
			category = "combat";
		}
		else if (skill != null)
		{
			activity = skill.getKey().getName();
			category = "skilling";
		}
		else if (activeTicks >= ACTIVE_TICKS)
		{
			activity = "Other";
			category = "other";
		}
		else
		{
			return null; // idle
		}

		Map<String, Object> record = new LinkedHashMap<>();
		record.put("ts", minuteIndex * 60_000);
		record.put("activity", activity);
		record.put("category", category);
		record.put("xp", totalXp);
		if ("combat".equals(category))
		{
			weaponTicks.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(w -> record.put("weapon", w.getKey()));
		}
		return record;
	}
}
