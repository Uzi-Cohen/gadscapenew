package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.config.CfgCodec.Item;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec rule 10: an enum the user modified (906 spec costs, 1034 item sets, 1739 spec descriptions, ...) becomes
 * BASE's enum plus the user's edit.
 * <ul>
 *   <li><b>keyed enums</b> (the usual case): BASE's pairs, then per key the user's delta against VAN211: keys the user
 *       added or changed take the USER value (references mapped; when BASE has another value for the key the USER
 *       value wins and the key is reported as a conflict), keys the user removed are removed; keys the user did not
 *       touch keep BASE's value, so Jagex's additions and fixes survive.</li>
 *   <li><b>list enums the user re-ordered</b> (keys exactly 0..n-1 in VAN211, USER and BASE, key type int, and the user
 *       removed or inserted elements instead of only appending): an index shift makes every key look changed, so the
 *       merge works on values: BASE's list minus the values the user removed, plus the values the user added (in the
 *       user's order), renumbered from 0. A list the user only appended to merges by key like any other enum, so
 *       the user's indexes (which the server and parallel enums use) stay.</li>
 *   <li>Scalar fields (key/value types, defaults) merge like any other field.</li>
 * </ul>
 * An enum whose USER key/value types differ from BASE's is a different enum: {@link ConfigPlanStep} moves it to the
 * reserved range instead. An empty BASE enum takes the USER enum as it is.
 */
public final class EnumMerge {
    private EnumMerge() {}

    public static final class Result {
        public final Entry entry;
        public final List<String> conflicts = new ArrayList<>();
        public final boolean list;
        public int added, changed, removed;
        /** User removals left out because the merge ran as a strict union ({@code applyRemovals == false}). */
        public int removalsNotApplied;
        Result(Entry e, boolean list) { this.entry = e; this.list = list; }
        public String describe() {
            return (list ? "list" : "keyed") + " merge: +" + added + " ~" + changed + " -" + removed
                    + (removalsNotApplied == 0 ? "" : "; " + removalsNotApplied + " user removal(s) not applied (union)")
                    + (conflicts.isEmpty() ? "" : "; USER value wins over BASE for " + conflicts.size() + " key(s) " + abbreviate(conflicts));
        }
    }

    static String abbreviate(List<String> l) { return l.size() <= 12 ? l.toString() : l.subList(0, 12) + "... (" + l.size() + ")"; }

    /** The enum's pair container (op 5/6/7), or null. */
    static Item pairs(Entry e) {
        Item r = null;
        for (Item i : e.items) if (i.isContainer()) r = i;
        return r;
    }

    /** No pairs and no types: nothing BASE's scripts could rely on. */
    public static boolean isEmpty(Entry e) {
        if (e == null) return true;
        Item p = pairs(e);
        return (p == null || p.children.isEmpty()) && e.get("1") == null && e.get("2") == null;
    }

    /** USER and BASE disagree on key/value types while BASE's enum has content: different enums sharing an id. */
    public static boolean incompatible(Entry u, Entry b) {
        if (isEmpty(b) || u == null) return false;
        Item bp = pairs(b);
        if (bp == null || bp.children.isEmpty()) return false;
        return !Arrays.equals(CfgCodec.enumTypes(u), CfgCodec.enumTypes(b));
    }

    /**
     * How the user's enum relates to the vanilla one by values (multisets of value bytes): {vanilla values the user
     * kept, values the user brought in that vanilla does not have, vanilla size, user size}.
     */
    public static int[] valueDelta(Entry v, Entry u) {
        Map<String, Integer> vc = new HashMap<>();
        LinkedHashMap<Integer, byte[]> vp = pairsOf(v), up = pairsOf(u);
        for (byte[] x : vp.values()) vc.merge(Bytes.hex(x), 1, Integer::sum);
        int kept = 0;
        for (byte[] x : up.values()) {
            String k = Bytes.hex(x);
            Integer n = vc.get(k);
            if (n != null && n > 0) { kept++; vc.put(k, n - 1); }
        }
        return new int[]{kept, up.size() - kept, vp.size(), up.size()};
    }

