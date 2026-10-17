package net.runelite.client.plugins.gadscapelogin;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.util.LinkBrowser;
import net.unethicalite.api.input.Keyboard;

/**
 * GadScape's login screen. Fills the whole client window at any size: a painted background with live torch
 * flames and embers, and the login panel drawn as vectors (scaled, so it stays sharp at high resolution).
 * Username / password, Remember me, saved accounts with a face picture of each character, a login button with
 * hover and "logging in" states, a message box for errors, and links to the website.
 * Logging in is done by the game itself: we fill its username and password and press Enter for it.
 */
final class LoginScreen implements KeyListener, MouseListener
{
	/** Layout units: the panel is laid out on the game's 765 x 503 login area and scaled up from there. */
	static final int W = 765, H = 503;

	private static final Color GOLD = new Color(0xE2B54A);
	private static final Color GOLD_LIGHT = new Color(0xFFE08A);
	private static final Color GOLD_DARK = new Color(0x8A6420);
	private static final Color RED = new Color(0x5E1414);
	private static final Color PANEL = new Color(16, 7, 7, 225);
	private static final Color FIELD = new Color(0x120707);
	private static final Color TEXT = new Color(0xF2E6C8);
	private static final Color SUBTLE = new Color(0xB9A588);

	/** Same limits as the game: 12 character usernames (letters, numbers, space, - and _), 20 character passwords. */
	static final int MAX_USERNAME = 12, MAX_PASSWORD = 20;

	private static boolean allowed(int field, char c)
	{
		if (c < 32 || c >= 127)
		{
			return false;
		}
		return field != 0 || Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_';
	}

	private static int max(int field)
	{
		return field == 0 ? MAX_USERNAME : MAX_PASSWORD;
	}

	private static final String FORGOT_URL = "https://gadscape.com/login";
	private static final String SITE_URL = "https://gadscape.com";

	/** Torch flames in the background picture (its pixel coordinates, 1920 x 1080). */
	private static final int[][] TORCHES = {{150, 490}, {1770, 490}};

	private final Client client;
	private final SavedAccounts saved;
	private final BufferedImage background;
	private final BufferedImage shield;
	private final File headsDir;
	private final Font title, heading, label, input;

	private final StringBuilder username = new StringBuilder();
	private final StringBuilder password = new StringBuilder();
	private int field;
	private volatile String hover = "";
	private volatile String message;
	private int accountPage;

	private volatile boolean attempting;
	private volatile boolean sawLoggingIn;
	private volatile long attemptStart;
	private volatile String attemptUser, attemptPass;
	private volatile boolean injecting;

	// layout of the last frame, used to map mouse positions back to layout units
	private volatile double scale = 1, offX, offY;
	private List<Hit> building = new ArrayList<>();
	private volatile List<Hit> hits = new ArrayList<>();

	private BufferedImage scaledBg;
	private int scaledW, scaledH;
	private double bgScale = 1, bgX, bgY;

	private final Map<String, BufferedImage> heads = new HashMap<>();
	private final Map<String, Long> headTimes = new HashMap<>();
	private long lastHeadCheck;

	private final List<float[]> embers = new ArrayList<>(); // x, y, vx, vy, life, size (screen pixels)
	private final Random rnd = new Random();
	private long lastFrame;

	private static final class Hit
	{
		final String id;
		final Rectangle r;

		Hit(String id, Rectangle r)
		{
			this.id = id;
			this.r = r;
		}
	}

	LoginScreen(Client client, SavedAccounts saved, BufferedImage background, BufferedImage shield, File headsDir,
		Font titleFont, Font textFont)
	{
		this.client = client;
		this.saved = saved;
		this.background = background;
		this.shield = shield;
		this.headsDir = headsDir;
		Font serif = textFont != null ? textFont : new Font(Font.SERIF, Font.BOLD, 12);
		Font deco = titleFont != null ? titleFont : serif;
		this.title = deco.deriveFont(Font.BOLD, 40f);
		this.heading = deco.deriveFont(Font.BOLD, 15f);
		this.label = serif.deriveFont(Font.BOLD, 12.5f);
		this.input = new Font(Font.SANS_SERIF, Font.PLAIN, 15);
		String last = saved.getLastUsername();
		username.append(last.length() > MAX_USERNAME ? last.substring(0, MAX_USERNAME) : last);
		field = username.length() > 0 ? 1 : 0;
	}

	String lastAttemptUser()
	{
		return attemptUser;
	}

	boolean active()
	{
		GameState s = client.getGameState();
		if (s != GameState.LOGIN_SCREEN && s != GameState.LOGGING_IN)
		{
			return false;
		}
		return !client.isWorldSelectOpen();
	}

	// ------------------------------------------------------------------ drawing

