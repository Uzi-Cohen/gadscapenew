package com.zenyte.game.world.entity.npc.combatdefs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenyte.game.world.DefaultGson;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import mgi.types.config.npcs.NPCDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NPC combat-definition fixes bundled with the server code, applied over {@code cache/data/npcs/combat/*.npc.json}
 * (the data dir is not in git) before {@link NPCCDLoader} indexes the definitions.
 * <p>
 * Resource {@value #RESOURCE}: a JSON object whose {@code patches} array holds entries with
 * <ul>
 *     <li>{@code id} - the NPC id to change (or to add).</li>
 *     <li>{@code merge} - an object in the shape of a {@code .npc.json} file; it is merged key by key into the
 *     definition (nested objects merge, anything else replaces; {@code null} removes the key).</li>
 *     <li>{@code copyFrom} - optional: start from this other id's definition (its own id is kept). With
 *     {@code onlyIfAbsent: true} the entry only fills an id the data dir does not define.</li>
 *     <li>{@code name} - optional: the cache name (or an array of names) the entry was written for. When the loaded
 *     cache has another NPC at the id (ids moved, a different cache) the entry is skipped with a warning.</li>
 *     <li>keys starting with {@code _} - documentation, ignored.</li>
 * </ul>
 * An id the data dir does not define and that has no {@code copyFrom} is added from {@code merge} alone.
 * System property {@code gadscape.npcs.bundled=false} turns the layer off.
 *
 * @author GadScape
 */
public final class BundledNpcCombatPatches {

    private static final Logger log = LoggerFactory.getLogger(BundledNpcCombatPatches.class);

    public static final String RESOURCE = "gadscape/npcs/combat-patches.json";

    private BundledNpcCombatPatches() {
    }

    /**
     * Applies the bundled patches to the definitions read from the data dir and returns the list to index.
     */
    public static List<NPCCombatDefinitions> apply(final List<NPCCombatDefinitions> loaded) {
        if (!Boolean.parseBoolean(System.getProperty("gadscape.npcs.bundled", "true"))) {
            log.info("Bundled NPC combat patches are turned off (gadscape.npcs.bundled=false).");
            return loaded;
        }
        final JsonArray patches = readPatches();
        if (patches == null || patches.isEmpty()) {
            return loaded;
        }
        final Int2ObjectMap<NPCCombatDefinitions> byId = new Int2ObjectLinkedOpenHashMap<>(loaded.size() + 16);
        for (final NPCCombatDefinitions def : loaded) {
            if (def != null) {
                byId.put(def.getId(), def);
            }
        }
        final Gson gson = DefaultGson.getGson();
        int changed = 0, added = 0, skipped = 0;
        for (final JsonElement element : patches) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            final JsonObject patch = element.getAsJsonObject();
            final int id;
            try {
                id = patch.get("id").getAsInt();
            } catch (final Exception e) {
                log.warn("Bundled NPC combat patch without a numeric id: {}", patch);
                skipped++;
                continue;
            }
            if (!nameMatches(id, patch.get("name"))) {
                skipped++;
                continue;
            }
            try {
                final NPCCombatDefinitions existing = byId.get(id);
                JsonObject base;
                if (patch.has("copyFrom")) {
                    if (existing != null && patch.has("onlyIfAbsent") && patch.get("onlyIfAbsent").getAsBoolean()) {
                        continue;
                    }
                    final NPCCombatDefinitions source = byId.get(patch.get("copyFrom").getAsInt());
                    if (source == null) {
                        log.warn("Bundled NPC combat patch for {}: copyFrom {} is not defined; skipped.", id, patch.get("copyFrom"));
                        skipped++;
                        continue;
                    }
                    base = gson.toJsonTree(source).getAsJsonObject();
                } else if (existing != null) {
                    base = gson.toJsonTree(existing).getAsJsonObject();
                } else {
                    base = new JsonObject();
                }
                if (patch.has("merge") && patch.get("merge").isJsonObject()) {
                    merge(base, patch.getAsJsonObject("merge"));
                }
                base.addProperty("id", id);
                final NPCCombatDefinitions result = gson.fromJson(base, NPCCombatDefinitions.class);
                if (existing == null) {
                    added++;
                } else {
                    changed++;
                }
                byId.put(id, result);
            } catch (final Exception e) {
                log.error("Failed to apply the bundled NPC combat patch for " + id + "; the data-dir definition is kept.", e);
                skipped++;
            }
        }
        log.info("Bundled NPC combat patches: {} changed, {} added, {} skipped.", changed, added, skipped);
        return new ArrayList<>(byId.values());
    }

    private static JsonArray readPatches() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = BundledNpcCombatPatches.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("No bundled NPC combat patches found ({} is not on the classpath).", RESOURCE);
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                final JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonArray()) {
                    return root.getAsJsonArray();
                }
                if (root.isJsonObject() && root.getAsJsonObject().has("patches")) {
                    return root.getAsJsonObject().getAsJsonArray("patches");
                }
                log.error("{} has no 'patches' array; ignored.", RESOURCE);
            }
        } catch (final Exception e) {
            log.error("Failed to read " + RESOURCE + "; no bundled NPC combat patches applied.", e);
        }
        return null;
    }

    private static boolean nameMatches(final int id, final JsonElement expected) {
        if (expected == null || expected.isJsonNull()
                || !Boolean.parseBoolean(System.getProperty("gadscape.npcs.nameCheck", "true"))) {
            return true;
        }
        final NPCDefinitions definitions;
        try {
            definitions = NPCDefinitions.get(id);
        } catch (final Exception e) {
            return true;
        }
        if (definitions == null || definitions.getName() == null) {
            // NPC definitions not loaded (tools) or the id is absent: nothing to compare against.
            return true;
        }
        final String actual = definitions.getName().toLowerCase(Locale.ROOT);
        if (expected.isJsonArray()) {
            for (final JsonElement name : expected.getAsJsonArray()) {
                if (actual.equals(name.getAsString().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } else if (actual.equals(expected.getAsString().toLowerCase(Locale.ROOT))) {
            return true;
        }
        log.warn("Bundled NPC combat patch for {} was written for '{}' but the cache has '{}'; skipped.", id, expected,
                definitions.getName());
        return false;
    }

    private static void merge(final JsonObject target, final JsonObject patch) {
        for (final Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            final String key = entry.getKey();
            if (key.startsWith("_")) {
                continue;
            }
            final JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                target.remove(key);
            } else if (value.isJsonObject() && target.has(key) && target.get(key).isJsonObject()) {
                merge(target.getAsJsonObject(key), value.getAsJsonObject());
            } else {
                target.add(key, value.deepCopy());
            }
        }
    }
}