    /**
     * The user replaced a vanilla enum's content instead of editing it (enum 708: 200 holiday items became 12 GadScape
     * items): fewer than half of the vanilla values survive and at least half of the user's values are new. Such an
     * enum is a different enum that happens to reuse the id; {@link ConfigPlanStep} moves it to the reserved range so
     * BASE keeps its own. Pruned lists (values only removed: collection log and combat achievement lists) are edits.
     */
    public static boolean replaced(Entry v, Entry u) {
        int[] d = valueDelta(v, u);
        return d[2] >= 2 && d[0] * 2 < d[2] && d[1] > 0 && d[1] * 2 >= d[3];
    }

    /** Keys are exactly 0..n-1 (in any order). */
    static boolean dense(Item p) {
        if (p == null || p.children.isEmpty()) return false;
        int n = p.children.size();
        boolean[] seen = new boolean[n];
        for (Item c : p.children) {
            int k = Bytes.i32(c.raw, 0);
            if (k < 0 || k >= n || seen[k]) return false;
            seen[k] = true;
        }
        return true;
    }

    /** The elements of a dense pair container in key order. */
    static List<Item> byKey(Item p) {
        Item[] r = new Item[p.children.size()];
        for (Item c : p.children) r[Bytes.i32(c.raw, 0)] = c;
        return Arrays.asList(r);
    }

    /**
     * Whether the enum merges as a list (by values) instead of by key: keys are exactly 0..n-1 in VAN211, USER and
     * BASE, key type int, the user did more than append, and either the user resized the list (an insertion or
     * removal shifts every later index) or the values are ids of things (objs, structs, ...), whose position BASE may
     * have shifted too. Same-size int/string tables the user edited in place merge by key.
     */
    static boolean isList(Item vp, Item up, Item bp, int[] typesU) {
        if (typesU[0] != 'i' || !dense(vp) || !dense(up) || !dense(bp) || appendOnly(vp, up)) return false;
        return vp.children.size() != up.children.size() || CfgCodec.kindOfType(typesU[1]) != null;
    }

    static List<Item> mapAll(List<Item> l, CfgCodec.Mapper mapper) {
        List<Item> r = new ArrayList<>(l.size());
        for (Item c : l) r.add(CfgCodec.map(c, mapper));
        return r;
    }

    static Map<String, Integer> valueCounts(List<Item> l) {
        Map<String, Integer> m = new HashMap<>();
        for (Item c : l) m.merge(Bytes.hex(value(c)), 1, Integer::sum);
        return m;
    }

