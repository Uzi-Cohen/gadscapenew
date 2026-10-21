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

/**
 * Starts the GadScape build 241 TEST client: stock RuneLite 1.13.3 with the patched injected-client.
 *
 * <p>What it does, in order:
 * <ol>
 *   <li>Moves everything RuneLite and the game write into a separate home folder (user.home), so the player's real
 *   RuneLite settings and OSRS cache are never read or changed. RuneLite keeps its files in user.home/.runelite and
 *   the game keeps its cache in user.home/.runelite/jagexcache (RuneLite sets jagex.userhome to that folder).</li>
 *   <li>Checks that the injected-client on the classpath is the GadScape-patched one, and that there is only one.</li>
 *   <li>Writes RuneLite's runtime config to a local file (runelite.rtconf) without "outdatedClientVersions": RuneLite
 *   uses that list to block logins on old client versions, and this pinned test client must keep working after
 *   RuneLite releases a newer version.</li>
 *   <li>Serves jav_config.ws and the world list on 127.0.0.1 (a random free port). RuneLite 1.13.3 only takes a
 *   --jav_config on a *.runescape.com / *.jagex.com host, so it gets http://{@value #JAV_HOST}/jav_config.ws and a
 *   proxy rule in this JVM sends that one name to the local server (no DNS lookup, nothing else rerouted).
 *   With --jav-config URL, the launcher downloads that jav_config and serves it the same way.</li>
 *   <li>Runs net.runelite.client.RuneLite.main in this same JVM.</li>
 * </ol>
 *
 * <p>Options: --host H (game server host, default 127.0.0.1, or the first line of --host-file F), --world N (default 1),
 * --session S (jav_config param 9), --home DIR, --jav-config URL, --http-port P, --serve-only; anything after "--" goes
 * to RuneLite as is (for example -- --safe-mode --debug).
 */
public final class Launcher
{
	static final int REVISION = 241;
	/** Jagex's param 9 as of build 241; the old GadScape server checked for this same value. */
	static final String DEFAULT_SESSION = "ElZAIrq5NpKN6D3mDdihco3oPeYN2KFy2DCquj7JMmECPmLrDP3Bnw";
	static final String MARKER = "gadscape-patch.properties";
	/** Name RuneLite fetches jav_config from; routed to this launcher's own HTTP server, never resolved in DNS. */
	static final String JAV_HOST = "jav-config.gadscape-test.runescape.com";

