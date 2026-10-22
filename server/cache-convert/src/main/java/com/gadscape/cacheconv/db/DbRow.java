package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * A dbrow (config group 38) or dbtable (config group 39) entry in the build-241 layout, decoded the way the client
 * decodes it ({@code DbRowType} / {@code DbTableType}):
 * <ul>
 *   <li>dbrow opcode 3: column count (u8); per column until 255: column id (u8), type count (u8), type ids (smart),
 *       field count (smart), then per field one value per type; opcode 4: table id (protobuf varint).</li>
 *   <li>dbtable opcode 1: column count (u8); per column until 255: setting (u8, column = setting &amp; 127, bit 7 =
 *       has defaults), type count (u8), type ids (smart), and with bit 7 the default values (same layout as a row).</li>
 * </ul>
 * Values are kept as Integer / Long / String by the base type of their script var type ({@link #baseType}).
 * {@code encode(decode(b))} gives b back for every BASE and USER entry, minus a zero tail ({@link #padding}).
 */
public final class DbRow {
    /** One column: its script var type ids and the values, {@code fields x types.length}, field-major. */
    public static final class Column {
        public final int id;
        public final int[] types;
        public final Object[] values;
        /** dbtable only: the column declares default values. */
        public final boolean hasDefaults;

        public Column(int id, int[] types, Object[] values, boolean hasDefaults) {
            this.id = id; this.types = types; this.values = values; this.hasDefaults = hasDefaults;
        }

        public int fields() { return types.length == 0 ? 0 : values.length / types.length; }
        /** Value of tuple element {@code t} in field {@code f}. */
        public Object value(int f, int t) { return values[f * types.length + t]; }

        @Override public boolean equals(Object o) {
            return o instanceof Column c && c.id == id && c.hasDefaults == hasDefaults && Arrays.equals(c.types, types) && Arrays.equals(c.values, values);
        }
        @Override public int hashCode() { return id * 31 + Arrays.hashCode(types); }
    }

    /** True for a dbtable definition, false for a dbrow. */
    public final boolean table;
    /** Declared column count (opcode 3/1 first byte). */
    public int columnCount;
    /** dbrow only: the table (opcode 4), or -1. */
    public int tableId = -1;
    public final TreeMap<Integer, Column> columns = new TreeMap<>();
    /** Opcode order as read (3/4 for rows), so encode() reproduces the bytes. */
    private int[] order = new int[0];
    /** Zero bytes after the end opcode (the GadScape server's packer pads its universal-shop rows); not written back. */
    public int padding;

    private DbRow(boolean table) { this.table = table; }

    /**
     * Base var type of a script var type id: 0 int, 1 long, 2 string (the client's BaseVarType). Type 36 is string;
     * the long types are 35, 49, 56, 71, 110, 115 and 116; everything else is an int.
     */
    public static int baseType(int typeId) {
        if (typeId == 36) return 2;
        return switch (typeId) { case 35, 49, 56, 71, 110, 115, 116 -> 1; default -> 0; };
    }

    public static int varint(Buf in) { int v = 0, bits = 0, r; do { r = in.u8(); v |= (r & 0x7f) << bits; bits += 7; } while (r > 127); return v; }
    public static void varint(BufOut o, int v) { do { int x = v & 0x7f; v >>>= 7; o.u8(v != 0 ? x | 0x80 : x); } while (v != 0); }

    public static DbRow row(byte[] b) { return decode(b, false); }
    public static DbRow tableDef(byte[] b) { return decode(b, true); }

    /** The table id of a dbrow without keeping the values (-1 if none). */
    public static int tableOf(byte[] b) { return row(b).tableId; }

    private static DbRow decode(byte[] b, boolean table) {
        DbRow r = new DbRow(table);
        Buf in = new Buf(b);
        int[] ord = new int[4];
        int n = 0;
        while (true) {
            int op = in.u8();
            if (op == 0) break;
            if (n == ord.length) ord = Arrays.copyOf(ord, n * 2);
            ord[n++] = op;
            if (op == (table ? 1 : 3)) {
                r.columnCount = in.u8();
                while (true) {
                    int s = in.u8();
                    if (s == 255) break;
                    int col = table ? s & 127 : s;
                    boolean def = table && (s & 128) != 0;
                    int tc = in.u8();
                    int[] types = new int[tc];
                    for (int i = 0; i < tc; i++) types[i] = in.smart();
                    Object[] vals = table && !def ? new Object[0] : values(in, types);
                    r.columns.put(col, new Column(col, types, vals, def));
                }
            } else if (!table && op == 4) {
                r.tableId = varint(in);
            } else {
                throw new IllegalStateException((table ? "dbtable" : "dbrow") + ": unknown opcode " + op + " at " + (in.p - 1));
            }
        }
        if (in.p != b.length) {
            if (!com.gadscape.cacheconv.core.Bytes.isZero(b, in.p, b.length))
                throw new IllegalStateException((table ? "dbtable" : "dbrow") + ": " + (b.length - in.p) + " non-zero bytes after the end opcode");
            r.padding = b.length - in.p;
        }
        r.order = Arrays.copyOf(ord, n);
        return r;
    }

    private static Object[] values(Buf in, int[] types) {
        int fields = in.smart();
        Object[] v = new Object[fields * types.length];
        for (int f = 0; f < fields; f++)
            for (int t = 0; t < types.length; t++)
                v[f * types.length + t] = switch (baseType(types[t])) { case 2 -> in.str(); case 1 -> in.i64(); default -> in.i32(); };
        return v;
    }

    public byte[] encode() {
        BufOut o = new BufOut();
        for (int op : order) {
            o.u8(op);
            if (op == 4) { varint(o, tableId); continue; }
            o.u8(columnCount);
            for (Map.Entry<Integer, Column> e : columns.entrySet()) {
                Column c = e.getValue();
                o.u8(table ? (c.id | (c.hasDefaults ? 128 : 0)) : c.id);
                o.u8(c.types.length);
                for (int t : c.types) o.smart(t);
                if (table && !c.hasDefaults) continue;
                o.smart(c.fields());
                for (int i = 0; i < c.values.length; i++) {
                    Object v = c.values[i];
                    switch (baseType(c.types[i % c.types.length])) {
                        case 2 -> o.str((String) v);
                        case 1 -> o.i64((Long) v);
                        default -> o.i32((Integer) v);
                    }
                }
            }
            o.u8(255);
        }
        o.u8(0);
        return o.done();
    }
}
