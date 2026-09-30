package com.squire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Reads every combat achievement task and its completion state straight from the game cache and varps,
 * so no interface needs to be open.
 */
final class CombatAchievements
{
	// One enum of task structs per tier, easy through grandmaster
	private static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	private static final String[] TIER_NAMES = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	private static final int TYPE_NAME_ENUM = 3969;
	private static final int MONSTER_NAME_ENUM = 3971;

	private static final int PARAM_TASK_ID = 1306;
	private static final int PARAM_NAME = 1308;
	private static final int PARAM_DESCRIPTION = 1309;
	private static final int PARAM_TYPE = 1311;
	private static final int PARAM_MONSTER = 1312;

	// Task N is bit N%32 of COMPLETED_VARPS[N/32]
	private static final int[] COMPLETED_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};

	static boolean isCompletionVarp(int varpId)
	{
		for (int varp : COMPLETED_VARPS)
		{
			if (varp == varpId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Must run on the client thread.
	 */
	static List<Map<String, Object>> read(Client client)
	{
		List<Map<String, Object>> tasks = new ArrayList<>();
		EnumComposition typeNames = client.getEnum(TYPE_NAME_ENUM);
		EnumComposition monsterNames = client.getEnum(MONSTER_NAME_ENUM);

		for (int tier = 0; tier < TIER_ENUMS.length; tier++)
		{
			for (int structId : client.getEnum(TIER_ENUMS[tier]).getIntVals())
			{
				StructComposition struct = client.getStructComposition(structId);
				int id = struct.getIntValue(PARAM_TASK_ID);
				int varpIndex = id / 32;
				boolean complete = varpIndex < COMPLETED_VARPS.length
					&& (client.getVarpValue(COMPLETED_VARPS[varpIndex]) & (1 << (id % 32))) != 0;

				Map<String, Object> task = new LinkedHashMap<>();
				task.put("id", id);
				task.put("name", struct.getStringValue(PARAM_NAME));
				task.put("description", struct.getStringValue(PARAM_DESCRIPTION));
				task.put("tier", TIER_NAMES[tier]);
				task.put("type", typeNames.getStringValue(struct.getIntValue(PARAM_TYPE)));
				task.put("monster", monsterNames.getStringValue(struct.getIntValue(PARAM_MONSTER)));
				task.put("complete", complete);
				tasks.add(task);
			}
		}
		return tasks;
	}

	private CombatAchievements()
	{
	}
}