	void paint(Graphics2D g)
	{
		checkAttempt();
		if (!active())
		{
			return;
		}
		int cw = Math.max(1, client.getCanvasWidth()), ch = Math.max(1, client.getCanvasHeight());
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		long now = System.currentTimeMillis();
		float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;

		paintBackground(g, cw, ch, now, dt);

		// the panel: as big as fits, up to a bit over twice the game's size
		double s = Math.max(1.0, Math.min(2.2, Math.min(cw / (double) W, ch / (double) H) * 0.82));
		if (cw < W * s || ch < H * s)
		{
			s = Math.min(cw / (double) W, ch / (double) H);
		}
		double ox = (cw - W * s) / 2, oy = (ch - H * s) / 2;

		AffineTransform t0 = g.getTransform();
		g.translate(ox, oy);
		g.scale(s, s);
		building = new ArrayList<>();
		paintPanel(g, now);
		g.setTransform(t0);
		scale = s;
		offX = ox;
		offY = oy;
		hits = building;
	}

	private void paintBackground(Graphics2D g, int cw, int ch, long now, float dt)
	{
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, cw, ch);
		if (background != null)
		{
			if (scaledBg == null || scaledW != cw || scaledH != ch)
			{
				// cover the window, keep the picture's shape
				bgScale = Math.max(cw / (double) background.getWidth(), ch / (double) background.getHeight());
				int bw = (int) Math.ceil(background.getWidth() * bgScale), bh = (int) Math.ceil(background.getHeight() * bgScale);
				bgX = (cw - bw) / 2.0;
				bgY = (ch - bh) / 2.0;
				BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
				Graphics2D ig = img.createGraphics();
				ig.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
				ig.drawImage(background, (int) bgX, (int) bgY, bw, bh, null);
				ig.dispose();
				scaledBg = img;
				scaledW = cw;
				scaledH = ch;
				embers.clear();
			}
			g.drawImage(scaledBg, 0, 0, null);
		}

