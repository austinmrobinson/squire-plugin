package com.squire;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import net.runelite.api.PlayerComposition;
import org.junit.Test;

/** Dressing the avatar: chosen items go in their slots, and body parts show or hide to match what the gear covers. */
public class AvatarStudioTest
{
	private static final int ITEM = PlayerComposition.ITEM_OFFSET, KIT = PlayerComposition.KIT_OFFSET;
	// Kit/equipment indices
	private static final int HEAD = 0, WEAPON = 3, TORSO = 4, SHIELD = 5, ARMS = 6, HAIR = 8, JAW = 11;

	/** Bare-headed, a shirt with arms, a beard, and a shield. */
	private static int[] wearing()
	{
		int[] ids = new int[12];
		ids[TORSO] = KIT + 18;
		ids[ARMS] = KIT + 26;
		ids[HAIR] = KIT + 0;
		ids[JAW] = KIT + 10;
		ids[SHIELD] = ITEM + 1201;
		return ids;
	}

	@Test
	public void fullHelmHidesHairAndJaw()
	{
		int[] own = new int[12];
		int[] d = AvatarStudio.dress(wearing(), Map.of(HEAD, 1163), own);
		assertEquals(ITEM + 1163, d[HEAD]);
		assertEquals(0, d[HAIR]);
		assertEquals(0, d[JAW]);
	}

	@Test
	public void platebodyHidesArms()
	{
		int[] d = AvatarStudio.dress(wearing(), Map.of(TORSO, 1127), new int[12]);
		assertEquals(ITEM + 1127, d[TORSO]);
		assertEquals(0, d[ARMS]);
	}

	@Test
	public void twoHandedWeaponHidesTheShield()
	{
		int[] d = AvatarStudio.dress(wearing(), Map.of(WEAPON, 20997), new int[12]);
		assertEquals(0, d[SHIELD]);
	}

	@Test
	public void ownHairComesBackUnderAHatThatDoesntCoverIt()
	{
		// Wearing a full helm (hair and jaw hidden), dressed in a hat that only hides hair: the beard is put back
		int[] helmed = wearing();
		helmed[HEAD] = ITEM + 1163;
		helmed[HAIR] = 0;
		helmed[JAW] = 0;
		int[] own = new int[12];
		own[HAIR] = KIT + 0;
		own[JAW] = KIT + 10;
		int[] d = AvatarStudio.dress(helmed, Map.of(HEAD, 10828), own);
		assertEquals(ITEM + 10828, d[HEAD]);
		assertEquals(0, d[HAIR]);
		assertEquals(KIT + 10, d[JAW]);
	}
}
