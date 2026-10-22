package gadscape.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts the GadScape client: RuneLite 1.13.3 with the patched injected-client, RuneLite's client jar branded as
 * GadScape and GadScape's own plugins (gadscape-plugins.jar).
 *
 * <p>The build channel ({@link Channel}: release or test) is baked into the launcher jar by build.sh
 * (channel.properties): window title, world name, the home folder and the default server differ, so a tester can
 * have both installed. A launcher without that file behaves as the test channel.
 *
 * <p>What it does, in order:
 * <ol>
 *   <li>Moves everything RuneLite and the game write into a separate home folder (user.home), so the player's real
 *   RuneLite settings and OSRS cache are never read or changed. RuneLite keeps its files in user.home/.runelite and
 *   the game keeps its cache in user.home/.runelite/jagexcache (RuneLite sets jagex.userhome to that folder).
 *   run.sh, run.bat and the exe also pass -Duser.home, so values the JVM caches at startup agree.</li>
 *   <li>Checks that the injected-client on the classpath is the GadScape-patched one, and that there is only one.</li>
 *   <li>Serves jav_config.ws and the world list on 127.0.0.1 (a random free port). RuneLite 1.13.3 only takes a
 *   --jav_config on a *.runescape.com / *.jagex.com host, so it gets http://{@value #JAV_HOST}/jav_config.ws and a
 *   proxy rule in this JVM sends that one name to the local server (no DNS lookup).
 *   With --jav-config URL, the launcher downloads that jav_config and serves it the same way.</li>
 *   <li>Keeps private-server gameplay out of public services: requests to {@link #BLOCKED_HOSTS} (OSRS Wiki
 *   crowdsourcing, XP trackers) are answered "403" by a local dead-end proxy, and RuneLite's API base is pointed at
 *   the local server, which passes reads (GET/HEAD: item prices, world list, Plugin Hub counts, chat lookups) on to
 *   RuneLite with a redirect and refuses writes (POST/PUT/PATCH/DELETE: !kc/!pb/!qp and other chat submissions,
 *   loot and config sync).</li>
 *   <li>Writes RuneLite's runtime config to a local file (runelite.rtconf): RuneLite's live config without the
 *   lists that block logins on old clients or old Plugin Hub plugins ("outdatedClientVersions",
 *   "outdatedPluginVersions") and the outage fields, plus props: Discord off (it registers a URL handler outside the
 *   client's home), the window title and the API base above.</li>
 *   <li>Writes GadScape's default RuneLite settings ({@link Defaults}: plugins that cannot work on a private server
 *   off, GPU on), once per profile; --no-defaults skips it.</li>
 *   <li>Runs net.runelite.client.RuneLite.main in this same JVM.</li>
 * </ol>
 *
 * <p>Options: --host H (game server host, default: the channel's server, or the first line of --host-file F),
 * --world N (default 1), --session S (jav_config param 9), --home DIR, --jav-config URL, --http-port P, --serve-only,
 * --no-defaults; anything after "--" goes to RuneLite as is (for example -- --safe-mode --debug).
 */
public final class Launcher
{
	static final int REVISION = 241;
	/** Jagex's param 9 as of build 241; the old GadScape server checked for this same value. */
	static final String DEFAULT_SESSION = "ElZAIrq5NpKN6D3mDdihco3oPeYN2KFy2DCquj7JMmECPmLrDP3Bnw";
	static final String MARKER = "gadscape-patch.properties";
	/** Written by client241/patcher brand into RuneLite's client jar. */
	static final String CLIENT_MARKER = "gadscape-client-patch.properties";
	/** Name RuneLite fetches jav_config from; routed to this launcher's own HTTP server, never resolved in DNS. */
	static final String JAV_HOST = "jav-config.gadscape-test.runescape.com";
	/** This build's channel (release or test), from channel.properties in the launcher jar. */
	static final Channel CHANNEL = Channel.load();
	/**
	 * Hosts (and their subdomains) that only receive gameplay data: requests to them are refused. OSRS Wiki
	 * crowdsourcing (on by default in RuneLite) and the XP updater's trackers.
	 */
	static final String[] BLOCKED_HOSTS = {"crowdsource.runescape.wiki", "wiseoldman.net", "templeosrs.com", "crystalmathlabs.com"};
	/** Path on the local server that stands in for RuneLite's API base (runelite.api.base). */
	static final String API_PATH = "/runelite-api";
	/** The game's port: the build-241 client always connects here (then 443). */
	static final int GAME_PORT = 43594;

	public static void main(String[] args) throws Exception
	{
		try
		{
			run(args);
		}
		catch (IllegalArgumentException e)
		{
			System.err.println("[gadscape-launcher] error: " + e.getMessage());
			System.exit(2);
		}
	}

	static void run(String[] args) throws Exception
	{
		String host = null, hostFile = null, session = DEFAULT_SESSION, home = null, javConfig = null, activity = CHANNEL.activity;
		boolean defaults = true;
		int world = 1, httpPort = 0;
		boolean serveOnly = false;
		List<String> rlArgs = new ArrayList<>();
		for (int i = 0; i < args.length; i++)
		{
			String a = args[i];
			switch (a)
			{
				case "--host": host = value(args, ++i, a); break;
				case "--host-file": hostFile = value(args, ++i, a); break;
				case "--world": world = number(args, ++i, a); break;
				case "--activity": activity = value(args, ++i, a); break;
				case "--session": session = value(args, ++i, a); break;
				case "--home": home = value(args, ++i, a); break;
				case "--jav-config": javConfig = value(args, ++i, a); break;
				case "--http-port": httpPort = number(args, ++i, a); break;
				case "--serve-only": serveOnly = true; break;
				case "--no-defaults": defaults = false; break;
				case "--":
					for (int j = i + 1; j < args.length; j++)
					{
						rlArgs.add(args[j]);
					}
					i = args.length;
					break;
				default:
					throw new IllegalArgumentException("unknown option " + a + " (RuneLite options go after --)");
			}
		}
		if (host == null && hostFile != null)
		{
			host = readHostFile(Paths.get(hostFile));
		}
		host = host == null ? "" : normalizeHost(host);
		if (host.isEmpty())
		{
			host = CHANNEL.defaultHost;
		}
		if (!host.matches("[A-Za-z0-9.\\-]+"))
		{
			throw new IllegalArgumentException("server host must be a host name or IPv4 address, got '" + host + "'"
				+ (hostFile != null ? " (from " + hostFile + ")" : ""));
		}

		// 1. isolation: a separate user.home for RuneLite and the game cache
		Path homeDir = Paths.get(home != null ? home : defaultHome()).toAbsolutePath();
		Files.createDirectories(homeDir);
		System.setProperty("user.home", homeDir.toString());
		log(CHANNEL.title + " (" + CHANNEL.name + " build); home folder " + homeDir + " (RuneLite settings: " + homeDir.resolve(".runelite") + ", game cache: "
			+ homeDir.resolve(".runelite").resolve("jagexcache") + "; your normal RuneLite folders are not used)");

		// 2. the patched injected-client must be the only one on the classpath, and RuneLite's client jar only once
		if (!serveOnly)
		{
			checkInjectedClient();
			checkRuneLiteClient();
		}

		// 3. jav_config + world list, served on 127.0.0.1
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), httpPort), 0);
		int port = server.getAddress().getPort();
		String base = "http://127.0.0.1:" + port;
		byte[] jav;
		if (javConfig == null)
		{
			jav = javConfig(host, world, session, base + "/worldlist.ws", CHANNEL).getBytes(StandardCharsets.ISO_8859_1);
			byte[] worlds = worldList(world, host, activity);
			server.createContext("/worldlist.ws", ex -> reply(ex, "application/octet-stream", worlds));
			log("jav_config: world " + world + " at " + host + ":" + GAME_PORT + ", world list " + base + "/worldlist.ws");
		}
		else
		{
			jav = fetch(javConfig);
			log("jav_config: copied from " + javConfig + " (" + jav.length + " bytes)");
		}
		server.createContext("/jav_config.ws", ex -> reply(ex, "text/plain; charset=ISO-8859-1", jav));
		// 4. RuneLite's API: reads go on to RuneLite, writes (game data submissions) stop here
		String realApi = runeLiteProperty("runelite.api.base");
		if (realApi != null)
		{
			server.createContext(API_PATH + "/", ex -> apiFilter(ex, realApi));
		}
		server.createContext("/", ex -> reply(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.US_ASCII)));
		server.setExecutor(null);
		server.start();
		// RuneLite 1.13.3 only accepts a --jav_config URL on a host ending in .jagex.com or .runescape.com
		// (ClientLoader.downloadConfig). JAV_HOST is such a name, but it is never looked up: this proxy rule sends
		// requests for it to the server above on 127.0.0.1, and requests for BLOCKED_HOSTS to a dead end.
		installProxyRules(JAV_HOST, port, deadEndProxy());
		String javUrl = "http://" + JAV_HOST + "/jav_config.ws";
		log("serving jav_config on " + base + "/jav_config.ws, given to RuneLite as " + javUrl);

		// 5. runtime config without the kill switches, Discord off, API writes kept local
		Path rtconf = homeDir.resolve("gadscape").resolve("runelite-runtime-config.json");
		Files.createDirectories(rtconf.getParent());
		Files.write(rtconf, runtimeConfig(realApi != null ? base + API_PATH : null, CHANNEL.title).getBytes(StandardCharsets.UTF_8));
		System.setProperty("runelite.rtconf", rtconf.toString());
		if (realApi != null)
		{
			log("RuneLite API " + realApi + " -> " + base + API_PATH + " (reads pass, writes are refused)");
		}
		else
		{
			log("RuneLite is not on the classpath, RuneLite API filter not set up");
		}
		// 6. GadScape's default RuneLite settings (plugins that cannot work on a private server off, GPU on)
		if (defaults)
		{
			try
			{
				log("RuneLite settings: " + Defaults.apply(homeDir.resolve(".runelite")));
			}
			catch (IOException | RuntimeException e)
			{
				log("WARNING: could not write GadScape's default RuneLite settings: " + e);
			}
		}
		if (serveOnly)
		{
			log("--serve-only: not starting RuneLite; Ctrl+C to stop");
			Thread.currentThread().join();
			return;
		}

		// 7. RuneLite, in this JVM
		List<String> a = new ArrayList<>();
		a.add("--jav_config");
		a.add(javUrl);
		a.add("--disable-telemetry");
		a.addAll(rlArgs);
		log("starting RuneLite " + String.join(" ", a));
		Class<?> rl = Class.forName("net.runelite.client.RuneLite");
		Method main = rl.getMethod("main", String[].class);
		main.invoke(null, (Object) a.toArray(new String[0]));
	}

	static String value(String[] args, int i, String option)
	{
		if (i >= args.length)
		{
			throw new IllegalArgumentException(option + " needs a value");
		}
		return args[i];
	}

	static int number(String[] args, int i, String option)
	{
		String v = value(args, i, option);
		try
		{
			return Integer.parseInt(v);
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(option + " needs a number, got '" + v + "'");
		}
	}

	static boolean blocked(String host)
	{
		if (host == null)
		{
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		for (String b : BLOCKED_HOSTS)
		{
			if (h.equals(b) || h.endsWith("." + b))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Proxy rules for this JVM: requests for one host name go to 127.0.0.1:port (as an HTTP proxy), requests for
	 * {@link #BLOCKED_HOSTS} go to the dead-end proxy, everything else is unchanged.
	 */
	static void installProxyRules(String hostName, int port, Proxy deadEnd)
	{
		ProxySelector previous = ProxySelector.getDefault();
		Proxy local = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
		Set<String> reported = ConcurrentHashMap.newKeySet();
		ProxySelector.setDefault(new ProxySelector()
		{
			@Override
			public List<Proxy> select(URI uri)
			{
				String h = uri.getHost();
				if (hostName.equalsIgnoreCase(h))
				{
					return Collections.singletonList(local);
				}
				if (blocked(h))
				{
					if (reported.add(h.toLowerCase(Locale.ROOT)))
					{
						log("blocked " + h + " (it would get gameplay from this private server)");
					}
					return Collections.singletonList(deadEnd);
				}
				return previous != null ? previous.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
			}

			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException e)
			{
				if (previous != null && !hostName.equalsIgnoreCase(uri.getHost()) && !blocked(uri.getHost()))
				{
					previous.connectFailed(uri, sa, e);
				}
			}
		});
	}

	/** A local HTTP proxy that answers every request (CONNECT included) with 403 and closes: nothing gets through. */
	static Proxy deadEndProxy() throws IOException
	{
		ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread t = new Thread(() ->
		{
			while (true)
			{
				try
				{
					Socket s = ss.accept();
					Thread h = new Thread(() -> refuse(s), "gadscape-dead-end");
					h.setDaemon(true);
					h.start();
				}
				catch (IOException e)
				{
					return;
				}
			}
		}, "gadscape-dead-end-accept");
		t.setDaemon(true);
		t.start();
		return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), ss.getLocalPort()));
	}

	static void refuse(Socket s)
	{
		try (Socket sock = s)
		{
			sock.setSoTimeout(5000);
			InputStream in = sock.getInputStream();
			// read the request head (up to the empty line), then refuse
			int state = 0;
			for (int n = 0; n < 16384 && state < 4; n++)
			{
				int c = in.read();
				if (c < 0)
				{
					break;
				}
				state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : (c == '\r' ? 1 : 0);
			}
			OutputStream out = sock.getOutputStream();
			out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
		}
		catch (IOException ignored)
		{
		}
	}

	/** RuneLite API stand-in: GET/HEAD are redirected to RuneLite's real API, anything else is refused. */
	static void apiFilter(HttpExchange ex, String realApi) throws IOException
	{
		String method = ex.getRequestMethod();
		String rest = ex.getRequestURI().getRawPath().substring(API_PATH.length());
		String query = ex.getRequestURI().getRawQuery();
		try (InputStream in = ex.getRequestBody())
		{
			in.readAllBytes();
		}
		if (method.equals("GET") || method.equals("HEAD"))
		{
			log("api " + method + " " + rest + " -> RuneLite");
			ex.getResponseHeaders().set("Location", realApi + rest + (query != null ? "?" + query : ""));
			ex.getResponseHeaders().set("Cache-Control", "no-store");
			ex.sendResponseHeaders(307, -1);
			ex.close();
		}
		else
		{
			log("api " + method + " " + rest + " refused: the GadScape client does not send game data to RuneLite's API");
			reply(ex, 403, "text/plain", "refused by the GadScape launcher\n".getBytes(StandardCharsets.US_ASCII));
		}
	}

	/** A value from RuneLite's own runelite.properties (in the RuneLite client jar), or null without RuneLite. */
	static String runeLiteProperty(String key) throws IOException
	{
		try (InputStream in = Launcher.class.getClassLoader().getResourceAsStream("net/runelite/client/runelite.properties"))
		{
			if (in == null)
			{
				return null;
			}
			Properties p = new Properties();
			p.load(in);
			String v = p.getProperty(key);
			return v == null || v.isEmpty() ? null : v.replaceAll("/+$", "");
		}
	}

	static byte[] fetch(String url) throws IOException
	{
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(10000);
		c.setReadTimeout(10000);
		if (c.getResponseCode() != 200)
		{
			throw new IOException("jav_config " + url + ": HTTP " + c.getResponseCode());
		}
		try (InputStream in = c.getInputStream())
		{
			return in.readAllBytes();
		}
	}

	static String defaultHome()
	{
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String local = System.getenv("LOCALAPPDATA");
		if (os.contains("win") && local != null)
		{
			return Paths.get(local, CHANNEL.windowsDir, "home").toString();
		}
		return Paths.get(System.getProperty("user.home"), CHANNEL.unixDir, "home").toString();
	}

	/**
	 * The first line of the host file that is not empty or a comment. UTF-8 with or without BOM, or UTF-16 with BOM
	 * (Notepad's "Unicode"). A '#' starts a comment, also after the address.
	 */
	static String readHostFile(Path f) throws IOException
	{
		if (!Files.exists(f))
		{
			return null;
		}
		byte[] b = Files.readAllBytes(f);
		String text;
		if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
		{
			text = new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
		}
		else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
		{
			text = new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
		}
		else if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
		{
			text = new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
		}
		else
		{
			text = new String(b, StandardCharsets.UTF_8);
		}
		for (String line : text.split("\r\n|\r|\n"))
		{
			int hash = line.indexOf('#');
			if (hash >= 0)
			{
				line = line.substring(0, hash);
			}
			line = line.replace("\uFEFF", "").trim();
			if (!line.isEmpty())
			{
				return line;
			}
		}
		return null;
	}

	/**
	 * Accepts what people paste as a server address: drops a URL scheme ("http://"), a path, a user name and a port.
	 * The port cannot be changed (the client always uses {@value #GAME_PORT}), so a different one only gets a warning.
	 */
	static String normalizeHost(String s)
	{
		String h = s.trim().replaceFirst("^[A-Za-z][A-Za-z0-9+.\\-]*://", "");
		int slash = h.indexOf('/');
		if (slash >= 0)
		{
			h = h.substring(0, slash);
		}
		int at = h.lastIndexOf('@');
		if (at >= 0)
		{
			h = h.substring(at + 1);
		}
		int colon = h.indexOf(':');
		if (colon >= 0 && colon == h.lastIndexOf(':'))
		{
			String port = h.substring(colon + 1);
			h = h.substring(0, colon);
			if (!port.isEmpty() && !port.equals(String.valueOf(GAME_PORT)))
			{
				log("WARNING: port " + port + " ignored; the build 241 client always connects to port " + GAME_PORT + " (then 443)");
			}
		}
		return h;
	}

	static void checkInjectedClient() throws IOException
	{
		ClassLoader cl = Launcher.class.getClassLoader();
		List<URL> clients = Collections.list(cl.getResources("client.class"));
		if (clients.size() != 1)
		{
			throw new IllegalStateException("expected exactly one injected-client on the classpath, found " + clients.size() + ": " + clients
				+ " (put only injected-client-1.13.3-gadscape.jar there, not the stock injected-client)");
		}
		Enumeration<URL> markers = cl.getResources(MARKER);
		if (!markers.hasMoreElements())
		{
			throw new IllegalStateException("the injected-client on the classpath (" + clients.get(0)
				+ ") is not patched by client241/patcher (no " + MARKER + "); it would talk to Jagex's RSA key");
		}
		Properties p = new Properties();
		try (InputStream in = markers.nextElement().openStream())
		{
			p.load(in);
		}
		String mod = p.getProperty("rsa.modulus", "");
		log("patched injected-client ok: modulus " + (mod.length() > 16 ? mod.substring(0, 16) + "..." : mod)
			+ ", from stock sha256 " + p.getProperty("source.sha256"));
	}

	/**
	 * RuneLite's client jar must be on the classpath once (two copies of the same classes would mix), and should be
	 * the one client241/patcher branded (GadScape icons, splash, login screen hook). A stock one still runs, with
	 * RuneLite's look and the game's own login screen, so that is only a warning.
	 */
	static void checkRuneLiteClient() throws IOException
	{
		ClassLoader cl = Launcher.class.getClassLoader();
		List<URL> clients = Collections.list(cl.getResources("net/runelite/client/RuneLite.class"));
		if (clients.size() > 1)
		{
			throw new IllegalStateException("RuneLite's client jar is on the classpath " + clients.size() + " times: " + clients
				+ " (keep only client-1.13.3-gadscape.jar)");
		}
		Enumeration<URL> markers = cl.getResources(CLIENT_MARKER);
		if (!markers.hasMoreElements())
		{
			log("WARNING: RuneLite's client jar is not branded by client241/patcher (no " + CLIENT_MARKER
				+ "): RuneLite icons and the game's own login screen");
			return;
		}
		Properties p = new Properties();
		try (InputStream in = markers.nextElement().openStream())
		{
			p.load(in);
		}
		log("branded RuneLite client ok: title " + p.getProperty("title") + ", accent " + p.getProperty("accent"));
	}

	/**
	 * RuneLite's live runtime config minus the fields that could stop this pinned client, plus props that
	 * RuneLite applies over its runelite.properties (RuneLiteModule): Discord off, the window title and, if given, the
	 * API base.
	 */
	static String runtimeConfig(String apiBase, String title)
	{
		String body = null;
		try
		{
			HttpURLConnection c = (HttpURLConnection) new URL("https://static.runelite.net/config.json").openConnection();
			c.setConnectTimeout(5000);
			c.setReadTimeout(5000);
			c.setRequestProperty("User-Agent", "GadScapeLauncher");
			if (c.getResponseCode() == 200)
			{
				try (InputStream in = c.getInputStream())
				{
					body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		}
		catch (IOException e)
		{
			log("could not fetch RuneLite runtime config (" + e + "), using an empty one");
		}
		JsonObject o;
		try
		{
			JsonElement e = body == null ? null : new JsonParser().parse(body);
			o = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
		}
		catch (RuntimeException e)
		{
			o = new JsonObject();
		}
		// outdatedClientVersions / outdatedPluginVersions make Hooks.isRuneLiteClientOutdated() refuse every login
		for (String k : new String[]{"outdatedClientVersions", "outdatedPluginVersions", "outageMessage", "outageLinks", "outageStart", "outageEnd", "updateLauncherWinVers", "updateRollout"})
		{
			o.remove(k);
		}
		JsonObject props = o.has("props") && o.get("props").isJsonObject() ? o.getAsJsonObject("props") : new JsonObject();
		// Discord rich presence registers a discord-<id> URL handler outside the client's home (Windows registry,
		// ~/.local/share/applications) that would replace the one of the player's normal RuneLite
		props.addProperty("runelite.discord.enable", "false");
		// window title (ClientUI's @Named("runelite.title")); the branded client jar has "GadScape" built in, this also
		// marks test builds
		props.addProperty("runelite.title", title);
		if (apiBase != null)
		{
			props.addProperty("runelite.api.base", apiBase);
		}
		o.add("props", props);
		return o.toString();
	}

	/** Same keys and params as Jagex's https://oldschool.runescape.com/jav_config.ws (build 241), pointed at our server. */
	static String javConfig(String host, int world, String session, String worldListUrl, Channel channel)
	{
		StringBuilder sb = new StringBuilder();
		String[] lines = {
			"title=" + channel.title,
			"adverturl=",
			"codebase=http://" + host + "/",
			"cachedir=oldschool",
			"storebase=0",
			"initial_jar=gamepack_241.jar",
			"initial_class=client.class",
			"termsurl=http://www.jagex.com/g=oldscape/terms/terms.ws",
			"privacyurl=http://www.jagex.com/g=oldscape/privacy/privacy.ws",
			"viewerversion=124",
			"win_sub_version=1",
			"mac_sub_version=2",
			"other_sub_version=2",
			"browsercontrol_win_x86_jar=browsercontrol_0_-1928975093.jar",
			"browsercontrol_win_amd64_jar=browsercontrol_1_1674545273.jar",
			"gedigesturl=https://secure.runescape.com/m=itemdb_oldschool/g=oldscape/digest.csv",
			"download=0",
			"window_preferredwidth=800",
			"window_preferredheight=600",
			"advert_height=96",
			"applet_minwidth=765",
			"applet_minheight=503",
			"applet_maxwidth=5760",
			"applet_maxheight=2160",
			"msg=lang0=English",
			"msg=tandc=" + channel.title + ". Not affiliated with Jagex.",
			"param=21=0",
			"param=2=https://payments.jagex.com/",
			"param=7=0",
			"param=11=https://auth.jagex.com/",
			"param=9=" + session,
			"param=19=196515767263-1oo20deqm6edn7ujlihl6rpadk9drhva.apps.googleusercontent.com",
			"param=17=" + worldListUrl,
			"param=14=0",
			"param=12=" + world,
			"param=28=https://account.jagex.com/",
			"param=29=1",
			"param=4=1",
			"param=15=0",
			"param=13=.runescape.com",
			"param=16=false",
			"param=25=" + REVISION,
			"param=22=https://auth.runescape.com/",
			"param=10=5",
			"param=5=1",
			"param=18=",
			"param=20=https://social.auth.jagex.com/",
			"param=8=true",
			"param=3=true",
		};
		for (String l : lines)
		{
			sb.append(l).append('\n');
		}
		return sb.toString();
	}

	/**
	 * The world list in the binary format the game client reads from param 17 (Jagex's slr.ws):
	 * int length of the rest, u16 count, then per world u16 id, i32 flags, cp1252 host + 0, cp1252 activity + 0,
	 * u8 location, i16 players. Flags 1 = members.
	 */
	static byte[] worldList(int world, String host, String activity) throws IOException
	{
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(body);
		d.writeShort(1);
		d.writeShort(world);
		d.writeInt(1);
		d.write(host.getBytes(StandardCharsets.ISO_8859_1));
		d.write(0);
		d.write(activity.getBytes(StandardCharsets.ISO_8859_1));
		d.write(0);
		d.write(0); // location: United States
		d.writeShort(0); // players
		d.flush();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		DataOutputStream o = new DataOutputStream(out);
		o.writeInt(body.size());
		o.write(body.toByteArray());
		o.flush();
		return out.toByteArray();
	}

	static void reply(HttpExchange ex, String type, byte[] body) throws IOException
	{
		reply(ex, 200, type, body);
	}

	static void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException
	{
		log("http " + ex.getRequestMethod() + " " + ex.getRequestURI() + " -> " + code + " (" + body.length + " bytes)");
		ex.getResponseHeaders().set("Content-Type", type);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.sendResponseHeaders(code, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	static void log(String s)
	{
		System.out.println("[gadscape-launcher] " + s);
	}
}
