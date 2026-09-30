package com.squire;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Conversation starters for what the player is doing right now: just levelled, a big drop, fighting something,
 * a slayer task, training a skill, holding a clue, in the Wilderness, at the bank, partway through a quest.
 * Built from a snapshot of the client (taken on the client thread); the most timely come first. Each prompt
 * says what's going on, and the message carries the live gear and inventory, so the agent can answer in context.
 */
final class Scenarios
{
	/** How long a level-up or drop stays "just happened". */
	static final long RECENT_MS = 15 * 60_000;
	static final int MAX = 3;

	static final class Prompt
	{
		final String title;
		final String prompt;

		Prompt(String title, String prompt)
		{
			this.title = title;
			this.prompt = prompt;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Prompt && ((Prompt) o).title.equals(title);
		}

		@Override
		public int hashCode()
		{
			return title.hashCode();
		}
	}

	/** What the client shows right now. Fields left null/0 don't apply. */
	static final class Snapshot
	{
		long now = System.currentTimeMillis();
		String accountType;
		/** The NPC being fought (combat level > 0), if any. */
		String fighting;
		/** This minute's activity: a monster, a skill name, or "Other". */
		String activity;
		int activityLevel;
		String levelledSkill;
		int levelledTo;
		long levelledAt;
		String lootItem;
		String lootSource;
		long lootValue;
		long lootAt;
		boolean newCollectionLog;
		String slayerTask;
		int slayerLeft;
		String slayerArea;
		String clueTier;
		int wildernessLevel;
		boolean bankOpen;
		String questInProgress;
		/** Current hitpoints as a fraction of the maximum. */
		double hitpoints = 1;
	}

	static List<Prompt> suggest(Snapshot s)
	{
		List<Prompt> out = new ArrayList<>();
		boolean iron = s.accountType != null && s.accountType.toLowerCase(Locale.ROOT).contains("iron");

		if (s.levelledSkill != null && s.now - s.levelledAt < RECENT_MS)
		{
			add(out, "What does " + s.levelledTo + " " + s.levelledSkill + " unlock?",
				"I just got " + s.levelledTo + " " + s.levelledSkill + ". What does it unlock for me now, and what's the best thing to do with it?");
		}
		if (s.lootItem != null && s.now - s.lootAt < RECENT_MS)
		{
			String from = s.lootSource != null ? " from " + s.lootSource : "";
			add(out, (s.newCollectionLog ? "New clog slot: " : "What to do with my ") + s.lootItem + (s.newCollectionLog ? "" : "?"),
				"I just got a " + s.lootItem + from + ". Should I use it, keep it or " + (iron ? "alch it (I'm an ironman)" : "sell it")
					+ "? What does it let me do now?");
		}
		if (s.fighting != null)
		{
			add(out, "Kill " + s.fighting + " faster",
				"I'm fighting " + s.fighting + " right now. Looking at my current gear and inventory, how can I kill it faster or more safely?"
					+ (s.hitpoints < 0.5 ? " I'm taking a lot of damage." : ""));
		}
		if (s.slayerTask != null && s.slayerLeft > 0)
		{
			String area = s.slayerArea != null ? " in " + s.slayerArea : "";
			add(out, "Best way to do my " + s.slayerTask.toLowerCase(Locale.ROOT) + " task",
				"My slayer task is " + s.slayerLeft + " " + s.slayerTask.toLowerCase(Locale.ROOT) + area
					+ ". Where should I do it, what should I bring from my bank, and is it worth skipping or blocking?");
		}
		if (s.activity != null && s.fighting == null && isSkill(s.activity))
		{
			add(out, "Faster " + s.activity + " XP from here",
				"I'm training " + s.activity + (s.activityLevel > 0 ? " (level " + s.activityLevel + ")" : "")
					+ ". Is there a faster or more profitable method for my account right now, and how long to my next milestone?");
		}
		if (s.clueTier != null)
		{
			add(out, "Help with my " + s.clueTier + " clue",
				"I have a " + s.clueTier + " clue scroll. What should I bring for it, and can you help with my current step? (I can attach a screenshot.)");
		}
		if (s.wildernessLevel > 0)
		{
			add(out, "What should I risk out here?",
				"I'm in the Wilderness (level " + s.wildernessLevel + "). With my current gear and inventory, what am I risking, and how do I stay safe here?");
		}
		if (s.bankOpen)
		{
			add(out, iron ? "What can I use from my bank?" : "Clean up my bank",
				iron ? "My bank is open. What's sitting in it that I could be using or processing for XP or supplies?"
					: "My bank is open. What should I sell, alch or use up, and what's worth keeping?");
		}
		if (s.questInProgress != null)
		{
			add(out, "Next step in " + s.questInProgress,
				"I'm partway through " + s.questInProgress + ". What's my next step, and what do I need to bring?");
		}
		return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
	}

	private static void add(List<Prompt> out, String title, String prompt)
	{
		Prompt p = new Prompt(title, prompt);
		if (!out.contains(p))
		{
			out.add(p);
		}
	}

	private static boolean isSkill(String name)
	{
		for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return true;
			}
		}
		return false;
	}

	private Scenarios()
	{
	}
}
