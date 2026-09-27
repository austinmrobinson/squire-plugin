package com.osrssync;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * The player's portrait for Home: their actual in-game model (the same one the game draws, with every item,
 * colour and texture), chest up, at a three-quarter angle. The approach follows RuneProfile's renderer: take
 * {@code player.getModel()} while idle, map textured faces through their texture triangles, nudge faces by
 * render priority, and draw see-through faces last. Client thread only.
 */
final class PlayerPortrait
{
	/** Chest-up framing in model units (the model stands on y = 0; up is negative y). */
	static float CROP_TOP = -206f;
	static float CROP_BOTTOM = -142f;
	/** Turned a little so the face reads, like the game's own character views. */
	private static final double YAW = Math.toRadians(-22);
	/** How far each step of face render priority pulls a face toward the viewer, in model units. */
	private static final float PRIORITY_STEP = 3f;
	private static final int SUPERSAMPLE = 3;

	/** Something that changes when the portrait would look different (gear, colours, gender). */
	static String appearanceKey(Player player)
	{
		PlayerComposition c = player == null ? null : player.getPlayerComposition();
		return c == null ? null : Arrays.toString(c.getEquipmentIds()) + Arrays.toString(c.getColors()) + c.getGender();
	}

	/** True when the player is standing still, so the portrait isn't caught mid-stride or mid-swing. */
	static boolean isIdle(Player player)
	{
		return player != null && player.getAnimation() == -1 && player.getPoseAnimation() == player.getIdlePoseAnimation();
	}

	/** A copy of the player's current model, or null if there's none yet. */
	static Mesh capture(Client client)
	{
		Player player = client.getLocalPlayer();
		Model model = player == null ? null : player.getModel();
		return model == null ? null : Mesh.of(client, model);
	}

	/**
	 * A cheap fingerprint of the model. Right after login or a gear change the game may still be loading item
	 * models and hands back an incomplete one; waiting until this stops changing avoids drawing that.
	 */
	static long signature(Mesh m)
	{
		long h = m.vx.length * 31L + m.faceCount();
		for (int i = 0; i < m.faceCount(); i += 7)
		{
			h = h * 31 + m.c1[i];
		}
		return h;
	}

	/** Everything the renderer reads from a game model, copied out so it can also come from a saved file. */
	static final class Mesh
	{
		double brightness = 0.8;
		float[] vx, vy, vz;
		int[] f1, f2, f3, c1, c2, c3, t1, t2, t3;
		byte[] alpha, priorities, textureFaces;
		short[] textures;
		Map<Integer, int[]> texels = new HashMap<>();

		static Mesh of(Client client, Model m)
		{
			Mesh x = new Mesh();
			x.brightness = client.getTextureProvider() != null ? client.getTextureProvider().getBrightness() : 0.8;
			x.vx = m.getVerticesX();
			x.vy = m.getVerticesY();
			x.vz = m.getVerticesZ();
			x.f1 = m.getFaceIndices1();
			x.f2 = m.getFaceIndices2();
			x.f3 = m.getFaceIndices3();
			x.c1 = m.getFaceColors1();
			x.c2 = m.getFaceColors2();
			x.c3 = m.getFaceColors3();
			x.alpha = m.getFaceTransparencies();
			x.priorities = m.getFaceRenderPriorities();
			x.textures = m.getFaceTextures();
			x.textureFaces = m.getTextureFaces();
			x.t1 = m.getTexIndices1();
			x.t2 = m.getTexIndices2();
			x.t3 = m.getTexIndices3();
			int vertices = m.getVerticesCount();
			x.vx = java.util.Arrays.copyOf(x.vx, vertices);
			x.vy = java.util.Arrays.copyOf(x.vy, vertices);
			x.vz = java.util.Arrays.copyOf(x.vz, vertices);
			int faces = m.getFaceCount();
			x.f1 = java.util.Arrays.copyOf(x.f1, faces);
			x.f2 = java.util.Arrays.copyOf(x.f2, faces);
			x.f3 = java.util.Arrays.copyOf(x.f3, faces);
			x.c1 = java.util.Arrays.copyOf(x.c1, faces);
			x.c2 = java.util.Arrays.copyOf(x.c2, faces);
			x.c3 = java.util.Arrays.copyOf(x.c3, faces);
			if (x.textures != null)
			{
				for (short t : x.textures)
				{
					if (t >= 0 && !x.texels.containsKey((int) t))
					{
						int[] px = loadTexture(client, t);
						if (px != null)
						{
							x.texels.put((int) t, px);
						}
					}
				}
			}
			return x;
		}

