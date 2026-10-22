package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.CacheView;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spellbook (interface 218) anchors: every spell obj names its component in param 596 (interface << 16 | component;
 * the server's SpellDefinitions reads the same param). A spell obj present in VAN211 and BASE that names exactly one
 * component in each pairs the two components. These override the docs table, which is two components off for a few
 * 211 spells (218:4/5, 58, 70, 96-99).
 */
public final class SpellAnchors {
    private SpellAnchors() {}

    public static final int SPELLBOOK = 218;
    static final int PARAM_COMPONENT = 596;

    /** VAN211 component -> BASE component. */
    public static TreeMap<Integer, Integer> anchors(CacheView van, CacheView base) {
        Map<Integer, java.util.Set<Integer>> v = comps(van), b = comps(base);
        TreeMap<Integer, java.util.Set<Integer>> pairs = new TreeMap<>();
        for (var e : v.entrySet()) {
            java.util.Set<Integer> bb = b.get(e.getKey());
            if (e.getValue().size() != 1 || bb == null || bb.size() != 1) continue;
            pairs.computeIfAbsent(e.getValue().iterator().next(), k -> new java.util.TreeSet<>()).add(bb.iterator().next());
        }
        TreeMap<Integer, Integer> out = new TreeMap<>();
        for (var e : pairs.entrySet()) if (e.getValue().size() == 1) out.put(e.getKey(), e.getValue().iterator().next());
        return out;
    }

    /** Spell obj -> components of interface 218 its param 596 names. */
    static Map<Integer, java.util.Set<Integer>> comps(CacheView c) {
        Map<Integer, java.util.Set<Integer>> m = new HashMap<>();
        for (int obj : c.configIds(Arch.CFG_OBJ)) {
            byte[] b = c.configRef(Arch.CFG_OBJ, obj);
            if (b == null) continue;
            int v = param(b, PARAM_COMPONENT);
            if (v != Integer.MIN_VALUE && (v >>> 16) == SPELLBOOK) m.computeIfAbsent(obj, k -> new java.util.TreeSet<>()).add(v & 0xffff);
        }
        return m;
    }

    /** An int param of an obj config (the params block, opcode 249), found by its type-0 key bytes; MIN_VALUE if absent. */
    static int param(byte[] b, int key) {
        for (int p = 0; p + 8 <= b.length; p++)
            if (b[p] == 0 && Bytes.u24(b, p + 1) == key && p > 0) {
                // the params block is the last opcode before 0: walk it properly from the 249 opcode when possible
                int q = find249(b);
                if (q < 0) return Bytes.i32(b, p + 4);
                int n = b[q + 1] & 255, r = q + 2;
                for (int i = 0; i < n && r < b.length; i++) {
                    int t = b[r] & 255, k = Bytes.u24(b, r + 1);
                    r += 4;
                    if (t == 1) { if (k == key) return Integer.MIN_VALUE; while (b[r] != 0) r++; r++; }
                    else if (t == 2) r += 8;
                    else { if (k == key) return Bytes.i32(b, r); r += 4; }
                }
                return Integer.MIN_VALUE;
            }
        return Integer.MIN_VALUE;
    }

    /** Offset of the params opcode (249) if the block parses to the end of the entry; -1 otherwise. */
    static int find249(byte[] b) {
        for (int q = b.length - 2; q >= 0; q--) {
            if ((b[q] & 255) != 249) continue;
            int n = b[q + 1] & 255, r = q + 2;
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                if (r + 4 > b.length) { ok = false; break; }
                int t = b[r] & 255;
                r += 4;
                if (t == 1) { while (r < b.length && b[r] != 0) r++; r++; }
                else if (t == 2) r += 8;
                else if (t == 0) r += 4;
                else { ok = false; break; }
            }
            if (ok && r < b.length && b[r] == 0 && Bytes.isZero(b, r, b.length)) return q;
        }
        return -1;
    }
}
