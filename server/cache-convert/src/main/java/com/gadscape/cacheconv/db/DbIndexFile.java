package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * One file of the dbtable index (archive 21: group = table id; file 0 = the master index, a single int tuple whose
 * only key 0 lists every row of the table; file c + 1 = the lookup index of column c). The client and the server's
 * {@code DBTableIndexDefinition} read: a varint tuple count; per tuple a base var type byte (0 int, 1 long, 2 string),
 * a varint value count, and per value the value (int, long, or 0-terminated string) followed by a varint row count
 * and varint row ids. Varints are protobuf style (7 bits per byte, least significant group first).
 * <p>
 * {@code parse(b).encode()} gives b back (a zero tail is recorded in {@link #padding} and dropped).
 */
public final class DbIndexFile {
    /** One key and the rows holding it. */
    public record Entry(Object value, int[] rows) {
        @Override public boolean equals(Object o) { return o instanceof Entry e && Objects.equals(e.value, value) && Arrays.equals(e.rows, rows); }
        @Override public int hashCode() { return Objects.hashCode(value) * 31 + Arrays.hashCode(rows); }
        @Override public String toString() { return value + "=" + Arrays.toString(rows); }
    }

    /** One tuple element: its base type and keys. */
    public static final class Tuple {
        public final int baseType;
        public final List<Entry> entries = new ArrayList<>();
        public Tuple(int baseType) { this.baseType = baseType; }
        @Override public boolean equals(Object o) { return o instanceof Tuple t && t.baseType == baseType && t.entries.equals(entries); }
        @Override public int hashCode() { return baseType * 31 + entries.hashCode(); }
    }

    public final List<Tuple> tuples = new ArrayList<>();
    /** Zero bytes after the data (some packers pad); not written back. */
    public int padding;

    public static DbIndexFile parse(byte[] b) {
        DbIndexFile d = new DbIndexFile();
        Buf in = new Buf(b);
        int n = DbRow.varint(in);
        for (int t = 0; t < n; t++) {
            Tuple tu = new Tuple(in.u8());
            int count = DbRow.varint(in);
            for (int i = 0; i < count; i++) {
                Object v = switch (tu.baseType) {
                    case 0 -> in.i32();
                    case 1 -> in.i64();
                    case 2 -> in.str();
                    default -> throw new IllegalStateException("dbtable index: unknown base type " + tu.baseType);
                };
                int rc = DbRow.varint(in);
                int[] rows = new int[rc];
                for (int r = 0; r < rc; r++) rows[r] = DbRow.varint(in);
                tu.entries.add(new Entry(v, rows));
            }
            d.tuples.add(tu);
        }
        if (in.p != b.length) {
            if (!Bytes.isZero(b, in.p, b.length)) throw new IllegalStateException("dbtable index: " + (b.length - in.p) + " trailing bytes");
            d.padding = b.length - in.p;
        }
        return d;
    }

    public byte[] encode() {
        BufOut o = new BufOut();
        DbRow.varint(o, tuples.size());
        for (Tuple t : tuples) {
            o.u8(t.baseType);
            DbRow.varint(o, t.entries.size());
            for (Entry e : t.entries) {
                switch (t.baseType) {
                    case 0 -> o.i32((Integer) e.value);
                    case 1 -> o.i64((Long) e.value);
                    default -> o.str((String) e.value);
                }
                DbRow.varint(o, e.rows.length);
                for (int r : e.rows) DbRow.varint(o, r);
            }
        }
        return o.done();
    }

    /** The master index of a table: one int tuple, key 0, every row id in the given order. */
    public static DbIndexFile master(int[] rows) {
        DbIndexFile d = new DbIndexFile();
        Tuple t = new Tuple(0);
        t.entries.add(new Entry(0, rows.clone()));
        d.tuples.add(t);
        return d;
    }

    /** Every row id renumbered (keys and their order kept). */
    public DbIndexFile mapRows(IntUnaryOperator f) {
        DbIndexFile d = new DbIndexFile();
        for (Tuple t : tuples) {
            Tuple n = new Tuple(t.baseType);
            for (Entry e : t.entries) {
                int[] rows = e.rows.clone();
                for (int i = 0; i < rows.length; i++) rows[i] = f.applyAsInt(rows[i]);
                n.entries.add(new Entry(e.value, rows));
            }
            d.tuples.add(n);
        }
        return d;
    }

    /** All row ids referenced, in file order. */
    public List<Integer> rows() {
        List<Integer> r = new ArrayList<>();
        for (Tuple t : tuples) for (Entry e : t.entries) for (int x : e.rows) r.add(x);
        return r;
    }

    @Override public boolean equals(Object o) { return o instanceof DbIndexFile d && d.tuples.equals(tuples); }
    @Override public int hashCode() { return tuples.hashCode(); }
}
