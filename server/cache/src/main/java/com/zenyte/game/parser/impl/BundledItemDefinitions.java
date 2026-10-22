package com.zenyte.game.parser.impl;

import com.gadscape.game.rev241.Gad211;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.zenyte.game.world.DefaultGson;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import mgi.types.config.items.JSONItemDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Item definitions bundled with the server code, layered over {@code data/items/ItemDefinitions.json}.
 * <p>
 * Every {@code gadscape/items/*.json} resource on the classpath is read ({@code base.json} first, then the other
 * files in name order). Each file holds a JSON array of entries in the same shape as the entries of
 * {@code data/items/ItemDefinitions.json}, plus these optional keys:
 * <ul>
 *     <li>{@code "override": true} - the entry also changes an id the data dir already defines. Only the keys the
 *     entry gives are replaced: nested objects ({@code equipmentDefinition}, {@code weaponDefinition}) are merged key
 *     by key, {@code requirements} and {@code bonuses} are replaced whole, and a {@code null} value removes the key
 *     (for example {@code "equipmentDefinition": null}). Without it, an id the data dir defines is left alone.</li>
 *     <li>{@code "ifDataDir": {...}} - with {@code override}: change the data-dir entry only while it still has these
 *     values (same shape, compared key by key; {@code null} = key absent). When the owner has since edited the item
 *     (for example with the editor's server-stats panel), the data dir wins and the entry is skipped.</li>
 *     <li>{@code "name"} - the cache name the entry was written for (or an array of accepted names). When the loaded cache has another item at that
 *     id (ids moved, a different cache), the entry is not applied and a warning names it.</li>
 *     <li>any key starting with {@code _} (for example {@code "_source"}) - documentation, ignored.</li>
 * </ul>
 * Ids the data dir does not define are always added. A later file may change an id an earlier bundled file added
 * (its keys are merged in the same way). Bundled entries never end up in {@code ItemDefinitions.json} when
 * {@link JSONItemDefinitionsLoader#save()} writes it, unless something replaced the entry after loading.
 * <p>
 * System properties: {@code gadscape.items.bundled=false} turns the whole layer off,
 * {@code gadscape.items.nameCheck=false} skips the cache-name check.
 *
 * @author GadScape
 */
public final class BundledItemDefinitions {

    private static final Logger log = LoggerFactory.getLogger(BundledItemDefinitions.class);

    public static final String RESOURCE_DIR = "gadscape/items";
    /** Entry key saying which ids the entry uses; see {@link #toCacheIds(JsonObject)}. */
    static final String ID_SPACE = "idSpace";
    /** Entry key naming a new GadScape obj by its gadconv key ({@code idmap.json} {@code _new.obj}). */
    static final String NEW_OBJ = "newObj";
    private static final String FIRST_FILE = "base.json";

    /** Ids only the bundled files define, mapped to the object put in the loader's map. */
    private static final Int2ObjectMap<JSONItemDefinitions> added = new Int2ObjectOpenHashMap<>();
    /** Data-dir ids a bundled file changed: the object put in the loader's map. */
    private static final Int2ObjectMap<JSONItemDefinitions> overridden = new Int2ObjectOpenHashMap<>();
    /** Data-dir ids a bundled file changed: the data-dir entry as it was read. */
    private static final Int2ObjectMap<JSONItemDefinitions> originals = new Int2ObjectOpenHashMap<>();
    /** The cache name(s) each bundled entry was written for. */
    private static final Int2ObjectMap<List<String>> expectedNames = new Int2ObjectOpenHashMap<>();
    /** The bundled file that last changed each id. */
    private static final Int2ObjectMap<String> sources = new Int2ObjectOpenHashMap<>();
    /** Ids whose bundled entry gives its own requirements (they replace the ones already registered). */
    private static final IntSet ownRequirements = new IntOpenHashSet();

    private BundledItemDefinitions() {
    }

    /**
     * Merges every bundled file into {@code definitions}, which already holds the data-dir entries.
     */
    public static synchronized void load(final Int2ObjectMap<JSONItemDefinitions> definitions) {
        if (!Boolean.parseBoolean(System.getProperty("gadscape.items.bundled", "true"))) {
            log.info("Bundled item definitions are turned off (gadscape.items.bundled=false).");
            return;
        }
        final Map<String, URL> files = findFiles();
        if (files.isEmpty()) {
            log.warn("No bundled item definition files found under {}/ on the classpath.", RESOURCE_DIR);
            return;
        }
        final Gson gson = DefaultGson.getGson();
        int addedCount = 0, overriddenCount = 0, skipped = 0, stale = 0, bad = 0;
        final IntList skippedIds = new IntArrayList();
        for (final Map.Entry<String, URL> file : files.entrySet()) {
            final String fileName = file.getKey();
            final JsonArray entries;
            try (InputStream in = file.getValue().openStream();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                final JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonArray()) {
                    entries = root.getAsJsonArray();
                } else if (root.isJsonObject() && root.getAsJsonObject().has("items")) {
                    entries = root.getAsJsonObject().getAsJsonArray("items");
                } else {
                    log.error("Bundled item file {} is not a JSON array; skipped.", fileName);
                    continue;
                }
            } catch (final Exception e) {
                log.error("Failed to read bundled item file " + fileName + "; skipped.", e);
                continue;
            }
            for (final JsonElement element : entries) {
                if (element == null || !element.isJsonObject()) {
                    bad++;
                    continue;
                }
                final JsonObject entry = element.getAsJsonObject();
                toCacheIds(entry);
                if (!entry.has("id") || !entry.get("id").isJsonPrimitive()) {
                    log.warn("Bundled item entry without an id in {}: {}", fileName, entry);
                    bad++;
                    continue;
                }
                final int id;
                try {
                    id = entry.get("id").getAsInt();
                } catch (final Exception e) {
                    log.warn("Bundled item entry with a bad id in {}: {}", fileName, entry);
                    bad++;
                    continue;
                }
                final boolean override = entry.has("override") && entry.get("override").isJsonPrimitive()
                        && entry.get("override").getAsBoolean();
                try {
                    final JSONItemDefinitions current = definitions.get(id);
                    final boolean fromDataDir = current != null && !added.containsKey(id);
                    if (fromDataDir && !override && !overridden.containsKey(id)) {
                        skipped++;
                        if (skippedIds.size() < 20) {
                            skippedIds.add(id);
                        }
                        continue;
                    }
                    final JsonObject merged = current == null ? new JsonObject() : gson.toJsonTree(current).getAsJsonObject();
                    final JsonElement condition = entry.get("ifDataDir");
                    if (fromDataDir && condition != null && condition.isJsonObject()
                            && !matches(gson.toJsonTree(originals.getOrDefault(id, current)).getAsJsonObject(), condition.getAsJsonObject())) {
                        stale++;
                        log.info("Bundled override of item {} in {} skipped: the data dir entry was changed since it was written.", id, fileName);
                        continue;
                    }
                    merge(merged, entry);
                    merged.addProperty("id", id);
                    final JSONItemDefinitions def = gson.fromJson(merged, JSONItemDefinitions.class);
                    if (fromDataDir) {
                        if (!overridden.containsKey(id)) {
                            originals.put(id, current);
                            overriddenCount++;
                        }
                        overridden.put(id, def);
                    } else {
                        if (!added.containsKey(id)) {
                            addedCount++;
                        }
                        added.put(id, def);
                    }
                    definitions.put(id, def);
                    sources.put(id, fileName);
                    final JsonElement name = entry.get("name");
                    if (name != null && name.isJsonPrimitive()) {
                        expectedNames.put(id, List.of(name.getAsString()));
                    } else if (name != null && name.isJsonArray()) {
                        final List<String> names = new ArrayList<>();
                        name.getAsJsonArray().forEach(n -> names.add(n.getAsString()));
                        expectedNames.put(id, names);
                    }
                    final JsonElement equipment = entry.get("equipmentDefinition");
                    if (equipment != null && equipment.isJsonObject() && equipment.getAsJsonObject().has("requirements")) {
                        ownRequirements.add(id);
                    }
                } catch (final Exception e) {
                    log.error("Bad bundled item entry for " + id + " in " + fileName + "; skipped.", e);
                    bad++;
                }
            }
        }
        log.info("Bundled item definitions: {} file(s) {}, {} added, {} data-dir entries changed, {} skipped"
                        + " (already in the data dir, no \"override\"), {} skipped (data dir edited since), {} bad.",
                files.size(), files.keySet(), addedCount, overriddenCount, skipped, stale, bad);
        if (skipped > 0) {
            log.debug("Bundled entries skipped because the data dir defines them (first ids): {}", skippedIds);
        }
    }

    /**
     * The 241 cache moved a few of GadScape's 211 ids (gadconv {@code idmap.json}, see {@link Gad211}): items 34721,
     * 34722, 34723 and 34726 (Raids 4 items hold those ids in 2746), Zenyte's event seqs 15000-15162, and others.
     * Bundled entries are resolved here before they are applied:
     * <ul>
     *     <li>{@code "id"} names GadScape's item (the files were written for GadScape's items), so it is resolved;</li>
     *     <li>{@code ifDataDir} describes the data-dir entry, which holds GadScape's 211 ids until it is migrated
     *     ({@code docs/rev241/tools/migrate_data_ids.py}), so its {@code *Animation} seqs and
     *     {@code interfaceVarbit} are resolved too, to match the migrated data dir;</li>
     *     <li>the values the entry sets are kept as written: the areas wrote them against the 241 cache (for example
     *     weapons.json's "live seqs tfa_human_obligator_* 15118-15123" are 2746's seqs).</li>
     * </ul>
     * {@code "idSpace": "gadscape211"} resolves the entry's own seqs and varbits as well (an entry copied from the 211
     * data dir); {@code "idSpace": "cacheV"} resolves nothing, not even the item id (an entry for 2746 content at an
     * id GadScape used, such as a Raids 4 item). A resolved id is never an old one, so resolving twice is harmless.
     * {@code "newObj": "<key>"} names an obj GadScape added without a 211 id (gadconv's {@code _new.obj}, e.g. the
     * SKSP-15 kit pieces): the entry's id becomes the id the cache gave it, {@code "id"} being the fallback.
     */
    static void toCacheIds(final JsonObject entry) {
        final JsonElement newObj = entry.remove(NEW_OBJ);
        if (newObj != null && newObj.isJsonPrimitive()) {
            final JsonElement fallback = entry.get("id");
            final int planned = fallback != null && fallback.isJsonPrimitive() && fallback.getAsJsonPrimitive().isNumber()
                    ? fallback.getAsInt() : -1;
            entry.addProperty("id", Gad211.newObj(newObj.getAsString(), planned));
            return;
        }
        final JsonElement space = entry.get(ID_SPACE);
        final String idSpace = space != null && space.isJsonPrimitive() ? space.getAsString().toLowerCase(Locale.ROOT) : "";
        if (idSpace.equals("cachev") || idSpace.equals("241")) {
            return;
        }
        final JsonElement id = entry.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
            entry.addProperty("id", Gad211.obj(id.getAsInt()));
        }
        final boolean all = idSpace.equals("gadscape211") || idSpace.equals("211");
        for (final Map.Entry<String, JsonElement> e : entry.entrySet()) {
            if (e.getValue() != null && e.getValue().isJsonObject() && (all || e.getKey().equals("ifDataDir"))) {
                mapNested(e.getValue().getAsJsonObject());
            }
        }
    }

    private static void mapNested(final JsonObject object) {
        for (final Map.Entry<String, JsonElement> e : object.entrySet()) {
            final JsonElement value = e.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonObject()) {
                mapNested(value.getAsJsonObject());
                continue;
            }
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            final String key = e.getKey();
            if (key.endsWith("Animation")) {
                e.setValue(new JsonPrimitive(Gad211.seq(value.getAsInt())));
            } else if (key.equals("interfaceVarbit")) {
                e.setValue(new JsonPrimitive(Gad211.varbit(value.getAsInt())));
            }
        }
    }

    /**
     * Deep-merges {@code patch} into {@code base}: objects key by key (except {@code requirements}, which is
     * replaced whole), {@code null} removes a key, anything else replaces. Bookkeeping keys are dropped.
     */
    static void merge(final JsonObject base, final JsonObject patch) {
        for (final Map.Entry<String, JsonElement> e : patch.entrySet()) {
            final String key = e.getKey();
            if (key.equals("override") || key.equals("name") || key.equals("ifDataDir") || key.equals(ID_SPACE) || key.equals(NEW_OBJ) || key.startsWith("_")) {
                continue;
            }
            final JsonElement value = e.getValue();
            if (value == null || value.isJsonNull()) {
                base.remove(key);
            } else if (value.isJsonObject() && !key.equals("requirements") && base.has(key) && base.get(key).isJsonObject()) {
                merge(base.getAsJsonObject(key), value.getAsJsonObject());
            } else if (value.isJsonObject()) {
                final JsonObject copy = new JsonObject();
                merge(copy, value.getAsJsonObject());
                base.add(key, copy);
            } else {
                base.add(key, value.deepCopy());
            }
        }
    }

    /** True when {@code actual} has every value {@code expected} gives (objects compared key by key, null = absent). */
    static boolean matches(final JsonObject actual, final JsonObject expected) {
        for (final Map.Entry<String, JsonElement> e : expected.entrySet()) {
            final JsonElement want = e.getValue();
            final JsonElement have = actual.get(e.getKey());
            if (want == null || want.isJsonNull()) {
                if (have != null && !have.isJsonNull()) {
                    return false;
                }
            } else if (want.isJsonObject()) {
                if (have == null || !have.isJsonObject() || !matches(have.getAsJsonObject(), want.getAsJsonObject())) {
                    return false;
                }
            } else if (have == null || have.isJsonNull()) {
                return false;
            } else if (want.isJsonPrimitive() && want.getAsJsonPrimitive().isNumber()
                    && have.isJsonPrimitive() && have.getAsJsonPrimitive().isNumber()) {
                if (Double.compare(want.getAsDouble(), have.getAsDouble()) != 0) {
                    return false;
                }
            } else if (want.isJsonPrimitive() && want.getAsJsonPrimitive().isString()
                    && have.isJsonPrimitive() && have.getAsJsonPrimitive().isString()) {
                // bonus lists are written with and without spaces
                if (!want.getAsString().replace(" ", "").equals(have.getAsString().replace(" ", ""))) {
                    return false;
                }
            } else if (!want.equals(have)) {
                return false;
            }
        }
        return true;
    }

    /** True when a bundled file added or changed this id. */
    public static boolean isBundled(final int id) {
        return added.containsKey(id) || overridden.containsKey(id);
    }

    /** True when only bundled files define this id. */
    public static boolean isBundledOnly(final int id) {
        return added.containsKey(id);
    }

    /** True when the bundled entry for this id brought its own requirements (they replace the registered ones). */
    public static boolean hasOwnRequirements(final int id) {
        return ownRequirements.contains(id);
    }

    /** The bundled file that last changed this id, or null. */
    public static String sourceOf(final int id) {
        return sources.get(id);
    }

    /**
     * Checks a bundled entry against the item the loaded cache has at its id. On a mismatch the entry is taken
     * back out of {@code definitions} (a changed data-dir entry gets its original back) and this returns the
     * definition to apply instead: the data-dir original, or null for nothing.
     */
    public static synchronized JSONItemDefinitions checkAgainstCache(final Int2ObjectMap<JSONItemDefinitions> definitions,
                                                                     final JSONItemDefinitions def, final String cacheName) {
        final int id = def.getId();
        final List<String> expected = expectedNames.get(id);
        if (expected == null || !Boolean.parseBoolean(System.getProperty("gadscape.items.nameCheck", "true"))) {
            return def;
        }
        for (final String name : expected) {
            if (sameName(name, cacheName)) {
                return def;
            }
        }
        log.warn("Bundled item definition {} ({}) not applied: written for '{}' but the cache has '{}' at that id.",
                id, sources.get(id), expected, cacheName);
        ownRequirements.remove(id);
        if (added.remove(id) != null) {
            definitions.remove(id);
            return null;
        }
        overridden.remove(id);
        final JSONItemDefinitions original = originals.remove(id);
        if (original != null) {
            definitions.put(id, original);
        }
        return original;
    }

    private static boolean sameName(final String a, final String b) {
        return normalise(a).equals(normalise(b));
    }

    private static String normalise(final String name) {
        if (name == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(name.length());
        for (final char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * What {@link JSONItemDefinitionsLoader#save()} should write for a definition from the loader's map: the
     * data-dir original for a bundled change, nothing for a bundled-only entry, and the definition itself otherwise
     * (also when something replaced the bundled object after loading).
     */
    public static synchronized JSONItemDefinitions forSave(final JSONItemDefinitions def) {
        if (def == null) {
            return null;
        }
        final int id = def.getId();
        if (added.get(id) == def) {
            return null;
        }
        if (overridden.get(id) == def) {
            return originals.get(id);
        }
        return def;
    }

    /**
     * The bundled files on the classpath, by file name: base.json first, then the others in name order. A file name
     * found in more than one classpath entry is read from the first one.
     */
    static Map<String, URL> findFiles() {
        final TreeMap<String, URL> found = new TreeMap<>((a, b) -> {
            if (a.equals(b)) {
                return 0;
            }
            if (a.equals(FIRST_FILE)) {
                return -1;
            }
            if (b.equals(FIRST_FILE)) {
                return 1;
            }
            return a.compareTo(b);
        });
        final ClassLoader loader = classLoader();
        try {
            final Enumeration<URL> dirs = loader.getResources(RESOURCE_DIR);
            while (dirs.hasMoreElements()) {
                scan(dirs.nextElement(), found);
            }
        } catch (final IOException e) {
            log.warn("Could not list " + RESOURCE_DIR + " on the classpath.", e);
        }
        // Jars built without directory entries do not answer getResources for the directory: scan the classpath.
        final String classPath = System.getProperty("java.class.path", "");
        for (final String entry : classPath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            final File f = new File(entry);
            try {
                if (f.isDirectory()) {
                    final File dir = new File(f, RESOURCE_DIR);
                    if (dir.isDirectory()) {
                        scanDirectory(dir.toPath(), found);
                    }
                } else if (f.isFile() && entry.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    scanJar(f, found);
                }
            } catch (final Exception e) {
                log.debug("Could not scan classpath entry {} for bundled item files.", entry, e);
            }
        }
        return found;
    }

    private static ClassLoader classLoader() {
        final ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : BundledItemDefinitions.class.getClassLoader();
    }

    private static void scan(final URL url, final Map<String, URL> found) {
        try {
            if ("file".equals(url.getProtocol())) {
                scanDirectory(Paths.get(url.toURI()), found);
                return;
            }
            final URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                jarConnection.setUseCaches(false);
                final URL jarUrl = jarConnection.getJarFileURL();
                if ("file".equals(jarUrl.getProtocol())) {
                    scanJar(new File(jarUrl.toURI()), found);
                }
            }
        } catch (final Exception e) {
            log.debug("Could not scan {} for bundled item files.", url, e);
        }
    }

    private static void scanDirectory(final Path dir, final Map<String, URL> found) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path p : (Iterable<Path>) files::iterator) {
                final String name = p.getFileName().toString();
                if (name.endsWith(".json") && Files.isRegularFile(p)) {
                    found.putIfAbsent(name, p.toUri().toURL());
                }
            }
        }
    }

    private static void scanJar(final File jar, final Map<String, URL> found) throws IOException {
        final String prefix = RESOURCE_DIR + "/";
        try (JarFile jarFile = new JarFile(jar)) {
            final Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String path = entry.getName();
                if (entry.isDirectory() || !path.startsWith(prefix) || !path.endsWith(".json")) {
                    continue;
                }
                final String name = path.substring(prefix.length());
                if (name.contains("/")) {
                    continue;
                }
                if (!found.containsKey(name)) {
                    found.put(name, URI.create("jar:" + jar.toURI() + "!/" + path).toURL());
                }
            }
        }
    }

    /** Every id a bundled file added or changed, for tools and tests. */
    public static synchronized List<Integer> bundledIds() {
        final List<Integer> ids = new ArrayList<>(added.keySet());
        ids.addAll(overridden.keySet());
        ids.sort(Integer::compare);
        return ids;
    }
}
