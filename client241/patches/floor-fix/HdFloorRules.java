package gadscape.launcher;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * 117 HD's floor rules for GadScape: GadScape's painted floors (twin floors from 4000 up, HD looks from 10000 up,
 * which the map editor makes) are listed in a tile_overrides.json that 117 HD reads (rlhd.tile-overrides-path).
 * The lists come from the game cache's floor ids, read at startup and again whenever the cache changes.
 *
 * The client writes to the game cache all the time while it plays (it downloads what it needs), so a read can
 * catch a file half written. A read that fails, or finds no floors, never replaces rules that already list
 * floors: the last good rules stay until a good read gives new ones. (Before, a failed read wrote the empty
 * default rules and 117 HD, which reloads the file, went back to its own floors.)
 */
final class HdFloorRules
{
	static final String PROPERTY = "rlhd.tile-overrides-path";
	static final String PAINTED = "GADSCAPE_PAINTED_FLOORS";
	static final int FIRST_TWIN = 4000;
	static final String HD_LOOK = "GADSCAPE_HD_LOOK_";
	static final int FIRST_HD_LOOK = 10000;
	static final int HD_LOOK_SLOTS = 100;
	static final long CHECK_EVERY_MS = 20000L;

	private HdFloorRules()
	{
	}

	static void install(Path home)
	{
		if (System.getProperty(PROPERTY) != null)
		{
			Launcher.log("117 HD floor rules: " + System.getProperty(PROPERTY) + " (set outside the launcher)");
			return;
		}
		if ("hd".equalsIgnoreCase(System.getenv("GADSCAPE_HD_FLOORS")))
		{
			Launcher.log("117 HD floor rules: HD's own (GADSCAPE_HD_FLOORS=hd)");
			return;
		}
		try (InputStream in = Launcher.class.getResourceAsStream("hd-tile-overrides.json"))
		{
			if (in == null)
			{
				Launcher.log("WARNING: hd-tile-overrides.json is missing from the launcher; 117 HD uses its own floor rules");
				return;
			}
			byte[] base = in.readAllBytes();
			Path file = home.resolve("gadscape").resolve("hd").resolve("tile_overrides.json");
			Files.createDirectories(file.getParent());
			StringBuilder note = new StringBuilder();
			byte[] rules = rules(base, gameCache(home), note);
			boolean written;
			if (rules != null)
			{
				written = write(file, rules);
			}
			else if (hasFloors(file))
			{
				written = false;
				note.append("; kept the rules from the last time");
			}
			else
			{
				written = write(file, base);
			}
			String path = file.toAbsolutePath().toString().replace('\\', '/');
			System.setProperty(PROPERTY, path);
			Launcher.log("117 HD floors: HD's own, floors painted in the editor as painted; rules " + path
				+ (written ? "" : " (unchanged)") + "; " + note);
			watch(file, base, home);
		}
		catch (IOException | RuntimeException e)
		{
			Launcher.log("WARNING: could not set up 117 HD's floor rules (" + e + "); HD uses its own");
		}
	}

