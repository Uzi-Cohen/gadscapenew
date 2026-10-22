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
 * idmap.json: {@code {"seq": {"15000": 40000, ...}, "frame": {...}, ..., "_reasons": {"seq": {"15000": "..."}},
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
    private final EnumMap<Kind, Range> ranges = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, TreeSet<Integer>> taken = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Integer> next = new EnumMap<>(Kind.class);
    private final List<CacheView> occupancy = new ArrayList<>();

    public IdMap() {
        for (Kind k : Kind.values()) {
            map.put(k, new TreeMap<>()); inverse.put(k, new TreeMap<>()); reasons.put(k, new TreeMap<>()); taken.put(k, new TreeSet<>());
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
    /** The old id that was relocated to {@code to}, or -1. */
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
        for (Kind k : Kind.values()) {
            Object o = root.get(k.key);
            if (o == null) continue;
            Map<String, Object> why = rs.containsKey(k.key) ? Json.obj(rs.get(k.key)) : Map.of();
            for (var e : Json.obj(o).entrySet()) {
                Object r = why.get(e.getKey());
                m.record(k, Integer.parseInt(e.getKey()), Json.i(e.getValue()), r == null ? "" : r.toString());
            }
        }
        return m;
    }
}
