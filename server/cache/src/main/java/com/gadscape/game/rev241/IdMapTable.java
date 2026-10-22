package com.gadscape.game.rev241;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.io.Reader;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * One parsed gadconv {@code idmap.json}: for every kind, GadScape's 211 id -> the id the converted cache holds that
 * content at. Ids a kind does not list map to themselves.
 * <p>
 * The file's shape is {@code {"seq": {"15110": 40198, ...}, "obj": {...}, ..., "_reasons": {...}, "_format": 1}};
 * keys starting with {@code _} are metadata and kinds this class does not know are ignored. gadconv never moves an id
 * onto an id any GadScape content used, so a mapped id is never itself a key: applying a table twice changes nothing.
 * <p>
 * The one metadata key read is {@code _new}: {@code {"obj": {"sksp-15:Pegasian masori mask (f)": 60000, ...}}}, the
 * entries gadconv added for GadScape that had no 211 id at all (named by their gadconv key), see {@link #added}.
 */
public final class IdMapTable {

    /** The id kinds of {@code idmap.json} (its keys) that the server can name. */
    public enum Kind {
        SEQ("seq"), SPOT("spot"), OBJ("obj"), NPC("npc"), LOC("loc"), VARP("varp"), VARBIT("varbit"), VARC("varc"),
        SCRIPT("script"), INTERFACE("interface"), ENUM("enum"), STRUCT("struct"), PARAM("param"), SPRITE("sprite"),
        MODEL("model"), DBROW("dbrow"), DBTABLE("dbtable"), INV("inv"), HEALTHBAR("healthbar");

        /** The key in {@code idmap.json}. */
        public final String key;

        Kind(final String key) {
            this.key = key;
        }

        public static Kind byKey(final String key) {
            for (final Kind k : values()) {
                if (k.key.equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    public static final IdMapTable IDENTITY = new IdMapTable(new EnumMap<>(Kind.class), "identity");

    private final Map<Kind, Int2IntMap> maps;
    private final Map<Kind, Map<String, Integer>> added;
    private final String source;

    private IdMapTable(final Map<Kind, Int2IntMap> maps, final String source) {
        this(maps, new EnumMap<>(Kind.class), source);
    }

    private IdMapTable(final Map<Kind, Int2IntMap> maps, final Map<Kind, Map<String, Integer>> added, final String source) {
        this.maps = maps;
        this.added = added;
        this.source = source;
    }

    /** Reads an {@code idmap.json}. */
    public static IdMapTable parse(final Reader reader, final String source) {
        final JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException(source + ": an idmap.json is a JSON object");
        }
        final Map<Kind, Int2IntMap> maps = new EnumMap<>(Kind.class);
        for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            final Kind kind = Kind.byKey(e.getKey());
            if (kind == null || !e.getValue().isJsonObject()) {
                continue; // metadata (_reasons, _ranges, ...) and kinds the server never names (frame, framemap, ...)
            }
            final JsonObject kindMap = e.getValue().getAsJsonObject();
            final Int2IntOpenHashMap map = new Int2IntOpenHashMap(kindMap.size());
            map.defaultReturnValue(-1);
            for (final Map.Entry<String, JsonElement> m : kindMap.entrySet()) {
                map.put(Integer.parseInt(m.getKey().trim()), m.getValue().getAsInt());
            }
            if (!map.isEmpty()) {
                maps.put(kind, Int2IntMaps.unmodifiable(map));
            }
        }
        final Map<Kind, Map<String, Integer>> added = new EnumMap<>(Kind.class);
        final JsonElement news = root.getAsJsonObject().get("_new");
        if (news != null && news.isJsonObject()) {
            for (final Map.Entry<String, JsonElement> e : news.getAsJsonObject().entrySet()) {
                final Kind kind = Kind.byKey(e.getKey());
                if (kind == null || !e.getValue().isJsonObject()) {
                    continue;
                }
                final Map<String, Integer> byName = new java.util.HashMap<>();
                for (final Map.Entry<String, JsonElement> m : e.getValue().getAsJsonObject().entrySet()) {
                    if (m.getValue().isJsonPrimitive() && m.getValue().getAsJsonPrimitive().isNumber()) {
                        byName.put(m.getKey(), m.getValue().getAsInt());
                    }
                }
                if (!byName.isEmpty()) {
                    added.put(kind, Collections.unmodifiableMap(byName));
                }
            }
        }
        return new IdMapTable(maps, added, source);
    }

    /**
     * The id gadconv gave the new GadScape entry {@code key} of {@code kind} ({@code _new}, e.g. obj
     * {@code "sksp-15:Pegasian masori mask (f)"}), or -1 when this table does not list it.
     */
    public int added(final Kind kind, final String key) {
        final Map<String, Integer> byName = added.get(kind);
        final Integer id = byName == null ? null : byName.get(key);
        return id == null ? -1 : id;
    }

    /** A table holding exactly the given mappings (tests, tools). */
    public static IdMapTable of(final Map<Kind, ? extends Map<Integer, Integer>> mappings, final String source) {
        final Map<Kind, Int2IntMap> maps = new EnumMap<>(Kind.class);
        for (final Map.Entry<Kind, ? extends Map<Integer, Integer>> e : mappings.entrySet()) {
            final Int2IntOpenHashMap map = new Int2IntOpenHashMap();
            map.defaultReturnValue(-1);
            e.getValue().forEach(map::put);
            if (!map.isEmpty()) {
                maps.put(e.getKey(), Int2IntMaps.unmodifiable(map));
            }
        }
        return new IdMapTable(maps, source);
    }

    /** The id {@code id} of {@code kind} moved to, or {@code id} itself when the table does not move it. */
    public int map(final Kind kind, final int id) {
        final Int2IntMap map = maps.get(kind);
        return map != null && map.containsKey(id) ? map.get(id) : id;
    }

    /** True when the table moves {@code id} of {@code kind}. */
    public boolean moves(final Kind kind, final int id) {
        final Int2IntMap map = maps.get(kind);
        return map != null && map.containsKey(id);
    }

    /** Every moved id of {@code kind} (old -> new), read-only. */
    public Int2IntMap entries(final Kind kind) {
        final Int2IntMap map = maps.get(kind);
        return map == null ? Int2IntMaps.EMPTY_MAP : map;
    }

    /** The kinds that move at least one id. */
    public java.util.Set<Kind> kinds() {
        return Collections.unmodifiableSet(maps.keySet());
    }

    public int size() {
        int n = 0;
        for (final Int2IntMap m : maps.values()) {
            n += m.size();
        }
        return n;
    }

    public boolean isIdentity() {
        return maps.isEmpty();
    }

    /** Where the table came from, for logs. */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IdMapTable(").append(source);
        for (final Map.Entry<Kind, Int2IntMap> e : maps.entrySet()) {
            sb.append(", ").append(e.getKey().key).append('=').append(e.getValue().size());
        }
        return sb.append(')').toString();
    }
}