	/**
	 * The rules with the painted floors the game cache holds, or null when the cache could not be read or lists
	 * no painted floors (then the rules there are stay).
	 */
	static byte[] rules(byte[] base, Path cache, StringBuilder note)
	{
		JsonArray all = new JsonParser().parse(new String(base, StandardCharsets.UTF_8)).getAsJsonArray();
		JsonObject painted = null;
		List<JsonObject> looks = new ArrayList<>();
		for (JsonElement e : all)
		{
			JsonObject o = e.getAsJsonObject();
			String name = o.has("name") ? o.get("name").getAsString() : "";
			if (PAINTED.equals(name))
			{
				painted = o;
			}
			else if (name.startsWith(HD_LOOK))
			{
				looks.add(o);
			}
		}
		if (painted == null)
		{
			note.append("no ").append(PAINTED).append(" rule");
			return base;
		}
		if (cache == null)
		{
			note.append("no game cache yet");
			return null;
		}
		int[] overlayFiles;
		int[] underlayFiles;
		try
		{
			overlayFiles = configFiles(cache, 4);
			underlayFiles = configFiles(cache, 1);
		}
		catch (IOException | RuntimeException e)
		{
			note.append("the game cache could not be read (").append(e.getMessage()).append(")");
			return null;
		}
		if (overlayFiles == null || underlayFiles == null)
		{
			note.append("the game cache has no floor list yet");
			return null;
		}
		TreeSet<Integer> overlays = stored(overlayFiles, FIRST_TWIN, FIRST_HD_LOOK);
		TreeSet<Integer> underlays = stored(underlayFiles, FIRST_TWIN, FIRST_HD_LOOK);
		int found = overlays.size() + underlays.size();
		note.append("painted floors: ").append(overlays.size()).append(" overlays, ").append(underlays.size())
			.append(" underlays in the game cache");
		if (overlays.isEmpty())
		{
			overlays.add(FIRST_TWIN + 1);
		}
		if (underlays.isEmpty())
		{
			underlays.add(FIRST_TWIN + 1);
		}
		set(painted, "overlayIds", overlays);
		set(painted, "underlayIds", underlays);
		for (int i = 0; i < looks.size(); i++)
		{
			int from = FIRST_HD_LOOK + i * HD_LOOK_SLOTS;
			TreeSet<Integer> o = stored(overlayFiles, from, from + HD_LOOK_SLOTS);
			TreeSet<Integer> u = stored(underlayFiles, from, from + HD_LOOK_SLOTS);
			found += o.size() + u.size();
			if (o.isEmpty())
			{
				o.add(from + 1);
			}
			if (u.isEmpty())
			{
				u.add(from + 1);
			}
			set(looks.get(i), "overlayIds", o);
			set(looks.get(i), "underlayIds", u);
		}
		if (found == 0)
		{
			note.append(" (none: keeping the rules there are)");
			return null;
		}
		return (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(all) + "\n")
			.getBytes(StandardCharsets.UTF_8);
	}

