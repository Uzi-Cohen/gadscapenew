package gadscape.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * GadScape's default RuneLite settings, written before RuneLite starts (findings IF-RL-PLUGINS-03 / CRIT-08).
 *
 * <p>Turns off the plugins that cannot work on a private server (they look up or send Jagex/RuneLite data by
 * player name or world) and the one whose login screen key handling clashes with GadScape's login screen, and turns
 * GPU on. Nothing else is touched: every other plugin keeps RuneLite's own default.
 *
 * <p>How it lands in RuneLite (1.13.3, {@code ConfigManager.load}):
 * <ul>
 *   <li>Fresh home (no {@code .runelite/profiles2/profiles.json}): the keys go into {@code .runelite/settings.properties}.
 *   RuneLite migrates that file into a new active profile named "default" on its first start (its own upgrade
 *   path from pre-profile RuneLite).</li>
 *   <li>Existing home: the keys go into the profile RuneLite will pick (the active one that is not internal, else
 *   the first), {@code profiles2/<name>-<id>.properties}, under the same lock files RuneLite uses.</li>
 * </ul>
 * Each entry is applied once per profile: {@code gadscape.defaultsVersion} records the version applied, so a player
 * who turns a plugin back on keeps it on. Raising {@link #VERSION} applies only the entries added for that version.
 */
final class Defaults
{
	/** Raise when entries are added; give the new entries this version. */
	static final int VERSION = 1;
	static final String VERSION_KEY = "gadscape.defaultsVersion";

	/** key, value, version that added it, why. Keys are RuneLite's: runelite.<plugin class name lower case>. */
	static final String[][] ENTRIES = {
		{"runelite.hiscoreplugin", "false", "1", "HiScore: looks players up on Jagex's OSRS hiscores (wrong data here)"},
		{"runelite.chatcommandsplugin", "false", "1", "Chat Commands: !kc/!pb/!lvl/!qp answer with real OSRS data for the same name"},
		{"runelite.crowdsourcingplugin", "false", "1", "OSRS Wiki Crowdsourcing: sends gameplay to the OSRS Wiki (also blocked by the launcher)"},
		{"runelite.xpupdaterplugin", "false", "1", "XP Updater: sends XP to Wise Old Man/TempleOSRS/CML (also blocked)"},
		{"runelite.worldhopperplugin", "false", "1", "World Hopper: lists Jagex's worlds, hopping to them cannot log in"},
		{"runelite.defaultworldplugin", "false", "1", "Default World: picks worlds from Jagex's world list"},
		{"runelite.discordplugin", "false", "1", "Discord: shows OSRS activity, registers a URL handler outside the client folder"},
		{"runelite.twitchplugin", "false", "1", "Twitch: off (RuneLite's default too)"},
		{"runelite.loginscreenplugin", "false", "1", "Login Screen: its paste/username keys act on the game's hidden login fields under GadScape's login screen"},
		{"runelite.gpuplugin", "true", "1", "GPU: on (smooth frame rate, draw distance)"},
	};

	private Defaults()
	{
	}

	/** Applies the defaults for the RuneLite folder runeliteDir (user.home/.runelite). Returns what it did, for the log. */
	static String apply(Path runeliteDir) throws IOException
	{
		Path profilesDir = runeliteDir.resolve("profiles2");
		Path profilesJson = profilesDir.resolve("profiles.json");
		Files.createDirectories(profilesDir);
		Path target;
		Path profilesLock = profilesDir.resolve("profiles.lck");
		try (FileChannel lockChannel = FileChannel.open(profilesLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock ignored = lockChannel.lock())
		{
			target = targetProfile(profilesJson, profilesDir);
		}
		finally
		{
			Files.deleteIfExists(profilesLock); // RuneLite removes it after use too
		}
		if (target == null)
		{
			target = runeliteDir.resolve("settings.properties");
			String done = update(target);
			return "fresh RuneLite folder: " + done + " in " + target + " (RuneLite imports it into its 'default' profile)";
		}
		Path lck = target.resolveSibling(target.getFileName() + ".lck");
		try (FileChannel lockChannel = FileChannel.open(lck, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock ignored = lockChannel.lock())
		{
			return update(target) + " in " + target;
		}
		finally
		{
			Files.deleteIfExists(lck);
		}
	}

	/** The properties file of the profile RuneLite will use, or null when there is no (non-internal) profile yet. */
	static Path targetProfile(Path profilesJson, Path profilesDir) throws IOException
	{
		if (!Files.exists(profilesJson))
		{
			return null;
		}
		JsonElement root;
		try (Reader r = Files.newBufferedReader(profilesJson, StandardCharsets.UTF_8))
		{
			root = new JsonParser().parse(r);
		}
		catch (RuntimeException e)
		{
			return null; // RuneLite treats an unreadable file as "no profiles" too
		}
		if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("profiles") || !root.getAsJsonObject().get("profiles").isJsonArray())
		{
			return null;
		}
		JsonArray profiles = root.getAsJsonObject().getAsJsonArray("profiles");
		JsonObject first = null, active = null;
		for (JsonElement e : profiles)
		{
			if (!e.isJsonObject())
			{
				continue;
			}
			JsonObject p = e.getAsJsonObject();
			// internal profiles ($rsprofile) have a name starting with '$' (ConfigProfile.isInternal)
			String name = p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : "";
			if (name.startsWith("$") || !p.has("id"))
			{
				continue;
			}
			if (first == null)
			{
				first = p;
			}
			if (active == null && p.has("active") && p.get("active").getAsBoolean())
			{
				active = p;
			}
		}
		JsonObject pick = active != null ? active : first;
		if (pick == null)
		{
			return null;
		}
		String name = pick.has("name") && !pick.get("name").isJsonNull() ? pick.get("name").getAsString() : "";
		return profilesDir.resolve(name + "-" + pick.get("id").getAsLong() + ".properties");
	}

	/** Adds the entries newer than the file's recorded version. Same file format as RuneLite (Properties, UTF-8). */
	static String update(Path file) throws IOException
	{
		Properties p = new Properties();
		if (Files.exists(file))
		{
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
			{
				p.load(r);
			}
		}
		int have;
		try
		{
			have = Integer.parseInt(p.getProperty(VERSION_KEY, "0").trim());
		}
		catch (NumberFormatException e)
		{
			have = 0;
		}
		if (have >= VERSION)
		{
			return "defaults v" + have + " already applied";
		}
		List<String> set = new ArrayList<>();
		for (String[] e : ENTRIES)
		{
			if (Integer.parseInt(e[2]) > have)
			{
				p.setProperty(e[0], e[1]);
				set.add(e[0] + "=" + e[1]);
			}
		}
		p.setProperty(VERSION_KEY, String.valueOf(VERSION));
		Files.createDirectories(file.getParent());
		Path tmp = Files.createTempFile(file.getParent(), "gadscape_defaults", ".tmp");
		try (OutputStream os = Files.newOutputStream(tmp); Writer w = new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8))
		{
			p.store(w, "RuneLite configuration");
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		return "defaults v" + VERSION + " applied (" + String.join(", ", set) + ")";
	}

	/** For tests: the value an entry sets, or null. */
	static String valueOf(String key)
	{
		for (String[] e : ENTRIES)
		{
			if (e[0].equals(key))
			{
				return e[1];
			}
		}
		return null;
	}
}
