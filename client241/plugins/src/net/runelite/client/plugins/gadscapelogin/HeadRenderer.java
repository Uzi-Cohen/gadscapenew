package net.runelite.client.plugins.gadscapelogin;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Draws a chathead (or the top of a character) front on, from its 3D model: the same data the game draws with.
 * {@link #renderFrames} makes a strip of frames where the head sways and tilts a little, which the login
 * screen plays on the account's tile. Drawn 3x larger and scaled down, so edges are smooth.
 */
final class HeadRenderer
{
	private HeadRenderer()
	{
	}

	private static final int[] PALETTE = new int[65536];

	static
	{
		// the game's colour table: packed hue/saturation/lightness -> RGB, with its brightness curve
		double brightness = 0.78;
		for (int hsl = 0; hsl < 65536; hsl++)
		{
			double h = ((hsl >> 10) & 63) / 64.0 + 0.0078125;
			double s = ((hsl >> 7) & 7) / 8.0 + 0.0625;
			double l = (hsl & 127) / 128.0;
			double r = l, g = l, b = l;
			if (s != 0)
			{
				double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
				double p = 2 * l - q;
				r = hue(p, q, h + 1.0 / 3);
				g = hue(p, q, h);
				b = hue(p, q, h - 1.0 / 3);
			}
			int ri = (int) (Math.pow(r, brightness) * 256);
			int gi = (int) (Math.pow(g, brightness) * 256);
			int bi = (int) (Math.pow(b, brightness) * 256);
			PALETTE[hsl] = (Math.min(255, ri) << 16) | (Math.min(255, gi) << 8) | Math.min(255, bi);
		}
	}

	private static double hue(double p, double q, double t)
	{
		if (t < 0)
		{
			t += 1;
		}
		if (t > 1)
		{
			t -= 1;
		}
		if (t < 1.0 / 6)
		{
			return p + (q - p) * 6 * t;
		}
		if (t < 0.5)
		{
			return q;
		}
		if (t < 2.0 / 3)
		{
			return p + (q - p) * (2.0 / 3 - t) * 6;
		}
		return p;
	}

	static int rgb(int hsl)
	{
		return PALETTE[hsl & 0xFFFF];
	}

	/** A model's arrays (copied, so the game can keep using its own). */
	static final class Mesh
	{
		int vertexCount, faceCount;
		int[] vx, vy, vz, f1, f2, f3, c1, c2, c3;
		byte[] alpha;
		short[] textures;
	}

	/**
	 * A strip of {@code frames} pictures side by side, each {@code size} x {@code size}: the head turning and
	 * tilting a little from side to side and back, so played in a loop it sways.
	 *
	 * @param part which part of the model from the top (1 = all of it, for a chathead; 0.19 = head of a full body)
	 * @param front which way the face points along z: -1 for the game's models
	 */
	static BufferedImage renderFrames(Mesh m, double part, int size, int frames, int front)
	{
		if (m == null || m.vertexCount == 0 || m.faceCount == 0)
		{
			return null;
		}
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		for (int i = 0; i < m.vertexCount; i++)
		{
			minY = Math.min(minY, m.vy[i]);
			maxY = Math.max(maxY, m.vy[i]);
		}
		double cut = minY + (maxY - minY) * part;
		boolean[] use = new boolean[m.faceCount];
		double sx = 0, sy = 0, sz = 0, ext = 1;
		int n = 0;
		for (int f = 0; f < m.faceCount; f++)
		{
			if (m.c3 != null && m.c3[f] == -2)
			{
				continue;
			}
			if (m.alpha != null && (m.alpha[f] & 0xFF) >= 250)
			{
				continue;
			}
			int a = m.f1[f], b = m.f2[f], c = m.f3[f];
			if (part < 1 && (m.vy[a] + m.vy[b] + m.vy[c]) / 3.0 > cut)
			{
				continue;
			}
			use[f] = true;
			for (int v : new int[]{a, b, c})
			{
				sx += m.vx[v];
				sy += m.vy[v];
				sz += m.vz[v];
				n++;
			}
		}
		if (n == 0)
		{
			return null;
		}
		double cx = sx / n, cy = sy / n, cz = sz / n;
		for (int f = 0; f < m.faceCount; f++)
		{
			if (use[f])
			{
				for (int v : new int[]{m.f1[f], m.f2[f], m.f3[f]})
				{
					ext = Math.max(ext, Math.max(Math.abs(m.vx[v] - cx), Math.abs(m.vy[v] - cy)));
				}
			}
		}

		int big = size * 3;
		// 1) the head standing still, roughly fitted, to see where it really is (some faces are invisible)
		double s0 = big * 0.45 / ext;
		int[] box = bounds(raster(m, use, cx, cy, cz, 0, 0, front, s0, big / 2.0, big / 2.0, big), big);
		if (box == null)
		{
			return null;
		}
		// 2) frame that tightly, with room to sway, and draw every frame with the same framing
		double sidePx = Math.max(box[2] - box[0], box[3] - box[1]) * 1.22;
		double scale = s0 * big / sidePx;
		double mx = ((box[0] + box[2]) / 2.0 - big / 2.0) / s0; // centre of what's drawn, in model units
		double my = ((box[1] + box[3]) / 2.0 - big / 2.0) / s0;
		double ox = big / 2.0 - mx * scale, oy = big / 2.0 - my * scale;

		BufferedImage strip = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = strip.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		for (int i = 0; i < frames; i++)
		{
			double t = i * 2 * Math.PI / frames;
			double yaw = Math.toRadians(16) * Math.sin(t);           // turns left and right
			double roll = Math.toRadians(5) * Math.sin(t * 2 + 0.6); // and wobbles a bit
			int[] px = raster(m, use, cx, cy, cz, yaw, roll, front, scale, ox, oy, big);
			BufferedImage frame = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
			frame.setRGB(0, 0, big, big, px, 0, big);
			g.drawImage(frame.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), i * size, 0, null);
		}
		g.dispose();
		return strip;
	}

	/** Model -> pixels of an n x n square: rotate about the head's centre, flat (orthographic) projection. */
	private static int[] raster(Mesh m, boolean[] use, double cx, double cy, double cz, double yaw, double roll,
		int front, double scale, double ox, double oy, int n)
	{
		double cyaw = Math.cos(yaw), syaw = Math.sin(yaw), cr = Math.cos(roll), sr = Math.sin(roll);
		double[] X = new double[m.vertexCount], Y = new double[m.vertexCount], Z = new double[m.vertexCount];
		for (int i = 0; i < m.vertexCount; i++)
		{
			double x = (m.vx[i] - cx) * front, y = m.vy[i] - cy, z = (m.vz[i] - cz) * front;
			double x1 = x * cyaw - z * syaw, z1 = x * syaw + z * cyaw;
			double x2 = x1 * cr - y * sr, y2 = x1 * sr + y * cr;
			X[i] = x2 * scale + ox;
			Y[i] = y2 * scale + oy;
			Z[i] = z1;
		}
		int[] px = new int[n * n];
		float[] depth = new float[n * n];
		Arrays.fill(depth, Float.NEGATIVE_INFINITY);
		for (int f = 0; f < m.faceCount; f++)
		{
			if (!use[f])
			{
				continue;
			}
			int a = m.f1[f], b = m.f2[f], c = m.f3[f];
			double ax = X[a], ay = Y[a], az = Z[a], bx = X[b], by = Y[b], bz = Z[b], qx = X[c], qy = Y[c], qz = Z[c];
			double area = (bx - ax) * (qy - ay) - (qx - ax) * (by - ay);
			if (Math.abs(area) < 1e-6)
			{
				continue;
			}
			int ca, cb, cc;
			boolean textured = m.textures != null && m.textures[f] != -1;
			if (textured)
			{
				int base = (10 << 10) | (2 << 7);
				ca = PALETTE[base | (m.c1[f] & 127)];
				cb = m.c3[f] == -1 ? ca : PALETTE[base | (m.c2[f] & 127)];
				cc = m.c3[f] == -1 ? ca : PALETTE[base | (m.c3[f] & 127)];
			}
			else if (m.c3 == null || m.c3[f] == -1)
			{
				ca = cb = cc = rgb(m.c1[f]);
			}
			else
			{
				ca = rgb(m.c1[f]);
				cb = rgb(m.c2[f]);
				cc = rgb(m.c3[f]);
			}
			int al = m.alpha == null ? 0 : m.alpha[f] & 0xFF;
			int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, qx))));
			int x1 = Math.min(n - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, qx))));
			int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, qy))));
			int y1 = Math.min(n - 1, (int) Math.ceil(Math.max(ay, Math.max(by, qy))));
			for (int y = y0; y <= y1; y++)
			{
				for (int x = x0; x <= x1; x++)
				{
					double px0 = x + 0.5, py0 = y + 0.5;
					double w0 = ((bx - px0) * (qy - py0) - (qx - px0) * (by - py0)) / area;
					double w1 = ((qx - px0) * (ay - py0) - (ax - px0) * (qy - py0)) / area;
					double w2 = 1 - w0 - w1;
					if (w0 < 0 || w1 < 0 || w2 < 0)
					{
						continue;
					}
					float z = (float) (w0 * az + w1 * bz + w2 * qz);
					int i = y * n + x;
					if (z <= depth[i])
					{
						continue;
					}
					int r = (int) (w0 * (ca >> 16 & 255) + w1 * (cb >> 16 & 255) + w2 * (cc >> 16 & 255));
					int gg = (int) (w0 * (ca >> 8 & 255) + w1 * (cb >> 8 & 255) + w2 * (cc >> 8 & 255));
					int bl = (int) (w0 * (ca & 255) + w1 * (cb & 255) + w2 * (cc & 255));
					if (al > 0 && (px[i] >>> 24) != 0)
					{
						double t = al / 255.0;
						int o = px[i];
						r = (int) (r * (1 - t) + (o >> 16 & 255) * t);
						gg = (int) (gg * (1 - t) + (o >> 8 & 255) * t);
						bl = (int) (bl * (1 - t) + (o & 255) * t);
					}
					else
					{
						depth[i] = z;
					}
					px[i] = 0xFF000000 | (clamp(r) << 16) | (clamp(gg) << 8) | clamp(bl);
				}
			}
		}
		return px;
	}

	/** Bounds of the drawn pixels as {x0, y0, x1, y1}. */
	private static int[] bounds(int[] px, int n)
	{
		int x0 = n, y0 = n, x1 = -1, y1 = -1;
		for (int y = 0; y < n; y++)
		{
			for (int x = 0; x < n; x++)
			{
				if ((px[y * n + x] >>> 24) != 0)
				{
					x0 = Math.min(x0, x);
					x1 = Math.max(x1, x);
					y0 = Math.min(y0, y);
					y1 = Math.max(y1, y);
				}
			}
		}
		return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
	}

	private static int clamp(int v)
	{
		return v < 0 ? 0 : Math.min(255, v);
	}
}
