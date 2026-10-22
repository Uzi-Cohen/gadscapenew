package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every clientscript of one cache, parsed in the 241 layout, plus what {@link CsStack} needs to know about the cache
 * (callee signatures, return counts, param and dbtable types). {@link #analyseAll} runs the stack analysis over the
 * whole cache, computing return counts bottom-up: a script's return counts are the depths at its {@code return}, and
 * callers waiting for an unknown callee are re-run until nothing changes (recursion resolves through the paths that
 * return without recursing).
 */
public final class CsWorld implements CsStack.World {
    public final CacheView cache;
    public final CacheTypes types;
    public final TreeMap<Integer, Cs2.Script> scripts = new TreeMap<>();
    /** Scripts that do not parse as 241 scripts: id -> error. */
    public final TreeMap<Integer, String> parseErrors = new TreeMap<>();
    /** Groups too short to be a script (no trailer; e.g. stock script 0, 2 bytes): id -> length. Never callable. */
    public final TreeMap<Integer, Integer> stubs = new TreeMap<>();
    /** Smallest 241 script: name terminator, 16-byte trailer, switch table length. */
    public static final int MIN_SCRIPT = 1 + 16 + 2;
    private final Map<Integer, int[]> returns = new HashMap<>();
    /** Fixed return counts (tests, or callers that know better). */
    public final Map<Integer, int[]> fixedReturns = new HashMap<>();

    public CsWorld(CacheView cache) {
        this.cache = cache;
        this.types = new CacheTypes(cache);
        for (int id : cache.groupIds(Arch.SCRIPTS)) {
            byte[] b = cache.single(Arch.SCRIPTS, id);
            if (b == null) { parseErrors.put(id, "no data"); continue; }
            if (b.length < MIN_SCRIPT) { stubs.put(id, b.length); continue; }
            try {
                scripts.put(id, Cs2.parse241(b));
            } catch (RuntimeException e) {
                parseErrors.put(id, String.valueOf(e.getMessage()));
            }
        }
        cache.release(Arch.SCRIPTS);
    }

    /** A world over given scripts (tests); configs come from {@code cache} (may be an empty view). */
    public CsWorld(CacheView cache, Map<Integer, Cs2.Script> scripts) {
        this.cache = cache;
        this.types = new CacheTypes(cache);
        this.scripts.putAll(scripts);
    }

    @Override public int[] args(int script) {
        Cs2.Script s = scripts.get(script);
        return s == null ? null : new int[]{s.intArgs, s.objArgs, s.longArgs};
    }

    @Override public int[] returns(int script) {
        int[] f = fixedReturns.get(script);
        return f != null ? f : returns.get(script);
    }

    // array element types seen flowing into script args, out of returns and through varcs (conflicts -> -2)
    private final Map<Long, Integer> argArrays = new HashMap<>(), retArrays = new HashMap<>();
    private final Map<Integer, Integer> varcArrays = new HashMap<>();
    private boolean arraysChanged;

    private static long key(int script, int k) { return ((long) script << 16) | k; }

    private <K> void note(Map<K, Integer> m, K k, int type) {
        Integer old = m.get(k);
        if (old == null) { m.put(k, type); arraysChanged = true; }
        else if (old != type && old != -2) { m.put(k, -2); arraysChanged = true; }
    }

    private static int get(Integer v) { return v == null || v < 0 ? -1 : v; }

    @Override public int argArray(int script, int k) { return get(argArrays.get(key(script, k))); }
    @Override public void noteArgArray(int script, int k, int type) { note(argArrays, key(script, k), type); }
    @Override public int returnArray(int script, int k) { return get(retArrays.get(key(script, k))); }
    @Override public void noteReturnArray(int script, int k, int type) { note(retArrays, key(script, k), type); }
    @Override public int varcArray(int varc) { return get(varcArrays.get(varc)); }
    @Override public void noteVarcArray(int varc, int type) { note(varcArrays, varc, type); }

    @Override public int paramBase(int param) { return types.paramBase(param); }
    @Override public int[] dbColumn(int table, int column) { return types.dbColumn(table, column); }
    @Override public int charBase(int ch) { return types.charBase(ch); }

    /** Return counts as computed by the last {@link #analyseAll}. */
    public Map<Integer, int[]> returnCounts() { return returns; }

    /**
     * Analyses every script (ascending ids), iterating until the return counts are stable. The result holds the last
     * analysis of each script.
     */
    public TreeMap<Integer, CsStack.Result> analyseAll(CsStack.Effects fx) {
        TreeMap<Integer, CsStack.Result> res = null;
        // array element types learned in one round (args, returns, varcs) can unblock paths in the next
        for (int round = 0; round < 4; round++) {
            arraysChanged = false;
            res = analyse(new ArrayList<>(scripts.keySet()), fx);
            if (!arraysChanged) break;
        }
        return res;
    }

    public TreeMap<Integer, CsStack.Result> analyse(List<Integer> ids, CsStack.Effects fx) {
        TreeMap<Integer, CsStack.Result> res = new TreeMap<>();
        List<Integer> pending = new ArrayList<>(ids);
        for (int pass = 0; pass < 64 && !pending.isEmpty(); pass++) {
            boolean changed = false;
            List<Integer> next = new ArrayList<>();
            for (int id : pending) {
                CsStack.Result r = CsStack.analyse(id, scripts.get(id), this, fx);
                res.put(id, r);
                if (r.returns != null && !returns.containsKey(id)) { returns.put(id, r.returns); changed = true; }
                if (!r.blockedOn.isEmpty()) next.add(id);
            }
            pending = next;
            if (!changed) break;
        }
        return res;
    }

    /** Clears the computed return counts and array types (before analysing with another effect table). */
    public void resetReturns() { returns.clear(); argArrays.clear(); retArrays.clear(); varcArrays.clear(); }

    public CsStack.Effects effects(CsTable t) { return t::counts; }
}
