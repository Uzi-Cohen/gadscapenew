package net.runelite.client.plugins.gadscapelogin;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
import net.runelite.api.IndexDataBase;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

/**
 * After logging in, draws the account's chathead (head, jaw and helmet: what dialogue boxes show, nothing it
 * holds) as a short swaying animation and saves it as gadscape-heads/<name>.png, a strip of frames shown on
 * that account's tile. Renewed every login, so a new helmet or hairstyle shows up next time.
 */
final class HeadCapture
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HeadCapture.class);

	static final int SIZE = 160;
	static final int FRAMES = 32;

	private final Client client;
	private final File dir;
	private volatile String pending;
	private int ticks;
	/** The account logged in now, the look its face was last drawn with, and how long a new look has held. */
	private String account;
	private int[] drawnLook;
	private int changed;
	/** The last chathead model that wasn't in the game cache yet (for the log). */
	private int missingModel = -1;

	HeadCapture(Client client, File dir)
	{
		this.client = client;
		this.dir = dir;
	}

	static String fileName(String username)
	{
		return username.toLowerCase().replaceAll("[^a-z0-9_-]", "_") + ".png";
	}

	void start(String username)
	{
		pending = username;
		account = username;
		drawnLook = null;
		ticks = 0;
	}

	/** Back on the login screen: stop watching for look changes. */
	void stop()
	{
		pending = null;
		account = null;
		drawnLook = null;
	}

	/** Equipment, colours and gender: what the chathead is drawn from. */
	private static int[] look(PlayerComposition pc)
	{
		if (pc == null)
		{
			return null;
		}
		int[] eq = pc.getEquipmentIds(), c = pc.getColors();
		int[] out = new int[(eq == null ? 0 : eq.length) + (c == null ? 0 : c.length) + 2];
		int i = 0;
		if (eq != null) for (int v : eq) out[i++] = v;
		if (c != null) for (int v : c) out[i++] = v;
		out[i++] = pc.getGender();
		out[i] = pc.getTransformedNpcId();
		return out;
	}

	/** Called on the game thread every tick. */
	void onGameTick()
	{
		String user = pending;
		if (user == null)
		{
			// logged in with a face already drawn: draw it again when the look changes (a new helmet, a makeover)
			Player me = client.getLocalPlayer();
			int[] now = me == null ? null : look(me.getPlayerComposition());
			if (account == null || drawnLook == null || now == null || Arrays.equals(now, drawnLook))
			{
				changed = 0;
				return;
			}
			if (++changed < 2)
			{
				return; // let the change settle (swapping several items at once)
			}
			changed = 0;
			pending = user = account;
			ticks = 3;
		}
		if (++ticks > 300)
		{
			pending = null;
			return;
		}
		if (ticks < 3)
		{
			return; // let the character finish loading
		}
		Widget welcome = client.getWidget(WidgetInfo.LOGIN_CLICK_TO_PLAY_SCREEN);
		if (welcome != null && !welcome.isHidden())
		{
			return;
		}
		Player p = client.getLocalPlayer();
		if (p == null)
		{
			return;
		}
		// the chathead, built the way the game builds it for dialogue boxes (RuneLite 1.13.3 has no call for it)
		HeadRenderer.Mesh mesh = null;
		double part = 1;
		try
		{
			mesh = copy(chathead(p.getPlayerComposition()));
		}
		catch (Throwable e)
		{
			log.warn("GadScape face: couldn't build the chathead", e);
			mesh = null;
		}
		if (mesh == null && ticks < 100)
		{
			return; // its models may still be downloading from the server (a fresh cache): keep trying for a minute
		}
		if (mesh == null && new File(dir, fileName(user)).isFile())
		{
			// never replace a real face with the top of the body: keep the picture there is
			log.warn("GadScape face: no chathead for {} after {} ticks (missing model {}), keeping the old picture", user, ticks, missingModel);
			pending = null;
			drawnLook = look(p.getPlayerComposition());
			return;
		}
		if (mesh == null)
		{
			// no chathead (an NPC look, or a cache entry it couldn't read): the top of the character
			log.warn("GadScape face: no chathead for {}, drawing the top of the character", user);
			mesh = copy(p.getModel());
			part = 0.19;
		}
		if (mesh == null)
		{
			return; // try again next tick
		}
		pending = null;
		drawnLook = look(p.getPlayerComposition());
		final HeadRenderer.Mesh fm = mesh;
		final double fpart = part;
		final String name = user;
		final HeadRenderer.Mesh body = fpart == 1 ? copy(p.getModel()) : null;
		Thread t = new Thread(() ->
		{
			try
			{
				BufferedImage strip = null;
				try
				{
					strip = HeadRenderer.renderFrames(fm, fpart, SIZE, FRAMES, -1);
				}
				catch (Exception e)
				{
					log.warn("GadScape face: couldn't draw the chathead", e);
				}
				if (strip == null && body != null)
				{
					strip = HeadRenderer.renderFrames(body, 0.19, SIZE, FRAMES, -1);
				}
				if (strip != null)
				{
					dir.mkdirs();
					File tmp = new File(dir, fileName(name) + ".tmp");
					ImageIO.write(strip, "png", tmp);
					java.nio.file.Files.move(tmp.toPath(), new File(dir, fileName(name)).toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					log.info("GadScape face: saved {} ({})", fileName(name), fpart == 1 ? "chathead" : "top of character");
				}
			}
			catch (Exception e)
			{
				log.warn("GadScape face: couldn't save the picture for {}", name, e);
			}
		}, "gadscape-face");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ chathead

	/** The body colour tables the game recolours a player's look with (build 211 and 241 use the same ones). */
	private static final short[] COLOR_FIND_1 = {6798, 8741, 25238, 4626, 4550};
	private static final short[][] COLOR_REPLACE_1 = {
		{6798, 107, 10283, 16, 4797, 7744, 5799, 4634, -31839, 22433, 2983, -11343, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
		{8741, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153, -8915, 4783, 1341, 16578, -30533, 25239, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
		{25238, 8742, 12, -1506, -22374, 7735, 8404, 1701, -27106, 24094, 10153, -8915, 4783, 1341, 16578, -30533, 8, 5281, 10438, 3650, -27322, -21845, 200, 571, 908, 21830, 28946, -15701, -14010},
		{4626, 11146, 6439, 12, 4758, 10270},
		{4550, 4537, 5681, 5673, 5790, 6806, 8076, 4574, 17050, 0, 127, -31821, -17991}};
	private static final short[] COLOR_FIND_2 = {-10304, 9104, -1, -1, -1};
	private static final short[][] COLOR_REPLACE_2 = {
		{6554, 115, 10304, 28, 5702, 7756, 5681, 4510, -31835, 22437, 2859, -11339, 16, 5157, 10446, 3658, -27314, -21965, 472, 580, 784, 21966, 28950, -15697, -14002},
		{9104, 10275, 7595, 3610, 7975, 8526, 918, -26734, 24466, 10145, -6882, 5027, 1457, 16565, -30545, 25486, 24, 5392, 10429, 3673, -27335, -21957, 192, 687, 412, 21821, 28835, -15460, -14019},
		{}, {}, {}};

	/** One look part's chathead: its models plus the recolours / retextures that go with them. */
	private static final class Part
	{
		final java.util.List<Integer> models = new java.util.ArrayList<>();
		short[] recolorFrom, recolorTo, retextureFrom, retextureTo;
	}

	/**
	 * The player's chathead as the game builds it: for every worn item its chathead models (an item without them
	 * hides nothing), for every body part not covered its identity kit's chathead models, merged, then recoloured
	 * with the player's colours and lit. Null if the player looks like an NPC or something can't be read.
	 */
	private Model chathead(PlayerComposition pc)
	{
		if (pc == null || pc.getTransformedNpcId() != -1)
		{
			return null;
		}
		IndexDataBase config = client.getIndexConfig();
		int[] eq = pc.getEquipmentIds();
		boolean female = pc.getGender() == 1;
		java.util.List<ModelData> parts = new java.util.ArrayList<>();
		for (int i = 0; i < 12 && i < eq.length; i++)
		{
			int v = eq[i];
			Part part = null;
			// build 241 stores worn items as id + 2048 and body parts as kit id + 256
			if (v >= PlayerComposition.ITEM_OFFSET)
			{
				part = itemHead(config.loadData(10, v - PlayerComposition.ITEM_OFFSET), female);
			}
			else if (v >= PlayerComposition.KIT_OFFSET)
			{
				part = kitHead(config.loadData(3, v - 256));
			}
			if (part == null || part.models.isEmpty())
			{
				continue;
			}
			ModelData[] ms = new ModelData[part.models.size()];
			for (int k = 0; k < ms.length; k++)
			{
				ms[k] = client.loadModelData(part.models.get(k));
				if (ms[k] == null)
				{
					missingModel = part.models.get(k);
					return null; // not downloaded yet: asking for it starts the download, the next tick tries again
				}
			}
			ModelData md = (ms.length == 1 ? ms[0] : client.mergeModels(ms)).cloneColors();
			if (part.recolorFrom != null)
			{
				for (int k = 0; k < part.recolorFrom.length; k++) md.recolor(part.recolorFrom[k], part.recolorTo[k]);
			}
			if (part.retextureFrom != null)
			{
				for (int k = 0; k < part.retextureFrom.length; k++) md.retexture(part.retextureFrom[k], part.retextureTo[k]);
			}
			parts.add(md);
		}
		if (parts.isEmpty())
		{
			return null;
		}
		ModelData head = client.mergeModels(parts.toArray(new ModelData[0])).cloneColors();
		int[] colors = pc.getColors();
		for (int i = 0; i < 5 && colors != null && i < colors.length; i++)
		{
			int c = colors[i];
			if (c >= 0 && c < COLOR_REPLACE_1[i].length) head.recolor(COLOR_FIND_1[i], COLOR_REPLACE_1[i][c]);
			if (c >= 0 && c < COLOR_REPLACE_2[i].length) head.recolor(COLOR_FIND_2[i], COLOR_REPLACE_2[i][c]);
		}
		return head.light(ModelData.DEFAULT_AMBIENT, ModelData.DEFAULT_CONTRAST, ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
	}

	/** An item's chathead models for [female] (settings 90-93, or 51-54 with int ids) and its recolours. */
	private static Part itemHead(byte[] b, boolean female)
	{
		if (b == null) return null;
		Def r = new Def(b);
		int m1 = -1, m2 = -1;
		Part p = new Part();
		while (r.p < b.length)
		{
			int o = r.u8();
			if (o == 0) break;
			switch (o)
			{
				case 1: case 4: case 5: case 6: case 7: case 8: case 24: case 26: case 78: case 79: case 94: case 95: case 97: case 98:
				case 99: case 110: case 111: case 112: case 139: case 140: case 148: case 149: case 75: r.p += 2; break;
				case 2: case 3: case 9: r.str(); break;
				case 11: case 15: case 16: case 65: case 160: case 251: break;
				case 12: case 44: case 46: case 47: case 49: case 50: r.p += 4; break;
				case 13: case 14: case 27: case 42: case 113: case 114: case 115: r.p += 1; break;
				case 23: case 25: r.p += 3; break;
				case 45: case 48: r.p += 5; break;
				case 90: if (!female) m1 = r.u16(); else r.p += 2; break;
				case 91: if (female) m1 = r.u16(); else r.p += 2; break;
				case 92: if (!female) m2 = r.u16(); else r.p += 2; break;
				case 93: if (female) m2 = r.u16(); else r.p += 2; break;
				case 51: if (!female) m1 = r.i32(); else r.p += 4; break;
				case 53: if (female) m1 = r.i32(); else r.p += 4; break;
				case 52: if (!female) m2 = r.i32(); else r.p += 4; break;
				case 54: if (female) m2 = r.i32(); else r.p += 4; break;
				case 40: { int n = r.u8(); p.recolorFrom = new short[n]; p.recolorTo = new short[n]; for (int i = 0; i < n; i++) { p.recolorFrom[i] = (short) r.u16(); p.recolorTo[i] = (short) r.u16(); } break; }
				case 41: { int n = r.u8(); p.retextureFrom = new short[n]; p.retextureTo = new short[n]; for (int i = 0; i < n; i++) { p.retextureFrom[i] = (short) r.u16(); p.retextureTo[i] = (short) r.u16(); } break; }
				case 43: { r.p++; while (r.u8() != 0) r.str(); break; }
				case 161: { int n = r.u16(); r.p += 2 * n; break; }
				case 200: r.p += 2; r.str(); break;
				case 201: r.p += 13; r.str(); break;
				case 202: r.p += 15; r.str(); break;
				case 249: { int n = r.u8(); for (int i = 0; i < n; i++) { boolean st = r.u8() == 1; r.p += 3; if (st) r.str(); else r.p += 4; } break; }
				default:
					if (o >= 30 && o < 40) { r.str(); break; }
					if (o >= 100 && o < 110) { r.p += 4; break; }
					return null; // a setting this doesn't know: don't guess
			}
		}
		if (m1 != -1) p.models.add(m1);
		if (m2 != -1) p.models.add(m2);
		return p;
	}

	/** An identity kit's chathead models (settings 60-69, or 70-79 with int ids) and its recolours. */
	private static Part kitHead(byte[] b)
	{
		if (b == null) return null;
		Def r = new Def(b);
		int[] heads = {-1, -1, -1, -1, -1};
		Part p = new Part();
		while (r.p < b.length)
		{
			int o = r.u8();
			if (o == 0) break;
			if (o == 1) r.p += 1;
			else if (o == 2) { int n = r.u8(); r.p += 2 * n; }
			else if (o == 5) { int n = r.u8(); r.p += 4 * n; }
			else if (o == 3) { }
			else if (o == 40 || o == 41)
			{
				int n = r.u8(); short[] f = new short[n], t = new short[n];
				for (int i = 0; i < n; i++) { f[i] = (short) r.u16(); t[i] = (short) r.u16(); }
				if (o == 40) { p.recolorFrom = f; p.recolorTo = t; } else { p.retextureFrom = f; p.retextureTo = t; }
			}
			else if (o >= 60 && o < 70) { int id = r.u16(); if (o - 60 < 5) heads[o - 60] = id; }
			else if (o >= 70 && o < 80) { int id = r.i32(); if (o - 70 < 5) heads[o - 70] = id; }
			else return null;
		}
		for (int h : heads) if (h != -1 && h != 0xFFFF) p.models.add(h);
		return p;
	}

	private static final class Def
	{
		final byte[] b;
		int p;
		Def(byte[] b) { this.b = b; }
		int u8() { return b[p++] & 0xFF; }
		int u16() { int v = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); p += 2; return v; }
		int i32() { int v = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF); p += 4; return v; }
		void str() { while (b[p++] != 0) { } }
	}

	/** RuneLite 1.13.3 keeps model vertices as floats. */
	private static int[] ints(float[] f, int n)
	{
		int[] out = new int[n];
		for (int i = 0; i < n && i < f.length; i++)
		{
			out[i] = Math.round(f[i]);
		}
		return out;
	}

	/** Copies a model's arrays now (the game reuses them). */
	static HeadRenderer.Mesh copy(Model m)
	{
		if (m == null || m.getVerticesCount() == 0 || m.getFaceCount() == 0)
		{
			return null;
		}
		HeadRenderer.Mesh h = new HeadRenderer.Mesh();
		int vc = m.getVerticesCount(), fc = m.getFaceCount();
		h.vertexCount = vc;
		h.faceCount = fc;
		h.vx = ints(m.getVerticesX(), vc);
		h.vy = ints(m.getVerticesY(), vc);
		h.vz = ints(m.getVerticesZ(), vc);
		h.f1 = Arrays.copyOf(m.getFaceIndices1(), fc);
		h.f2 = Arrays.copyOf(m.getFaceIndices2(), fc);
		h.f3 = Arrays.copyOf(m.getFaceIndices3(), fc);
		h.c1 = Arrays.copyOf(m.getFaceColors1(), fc);
		h.c2 = Arrays.copyOf(m.getFaceColors2(), fc);
		h.c3 = Arrays.copyOf(m.getFaceColors3(), fc);
		h.alpha = m.getFaceTransparencies() == null ? null : Arrays.copyOf(m.getFaceTransparencies(), fc);
		h.textures = m.getFaceTextures() == null ? null : Arrays.copyOf(m.getFaceTextures(), fc);
		return h;
	}
}
