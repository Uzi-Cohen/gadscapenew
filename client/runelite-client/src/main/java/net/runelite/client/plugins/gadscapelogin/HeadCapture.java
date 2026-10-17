package net.runelite.client.plugins.gadscapelogin;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
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
	static final int SIZE = 160;
	static final int FRAMES = 32;

	private final Client client;
	private final File dir;
	private volatile String pending;
	private int ticks;

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
		ticks = 0;
	}

	/** Called on the game thread every tick. */
	void onGameTick()
	{
		String user = pending;
		if (user == null)
		{
			return;
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
		HeadRenderer.Mesh mesh = null;
		double part = 1;
		try
		{
			// the chathead the game builds for dialogue boxes
			PlayerComposition pc = p.getPlayerComposition();
			ModelData md = pc == null ? null : pc.getChatheadModelData();
			Model m = md == null ? null : md.light(ModelData.DEFAULT_AMBIENT, ModelData.DEFAULT_CONTRAST, ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z);
			mesh = copy(m);
		}
		catch (Throwable ignored)
		{
			// older client build without the chathead: fall back to the top of the character
		}
		if (mesh == null)
		{
			mesh = copy(p.getModel());
			part = 0.19;
		}
		if (mesh == null)
		{
			return; // try again next tick
		}
		pending = null;
		final HeadRenderer.Mesh fm = mesh;
		final double fpart = part;
		Thread t = new Thread(() ->
		{
			try
			{
				BufferedImage strip = HeadRenderer.renderFrames(fm, fpart, SIZE, FRAMES, -1);
				if (strip != null)
				{
					dir.mkdirs();
					File tmp = new File(dir, fileName(user) + ".tmp");
					ImageIO.write(strip, "png", tmp);
					java.nio.file.Files.move(tmp.toPath(), new File(dir, fileName(user)).toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			catch (Exception ignored)
			{
				// no picture this time; the tile shows the shield
			}
		}, "gadscape-face");
		t.setDaemon(true);
		t.start();
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
		h.vx = Arrays.copyOf(m.getVerticesX(), vc);
		h.vy = Arrays.copyOf(m.getVerticesY(), vc);
		h.vz = Arrays.copyOf(m.getVerticesZ(), vc);
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
