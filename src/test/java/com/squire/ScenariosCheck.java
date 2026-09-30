package com.squire;

/** Prints the starters for a few situations: ./gradlew scenariosCheck */
public class ScenariosCheck
{
	public static void main(String[] args)
	{
		long now = System.currentTimeMillis();
		Scenarios.Snapshot idle = new Scenarios.Snapshot();
		show("Idle, nothing happening", idle);

		Scenarios.Snapshot boss = new Scenarios.Snapshot();
		boss.fighting = "Vorkath";
		boss.activity = "Vorkath";
		boss.slayerTask = "BLUE DRAGONS";
		boss.slayerLeft = 112;
		boss.hitpoints = 0.3;
		show("Fighting Vorkath, low HP, blue dragon task", boss);

		Scenarios.Snapshot skilling = new Scenarios.Snapshot();
		skilling.activity = "Woodcutting";
		skilling.activityLevel = 72;
		skilling.levelledSkill = "Woodcutting";
		skilling.levelledTo = 72;
		skilling.levelledAt = now - 60_000;
		show("Just hit 72 Woodcutting while chopping", skilling);

		Scenarios.Snapshot drop = new Scenarios.Snapshot();
		drop.accountType = "IRONMAN";
		drop.lootItem = "Tanzanite fang";
		drop.lootSource = "Zulrah";
		drop.lootAt = now - 30_000;
		drop.bankOpen = true;
		show("Ironman, fang drop, bank open", drop);

		Scenarios.Snapshot stale = new Scenarios.Snapshot();
		stale.levelledSkill = "Agility";
		stale.levelledTo = 60;
		stale.levelledAt = now - 3_600_000;
		stale.clueTier = "hard";
		stale.wildernessLevel = 12;
		stale.questInProgress = "Dragon Slayer II";
		show("An hour-old level, hard clue, wilderness 12, in DS2", stale);
	}

	private static void show(String label, Scenarios.Snapshot s)
	{
		System.out.println(label + ":");
		for (Scenarios.Prompt p : Scenarios.suggest(s))
		{
			System.out.println("  - " + p.title + "   // " + p.prompt);
		}
		if (Scenarios.suggest(s).isEmpty())
		{
			System.out.println("  (none: falls back to account-based starters)");
		}
	}
}
