package com.squire;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * Renders the profile avatar: the player's own character, full body, optionally dressed in other gear they own. To
 * dress it, the local player's appearance is swapped for a moment (only on this client, like the Fashionscape
 * plugin), the model is captured once it has loaded, and the real appearance is put straight back. Nothing is sent to
 * the game. Client thread only: call {@link #onGameTick()} every tick.
 */
final class AvatarStudio
{
	// Body parts by the game's kit/equipment index
	private static final int SHIELD = 5, ARMS = 6, HAIR = 8, JAW = 11;
	/** Equipment slots that show on a character (ring and ammo don't). */
	static final int[] VISIBLE_SLOTS = {0, 1, 2, 3, 4, 5, 7, 9, 10};
	/** Body parts gear can hide, whose own look is put back when the new gear doesn't hide them. */
	private static final int[] HIDEABLE = {ARMS, HAIR, JAW, 9, 10};
	/** Full body, a little room above the head and below the feet. */
	private static final PlayerPortrait.Framing FULL_BODY = new PlayerPortrait.Framing(-228f, 6f, Math.toRadians(-22));
	static final int SIZE = 256;
	private static final int MAX_TICKS = 25;

	/** What each wearable item hides (bit = body part), from the game's item definitions (avatar-hides.txt). */
	private static final Map<Integer, Integer> HIDES = loadHides();

	private final Client client;
	/** The player's own look for parts gear can hide (hair, jaw, arms...), as last seen uncovered. */
	private final int[] ownKits = new int[12];
	private Job job;

	private static final class Job
	{
		final Map<Integer, Integer> gear;
		final Consumer<BufferedImage> done;
		int[] original;
		int[] applied;
		int ticks;
		long lastSignature;
		int stable;

		Job(Map<Integer, Integer> gear, Consumer<BufferedImage> done)
		{
			this.gear = gear;
			this.done = done;
		}
	}

	AvatarStudio(Client client)
	{
		this.client = client;
	}

	/** Render the character in this gear (equipment slot to item id; other slots as worn). Replaces a pending render. */
	void render(Map<Integer, Integer> gear, Consumer<BufferedImage> done)
	{
		cancel();
		job = new Job(new HashMap<>(gear), done);
	}

	boolean busy()
	{
		return job != null;
	}

	/** Put the real appearance back if a render was in progress (logout, shutdown). */
	void cancel()
	{
		Job j = job;
		job = null;
		Player p = client.getLocalPlayer();
		if (j != null && j.applied != null && p != null)
		{
			restore(p.getPlayerComposition(), j);
		}
	}

	void onGameTick()
	{
		Player p = client.getLocalPlayer();
		PlayerComposition c = p == null ? null : p.getPlayerComposition();
		if (c == null)
		{
			return;
		}
		Job j = job;
		if (j == null || j.applied == null)
		{
			remember(c.getEquipmentIds());
		}
		if (j == null)
		{
			return;
		}
		j.ticks++;
		if (j.original == null)
		{
			// Start only while standing still, so the capture isn't mid-stride
			if (!PlayerPortrait.isIdle(p))
			{
				if (j.ticks > MAX_TICKS * 4)
				{
					job = null;
				}
				return;
			}
			j.original = c.getEquipmentIds().clone();
			if (!j.gear.isEmpty())
			{
				apply(c, j);
			}
			j.ticks = 0;
			return;
		}
		// Item models load over a tick or two: capture once the model stops changing
		PlayerPortrait.Mesh mesh = PlayerPortrait.isIdle(p) ? PlayerPortrait.capture(client) : null;
		long signature = mesh == null ? 0 : PlayerPortrait.signature(mesh);
		j.stable = mesh != null && signature == j.lastSignature ? j.stable + 1 : 0;
		j.lastSignature = signature;
		boolean ready = mesh != null && j.stable >= 1 && j.ticks >= 2;
		if (!ready && j.ticks < MAX_TICKS)
		{
			return;
		}
		job = null;
		if (j.applied != null)
		{
			restore(c, j);
		}
		if (mesh != null)
		{
			j.done.accept(PlayerPortrait.draw(mesh, SIZE, FULL_BODY));
		}
	}

	/** Note the player's own hair, jaw, arms and so on whenever they're uncovered (a kit, not an item or hidden). */
	private void remember(int[] ids)
	{
		for (int k : HIDEABLE)
		{
			int v = ids[k];
			if (v >= PlayerComposition.KIT_OFFSET && v < PlayerComposition.ITEM_OFFSET)
			{
				ownKits[k] = v;
			}
		}
	}

	/** Dress the character: the chosen items, then hide or show body parts to match what the new gear covers. */
	static int[] dress(int[] original, Map<Integer, Integer> gear, int[] ownKits)
	{
		int[] ids = original.clone();
		gear.forEach((slot, item) -> ids[slot] = item + PlayerComposition.ITEM_OFFSET);
		int hidden = 0;
		for (int s : VISIBLE_SLOTS)
		{
			if (ids[s] >= PlayerComposition.ITEM_OFFSET)
			{
				hidden |= HIDES.getOrDefault(ids[s] - PlayerComposition.ITEM_OFFSET, 0);
			}
		}
		// A two-handed weapon hides the shield slot
		if ((hidden & (1 << SHIELD)) != 0 && !gear.containsKey(SHIELD))
		{
			ids[SHIELD] = 0;
		}
		for (int k : HIDEABLE)
		{
			if (ids[k] >= PlayerComposition.ITEM_OFFSET)
			{
				continue;
			}
			if ((hidden & (1 << k)) != 0)
			{
				ids[k] = 0;
			}
			else if (ids[k] == 0 && ownKits[k] != 0)
			{
				ids[k] = ownKits[k];
			}
		}
		return ids;
	}

	private void apply(PlayerComposition c, Job j)
	{
		int[] ids = c.getEquipmentIds();
		int[] dressed = dress(j.original, j.gear, ownKits);
		System.arraycopy(dressed, 0, ids, 0, Math.min(ids.length, dressed.length));
		j.applied = ids.clone();
		c.setHash();
	}

	/** Put the real appearance back, unless the game has already replaced it (they changed gear meanwhile). */
	private static void restore(PlayerComposition c, Job j)
	{
		if (c == null)
		{
			return;
		}
		int[] ids = c.getEquipmentIds();
		if (Arrays.equals(ids, j.applied))
		{
			System.arraycopy(j.original, 0, ids, 0, Math.min(ids.length, j.original.length));
			c.setHash();
		}
	}

	private static Map<Integer, Integer> loadHides()
	{
		Map<Integer, Integer> out = new HashMap<>();
		try (InputStream in = AvatarStudio.class.getResourceAsStream("avatar-hides.txt"))
		{
			if (in == null)
			{
				return out;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line = r.readLine(); line != null; line = r.readLine())
			{
				int colon = line.indexOf(':');
				if (colon > 0 && !line.startsWith("#"))
				{
					out.put(Integer.parseInt(line.substring(0, colon)), Integer.parseInt(line.substring(colon + 1).trim()));
				}
			}
		}
		catch (java.io.IOException | NumberFormatException e)
		{
			// Without the table, gear still shows; hair or arms may poke through
		}
		return out;
	}
}