		Composite c0 = g.getComposite();
		double t = now / 1000.0;
		float k = (float) bgScale;
		for (int[] tp : TORCHES)
		{
			float x = (float) (bgX + tp[0] * bgScale), y = (float) (bgY + tp[1] * bgScale);
			float flick = (float) (0.85 + 0.09 * Math.sin(t * 9.1 + tp[0]) + 0.06 * Math.sin(t * 23.7 + tp[0] * 0.3));
			// glow
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, 0.55f * flick)));
			float gr = Math.max(4, 300 * k * flick);
			g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), gr, new float[]{0f, 0.35f, 1f},
				new Color[]{new Color(255, 170, 70, 150), new Color(255, 110, 30, 60), new Color(255, 80, 20, 0)}));
			g.fill(new Ellipse2D.Float(x - gr, y - gr, gr * 2, gr * 2));
			g.setComposite(c0);
			// flame: three tongues that sway
			for (int i = 0; i < 3; i++)
			{
				float sway = (float) Math.sin(t * (6 + i * 1.7) + i * 2 + tp[0]) * 8 * k;
				float fh = (112 - i * 26) * k * flick, fw = (44 - i * 12) * k;
				Color[] cols = i == 0
					? new Color[]{new Color(255, 120, 30, 220), new Color(200, 50, 10, 0)}
					: i == 1 ? new Color[]{new Color(255, 190, 70, 240), new Color(255, 120, 30, 0)}
					: new Color[]{new Color(255, 245, 200, 255), new Color(255, 200, 90, 0)};
				g.setPaint(new RadialGradientPaint(new Point2D.Float(x + sway * 0.4f, y + 10 * k), Math.max(1, fh * 0.75f), new float[]{0f, 1f}, cols));
				Path2D.Float p = new Path2D.Float();
				p.moveTo(x - fw / 2, y + 30 * k);
				p.quadTo(x - fw * 0.7f, y - fh * 0.2f, x + sway, y - fh);
				p.quadTo(x + fw * 0.7f, y - fh * 0.2f, x + fw / 2, y + 30 * k);
				p.closePath();
				g.fill(p);
			}
			// sparks from the torches
			if (rnd.nextFloat() < dt * 6)
			{
				embers.add(new float[]{x + (rnd.nextFloat() - 0.5f) * 20 * k, y - 70 * k, (rnd.nextFloat() - 0.5f) * 30,
					-(40 + rnd.nextFloat() * 60), 1.2f + rnd.nextFloat(), (1.2f + rnd.nextFloat() * 1.8f) * Math.max(1f, k)});
			}
		}
		// embers drifting up across the whole screen
		if (embers.size() < 140 && rnd.nextFloat() < dt * 28)
		{
			embers.add(new float[]{rnd.nextFloat() * cw, ch + 5, (rnd.nextFloat() - 0.5f) * 25, -(25 + rnd.nextFloat() * 55),
				4f + rnd.nextFloat() * 5, (1f + rnd.nextFloat() * 2.4f) * Math.max(1f, k)});
		}
		for (int i = embers.size() - 1; i >= 0; i--)
		{
			float[] e = embers.get(i);
			e[0] += (e[2] + (float) Math.sin(t * 2 + i) * 12) * dt;
			e[1] += e[3] * dt;
			e[4] -= dt;
			if (e[4] <= 0 || e[1] < -10)
			{
				embers.remove(i);
				continue;
			}
			float a = Math.min(1f, e[4] / 1.2f);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f * a));
			g.setColor(new Color(255, 190 - (int) (60 * (1 - a)), 90));
			g.fill(new Ellipse2D.Float(e[0], e[1], e[5], e[5]));
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f * a));
			g.fill(new Ellipse2D.Float(e[0] - e[5], e[1] - e[5], e[5] * 3, e[5] * 3));
		}
		g.setComposite(c0);
	}

	private void paintPanel(Graphics2D g, long now)
	{
		boolean busy = attempting || client.getGameState() == GameState.LOGGING_IN;

		// logo
		if (shield != null)
		{
			g.drawImage(shield, W / 2 - 42, 4, 84, 84, null);
		}
		goldText(g, "GADSCAPE", title, W / 2, 124);

		int px = 242, py = 150, pw = 281, ph = 238;
		ornateFrame(g, px, py, pw, ph);
		if (message != null)
		{
			plaque(g, "NOTICE", W / 2, py, 110);
			paintMessage(g, px, py, pw, ph);
			paintAccounts(g, py + ph + 4);
			return;
		}
		plaque(g, "WELCOME", W / 2, py, 132);

		int fx = px + 26, fw = pw - 52;
		textLeft(g, "USERNAME", label, fx + 2, py + 34, GOLD);
		Rectangle uf = new Rectangle(fx, py + 40, fw, 28);
		drawField(g, uf, username.toString(), field == 0);
		building.add(new Hit("user", uf));

		textLeft(g, "PASSWORD", label, fx + 2, py + 86, GOLD);
		Rectangle pf = new Rectangle(fx, py + 92, fw, 28);
		drawField(g, pf, mask(password.length()), field == 1);
		building.add(new Hit("pass", pf));

		// remember me + forgot password
		Font small = label.deriveFont(Font.PLAIN, 11.5f);
		Rectangle rb = new Rectangle(fx + 1, py + 130, 14, 14);
		g.setColor(FIELD);
		g.fillRoundRect(rb.x, rb.y, rb.width, rb.height, 4, 4);
		g.setColor("remember".equals(hover) ? GOLD_LIGHT : GOLD_DARK);
		g.drawRoundRect(rb.x, rb.y, rb.width, rb.height, 4, 4);
		if (saved.isRemember())
		{
			g.setColor(GOLD_LIGHT);
			g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(rb.x + 3, rb.y + 7, rb.x + 6, rb.y + 11);
			g.drawLine(rb.x + 6, rb.y + 11, rb.x + 11, rb.y + 3);
			g.setStroke(new BasicStroke(1f));
		}
		textLeft(g, "Remember me", small, rb.x + 21, rb.y + 11, "remember".equals(hover) ? TEXT : SUBTLE);
		building.add(new Hit("remember", new Rectangle(rb.x, rb.y - 2, 100, 18)));
		String forgot = "Forgot password?";
		int fwid = g.getFontMetrics(small).stringWidth(forgot);
		textLeft(g, forgot, small, fx + fw - fwid, rb.y + 11, "forgot".equals(hover) ? GOLD_LIGHT : SUBTLE);
		building.add(new Hit("forgot", new Rectangle(fx + fw - fwid, rb.y - 2, fwid, 18)));

		// log in button
		Rectangle lb = new Rectangle(fx, py + 156, fw, 38);
		boolean lh = "login".equals(hover) && !busy;
		g.setPaint(new GradientPaint(0, lb.y, busy ? GOLD_DARK : (lh ? new Color(0xFFF0B0) : GOLD_LIGHT), 0, lb.y + lb.height,
			busy ? new Color(0x4A3412) : new Color(0xB07A22)));
		g.fill(new RoundRectangle2D.Float(lb.x, lb.y, lb.width, lb.height, 8, 8));
		g.setColor(new Color(0x3A1A06));
		g.setStroke(new BasicStroke(1.5f));
		g.draw(new RoundRectangle2D.Float(lb.x, lb.y, lb.width, lb.height, 8, 8));
		g.setColor(new Color(255, 255, 255, 70));
		g.draw(new RoundRectangle2D.Float(lb.x + 2, lb.y + 2, lb.width - 4, lb.height / 2f, 6, 6));
		g.setStroke(new BasicStroke(1f));
		String lt = busy ? "LOGGING IN" + dots(now) : "LOG IN";
		centerText(g, lt, heading.deriveFont(19f), W / 2, lb.y + 26, busy ? SUBTLE : new Color(0x3A1206), new Color(255, 240, 200, 120));
		building.add(new Hit("login", lb));

		// "New here? Check us out" - "Check us out" opens the website
		String newHere = "New here? ";
		String site = "Check us out";
		FontMetrics sm = g.getFontMetrics(small);
		int nw = sm.stringWidth(newHere), sw = sm.stringWidth(site);
		int cx = W / 2 - (nw + sw) / 2, ty = py + 218;
		boolean sh = "site".equals(hover);
		textLeft(g, newHere, small, cx, ty, SUBTLE);
		textLeft(g, site, small, cx + nw, ty, sh ? GOLD_LIGHT : GOLD);
		g.setColor(sh ? GOLD_LIGHT : GOLD);
		g.drawLine(cx + nw, ty + 2, cx + nw + sw, ty + 2);
		building.add(new Hit("site", new Rectangle(cx + nw - 2, ty - 13, sw + 4, 18)));

		paintAccounts(g, py + ph + 4);
	}

	private void paintAccounts(Graphics2D g, int top)
	{
		List<SavedAccounts.Account> list = saved.list();
		loadHeads(list);
		int perPage = 4;
		int tiles = list.size() + 1; // + the "New" tile
		int pages = Math.max(1, (tiles + perPage - 1) / perPage);
		accountPage = Math.max(0, Math.min(accountPage, pages - 1));
		int tw = 58, th = 66, gap = 8;
		int first = accountPage * perPage, count = Math.min(perPage, tiles - first);
		int total = count * tw + (count - 1) * gap;
		int bw = Math.max(total + 64, 200), bx = W / 2 - bw / 2, by = top + 10, bh = th + 20;
		ornateFrame(g, bx, by, bw, bh);
		plaque(g, "SAVED ACCOUNTS", W / 2, by, 150);
		int x = W / 2 - total / 2, y = by + 13;
		for (int i = 0; i < count; i++)
		{
			int idx = first + i;
			Rectangle r = new Rectangle(x + i * (tw + gap), y, tw, th);
			if (idx == list.size())
			{
				boolean h = "new".equals(hover);
				tile(g, r, h);
				g.setColor(h ? GOLD_LIGHT : GOLD);
				g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(r.x + tw / 2, r.y + 14, r.x + tw / 2, r.y + 36);
				g.drawLine(r.x + tw / 2 - 11, r.y + 25, r.x + tw / 2 + 11, r.y + 25);
				g.setStroke(new BasicStroke(1f));
				centerText(g, "New", label.deriveFont(11f), r.x + tw / 2, r.y + th - 7, h ? GOLD_LIGHT : SUBTLE, null);
				building.add(new Hit("new", r));
				continue;
			}
			SavedAccounts.Account a = list.get(idx);
			boolean h = ("acc:" + a.username).equals(hover);
			tile(g, r, h);
			BufferedImage head = heads.get(a.username.toLowerCase());
			if (head != null)
			{
				// a strip of frames side by side: play it as a loop, each account at its own point in it
				int fs = head.getHeight(), frames = Math.max(1, head.getWidth() / Math.max(1, fs));
				int fi = (int) ((System.currentTimeMillis() / 75 + Math.abs(a.username.hashCode()) % frames) % frames);
				g.drawImage(head, r.x + 3, r.y + 2, r.x + tw - 3, r.y + tw - 4, fi * fs, 0, fi * fs + fs, fs, null);
			}
			else if (shield != null)
			{
				g.drawImage(shield, r.x + 12, r.y + 8, tw - 24, tw - 24, null);
			}
			Font nf = new Font(Font.SANS_SERIF, Font.BOLD, 10);
			centerText(g, ellipsize(g, a.username, nf, tw - 6), nf, r.x + tw / 2, r.y + th - 5, h ? GOLD_LIGHT : TEXT, null);
			building.add(new Hit("acc:" + a.username, r));
			Rectangle xr = new Rectangle(r.x + tw - 14, r.y + 2, 12, 12);
			boolean xh = ("del:" + a.username).equals(hover);
			g.setColor(new Color(0, 0, 0, 150));
			g.fillOval(xr.x, xr.y, xr.width, xr.height);
			g.setColor(xh ? new Color(0xFF6B6B) : GOLD);
			g.setStroke(new BasicStroke(1.5f));
			g.drawLine(xr.x + 3, xr.y + 3, xr.x + 9, xr.y + 9);
			g.drawLine(xr.x + 9, xr.y + 3, xr.x + 3, xr.y + 9);
			g.setStroke(new BasicStroke(1f));
			building.add(0, new Hit("del:" + a.username, xr));
		}
		if (pages > 1)
		{
			if (accountPage > 0)
			{
				Rectangle l = new Rectangle(bx + 10, y + th / 2 - 10, 14, 20);
				arrow(g, l, -1, "prev".equals(hover));
				building.add(new Hit("prev", l));
			}
			if (accountPage < pages - 1)
			{
				Rectangle r = new Rectangle(bx + bw - 24, y + th / 2 - 10, 14, 20);
				arrow(g, r, 1, "next".equals(hover));
				building.add(new Hit("next", r));
			}
		}
	}

	/** A line of the message; {@code url} makes it a link. */
	private static final class Line
	{
		final String text, url;

		Line(String text, String url)
		{
			this.text = text;
			this.url = url;
		}
	}

	/** The game's login replies in plain words, with where to go to fix it. */
	private static List<Line> friendly(String m)
	{
		String l = m.toLowerCase();
		List<Line> out = new ArrayList<>();
		if (l.contains("invalid") || l.contains("credential") || l.contains("incorrect") || l.contains("wrong password"))
		{
			out.add(new Line("Invalid username or password.", null));
			out.add(new Line("Forgot your password?", null));
			out.add(new Line("Check out gadscape.com/login", FORGOT_URL));
		}
		else if (l.contains("not logged out") || l.contains("already logged"))
		{
			out.add(new Line("Your account is still logged in.", null));
			out.add(new Line("Please wait a minute and try again.", null));
		}
		else if (l.contains("disabled") || l.contains("banned") || l.contains("locked"))
		{
			out.add(new Line("This account has been disabled.", null));
			out.add(new Line("For help, visit gadscape.com", "https://gadscape.com"));
		}
		else if (l.contains("updated"))
		{
			out.add(new Line("GadScape has been updated!", null));
			out.add(new Line("Please restart the game to get the update.", null));
		}
		else if (l.contains("world is full") || l.contains("this world is full"))
		{
			out.add(new Line("The world is full right now.", null));
			out.add(new Line("Please try again in a moment.", null));
		}
		else if (l.contains("too many") || l.contains("login limit"))
		{
			out.add(new Line("Too many login attempts.", null));
			out.add(new Line("Please wait a few minutes and try again.", null));
		}
		else if (l.contains("connect") || l.contains("timed out") || l.contains("no response") || l.contains("offline")
			|| l.contains("no reply") || l.contains("unavailable") || l.contains("being updated"))
		{
			out.add(new Line("Couldn't reach the GadScape server.", null));
			out.add(new Line("Please try again in a moment.", null));
		}
		else
		{
			out.add(new Line(m, null));
		}
		return out;
	}

	private void paintMessage(Graphics2D g, int px, int py, int pw, int ph)
	{
		Font f = new Font(Font.SANS_SERIF, Font.BOLD, 14);
		List<Line> lines = new ArrayList<>();
		for (Line l : friendly(message))
		{
			for (String w : wrap(g, l.text, f, pw - 44))
			{
				lines.add(new Line(w, l.url));
			}
		}
		int lh = 24, ty = py + ph / 2 - (lines.size() * lh) / 2 + 14;
		FontMetrics fm = g.getFontMetrics(f);
		for (Line l : lines)
		{
			if (l.url != null)
			{
				boolean h = "link".equals(hover);
				centerText(g, l.text, f, W / 2, ty, h ? GOLD_LIGHT : GOLD, new Color(0, 0, 0, 200));
				int w = fm.stringWidth(l.text);
				g.setColor(h ? GOLD_LIGHT : GOLD_DARK);
				g.fillRect(W / 2 - w / 2, ty + 3, w, 1);
				building.add(new Hit("link:" + l.url, new Rectangle(W / 2 - w / 2, ty - 14, w, 20)));
			}
			else
			{
				centerText(g, l.text, f, W / 2, ty, TEXT, new Color(0, 0, 0, 200));
			}
			ty += lh;
		}
		// close (x), top right of the panel
		Rectangle x = new Rectangle(px + pw - 30, py + 14, 16, 16);
		boolean xh = "close".equals(hover);
		g.setColor(xh ? GOLD_LIGHT : GOLD);
		g.setStroke(new BasicStroke(1.4f));
		g.draw(new Ellipse2D.Float(x.x, x.y, x.width, x.height));
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(x.x + 5, x.y + 5, x.x + 11, x.y + 11);
		g.drawLine(x.x + 11, x.y + 5, x.x + 5, x.y + 11);
		g.setStroke(new BasicStroke(1f));
		building.add(new Hit("close", new Rectangle(x.x - 4, x.y - 4, x.width + 8, x.height + 8)));
	}

	// ------------------------------------------------------------------ drawing helpers

	/** Dark panel with a double gold border and corner ornaments. */
	private static void ornateFrame(Graphics2D g, int x, int y, int w, int h)
	{
		g.setColor(new Color(0, 0, 0, 120));
		g.fill(new RoundRectangle2D.Float(x + 3, y + 4, w, h, 14, 14));
		g.setPaint(new GradientPaint(0, y, new Color(40, 12, 12, 235), 0, y + h, PANEL));
		g.fill(new RoundRectangle2D.Float(x, y, w, h, 14, 14));
		g.setStroke(new BasicStroke(2.2f));
		g.setPaint(new GradientPaint(0, y, GOLD_LIGHT, 0, y + h, GOLD_DARK));
		g.draw(new RoundRectangle2D.Float(x, y, w, h, 14, 14));
		g.setStroke(new BasicStroke(0.9f));
		g.setColor(new Color(226, 181, 74, 140));
		g.draw(new RoundRectangle2D.Float(x + 5, y + 5, w - 10, h - 10, 10, 10));
		g.setStroke(new BasicStroke(1f));
		for (int[] c : new int[][]{{x, y}, {x + w, y}, {x, y + h}, {x + w, y + h}})
		{
			diamond(g, c[0], c[1], 7);
		}
		diamond(g, x, y + h / 2, 5);
		diamond(g, x + w, y + h / 2, 5);
	}

	private static void diamond(Graphics2D g, int cx, int cy, int r)
	{
		Path2D.Float p = new Path2D.Float();
		p.moveTo(cx, cy - r);
		p.lineTo(cx + r, cy);
		p.lineTo(cx, cy + r);
		p.lineTo(cx - r, cy);
		p.closePath();
		g.setPaint(new GradientPaint(cx, cy - r, GOLD_LIGHT, cx, cy + r, GOLD_DARK));
		g.fill(p);
		g.setColor(new Color(0x3A1A06));
		g.draw(p);
		g.setColor(RED);
		int d = Math.max(2, r * 2 / 3);
		g.fill(new Ellipse2D.Float(cx - d / 2f, cy - d / 2f, d, d));
	}

	/** A small gold title plate sitting on a frame's top edge. */
	private void plaque(Graphics2D g, String text, int cx, int y, int w)
	{
		int h = 20, x = cx - w / 2, py = y - h / 2;
		Path2D.Float p = new Path2D.Float();
		p.moveTo(x, py + h / 2f);
		p.lineTo(x + 10, py);
		p.lineTo(x + w - 10, py);
		p.lineTo(x + w, py + h / 2f);
		p.lineTo(x + w - 10, py + h);
		p.lineTo(x + 10, py + h);
		p.closePath();
		g.setPaint(new GradientPaint(0, py, new Color(0x6E1A1A), 0, py + h, new Color(0x2E0909)));
		g.fill(p);
		g.setPaint(new GradientPaint(0, py, GOLD_LIGHT, 0, py + h, GOLD_DARK));
		g.setStroke(new BasicStroke(1.6f));
		g.draw(p);
		g.setStroke(new BasicStroke(1f));
		centerText(g, text, heading.deriveFont(12.5f), cx, py + 15, GOLD_LIGHT, new Color(0, 0, 0, 200));
	}

	private static void tile(Graphics2D g, Rectangle r, boolean hover)
	{
		g.setPaint(new GradientPaint(0, r.y, hover ? new Color(0x4A1414) : new Color(0x2A0C0C), 0, r.y + r.height, new Color(0x120606)));
		g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6));
		g.setColor(hover ? GOLD_LIGHT : GOLD_DARK);
		g.setStroke(new BasicStroke(hover ? 1.6f : 1.1f));
		g.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6));
		g.setStroke(new BasicStroke(1f));
	}

	private static void arrow(Graphics2D g, Rectangle r, int dir, boolean hover)
	{
		Path2D.Float p = new Path2D.Float();
		if (dir < 0)
		{
			p.moveTo(r.x + r.width, r.y);
			p.lineTo(r.x, r.y + r.height / 2f);
			p.lineTo(r.x + r.width, r.y + r.height);
		}
		else
		{
			p.moveTo(r.x, r.y);
			p.lineTo(r.x + r.width, r.y + r.height / 2f);
			p.lineTo(r.x, r.y + r.height);
		}
		p.closePath();
		g.setColor(hover ? GOLD_LIGHT : GOLD);
		g.fill(p);
	}

	private void drawField(Graphics2D g, Rectangle r, String value, boolean focused)
	{
		if (focused)
		{
			g.setColor(new Color(226, 181, 74, 50));
			g.fill(new RoundRectangle2D.Float(r.x - 2, r.y - 2, r.width + 4, r.height + 4, 8, 8));
		}
		g.setColor(FIELD);
		g.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6));
		g.setColor(focused ? GOLD : GOLD_DARK);
		g.draw(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 6, 6));
		FontMetrics fm = g.getFontMetrics(input);
		String shown = value;
		while (fm.stringWidth(shown) > r.width - 20 && shown.length() > 0)
		{
			shown = shown.substring(1);
		}
		textLeft(g, shown, input, r.x + 9, r.y + 19, TEXT);
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0)
		{
			g.setColor(GOLD_LIGHT);
			g.fill(new Rectangle(r.x + 10 + fm.stringWidth(shown), r.y + 6, 1, r.height - 12));
		}
	}

	private static void goldText(Graphics2D g, String s, Font f, int cx, int y)
	{
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		int x = cx - fm.stringWidth(s) / 2;
		g.setColor(new Color(0, 0, 0, 190));
		g.drawString(s, x + 2, y + 3);
		g.setColor(new Color(0x3A1A06));
		g.drawString(s, x + 1, y + 1);
		g.setPaint(new GradientPaint(0, y - fm.getAscent(), new Color(0xFFF2C0), 0, y, new Color(0xC08A2A)));
		g.drawString(s, x, y);
	}

	private static void textLeft(Graphics2D g, String s, Font f, int x, int y, Color c)
	{
		g.setFont(f);
		g.setColor(new Color(0, 0, 0, 170));
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}

	private static void centerText(Graphics2D g, String s, Font f, int cx, int y, Color c, Color shadow)
	{
		g.setFont(f);
		int x = cx - g.getFontMetrics().stringWidth(s) / 2;
		if (shadow != null)
		{
			g.setColor(shadow);
			g.drawString(s, x + 1, y + 1);
		}
		g.setColor(c);
		g.drawString(s, x, y);
	}

	private static String ellipsize(Graphics2D g, String s, Font f, int max)
	{
		FontMetrics fm = g.getFontMetrics(f);
		if (fm.stringWidth(s) <= max)
		{
			return s;
		}
		while (s.length() > 1 && fm.stringWidth(s + "..") > max)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + "..";
	}

	private static List<String> wrap(Graphics2D g, String s, Font f, int max)
	{
		FontMetrics fm = g.getFontMetrics(f);
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String w : s.split("\\s+"))
		{
			String t = line.length() == 0 ? w : line + " " + w;
			if (fm.stringWidth(t) > max && line.length() > 0)
			{
				out.add(line.toString());
				line = new StringBuilder(w);
			}
			else
			{
				line = new StringBuilder(t);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out.size() > 3 ? out.subList(0, 3) : out;
	}

	private static String mask(int n)
	{
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++)
		{
			b.append('*');
		}
		return b.toString();
	}

	private static String dots(long now)
	{
		return "...".substring(0, (int) (now / 400 % 4));
	}

	/** Face pictures saved after logging in (gadscape-heads/<name>.png); re-read when one changes. */
	private void loadHeads(List<SavedAccounts.Account> list)
	{
		long now = System.currentTimeMillis();
		if (now - lastHeadCheck < 1500)
		{
			return;
		}
		lastHeadCheck = now;
		for (SavedAccounts.Account a : list)
		{
			String key = a.username.toLowerCase();
			File f = new File(headsDir, HeadCapture.fileName(a.username));
			long t = f.isFile() ? f.lastModified() : 0;
			if (t == 0)
			{
				heads.remove(key);
				continue;
			}
			Long known = headTimes.get(key);
			if (known == null || known != t)
			{
				try
				{
					heads.put(key, ImageIO.read(f));
					headTimes.put(key, t);
				}
				catch (Exception ignored)
				{
					// keep the shield instead
				}
			}
		}
	}

	// ------------------------------------------------------------------ logging in

	private void login(String user, String pass)
	{
		if (attempting || client.getGameState() == GameState.LOGGING_IN)
		{
			return;
		}
		if (user.trim().isEmpty() || pass.isEmpty())
		{
			message = user.trim().isEmpty() ? "Please enter your username." : "Please enter your password.";
			return;
		}
		attemptUser = user.trim();
		attemptPass = pass;
		attempting = true;
		sawLoggingIn = false;
		attemptStart = System.currentTimeMillis();
		saved.setLastUsername(attemptUser);
		Thread t = new Thread(() ->
		{
			try
			{
				if (client.getLoginIndex() != 2)
				{
					client.setLoginIndex(2);
					Thread.sleep(150);
				}
				client.setUsername(attemptUser);
				client.setPassword(attemptPass);
				Thread.sleep(50);
				injecting = true;
				try
				{
					// Enter on the username field moves to the password, Enter on the password logs in
					Keyboard.sendEnter();
					Keyboard.sendEnter();
				}
				finally
				{
					injecting = false;
				}
			}
			catch (Exception e)
			{
				attempting = false;
				message = "Couldn't start the login. Please try again.";
			}
		}, "gadscape-login");
		t.setDaemon(true);
		t.start();
	}

	private void checkAttempt()
	{
		if (!attempting)
		{
			return;
		}
		GameState s = client.getGameState();
		if (s == GameState.LOGGING_IN)
		{
			sawLoggingIn = true;
			return;
		}
		if (s == GameState.LOGGED_IN || s == GameState.LOADING || s == GameState.LOGIN_SCREEN_AUTHENTICATOR)
		{
			return;
		}
		long waited = System.currentTimeMillis() - attemptStart;
		if (s == GameState.LOGIN_SCREEN && (sawLoggingIn ? waited > 400 : waited > 4000))
		{
			attempting = false;
			String m = client.getLoginMessage();
			m = m == null ? "" : m.replaceAll("\\s+", " ").trim();
			if (m.isEmpty() || m.toLowerCase().contains("enter your username"))
			{
				m = "Couldn't log in. Please check your details and try again.";
			}
			message = m;
			field = 1;
			password.setLength(0);
			if (client.getLoginIndex() != 2)
			{
				client.setLoginIndex(2);
			}
		}
	}

	void onLoggedIn()
	{
		if (attemptUser != null)
		{
			if (saved.isRemember())
			{
				saved.put(attemptUser, attemptPass);
			}
			else
			{
				saved.setLastUsername(attemptUser);
			}
		}
		attempting = false;
		attemptPass = null;
		password.setLength(0);
		message = null;
	}

	// ------------------------------------------------------------------ mouse

	private String hitAt(MouseEvent e)
	{
		double x = (e.getX() - offX) / scale, y = (e.getY() - offY) / scale;
		for (Hit h : hits)
		{
			if (h.r.contains(x, y))
			{
				return h.id;
			}
		}
		return null;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (!active())
		{
			return e;
		}
		String id = hitAt(e);
		if (id != null)
		{
			click(id);
		}
		e.consume(); // the game's own login screen is underneath: it must not react
		return e;
	}

	private void click(String id)
	{
		if (message != null)
		{
			if (id.equals("close"))
			{
				message = null;
				return;
			}
			if (id.startsWith("link:"))
			{
				LinkBrowser.browse(id.substring(5));
				return;
			}
			if (!id.startsWith("acc:") && !id.startsWith("del:") && !id.equals("new") && !id.equals("prev") && !id.equals("next"))
			{
				return;
			}
			if (!id.startsWith("del:") && !id.equals("prev") && !id.equals("next"))
			{
				message = null;
			}
		}
		switch (id)
		{
			case "user":
				field = 0;
				break;
			case "pass":
				field = 1;
				break;
			case "remember":
				saved.setRemember(!saved.isRemember());
				break;
			case "forgot":
				LinkBrowser.browse(FORGOT_URL);
				break;
			case "site":
				LinkBrowser.browse(SITE_URL);
				break;
			case "login":
				login(username.toString(), password.toString());
				break;
			case "new":
				username.setLength(0);
				password.setLength(0);
				field = 0;
				break;
			case "prev":
				accountPage--;
				break;
			case "next":
				accountPage++;
				break;
			default:
				if (id.startsWith("del:"))
				{
					String user = id.substring(4);
					saved.remove(user);
					new File(headsDir, HeadCapture.fileName(user)).delete();
				}
				else if (id.startsWith("acc:"))
				{
					String user = id.substring(4);
					for (SavedAccounts.Account a : saved.list())
					{
						if (a.username.equals(user))
						{
							username.setLength(0);
							username.append(a.username);
							password.setLength(0);
							password.append(a.password);
							field = 1;
							login(a.username, a.password);
							break;
						}
					}
				}
		}
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		if (active())
		{
			String id = hitAt(e);
			hover = id == null ? "" : id.startsWith("link:") ? "link" : id;
		}
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (active())
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (active())
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}

	// ------------------------------------------------------------------ keyboard

	@Override
	public boolean isEnabledOnLoginScreen()
	{
		return true;
	}

	private boolean ours()
	{
		return !injecting && active();
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!ours())
		{
			return;
		}
		char c = e.getKeyChar();
		if (message == null && !attempting && allowed(field, c) && !e.isControlDown())
		{
			StringBuilder b = field == 0 ? username : password;
			if (b.length() < max(field))
			{
				b.append(c);
			}
		}
		e.consume();
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!ours())
		{
			return;
		}
		if (message != null)
		{
			if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				message = null;
			}
			e.consume();
			return;
		}
		StringBuilder b = field == 0 ? username : password;
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_BACK_SPACE:
				if (b.length() > 0)
				{
					b.setLength(b.length() - 1);
				}
				break;
			case KeyEvent.VK_TAB:
			case KeyEvent.VK_UP:
			case KeyEvent.VK_DOWN:
				field = 1 - field;
				break;
			case KeyEvent.VK_ENTER:
				if (field == 0 && password.length() == 0)
				{
					field = 1;
				}
				else
				{
					login(username.toString(), password.toString());
				}
				break;
			case KeyEvent.VK_V:
				if (e.isControlDown())
				{
					paste(b, field);
				}
				break;
			default:
				break;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (ours())
		{
			e.consume();
		}
	}

	private static void paste(StringBuilder b, int field)
	{
		try
		{
			String s = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			for (char c : s.toCharArray())
			{
				if (allowed(field, c) && b.length() < max(field))
				{
					b.append(c);
				}
			}
		}
		catch (Exception ignored)
		{
			// nothing usable on the clipboard
		}
	}
}