		int faceCount()
		{
			return f1.length;
		}
	}

	/** How to frame the portrait: the crop's top and bottom (model units, up is negative) and the turn. */
	static final class Framing
	{
		final float top;
		final float bottom;
		final double yaw;

		Framing(float top, float bottom, double yaw)
		{
			this.top = top;
			this.bottom = bottom;
			this.yaw = yaw;
		}
	}

	static final Framing DEFAULT_FRAMING = new Framing(CROP_TOP, CROP_BOTTOM, YAW);

	private static final class Face
	{
		final int index;
		final float depth;

		Face(int index, float depth)
		{
			this.index = index;
			this.depth = depth;
		}
	}

	static BufferedImage draw(Mesh model, int size, Framing framing)
	{
		int w = size * SUPERSAMPLE;
		int faces = model.faceCount();
		float[] vx = model.vx, vy = model.vy, vz = model.vz;
		int[] f1 = model.f1, f2 = model.f2, f3 = model.f3;
		int[] c1 = model.c1, c2 = model.c2, c3 = model.c3;
		byte[] alpha = model.alpha;
		short[] textures = model.textures;
		byte[] priorities = model.priorities;
		double brightness = model.brightness;

		// Turn about the vertical axis, then project straight on (orthographic, like the game's portraits)
		double cos = Math.cos(framing.yaw), sin = Math.sin(framing.yaw);
		int n = vx.length;
		float[] sx = new float[n], sy = new float[n], sz = new float[n];
		float scale = w / (framing.bottom - framing.top);
		for (int i = 0; i < n; i++)
		{
			double x = vx[i] * cos - vz[i] * sin;
			double z = vx[i] * sin + vz[i] * cos;
			// Models face the viewer with +x to the right and nearer faces at smaller z (as the chathead showed)
			sx[i] = (float) (w / 2f + x * scale);
			sy[i] = (vy[i] - framing.top) * scale;
			sz[i] = (float) z;
		}

		// Opaque faces first (depth tested and written), then see-through faces far to near, blended
		List<Face> opaque = new ArrayList<>();
		List<Face> translucent = new ArrayList<>();
		for (int f = 0; f < faces; f++)
		{
			if (c3[f] == -2)
			{
				continue; // a face the game never draws
			}
			int a = alpha == null ? 255 : 255 - (alpha[f] & 0xFF);
			if (a == 0)
			{
				continue;
			}
			float depth = (sz[f1[f]] + sz[f2[f]] + sz[f3[f]]) / 3f;
			(a < 255 ? translucent : opaque).add(new Face(f, depth));
		}
		translucent.sort((x, y) -> Float.compare(y.depth, x.depth));

		int[] rgb = new int[w * w];
		float[] zbuf = new float[w * w];
		Arrays.fill(zbuf, Float.MAX_VALUE);
		float[] u = new float[3], v = new float[3];
		for (List<Face> pass : List.of(opaque, translucent))
		{
			boolean blend = pass == translucent;
			for (Face face : pass)
			{
				int f = face.index;
				int a = f1[f], b = f2[f], c = f3[f];
				float nudge = priorities == null ? 0 : (priorities[f] & 0xFF) * PRIORITY_STEP;
				int tex = textures == null ? -1 : textures[f];
				int[] texels = null;
				if (tex >= 0)
				{
					texels = model.texels.get(tex);
					faceUvs(model, f, u, v);
				}
				boolean flat = c3[f] == -1;
				int ca, cb, cc;
				if (texels != null)
				{
					// On a textured face the colours are a 7-bit lightness that scales the texel
					ca = Math.min(127, Math.max(0, c1[f]));
					cb = flat ? ca : Math.min(127, Math.max(0, c2[f]));
					cc = flat ? ca : Math.min(127, Math.max(0, c3[f]));
				}
				else
				{
					ca = hslToRgb(c1[f], brightness);
					cb = flat ? ca : hslToRgb(c2[f], brightness);
					cc = flat ? ca : hslToRgb(c3[f], brightness);
				}
				float opacity = (alpha == null ? 255 : 255 - (alpha[f] & 0xFF)) / 255f;
				triangle(rgb, zbuf, w, sx[a], sy[a], sz[a] - nudge, sx[b], sy[b], sz[b] - nudge, sx[c], sy[c], sz[c] - nudge,
					ca, cb, cc, texels, u, v, opacity, blend);
			}
		}

		BufferedImage big = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
		big.setRGB(0, 0, w, w, rgb, 0, w);
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(big.getScaledInstance(size, size, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
		g.dispose();
		return out;
	}

	private static int[] loadTexture(Client client, int id)
	{
		try
		{
			int[] pixels = client.getTextureProvider().load(id);
			return pixels != null && pixels.length > 0 ? pixels : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static void triangle(int[] rgb, float[] zbuf, int w,
		float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
		int ca, int cb, int cc, int[] texels, float[] u, float[] v, float opacity, boolean blend)
	{
		float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
		if (Math.abs(area) < 1e-4f)
		{
			return;
		}
		int texSize = texels == null ? 0 : (int) Math.round(Math.sqrt(texels.length));
		int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
		int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
		int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
		int y1 = Math.min(w - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
		for (int y = y0; y <= y1; y++)
		{
			float py = y + 0.5f;
			for (int x = x0; x <= x1; x++)
			{
				float px = x + 0.5f;
				float wa = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
				float wb = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
				float wc = 1f - wa - wb;
				if (wa < 0 || wb < 0 || wc < 0)
				{
					continue;
				}
				float z = wa * az + wb * bz + wc * cz;
				int i = y * w + x;
				if (z >= zbuf[i])
				{
					continue;
				}
				int r, g, bl;
				if (texels != null)
				{
					float tu = wa * u[0] + wb * u[1] + wc * u[2];
					float tv = wa * v[0] + wb * v[1] + wc * v[2];
					int tx = Math.floorMod((int) Math.floor(tu * texSize), texSize);
					int ty = Math.floorMod((int) Math.floor(tv * texSize), texSize);
					int texel = texels[ty * texSize + tx];
					if ((texel & 0xFFFFFF) == 0)
					{
						continue; // transparent texel
					}
					float light = (wa * ca + wb * cb + wc * cc) / 127f;
					r = Math.min(255, (int) ((texel >> 16 & 255) * light));
					g = Math.min(255, (int) ((texel >> 8 & 255) * light));
					bl = Math.min(255, (int) ((texel & 255) * light));
				}
				else
				{
					r = (int) (wa * (ca >> 16 & 255) + wb * (cb >> 16 & 255) + wc * (cc >> 16 & 255));
					g = (int) (wa * (ca >> 8 & 255) + wb * (cb >> 8 & 255) + wc * (cc >> 8 & 255));
					bl = (int) (wa * (ca & 255) + wb * (cb & 255) + wc * (cc & 255));
				}
				if (blend)
				{
					int under = rgb[i];
					float keep = (under >>> 24) == 0 ? 0f : 1f - opacity;
					r = (int) (r * (1 - keep) + (under >> 16 & 255) * keep);
					g = (int) (g * (1 - keep) + (under >> 8 & 255) * keep);
					bl = (int) (bl * (1 - keep) + (under & 255) * keep);
					int a = (under >>> 24) == 0 ? (int) (opacity * 255) : 255;
					rgb[i] = a << 24 | r << 16 | g << 8 | bl;
				}
				else
				{
					zbuf[i] = z;
					rgb[i] = 0xFF000000 | r << 16 | g << 8 | bl;
				}
			}
		}
	}

	/**
	 * A textured face's UVs: its corners projected onto the model's texture triangle (origin plus U and V axes),
	 * the same mapping the game's GPU renderer uses. Faces without a texture triangle map straight onto a corner.
	 */
	private static void faceUvs(Mesh model, int face, float[] u, float[] v)
	{
		byte[] textureFaces = model.textureFaces;
		if (textureFaces == null || textureFaces[face] == -1)
		{
			u[0] = 0f;
			v[0] = 0f;
			u[1] = 1f;
			v[1] = 0f;
			u[2] = 0f;
			v[2] = 1f;
			return;
		}
		float[] x = model.vx, y = model.vy, z = model.vz;
		int t = textureFaces[face] & 0xFF;
		int ta = model.t1[t], tb = model.t2[t], tc = model.t3[t];
		float ox = x[ta], oy = y[ta], oz = z[ta];
		float ux = x[tb] - ox, uy = y[tb] - oy, uz = z[tb] - oz;
		float wx = x[tc] - ox, wy = y[tc] - oy, wz = z[tc] - oz;
		int[] corners = {model.f1[face], model.f2[face], model.f3[face]};
		float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;

		float px = wy * nz - wz * ny, py = wz * nx - wx * nz, pz = wx * ny - wy * nx;
		float s = 1f / (px * ux + py * uy + pz * uz);
		for (int k = 0; k < 3; k++)
		{
			int c = corners[k];
			u[k] = (px * (x[c] - ox) + py * (y[c] - oy) + pz * (z[c] - oz)) * s;
		}
		px = uy * nz - uz * ny;
		py = uz * nx - ux * nz;
		pz = ux * ny - uy * nx;
		s = 1f / (px * wx + py * wy + pz * wz);
		for (int k = 0; k < 3; k++)
		{
			int c = corners[k];
			v[k] = (px * (x[c] - ox) + py * (y[c] - oy) + pz * (z[c] - oz)) * s;
		}
	}

	/** The game's packed HSL (6 bits hue, 3 saturation, 7 lightness) to RGB, with its brightness curve. */
	static int hslToRgb(int hsl, double brightness)
	{
		double h = ((hsl >> 10) & 63) / 64.0 + 0.5 / 64.0;
		double s = ((hsl >> 7) & 7) / 8.0 + 0.5 / 8.0;
		double l = (hsl & 127) / 128.0;
		double chroma = (1 - Math.abs(2 * l - 1)) * s;
		double x = chroma * (1 - Math.abs(((h * 6) % 2) - 1));
		double m = l - chroma / 2;
		double r = m, g = m, b = m;
		switch ((int) (h * 6))
		{
			case 0:
				r += chroma;
				g += x;
				break;
			case 1:
				g += chroma;
				r += x;
				break;
			case 2:
				g += chroma;
				b += x;
				break;
			case 3:
				b += chroma;
				g += x;
				break;
			case 4:
				b += chroma;
				r += x;
				break;
			default:
				r += chroma;
				b += x;
				break;
		}
		int ri = Math.min(255, (int) (Math.pow(r, brightness) * 256));
		int gi = Math.min(255, (int) (Math.pow(g, brightness) * 256));
		int bi = Math.min(255, (int) (Math.pow(b, brightness) * 256));
		return ri << 16 | gi << 8 | bi;
	}

	/**
	 * Saves everything the renderer reads from a model (plus the textures it uses) as JSON, so portraits can be
	 * re-rendered and tuned outside the game.
	 */
	static void dump(Mesh mesh, java.io.File file) throws java.io.IOException
	{
		try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(file.toPath()))
		{
			new com.google.gson.Gson().toJson(mesh, w);
		}
	}

	/** A mesh saved by {@link #dump}. */
	static Mesh load(java.io.File file) throws java.io.IOException
	{
		try (java.io.Reader r = java.nio.file.Files.newBufferedReader(file.toPath()))
		{
			return new com.google.gson.Gson().fromJson(r, Mesh.class);
		}
	}

	private PlayerPortrait()
	{
	}
}