    /** Index of the n-th (1-based) occurrence of x, or -1. */
    static int nthIndex(List<String> l, String x, int n) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).equals(x) && --n == 0) return i;
        return -1;
    }

    /** USER kept VAN211's first n elements and only appended: then the keys stay the user's (spec rule 10). */
    static boolean appendOnly(Item vp, Item up) {
        List<Item> v = byKey(vp), u = byKey(up);
        if (u.size() < v.size()) return false;
        for (int i = 0; i < v.size(); i++) if (!Arrays.equals(value(v.get(i)), value(u.get(i)))) return false;
        return true;
    }

    static byte[] value(Item child) { return Arrays.copyOfRange(child.raw, 4, child.raw.length); }

    public static Result merge(Entry v, Entry u, Entry b, CfgCodec.Mapper mapper) { return merge(v, u, b, mapper, true); }

    /**
     * @param applyRemovals false: strict union (spec rule 10 read literally): keys/values the user removed from the
     *                      vanilla enum stay in BASE's; they are counted in {@link Result#removalsNotApplied}
     */
    public static Result merge(Entry v, Entry u, Entry b, CfgCodec.Mapper mapper, boolean applyRemovals) {
        int g = Arch.CFG_ENUM;
        int notApplied = 0;
        if (applyRemovals && CfgModel.sameEffective(g, v, b)) {
            // Jagex did not touch the enum since 211: the user's enum is the whole story (keeps the user's order)
            Result r = new Result(CfgCodec.map(u, mapper), false);
            var vp0 = pairsOf(v); var up0 = pairsOf(u);
            for (var e : up0.entrySet()) { byte[] x = vp0.get(e.getKey()); if (x == null) r.added++; else if (!Arrays.equals(x, e.getValue())) r.changed++; }
            for (Integer k : vp0.keySet()) if (!up0.containsKey(k)) r.removed++;
            return r;
        }
        // scalar fields first (types, defaults) through the generic merge, with the pair containers left out
        Entry vs = withoutPairs(v), us = withoutPairs(u), bs = withoutPairs(b);
        Merge3.Result scal = Merge3.merge(g, vs, us, bs, mapper, null);
        Item vp = pairs(v), up = pairs(u), bp = pairs(b);
        int[] tu = CfgCodec.enumTypes(u);
        boolean list = isList(vp, up, bp, tu);
        Result r;
        List<Item> children;
        List<String> conflicts = new ArrayList<>();
        int added = 0, changed = 0, removed = 0;
        if (list) {
            // three-way per value: the count of each value is the user's where BASE kept vanilla's, BASE's where the
            // user kept vanilla's, the user's where both changed it (idempotent: merging again changes nothing).
            // VAN211 and USER values are compared after mapping, so a user obj that moved (34721 -> 60000) is not
            // taken for BASE's own 34721
            List<Item> vl = mapAll(byKey(vp), mapper), ul = mapAll(byKey(up), mapper), bl = byKey(bp);
            Map<String, Integer> vC = valueCounts(vl), uC = valueCounts(ul), bC = valueCounts(bl), target = new HashMap<>();
            Set<String> all = new LinkedHashSet<>(bC.keySet());
            all.addAll(uC.keySet());
            all.addAll(vC.keySet());
            for (String x : all) {
                int vv = vC.getOrDefault(x, 0), uu = uC.getOrDefault(x, 0), bb = bC.getOrDefault(x, 0);
                int t = bb == vv ? uu : uu == vv ? bb : uu;
                if (!applyRemovals && t < bb) { notApplied += bb - t; t = bb; }
                target.put(x, t);
            }
            // BASE's order, trimmed to the target counts
            List<String> keys = new ArrayList<>();
            List<byte[]> vals = new ArrayList<>();
            Map<String, Integer> used = new HashMap<>();
            for (Item c : bl) {
                String x = Bytes.hex(value(c));
                if (used.getOrDefault(x, 0) < target.get(x)) { keys.add(x); vals.add(value(c)); used.merge(x, 1, Integer::sum); }
                else removed++;
            }
            // the user's additions, each right after the element that precedes it in the user's list
            int anchor = -1;
            Map<String, Integer> occ = new HashMap<>();
            for (Item c : ul) {
                String x = Bytes.hex(value(c));
                int n = occ.merge(x, 1, Integer::sum), have = used.getOrDefault(x, 0);
                if (n <= have) { int i = nthIndex(keys, x, n); if (i >= 0) anchor = i; continue; }
                if (have >= target.get(x)) continue;
                keys.add(anchor + 1, x);
                vals.add(anchor + 1, value(c));
                anchor++;
                used.merge(x, 1, Integer::sum);
                added++;
            }
            children = new ArrayList<>(vals.size());
            for (int i = 0; i < vals.size(); i++) {
                byte[] raw = Bytes.concat(new BufOut(4).i32(i).done(), vals.get(i));
                children.add(reparseChild(bp.op, i, raw, up, mapper));
            }
        } else {
            Map<String, Item> vc = Merge3.children(vp), uc = Merge3.children(up);
            List<Item> bch = bp == null ? new ArrayList<>() : new ArrayList<>(bp.children);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < bch.size(); i++) idx.put(bch.get(i).key, i);
            Set<String> keys = new LinkedHashSet<>(uc.keySet());
            keys.addAll(vc.keySet());
            for (String k : keys) {
                Item vi = vc.get(k), ui = uc.get(k);
                if (CfgModel.equalItems(vi, ui)) continue;
                if (ui == null) {
                    if (!applyRemovals) { notApplied++; continue; }
                    Integer i = idx.get(k);
                    if (i == null || bch.get(i) == null) continue;
                    if (!CfgModel.equalItems(bch.get(i), vi)) conflicts.add(k.substring(1) + " (removed)");
                    bch.set(i, null);
                    removed++;
                    continue;
                }
                Item mu = CfgCodec.map(ui, mapper);
                Integer i = idx.get(mu.key);
                if (i != null && bch.get(i) != null) {
                    if (CfgModel.equalItems(bch.get(i), mu)) continue;
                    if (vi == null || !CfgModel.equalItems(bch.get(i), vi)) conflicts.add(mu.key.substring(1));
                    bch.set(i, mu);
                    changed++;
                } else {
                    idx.put(mu.key, bch.size());
                    bch.add(mu);
                    added++;
                }
            }
            bch.removeIf(x -> x == null);
            children = bch;
        }
        // rebuild: BASE's scalar result, then the pairs container where BASE had it (or at the end)
        List<Item> items = new ArrayList<>(scal.entry.items);
        Item container = Item.container("map", bp != null ? bp.op : up != null ? up.op : 6, children);
        int at = -1;
        for (int i = 0; i < b.items.size(); i++) if (b.items.get(i).isContainer()) at = i;
        if (children.isEmpty() && bp == null) { /* nothing */ }
        else if (at >= 0 && at <= items.size()) items.add(Math.min(at, items.size()), container);
        else items.add(container);
        r = new Result(CfgCodec.build(g, b.id, items), list);
        r.conflicts.addAll(conflicts);
        for (String c : scal.conflicts) r.conflicts.add("field " + c);
        r.added = added; r.changed = changed; r.removed = removed; r.removalsNotApplied = notApplied;
        return r;
    }

    /** A list element rebuilt from bytes, typed like the user's pairs (so its references are known). */
    private static Item reparseChild(int op, int index, byte[] raw, Item like, CfgCodec.Mapper mapper) {
        List<CfgCodec.Ref> refs = new ArrayList<>();
        if (like != null && !like.children.isEmpty() && op == 6) {
            for (CfgCodec.Ref r : like.children.get(0).refs) if (r.off() == 4) { refs.add(new CfgCodec.Ref(4, r.enc(), r.kind(), Bytes.i32(raw, 4))); break; }
        }
        return Item.leaf("k" + index, op, raw, refs);
    }

    /**
     * The user's enum without the pairs the user added or changed whose value (or key) {@code drop} rejects: GadScape
     * stand-ins for content BASE now has natively (collection log categories GadScape made for Duke Sucellus, Vardorvis,
     * Araxxor and the Tormented Demons, which BASE lists itself). Pairs vanilla 211 already had stay. Returns the
     * dropped pairs in {@code dropped} ("key=value").
     */
    public static Entry withoutUserPairs(Entry u, Entry v, java.util.function.BiPredicate<com.gadscape.cacheconv.core.Kind, Integer> drop, List<String> dropped) {
        Item up = pairs(u);
        if (up == null) return u;
        Map<String, Item> vc = Merge3.children(v == null ? null : pairs(v));
        List<Item> keep = new ArrayList<>();
        for (Item c : up.children) {
            boolean bad = false;
            for (CfgCodec.Ref r : c.refs) if (!r.none() && r.kind() != null && drop.test(r.kind(), r.value())) bad = true;
            if (bad && !CfgModel.equalItems(vc.get(c.key), c)) { dropped.add(Bytes.i32(c.raw, 0) + "=" + Bytes.i32(c.raw, 4)); continue; }
            keep.add(c);
        }
        if (keep.size() == up.children.size()) return u;
        if (dense(up)) {
            // a list (keys 0..n-1): the remaining elements are renumbered so it stays a list, in the user's order
            keep.sort(java.util.Comparator.comparingInt(c -> Bytes.i32(c.raw, 0)));
            List<Item> renum = new ArrayList<>(keep.size());
            for (int i = 0; i < keep.size(); i++) {
                Item c = keep.get(i);
                byte[] raw = c.raw.clone();
                raw[0] = (byte) (i >>> 24); raw[1] = (byte) (i >>> 16); raw[2] = (byte) (i >>> 8); raw[3] = (byte) i;
                renum.add(Item.leaf("k" + i, c.op, raw, c.refs));
            }
            keep = renum;
        }
        List<Item> items = new ArrayList<>();
        for (Item i : u.items) items.add(i == up ? up.withChildren(keep) : i);
        return CfgCodec.build(u.group, u.id, items);
    }

    static Entry withoutPairs(Entry e) {
        List<Item> items = new ArrayList<>();
        for (Item i : e.items) if (!i.isContainer()) items.add(i);
        return CfgCodec.build(e.group, e.id, items);
    }

    /** Pairs of an enum as key -> value bytes (for reports and tests). */
    public static LinkedHashMap<Integer, byte[]> pairsOf(Entry e) {
        LinkedHashMap<Integer, byte[]> m = new LinkedHashMap<>();
        Item p = pairs(e);
        if (p != null) for (Item c : p.children) m.put(Bytes.i32(c.raw, 0), value(c));
        return m;
    }
}