	public static void main(String[] args) throws Exception
	{
		String host = null, hostFile = null, session = DEFAULT_SESSION, home = null, javConfig = null, activity = "GadScape 241 Test";
		int world = 1, httpPort = 0;
		boolean serveOnly = false;
		List<String> rlArgs = new ArrayList<>();
		for (int i = 0; i < args.length; i++)
		{
			String a = args[i];
			switch (a)
			{
				case "--host": host = args[++i]; break;
				case "--host-file": hostFile = args[++i]; break;
				case "--world": world = Integer.parseInt(args[++i]); break;
				case "--activity": activity = args[++i]; break;
				case "--session": session = args[++i]; break;
				case "--home": home = args[++i]; break;
				case "--jav-config": javConfig = args[++i]; break;
				case "--http-port": httpPort = Integer.parseInt(args[++i]); break;
				case "--serve-only": serveOnly = true; break;
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
		if (host == null || host.isEmpty())
		{
			host = "127.0.0.1";
		}
		if (!host.matches("[A-Za-z0-9.\\-]+"))
		{
			throw new IllegalArgumentException("server host must be a host name or IPv4 address, got '" + host + "'");
		}

		// 1. isolation: a separate user.home for RuneLite and the game cache
		Path homeDir = Paths.get(home != null ? home : defaultHome()).toAbsolutePath();
		Files.createDirectories(homeDir);
		String realHome = System.getProperty("user.home");
		System.setProperty("user.home", homeDir.toString());
		log("home folder " + homeDir + " (RuneLite settings: " + homeDir.resolve(".runelite") + ", game cache: "
			+ homeDir.resolve(".runelite").resolve("jagexcache") + "; your normal home " + realHome + " is not used)");

		// 2. the patched injected-client must be the only one on the classpath
		if (!serveOnly)
		{
			checkInjectedClient();
		}

		// 3. runtime config without the "outdated client" kill switch
		Path rtconf = homeDir.resolve("gadscape").resolve("runelite-runtime-config.json");
		Files.createDirectories(rtconf.getParent());
		Files.write(rtconf, runtimeConfig().getBytes(StandardCharsets.UTF_8));
		System.setProperty("runelite.rtconf", rtconf.toString());

		// 4. jav_config + world list, served on 127.0.0.1
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), httpPort), 0);
		int port = server.getAddress().getPort();
		String base = "http://127.0.0.1:" + port;
		byte[] jav;
		if (javConfig == null)
		{
			jav = javConfig(host, world, session, base + "/worldlist.ws").getBytes(StandardCharsets.ISO_8859_1);
			byte[] worlds = worldList(world, host, activity);
			server.createContext("/worldlist.ws", ex -> reply(ex, "application/octet-stream", worlds));
			log("jav_config: world " + world + " at " + host + ":43594, world list " + base + "/worldlist.ws");
		}
		else
		{
			jav = fetch(javConfig);
			log("jav_config: copied from " + javConfig + " (" + jav.length + " bytes)");
		}
		server.createContext("/jav_config.ws", ex -> reply(ex, "text/plain; charset=ISO-8859-1", jav));
		server.createContext("/", ex -> reply(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.US_ASCII)));
		server.setExecutor(null);
		server.start();
		// RuneLite 1.13.3 only accepts a --jav_config URL on a host ending in .jagex.com or .runescape.com
		// (ClientLoader.downloadConfig). JAV_HOST is such a name, but it is never looked up: this proxy rule sends
		// requests for it to the server above on 127.0.0.1. Every other address goes where it would have gone.
		routeToLoopback(JAV_HOST, port);
		String javUrl = "http://" + JAV_HOST + "/jav_config.ws";
		log("serving jav_config on " + base + "/jav_config.ws, given to RuneLite as " + javUrl);
		if (serveOnly)
		{
			log("--serve-only: not starting RuneLite; Ctrl+C to stop");
			Thread.currentThread().join();
			return;
		}

		// 5. RuneLite, in this JVM
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

	/** Sends http requests for one host name to 127.0.0.1:port (as an HTTP proxy); other requests are unchanged. */
	static void routeToLoopback(String hostName, int port)
	{
		ProxySelector previous = ProxySelector.getDefault();
		Proxy local = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
		ProxySelector.setDefault(new ProxySelector()
		{
			@Override
			public List<Proxy> select(URI uri)
			{
				if (hostName.equalsIgnoreCase(uri.getHost()))
				{
					return Collections.singletonList(local);
				}
				return previous != null ? previous.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
			}

			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException e)
			{
				if (previous != null && !hostName.equalsIgnoreCase(uri.getHost()))
				{
					previous.connectFailed(uri, sa, e);
				}
			}
		});
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
			return Paths.get(local, "GadScape241Test", "home").toString();
		}
		return Paths.get(System.getProperty("user.home"), ".gadscape-241-test", "home").toString();
	}

	static String readHostFile(Path f) throws IOException
	{
		if (!Files.exists(f))
		{
			return null;
		}
		for (String line : Files.readAllLines(f, StandardCharsets.UTF_8))
		{
			line = line.trim();
			if (line.startsWith("﻿"))
			{
				line = line.substring(1);
			}
			if (!line.isEmpty() && !line.startsWith("#"))
			{
				return line;
			}
		}
		return null;
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

	/** RuneLite's live runtime config minus the fields that could stop this pinned test client. */
	static String runtimeConfig()
	{
		String body = null;
		try
		{
			HttpURLConnection c = (HttpURLConnection) new URL("https://static.runelite.net/config.json").openConnection();
			c.setConnectTimeout(5000);
			c.setReadTimeout(5000);
			c.setRequestProperty("User-Agent", "GadScape241TestLauncher");
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
		for (String k : new String[]{"outdatedClientVersions", "outageMessage", "outageLinks", "outageStart", "outageEnd", "updateLauncherWinVers", "updateRollout"})
		{
			o.remove(k);
		}
		return o.toString();
	}

	/** Same keys and params as Jagex's https://oldschool.runescape.com/jav_config.ws (build 241), pointed at our server. */
	static String javConfig(String host, int world, String session, String worldListUrl)
	{
		StringBuilder sb = new StringBuilder();
		String[] lines = {
			"title=GadScape 241 Test",
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
			"msg=tandc=GadScape TEST server (build 241). Not affiliated with Jagex.",
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