	/** Whether the rules file there lists any painted floor (anything besides the placeholder ids). */
	private static boolean hasFloors(Path file)
	{
		try
		{
			if (!Files.isRegularFile(file))
			{
				return false;
			}
			JsonArray all = new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonArray();
			for (JsonElement e : all)
			{
				JsonObject o = e.getAsJsonObject();
				String name = o.has("name") ? o.get("name").getAsString() : "";
				if (!PAINTED.equals(name) && !name.startsWith(HD_LOOK))
				{
					continue;
				}
				for (String key : new String[]{"overlayIds", "underlayIds"})
				{
					TreeSet<Integer> ids = ids(o, key);
					for (int id : ids)
					{
						if (id != FIRST_TWIN + 1 && (id - FIRST_HD_LOOK - 1) % HD_LOOK_SLOTS != 0)
						{
							return true;
						}
					}
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
		return false;
	}

	private static void set(JsonObject o, String key, TreeSet<Integer> ids)
	{
		if (ids.equals(ids(o, key)))
		{
			return;
		}
		JsonArray a = new JsonArray();
		ids.forEach(a::add);
		o.add(key, a);
	}

	/** The floor ids (file id + 1, as 117 HD counts them) of the files from..to-1. */
	static TreeSet<Integer> stored(int[] files, int from, int to)
	{
		TreeSet<Integer> out = new TreeSet<>();
		for (int f : files)
		{
			if (f >= from && f < to)
			{
				out.add(f + 1);
			}
		}
		return out;
	}

	private static TreeSet<Integer> ids(JsonObject o, String key)
	{
		TreeSet<Integer> out = new TreeSet<>();
		if (o.has(key))
		{
			for (JsonElement e : o.getAsJsonArray(key))
			{
				out.add(e.getAsInt());
			}
		}
		return out;
	}

	static boolean write(Path file, byte[] data) throws IOException
	{
		if (Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), data))
		{
			return false;
		}
		Path tmp = Files.createTempFile(file.getParent(), "tile_overrides", ".json~");
		try
		{
			Files.write(tmp, data);
			try
			{
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AccessDeniedException | AtomicMoveNotSupportedException e)
			{
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(tmp);
		}
		return true;
	}

	/** Reads the floor list again whenever the game cache changes; only a good read changes the rules. */
	static void watch(Path file, byte[] base, Path home)
	{
		Thread t = new Thread(() ->
		{
			String last = stamp(gameCache(home));
			while (true)
			{
				try
				{
					Thread.sleep(CHECK_EVERY_MS);
					Path cache = gameCache(home);
					String now = stamp(cache);
					if (now.equals(last))
					{
						continue;
					}
					StringBuilder note = new StringBuilder();
					byte[] rules = rules(base, cache, note);
					if (rules == null)
					{
						continue; // a failed or empty read: try again next time, the rules stay as they are
					}
					last = now;
					if (write(file, rules))
					{
						Launcher.log("117 HD floors: the game cache changed, rules updated; " + note);
					}
				}
				catch (InterruptedException e)
				{
					return;
				}
				catch (IOException | RuntimeException e)
				{
					// try again next time
				}
			}
		}, "gadscape-hd-floors");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static String stamp(Path cache)
	{
		if (cache == null)
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(cache.toString());
		for (String name : new String[]{"main_file_cache.idx2", "main_file_cache.idx255"})
		{
			try
			{
				Path p = cache.resolve(name);
				sb.append('|').append(Files.size(p)).append('@').append(Files.getLastModifiedTime(p).toMillis());
			}
			catch (IOException e)
			{
				sb.append("|-");
			}
		}
		return sb.toString();
	}

	static Path gameCache(Path home)
	{
		Path root = home.resolve(".runelite").resolve("jagexcache");
		Path live = root.resolve("oldschool").resolve("LIVE");
		if (Files.isRegularFile(live.resolve("main_file_cache.idx2")))
		{
			return live;
		}
		Path best = null;
		long newest = Long.MIN_VALUE;
		try (Stream<Path> games = Files.isDirectory(root) ? Files.list(root) : Stream.empty())
		{
			for (Path game : (Iterable<Path>) games::iterator)
			{
				if (!Files.isDirectory(game))
				{
					continue;
				}
				try (Stream<Path> kinds = Files.list(game))
				{
					for (Path kind : (Iterable<Path>) kinds::iterator)
					{
						Path idx = kind.resolve("main_file_cache.idx2");
						if (Files.isRegularFile(idx) && Files.getLastModifiedTime(idx).toMillis() > newest)
						{
							best = kind;
							newest = Files.getLastModifiedTime(idx).toMillis();
						}
					}
				}
			}
		}
		catch (IOException e)
		{
			return null;
		}
		return best;
	}

	static int[] configFiles(Path cache, int group) throws IOException
	{
		byte[] table = group(cache, 255, 2);
		if (table == null)
		{
			return null;
		}
		int[] files = fileIds(table, group);
		return files == null || files.length == 0 ? null : files;
	}

	/** The file ids of group [group] in a reference table. */
	static int[] fileIds(byte[] table, int group)
	{
		ByteBuffer b = ByteBuffer.wrap(table);
		int protocol = b.get() & 0xFF;
		if (protocol >= 6)
		{
			b.getInt();
		}
		int flags = b.get() & 0xFF;
		boolean big = protocol >= 7;
		int count = smart(b, big);
		int[] groups = new int[count];
		int id = 0;
		for (int i = 0; i < count; i++)
		{
			groups[i] = id += smart(b, big);
		}
		int skip = 4 * count; // crcs
		if ((flags & 1) != 0)
		{
			skip += 4 * count; // name hashes
		}
		if ((flags & 8) != 0)
		{
			skip += 4 * count;
		}
		if ((flags & 2) != 0)
		{
			skip += 64 * count; // whirlpool
		}
		if ((flags & 4) != 0)
		{
			skip += 8 * count; // sizes
		}
		skip += 4 * count; // versions
		b.position(b.position() + skip);
		int[] fileCounts = new int[count];
		for (int i = 0; i < count; i++)
		{
			fileCounts[i] = smart(b, big);
		}
		for (int i = 0; i < count; i++)
		{
			int[] files = new int[fileCounts[i]];
			int f = 0;
			for (int k = 0; k < files.length; k++)
			{
				files[k] = f += smart(b, big);
			}
			if (groups[i] == group)
			{
				return files;
			}
		}
		return null;
	}

	private static int smart(ByteBuffer b, boolean big)
	{
		if (!big)
		{
			return b.getShort() & 0xFFFF;
		}
		return (b.get(b.position()) & 0x80) != 0 ? b.getInt() & Integer.MAX_VALUE : b.getShort() & 0xFFFF;
	}

	/** One group of the cache folder, unpacked. Null when it is not there (yet). */
	static byte[] group(Path cache, int index, int group) throws IOException
	{
		try (RandomAccessFile idx = new RandomAccessFile(cache.resolve("main_file_cache.idx" + index).toFile(), "r");
			RandomAccessFile dat = new RandomAccessFile(cache.resolve("main_file_cache.dat2").toFile(), "r"))
		{
			if ((group + 1L) * 6L > idx.length())
			{
				return null;
			}
			byte[] e = new byte[6];
			idx.seek(group * 6L);
			idx.readFully(e);
			int size = (e[0] & 0xFF) << 16 | (e[1] & 0xFF) << 8 | e[2] & 0xFF;
			int sector = (e[3] & 0xFF) << 16 | (e[4] & 0xFF) << 8 | e[5] & 0xFF;
			if (size <= 0 || sector <= 0)
			{
				return null;
			}
			boolean big = group > 65535;
			int header = big ? 10 : 8;
			byte[] out = new byte[size];
			byte[] buf = new byte[520];
			int done = 0;
			int part = 0;
			while (done < size)
			{
				if (sector <= 0 || sector * 520L >= dat.length())
				{
					throw new IOException("index " + index + " group " + group + " is cut short");
				}
				dat.seek(sector * 520L);
				int n = (int) Math.min(520L, dat.length() - sector * 520L);
				dat.readFully(buf, 0, n);
				int g = big ? ByteBuffer.wrap(buf).getInt(0) : (buf[0] & 0xFF) << 8 | buf[1] & 0xFF;
				int p = big ? (buf[4] & 0xFF) << 8 | buf[5] & 0xFF : (buf[2] & 0xFF) << 8 | buf[3] & 0xFF;
				int ix = buf[header - 1] & 0xFF;
				if (g != group || p != part || ix != index)
				{
					throw new IOException("index " + index + " group " + group + " is damaged");
				}
				int take = Math.min(520 - header, size - done);
				System.arraycopy(buf, header, out, done, take);
				done += take;
				sector = big ? (buf[6] & 0xFF) << 16 | (buf[7] & 0xFF) << 8 | buf[8] & 0xFF
					: (buf[4] & 0xFF) << 16 | (buf[5] & 0xFF) << 8 | buf[6] & 0xFF;
				part++;
			}
			return unpack(out);
		}
	}

	static byte[] unpack(byte[] data) throws IOException
	{
		ByteBuffer b = ByteBuffer.wrap(data);
		int compression = b.get() & 0xFF;
		int length = b.getInt();
		if (compression == 0)
		{
			return Arrays.copyOfRange(data, 5, 5 + length);
		}
		int unpacked = b.getInt();
		if (compression != 2)
		{
			throw new IOException("compression " + compression + " isn't supported");
		}
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data, 9, length)))
		{
			byte[] out = in.readAllBytes();
			if (out.length != unpacked)
			{
				throw new IOException("unpacked " + out.length + " bytes, expected " + unpacked);
			}
			return out;
		}
	}
}
