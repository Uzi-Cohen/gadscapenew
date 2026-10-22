package com.gadscape.cacheconv.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every id relocation of the build: kind -> old id -> new id, with a reason. Steps record relocations here and look
 * ids up through {@link #map} when they rewrite references; the server is rewritten from idmap.json later, so the map
 * must be complete.
 * <p>
 * New ids come from the reserved GadScape range of each kind ({@link Kind}); an id is handed out only if it is unused
 * in every occupancy cache (BASE, USER and the output as built so far) and not already allocated, reserved or the
 * target of a relocation.
 * <p>
 * Aliases ({@link #alias}) map an id onto an id that already exists and stays where it is (a user model identical to
 * a BASE model at another id). Several ids may alias the same target; they appear in the kind's map like any other
 * relocation and are also listed under {@code _aliases}. Apply the map once, never transitively.
 * <p>
 * Vanilla ports ({@link #markVanilla}) are relocations of a vanilla 211 entry that Jagex reused or re-rigged by 241
 * (model 292 became the Cat mask, seq 189 a player emote): GadScape content that used the 211 entry is rewritten to the
 * ported copy. They are listed under {@code _vanilla}: the server rewrite applies them only where GadScape code means
 * the 211 entry on GadScape's own (211-era) content, never to stock 241 content.
 * <p>
 * New ids ({@link #addNew}) are ids the build created that replace nothing (adapter scripts): allocated from the
 * kind's reserved range like relocations, and listed under {@code _new} by name.
 * <p>
 * idmap.json: {@code {"seq": {"15000": 40000, ...}, "frame": {...}, ..., "_reasons": {"seq": {"15000": "..."}},
 * "_aliases": {"model": [58013, ...]}, "_vanilla": {"seq": [189, ...]}, "_new": {"script": {"adapter:98": 47900}},
 * "_ranges": {"seq": [40000, 49999], ...}, "_format": 1}} — every kind key is always present; keys starting with "_"
 * are metadata.
 */
public final class IdMap {
    public record Range(int lo, int hi) {
        public boolean contains(int id) { return id >= lo && id <= hi; }
        public int size() { return hi - lo + 1; }
    }

    private final EnumMap<Kind, TreeMap<Integer, Integer>> map = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeMap<Integer, Integer>> inverse = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeMap<Integer, String>> reasons = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeSet<Integer>> aliases = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeSet<Integer>> vanilla = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeMap<String, Integer>> added = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeMap<String, String>> addedWhy = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Range> ranges = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeSet<Integer>> taken = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Integer> next = new EnumMap<>(Kind.class);
    private final List<CacheView> occupancy = new ArrayList<>();

    public IdMap() {
        for (Kind k : Kind.values()) {
            map.put(k, new TreeMap<>()); inverse.put(k, new TreeMap<>()); reasons.put(k, new TreeMap<>()); taken.put(k, new TreeSet<>());
            aliases.put(k, new TreeSet<>()); vanilla.put(k, new TreeSet<>()); added.put(k, new TreeMap<>()); addedWhy.put(k, new TreeMap<>());
            if (k.rangeLo >= 0) ranges.put(k, new Range(k.rangeLo, k.rangeHi));
        }
    }

    /** Caches whose ids count as used when allocating (typically BASE, USER and OUT). */
    public IdMap occupancy(CacheView... caches) { occupancy.clear(); occupancy.addAll(List.of(caches)); return this; }

    // ------------------------------------------------------------------ lookups

    /** The new id of a relocated id, or the id itself. */
    public int map(Kind k, int id) { Integer to = map.get(k).get(id); return to == null ? id : to; }
    /** The new id, or -1 if the id was not relocated. */
    public int lookup(Kind k, int id) { Integer to = map.get(k).get(id); return to == null ? -1 : to; }
    public boolean moved(Kind k, int id) { return map.get(k).containsKey(id); }
    /** The old id that was relocated to {@code to}, or -1 (aliases of {@code to} are not counted). */
    public int origin(Kind k, int to) { Integer from = inverse.get(k).get(to); return from == null ? -1 : from; }
    public String reason(Kind k, int id) { return reasons.get(k).get(id); }
    /** Read-only view of one kind's relocations (old -> new). */
    public Map<Integer, Integer> all(Kind k) { return java.util.Collections.unmodifiableMap(map.get(k)); }
    public int size() { int n = 0; for (var m : map.values()) n += m.size(); return n; }

    /** Seq frame reference (group << 16 | file) with the frame group mapped; -1 stays -1. */
    public int mapFrameRef(int ref) {
        if (ref == -1) return -1;
        int group = ref >>> 16, file = ref & 0xffff;
        int to = map(Kind.FRAME, group);
        return to == group ? ref : (to << 16) | file;
    }

    // ------------------------------------------------------------------ recording / allocation

    /** Records a relocation. Recording the same mapping twice is fine; a different target for the same id is an error. */
    public void record(Kind k, int from, int to, String reason) {
        Integer cur = map.get(k).get(from);
        if (cur != null) {
            if (cur == to) return;
            throw new IllegalStateException(k.key + " " + from + " already relocated to " + cur + ", not " + to);
        }
        Integer other = inverse.get(k).get(to);
        if (other != null) throw new IllegalStateException(k.key + " " + to + " is already the new id of " + other);
        map.get(k).put(from, to);
        inverse.get(k).put(to, from);
        reasons.get(k).put(from, reason);
        taken.get(k).add(to);
    }

    /**
     * Records a many-to-one mapping onto an id that already exists and is not itself moved (for example a user model
     * identical to a BASE model at another id). Unlike {@link #record}, several ids may alias the same target, and the
     * target is not reported by {@link #origin}. Recording the same alias twice is fine.
     */
    public void alias(Kind k, int from, int to, String reason) {
        Integer cur = map.get(k).get(from);
        if (cur != null) {
            if (cur == to) return;
            throw new IllegalStateException(k.key + " " + from + " already relocated to " + cur + ", not " + to);
        }
        if (map.get(k).containsKey(to)) throw new IllegalStateException(k.key + " alias target " + to + " is itself relocated");
        if (inverse.get(k).containsKey(from)) throw new IllegalStateException(k.key + " " + from + " is the new id of " + inverse.get(k).get(from) + " and cannot alias");
        map.get(k).put(from, to);
        reasons.get(k).put(from, reason);
        aliases.get(k).add(from);
    }

    /** Whether {@code from} was mapped with {@link #alias}. */
    public boolean isAlias(Kind k, int from) { return aliases.get(k).contains(from); }

    /** Marks a recorded relocation as a port of a vanilla 211 entry Jagex changed (listed under {@code _vanilla}). */
    public void markVanilla(Kind k, int from) {
        if (!map.get(k).containsKey(from)) throw new IllegalStateException(k.key + " " + from + " is not relocated");
        vanilla.get(k).add(from);
    }

    public boolean isVanillaPort(Kind k, int from) { return vanilla.get(k).contains(from); }

    /** Ids marked with {@link #markVanilla}, ascending. */
    public java.util.Set<Integer> vanillaPorts(Kind k) { return java.util.Collections.unmodifiableSet(vanilla.get(k)); }

    /**
     * Removes a relocation again (a ported copy nothing in the output ended up using). Its target is released for
     * later allocations only if nothing else claims it; the id map stays as if the relocation never happened.
     */
    public void unrecord(Kind k, int from) {
        Integer to = map.get(k).remove(from);
        if (to == null) return;
        reasons.get(k).remove(from);
        vanilla.get(k).remove(from);
        if (aliases.get(k).remove(from)) return;
        inverse.get(k).remove(to);
        taken.get(k).remove(to);
    }

    /**
     * A new id the build creates for {@code name} (an adapter script): the same id again for the same name, otherwise
     * the next free id of the kind's reserved range. Listed under {@code _new}.
     */
    public int addNew(Kind k, String name, String why) { return addNew(k, name, why, false); }

    /** {@link #addNew(Kind, String, String)}; {@code fromTop} takes the highest free id of the range instead of the lowest. */
    public int addNew(Kind k, String name, String why, boolean fromTop) {
        Integer cur = added.get(k).get(name);
        if (cur != null) return cur;
        int id = fromTop ? allocateTop(k) : allocate(k);
        added.get(k).put(name, id);
        addedWhy.get(k).put(name, why);
        return id;
    }

    /** The id {@link #addNew} gave {@code name}, or -1. */
    public int newId(Kind k, String name) { Integer v = added.get(k).get(name); return v == null ? -1 : v; }

    /** Read-only view of one kind's new ids (name -> id). */
    public Map<String, Integer> allNew(Kind k) { return java.util.Collections.unmodifiableMap(added.get(k)); }

    /** Marks an id as used so the allocator never hands it out. */
    public void reserve(Kind k, int id) { taken.get(k).add(id); }

    public Range range(Kind k) { return ranges.get(k); }
    public void setRange(Kind k, int lo, int hi) {
        if (lo < 0 || hi < lo) throw new IllegalArgumentException("bad range " + lo + ".." + hi);
        ranges.put(k, new Range(lo, hi));
        next.remove(k);
    }

    /** Whether the id is unused in every occupancy cache and not allocated/reserved/targeted. */
    public boolean isFree(Kind k, int id) {
        if (taken.get(k).contains(id) || inverse.get(k).containsKey(id)) return false;
        for (CacheView c : occupancy) if (k.exists(c, id)) return false;
        return true;
    }

    /** Next free id of the kind's reserved range (marked taken). */
    public int allocate(Kind k) {
        Range r = ranges.get(k);
        if (r == null) throw new IllegalStateException("no reserved range for " + k.key + " (IdMap.setRange first)");
        for (int id = next.getOrDefault(k, r.lo); id <= r.hi; id++) {
            if (isFree(k, id)) { taken.get(k).add(id); next.put(k, id + 1); return id; }
        }
        throw new IllegalStateException("reserved " + k.key + " range " + r.lo + ".." + r.hi + " is full");
    }

    /** The highest free id of the kind's reserved range (marked taken). */
    public int allocateTop(Kind k) {
        Range r = ranges.get(k);
        if (r == null) throw new IllegalStateException("no reserved range for " + k.key + " (IdMap.setRange first)");
        for (int id = r.hi; id >= r.lo; id--) if (isFree(k, id)) { taken.get(k).add(id); return id; }
        throw new IllegalStateException("reserved " + k.key + " range " + r.lo + ".." + r.hi + " is full");
    }

    /** Relocates {@code from} to a newly allocated id (or returns the id it was already relocated to). */
    public int relocate(Kind k, int from, String reason) {
        Integer cur = map.get(k).get(from);
        if (cur != null) return cur;
        int to = allocate(k);
        record(k, from, to, reason);
        return to;
    }

    /** How many ids of each reserved range are already used in each occupancy cache (to check the ranges are free). */
    public Map<String, Object> rangeOccupancy() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            Range r = ranges.get(k);
            if (r == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("range", new int[]{r.lo, r.hi});
            for (CacheView c : occupancy) {
                int used = 0, first = -1;
                for (int id : k.ids(c)) if (r.contains(id)) { used++; if (first < 0) first = id; }
                row.put(c.label(), used == 0 ? 0 : used + " (first " + first + ")");
            }
            out.put(k.key, row);
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : map.get(k).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            root.put(k.key, m);
        }
        Map<String, Object> rs = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            if (reasons.get(k).isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : reasons.get(k).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            rs.put(k.key, m);
        }
        root.put("_reasons", rs);
        Map<String, Object> al = new LinkedHashMap<>();
        for (Kind k : Kind.values()) if (!aliases.get(k).isEmpty()) al.put(k.key, new ArrayList<>(aliases.get(k)));
        if (!al.isEmpty()) root.put("_aliases", al);
        Map<String, Object> va = new LinkedHashMap<>();
        for (Kind k : Kind.values()) if (!vanilla.get(k).isEmpty()) va.put(k.key, new ArrayList<>(vanilla.get(k)));
        if (!va.isEmpty()) root.put("_vanilla", va);
        Map<String, Object> nw = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            if (added.get(k).isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : added.get(k).entrySet()) m.put(e.getKey(), e.getValue());
            nw.put(k.key, m);
        }
        if (!nw.isEmpty()) root.put("_new", nw);
        Map<String, Object> nwWhy = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            if (addedWhy.get(k).isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : addedWhy.get(k).entrySet()) m.put(e.getKey(), e.getValue());
            nwWhy.put(k.key, m);
        }
        if (!nwWhy.isEmpty()) root.put("_newReasons", nwWhy);
        Map<String, Object> rg = new LinkedHashMap<>();
        for (Kind k : Kind.values()) { Range r = ranges.get(k); if (r != null) rg.put(k.key, new int[]{r.lo, r.hi}); }
        root.put("_ranges", rg);
        root.put("_format", 1);
        return root;
    }

    public void save(Path file) throws IOException { Json.write(file, toJson()); }

    public static IdMap load(Path file) throws IOException {
        IdMap m = new IdMap();
        Map<String, Object> root = Json.obj(Json.read(file));
        Map<String, Object> rg = root.containsKey("_ranges") ? Json.obj(root.get("_ranges")) : Map.of();
        for (var e : rg.entrySet()) { List<Object> l = Json.list(e.getValue()); m.setRange(Kind.byKey(e.getKey()), Json.i(l.get(0)), Json.i(l.get(1))); }
        Map<String, Object> rs = root.containsKey("_reasons") ? Json.obj(root.get("_reasons")) : Map.of();
        Map<String, Object> al = root.containsKey("_aliases") ? Json.obj(root.get("_aliases")) : Map.of();
        Map<String, Object> va = root.containsKey("_vanilla") ? Json.obj(root.get("_vanilla")) : Map.of();
        Map<String, Object> nw = root.containsKey("_new") ? Json.obj(root.get("_new")) : Map.of();
        Map<String, Object> nwWhy = root.containsKey("_newReasons") ? Json.obj(root.get("_newReasons")) : Map.of();
        for (Kind k : Kind.values()) {
            Object o = root.get(k.key);
            if (o == null) continue;
            Map<String, Object> why = rs.containsKey(k.key) ? Json.obj(rs.get(k.key)) : Map.of();
            java.util.Set<Integer> isAlias = new TreeSet<>();
            if (al.containsKey(k.key)) for (Object x : Json.list(al.get(k.key))) isAlias.add(Json.i(x));
            for (var e : Json.obj(o).entrySet()) {
                Object r = why.get(e.getKey());
                int from = Integer.parseInt(e.getKey());
                if (isAlias.contains(from)) m.alias(k, from, Json.i(e.getValue()), r == null ? "" : r.toString());
                else m.record(k, from, Json.i(e.getValue()), r == null ? "" : r.toString());
            }
            if (va.containsKey(k.key)) for (Object x : Json.list(va.get(k.key))) m.markVanilla(k, Json.i(x));
            if (nw.containsKey(k.key)) {
                Map<String, Object> nwr = nwWhy.containsKey(k.key) ? Json.obj(nwWhy.get(k.key)) : Map.of();
                for (var e : Json.obj(nw.get(k.key)).entrySet()) {
                    int id = Json.i(e.getValue());
                    m.added.get(k).put(e.getKey(), id);
                    Object w = nwr.get(e.getKey());
                    m.addedWhy.get(k).put(e.getKey(), w == null ? "" : w.toString());
                    m.taken.get(k).add(id);
                }
            }
        }
        return m;
    }
}
