package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.config.CfgCodec.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field-level three-way merge of a vanilla entry the user edited: the user's edit (VAN211 -> USER) is re-applied onto
 * BASE's entry, so Jagex's later changes to the same entry (new params, examine text, categories, model updates)
 * survive next to the user's.
 * <ul>
 *   <li>A key whose USER value differs from VAN211's is set to the USER value (references mapped through the id
 *       map); if BASE also changed it, the USER value wins and the conflict is reported.</li>
 *   <li>A key the user added only to write the client default (what the user's encoders do for every item they
 *       save: "Take", "Drop", shift-click -2, ...) changes nothing and is skipped.</li>
 *   <li>A key the user removed is removed from BASE, unless VAN211's value was the default or the key is one the
 *       user's encoders cannot write ({@link CfgModel#lossy}); BASE's value then stays.</li>
 *   <li>Params (249) merge per param the same way.</li>
 * </ul>
 * Keys the user did not touch keep BASE's value (or stay absent when Jagex removed them). The result keeps BASE's
 * opcode order; new keys go before BASE's params block.
 */
public final class Merge3 {
    private Merge3() {}

    public static final class Result {
        public final Entry entry;
        /** What was applied, e.g. "+33", "~44", "-37", "p451-". */
        public final List<String> applied = new ArrayList<>();
        /** Keys both the user and Jagex changed (the user value won). */
        public final List<String> conflicts = new ArrayList<>();
        Result(Entry e) { this.entry = e; }
        public boolean changedBase() { return !applied.isEmpty(); }
    }

    public static Result merge(int g, Entry v, Entry u, Entry b, CfgCodec.Mapper mapper, CfgModel.Lossy lossy) {
        return merge(g, v, u, b, mapper, lossy, true);
    }

    /**
     * @param applyRemovals false: fields (and params) the user's entry lacks are never removed from BASE's. Used when
     *                      the merge base is not what the user's editor loaded (a rev-225 import: GadScape's encoders
     *                      drop fields they cannot write, so a missing field says nothing about the user's intent)
     */
    public static Result merge(int g, Entry v, Entry u, Entry b, CfgCodec.Mapper mapper, CfgModel.Lossy lossy, boolean applyRemovals) {
        LinkedHashMap<String, Item> vm = v.map(), um = u.map(), bm = b.map();
        List<Item> out = new ArrayList<>(b.items);
        List<Item> appended = new ArrayList<>();
        List<String> applied = new ArrayList<>(), conflicts = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(um.keySet());
        keys.addAll(vm.keySet());
        for (String key : keys) {
            Item vi = vm.get(key), ui = um.get(key);
            if (CfgModel.equalItems(vi, ui)) continue;
            boolean container = (vi != null ? vi : ui).isContainer();
            if (container) { mergeContainer(key, vi, ui, out, appended, bm.get(key), mapper, lossy, applied, conflicts, applyRemovals); continue; }
            if (ui == null) {
                if (!applyRemovals || CfgModel.isDefault(g, vi) || (lossy != null && lossy.contains(vi))) continue;
                Item bi = bm.get(key);
                if (bi == null) continue;
                if (!CfgModel.equalItems(bi, vi)) conflicts.add("-" + key);
                out.removeIf(it -> it.key.equals(key));
                applied.add("-" + key);
                continue;
            }
            if (vi == null && CfgModel.isDefault(g, ui)) continue;
            Item mu = CfgCodec.map(ui, mapper);
            Item bi = bm.get(key);
            if (bi != null) {
                if (CfgModel.equalItems(bi, mu)) continue;
                if (vi == null || !CfgModel.equalItems(bi, vi)) conflicts.add("~" + key);
                replaceLast(out, key, mu);
                applied.add("~" + key);
            } else {
                appended.add(mu);
                applied.add("+" + key);
            }
        }
        insertBeforeContainers(out, appended);
        Result r = new Result(CfgCodec.build(g, b.id, out));
        r.applied.addAll(applied);
        r.conflicts.addAll(conflicts);
        return r;
    }

    /** Params (or any keyed container): per-element three-way. */
    static void mergeContainer(String key, Item vi, Item ui, List<Item> out, List<Item> appended, Item bi, CfgCodec.Mapper mapper,
                               CfgModel.Lossy lossy, List<String> applied, List<String> conflicts, boolean applyRemovals) {
        Map<String, Item> vc = children(vi), uc = children(ui);
        List<Item> bch = bi == null ? new ArrayList<>() : new ArrayList<>(bi.children);
        Map<String, Integer> bIdx = new HashMap<>();
        for (int i = 0; i < bch.size(); i++) bIdx.put(bch.get(i).key, i);
        boolean changed = false;
        Set<String> ck = new LinkedHashSet<>(uc.keySet());
        ck.addAll(vc.keySet());
        for (String c : ck) {
            Item v = vc.get(c), u = uc.get(c);
            if (CfgModel.equalItems(v, u)) continue;
            if (u == null) {
                if (!applyRemovals || (lossy != null && lossy.contains(v))) continue;
                Integer i = bIdx.get(c);
                if (i == null) continue;
                if (!CfgModel.equalItems(bch.get(i), v)) conflicts.add(c + "-");
                bch.set(i, null);
                applied.add(c + "-");
                changed = true;
                continue;
            }
            Item mu = CfgCodec.map(u, mapper);
            Integer i = bIdx.get(mu.key);
            if (i != null && bch.get(i) != null) {
                if (CfgModel.equalItems(bch.get(i), mu)) continue;
                if (v == null || !CfgModel.equalItems(bch.get(i), v)) conflicts.add(c + "~");
                bch.set(i, mu);
                applied.add(c + "~");
            } else {
                bIdx.put(mu.key, bch.size());
                bch.add(mu);
                applied.add(c + "+");
            }
            changed = true;
        }
        if (!changed) return;
        bch.removeIf(x -> x == null);
        Item op = vi != null ? vi : ui;
        if (bi == null) {
            if (!bch.isEmpty()) appended.add(Item.container(key, op.op, bch));
            return;
        }
        int last = -1;
        for (int i = 0; i < out.size(); i++) if (out.get(i).key.equals(key)) last = i;
        if (bch.isEmpty()) out.remove(last);
        else out.set(last, bi.withChildren(bch));
    }

    static Map<String, Item> children(Item c) {
        Map<String, Item> m = new LinkedHashMap<>();
        if (c != null && c.isContainer()) for (Item x : c.children) m.put(x.key, x);
        return m;
    }

    static void replaceLast(List<Item> out, String key, Item with) {
        for (int i = out.size() - 1; i >= 0; i--) if (out.get(i).key.equals(key)) { out.set(i, with); return; }
        out.add(with);
    }

    /** Appends new items before the first container (params), or at the end. */
    static void insertBeforeContainers(List<Item> out, List<Item> add) {
        if (add.isEmpty()) return;
        List<Item> leaves = new ArrayList<>(), conts = new ArrayList<>();
        for (Item a : add) (a.isContainer() ? conts : leaves).add(a);
        int at = out.size();
        for (int i = 0; i < out.size(); i++) if (out.get(i).isContainer()) { at = i; break; }
        out.addAll(at, leaves);
        out.addAll(conts);
    }
}
