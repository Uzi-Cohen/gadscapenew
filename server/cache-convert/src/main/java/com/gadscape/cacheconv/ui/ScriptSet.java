package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every clientscript of one 211 cache, analysed together with {@link CsSim}: return counts (computed callee first,
 * memoised), argument types (propagated through procs until nothing changes) and the typed constants of each script.
 * Param and dbtable types come from the same cache's configs.
 */
public final class ScriptSet implements CsSim.World {
    private final CacheView cache;
    private final CsCommands table;
    private final Map<Integer, byte[]> raw = new HashMap<>();
    private final Map<Integer, Cs2.Script> parsed = new HashMap<>();
    private final Map<Integer, int[]> returns = new HashMap<>();
    private final Set<Integer> inProgress = new HashSet<>();
    private final Set<Integer> noReturns = new HashSet<>();
    private final Map<Integer, String[]> argTypes = new HashMap<>();
    private final Map<Integer, Boolean> paramString = new HashMap<>();
    private final Map<Integer, boolean[][]> dbtables = new HashMap<>();
    private final TreeMap<Integer, CsSim.Result> results = new TreeMap<>();
    /** Replacement 211 bytes for some ids (scripts already patched), consulted before the cache. */
    private final Map<Integer, byte[]> overrides = new HashMap<>();

    public ScriptSet(CacheView cache211) {
        this.cache = cache211;
        this.table = CsCommands.get();
        loadParams();
        loadDbTables();
    }

    public CacheView cache() { return cache; }

    /** 211 bytes of a script (not copied: read only), or null. */
    public byte[] raw(int id) {
        byte[] o = overrides.get(id);
        if (o != null) return o;
        return raw.computeIfAbsent(id, k -> {
            byte[] b = cache.single(Arch.SCRIPTS, k);
            return b == null || b.length < 14 ? null : b;
        });
    }

    public Cs2.Script script(int id) {
        Cs2.Script s = parsed.get(id);
        if (s == null) { byte[] b = raw(id); if (b == null) return null; s = Cs2.parse211(b); parsed.put(id, s); }
        return s;
    }

    @Override public int[] args(int id) { byte[] b = raw(id); return b == null ? null : ScriptTrailer.args211(b); }

    @Override public int[] returns(int id) {
        int[] r = returns.get(id);
        if (r != null) return r;
        if (noReturns.contains(id) || inProgress.contains(id)) return null;
        Cs2.Script s = script(id);
        if (s == null) return null;
        inProgress.add(id);
        try {
            CsSim.Result res = CsSim.run(table, this, id, s);
            r = res.returns;
            // a script that stopped early still has a reliable return count if one return was reached;
            // a script without any reachable "return" returns nothing
            if (r == null && res.complete) r = new int[]{0, 0};
            if (r == null) { noReturns.add(id); return null; }
            returns.put(id, r);
            return r;
        } finally {
            inProgress.remove(id);
        }
    }

    @Override public String[] argTypes(int id) { return argTypes.get(id); }

    @Override public Boolean paramIsString(int param) { return paramString.get(param); }

    @Override public boolean[] dbColumn(int t, int c) {
        boolean[][] cols = dbtables.get(t);
        return cols == null || c >= cols.length ? null : cols[c];
    }

    /**
     * Simulates every script of the cache, repeating until the argument types stop changing (types flow from a proc's
     * uses to its callers, then to their callers). Returns the results by id.
     */
    public TreeMap<Integer, CsSim.Result> analyseAll() {
        int[] ids = cache.groupIds(Arch.SCRIPTS);
        for (int round = 0; round < 12; round++) {
            boolean changed = false;
            results.clear();
            for (int id : ids) {
                Cs2.Script s = script(id);
                if (s == null) continue;
                returns(id);
                CsSim.Result r = CsSim.run(table, this, id, s);
                results.put(id, r);
                String[] before = argTypes.get(id);
                if (!Arrays.equals(before, r.argTypes)) { argTypes.put(id, r.argTypes.clone()); changed = true; }
            }
            if (!changed) break;
        }
        return results;
    }

    public TreeMap<Integer, CsSim.Result> results() { return results; }

    /** Re-simulates one script (after its bytes were overridden), with the argument types already known. */
    public CsSim.Result analyse(int id) {
        parsed.remove(id);
        Cs2.Script s = script(id);
        return s == null ? null : CsSim.run(table, this, id, s);
    }

    // ------------------------------------------------------------------ configs

    /** Param configs (group 11): opcode 1 = type char, 2 = default int, 4 = no autodisable, 5 = default string. */
    private void loadParams() {
        for (int id : cache.configIds(Arch.CFG_PARAM)) {
            byte[] b = cache.configRef(Arch.CFG_PARAM, id);
            if (b == null) continue;
            Buf in = new Buf(b);
            char type = 'i';
            try {
                for (int op; !in.eof() && (op = in.u8()) != 0; ) {
                    switch (op) {
                        case 1 -> type = (char) in.u8();
                        case 2 -> in.i32();
                        case 4 -> { }
                        case 5 -> { while (in.u8() != 0) { } }
                        default -> throw new IllegalStateException("param " + id + " opcode " + op);
                    }
                }
                paramString.put(id, type == 's');
            } catch (RuntimeException e) {
                // unknown layout: leave the param untyped (scripts reading it stop being typed there)
            }
        }
    }

    /** Dbtable configs (group 39): opcode 1 = column types (and defaults). String type id is 36. */
    private void loadDbTables() {
        for (int id : cache.configIds(Arch.CFG_DBTABLE)) {
            byte[] b = cache.configRef(Arch.CFG_DBTABLE, id);
            if (b == null) continue;
            Buf in = new Buf(b);
            try {
                for (int op; !in.eof() && (op = in.u8()) != 0; ) {
                    if (op != 1) throw new IllegalStateException("dbtable " + id + " opcode " + op);
                    int n = in.u8();
                    boolean[][] cols = new boolean[n][];
                    for (int setting = in.u8(); setting != 255; setting = in.u8()) {
                        int col = setting & 0x7f;
                        int[] types = new int[in.u8()];
                        for (int i = 0; i < types.length; i++) types[i] = smartU(in);
                        boolean[] str = new boolean[types.length];
                        for (int i = 0; i < types.length; i++) str[i] = types[i] == 36;
                        if (col < n) cols[col] = str;
                        if ((setting & 0x80) != 0) {
                            int fields = smartU(in);
                            for (int f = 0; f < fields; f++) for (boolean st : str) { if (st) { while (in.u8() != 0) { } } else in.i32(); }
                        }
                    }
                    dbtables.put(id, cols);
                }
            } catch (RuntimeException e) {
                // leave untyped
            }
        }
    }

    private static int smartU(Buf in) { return (in.b[in.p] & 255) < 128 ? in.u8() : in.u16() - 32768; }
}
