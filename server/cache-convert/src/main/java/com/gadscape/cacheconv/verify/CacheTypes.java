package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import static com.gadscape.cacheconv.verify.CsTable.INT;
import static com.gadscape.cacheconv.verify.CsTable.LONG;
import static com.gadscape.cacheconv.verify.CsTable.OBJ;

/**
 * Script var types of one cache: which stack a type lives on (int / object / long), the param types and the dbtable
 * column types. Type chars and type ids follow the client (RuneLite's {@code ScriptVarType} for the chars of the
 * classic types). Long types are recognised by id ({@link #LONG_TYPE_IDS}) and by char: every char that a long-valued
 * enum (opcode 7) or a param with a long default (opcode 7) declares as its type, plus {@code 'Ï'}.
 */
public final class CacheTypes {
    /** Type ids whose values are longs (dbtable columns / param type ids). */
    public static final int[] LONG_TYPE_IDS = {35, 49, 56, 71, 110, 115, 116};
    public static final int STRING_TYPE_ID = 36;

    private final CacheView c;
    private final Map<Integer, Integer> paramChar = new HashMap<>();
    private final Map<Integer, Integer> paramTypeId = new HashMap<>();
    private final Map<Integer, Integer> paramDefaultBase = new HashMap<>();
    private final Map<Integer, int[][]> dbtables = new HashMap<>();
    private final TreeSet<Integer> longChars = new TreeSet<>();
    private boolean loaded;

    public CacheTypes(CacheView c) { this.c = c; }

    private void load() {
        if (loaded) return;
        loaded = true;
        longChars.add(0xCF); // 'Ï' LONG
        for (int id : c.configIds(Arch.CFG_PARAM)) {
            byte[] b = c.configRef(Arch.CFG_PARAM, id);
            if (b == null) continue;
            Buf in = new Buf(b);
            try {
                int ch = -1, tid = -1, def = INT;
                while (in.p < b.length) {
                    int op = in.u8();
                    if (op == 0) break;
                    switch (op) {
                        case 1 -> ch = in.u8();
                        case 2 -> { in.skip(4); def = INT; }
                        case 4 -> { }
                        case 5 -> { in.skipStr(); def = OBJ; }
                        case 7 -> { in.skip(8); def = LONG; }
                        case 8 -> tid = in.u8();
                        default -> throw new IllegalStateException("param " + id + " op " + op);
                    }
                }
                if (ch >= 0) paramChar.put(id, ch);
                if (tid >= 0) paramTypeId.put(id, tid);
                paramDefaultBase.put(id, def);
                if (def == LONG && ch >= 0) longChars.add(ch);
            } catch (RuntimeException e) {
                paramDefaultBase.put(id, INT);
            }
        }
        for (int id : c.configIds(Arch.CFG_ENUM)) {
            byte[] b = c.configRef(Arch.CFG_ENUM, id);
            if (b == null) continue;
            Buf in = new Buf(b);
            try {
                int vt = -1;
                boolean isLong = false;
                while (in.p < b.length) {
                    int op = in.u8();
                    if (op == 0) break;
                    switch (op) {
                        case 1 -> in.u8();
                        case 2 -> vt = in.u8();
                        case 3 -> in.skipStr();
                        case 4 -> in.skip(4);
                        case 5 -> { int n = in.u16(); for (int i = 0; i < n; i++) { in.skip(4); in.skipStr(); } }
                        case 6 -> { int n = in.u16(); in.skip(n * 8); }
                        case 7 -> { int n = in.u16(); in.skip(n * 12); isLong = true; }
                        case 8 -> { in.skip(8); isLong = true; }
                        default -> throw new IllegalStateException("enum " + id + " op " + op);
                    }
                }
                if (isLong && vt >= 0) longChars.add(vt);
            } catch (RuntimeException ignored) {
                // reported by the config walkers
            }
        }
    }

    /** Stack of a type char. */
    public int charBase(int ch) {
        load();
        if (ch == 's') return OBJ;
        return longChars.contains(ch) ? LONG : INT;
    }

    public TreeSet<Integer> longChars() { load(); return new TreeSet<>(longChars); }

    public static int typeIdBase(int typeId) {
        if (typeId == STRING_TYPE_ID) return OBJ;
        for (int t : LONG_TYPE_IDS) if (t == typeId) return LONG;
        return INT;
    }

    /** The type char of a type id (RuneLite's table), or -1. */
    public static int charOfTypeId(int typeId) {
        net.runelite.cache.util.ScriptVarType t = net.runelite.cache.util.ScriptVarType.forId(typeId);
        return t == null ? -1 : t.getKeyChar() & 0xff;
    }

    /** Stack of a param's values, or -1 if the param does not exist. */
    public int paramBase(int param) {
        load();
        Integer def = paramDefaultBase.get(param);
        if (def == null) return -1;
        Integer ch = paramChar.get(param);
        if (ch != null) return charBase(ch);
        Integer tid = paramTypeId.get(param);
        if (tid != null) return typeIdBase(tid);
        return def;
    }

    /** Type char of a param (from op 1, or op 8's type id), or -1. */
    public int paramChar(int param) {
        load();
        Integer ch = paramChar.get(param);
        if (ch != null) return ch;
        Integer tid = paramTypeId.get(param);
        return tid == null ? -1 : charOfTypeId(tid);
    }

    /** Type ids per column of a dbtable ({@code result[col]}, null for absent columns), or null if no such table. */
    public int[][] dbtable(int table) {
        return dbtables.computeIfAbsent(table, t -> {
            byte[] b = c.configRef(Arch.CFG_DBTABLE, t);
            if (b == null) return null;
            try { return parseDbTable(b); } catch (RuntimeException e) { return null; }
        });
    }

    /** Stacks of a dbtable column's tuple, or null. */
    public int[] dbColumn(int table, int column) {
        int[][] cols = dbtable(table);
        if (cols == null || column < 0 || column >= cols.length || cols[column] == null) return null;
        int[] r = new int[cols[column].length];
        for (int i = 0; i < r.length; i++) r[i] = typeIdBase(cols[column][i]);
        return r;
    }

    /** dbtable entry: op 1 = column count, then per column {setting (col | 0x80 = has defaults), n, n types, [defaults]}, 255. */
    public static int[][] parseDbTable(byte[] b) {
        Buf in = new Buf(b);
        int[][] cols = new int[0][];
        while (true) {
            int op = in.u8();
            if (op == 0) break;
            if (op != 1) throw new IllegalStateException("dbtable op " + op);
            int count = in.u8();
            cols = new int[Math.max(count, cols.length)][];
            while (true) {
                int setting = in.u8();
                if (setting == 255) break;
                int col = setting & 127;
                int nt = in.u8();
                int[] types = new int[nt];
                for (int i = 0; i < nt; i++) types[i] = in.smart();
                if (col >= cols.length) cols = java.util.Arrays.copyOf(cols, col + 1);
                cols[col] = types;
                if ((setting & 128) != 0) skipValues(in, types);
            }
        }
        if (in.p != b.length) throw new IllegalStateException("dbtable: " + (b.length - in.p) + " bytes after the end");
        return cols;
    }

    /** Skips one cell list: field count (smart), then per field one value per type. */
    public static void skipValues(Buf in, int[] types) {
        int fields = in.smart();
        for (int f = 0; f < fields; f++) {
            for (int t : types) {
                int base = typeIdBase(t);
                if (base == OBJ) in.skipStr();
                else if (base == LONG) in.skip(8);
                else in.skip(4);
            }
        }
    }
}
