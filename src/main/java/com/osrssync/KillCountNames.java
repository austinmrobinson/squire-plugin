package com.osrssync;

import java.util.HashMap;
import java.util.Map;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;

/**
 * Chat messages, the Chat Commands plugin and the hiscores all spell activity names slightly differently
 * ("Kree'arra" vs "Kree'Arra", "Corrupted Gauntlet" vs "The Corrupted Gauntlet"). Map them all to the hiscore name
 * so the same boss lands in one row.
 */
final class KillCountNames
{
	private static final Map<String, String> CANONICAL = new HashMap<>();

	static
	{
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() != HiscoreSkillType.SKILL)
			{
				CANONICAL.put(key(skill.getName()), skill.getName());
			}
		}
	}

	// Hiscore rows that are ranks or totals rather than counts
	private static final java.util.regex.Pattern NOT_A_KILL_COUNT = java.util.regex.Pattern.compile(
		"(^overall$|rank$|^collections logged$|league points|bounty hunter|deadman)", java.util.regex.Pattern.CASE_INSENSITIVE);

	private static final Map<String, String> ALIASES = Map.of("riftsclosed", "Guardians of the Rift");

	static boolean isKillCount(String name)
	{
		return !NOT_A_KILL_COUNT.matcher(name).find();
	}

	static String canonicalize(String name)
	{
		String alias = ALIASES.get(key(name));
		if (alias != null)
		{
			return alias;
		}
		String canonical = CANONICAL.get(key(name));
		if (canonical != null)
		{
			return canonical;
		}
		// Unknown to the hiscores (e.g. Chat Commands stores lowercase keys): title-case it
		StringBuilder out = new StringBuilder(name.length());
		boolean upper = true;
		for (char c : name.toCharArray())
		{
			out.append(upper ? Character.toUpperCase(c) : c);
			upper = c == ' ' || c == '(' || c == '-';
		}
		return out.toString();
	}

	private static String key(String name)
	{
		String k = name.toLowerCase();
		if (k.startsWith("the "))
		{
			k = k.substring(4);
		}
		return k.replaceAll("[^a-z0-9]", "");
	}

	private KillCountNames()
	{
	}
}
