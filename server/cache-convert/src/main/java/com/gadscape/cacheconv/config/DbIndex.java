package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * One file of the dbtable index (archive 21: group = table id, file 0 = the table's row list, file c + 1 = the
 * lookup index of column c), as RuneLite's DBTableIndexLoader reads it: a varint tuple count; per tuple a base var
 * type byte (0 int, 1 long, 2 string), a varint value count, and per value the value and a varint list of row ids.
 * Varints are protobuf style (7 bits per byte, least significant first).
 * <p>
 * Only the row ids are interpreted, so moved dbrows can be renumbered; {@code encode(parse(b)) == b}.
 */
public final class DbIndex {
    /** One indexed value: its encoded bytes (as stored) and the rows that hold it. */
    public static final class Value {
        public final byte[] value;
        public final int[] rows;
        Value(byte[] value, int[] rows) { this.value = value; this.rows = rows; }
    }

    public static final class Tuple {
        public final int baseType;
        public final List<Value> values = new ArrayList<>();
        Tuple(int baseType) { this.baseType = baseType; }
    }

    public final List<Tuple> tuples = new ArrayList<>();
    /** Zero bytes after the data (some packers pad); not written back. */
    public int padding;

    static int varint(Buf in) { int v = 0, bits = 0, r; do { r = in.u8(); v |= (r & 0x7f) << bits; bits += 7; } while (r > 127); return v; }

    static void varint(BufOut o, int v) { do { int x = v & 0x7f; v >>>= 7; o.u8(v != 0 ? x | 0x80 : x); } while (v != 0); }

    public static DbIndex parse(byte[] b) {
        DbIndex d = new DbIndex();
        Buf in = new Buf(b);
        int n = varint(in);
        for (int t = 0; t < n; t++) {
            Tuple tu = new Tuple(in.u8());
            int count = varint(in);
            for (int i = 0; i < count; i++) {
                int s = in.p;
                switch (tu.baseType) {
                    case 0 -> in.skip(4);
                    case 1 -> in.skip(8);
                    case 2 -> in.skipStr();
                    default -> throw new IllegalStateException("dbtable index: unknown base type " + tu.baseType);
                }
                byte[] v = in.slice(s, in.p);
                int rc = varint(in);
                int[] rows = new int[rc];
                for (int r = 0; r < rc; r++) rows[r] = varint(in);
                tu.values.add(new Value(v, rows));
            }
            d.tuples.add(tu);
        }
        if (in.p != b.length) {
            if (!com.gadscape.cacheconv.core.Bytes.isZero(b, in.p, b.length)) throw new IllegalStateException("dbtable index: " + (b.length - in.p) + " trailing bytes");
            d.padding = b.length - in.p; // zero padding left by the user's packer; dropped by encode()
        }
        return d;
    }

    public byte[] encode() {
        BufOut o = new BufOut();
        varint(o, tuples.size());
        for (Tuple t : tuples) {
            o.u8(t.baseType);
            varint(o, t.values.size());
            for (Value v : t.values) {
                o.raw(v.value);
                varint(o, v.rows.length);
                for (int r : v.rows) varint(o, r);
            }
        }
        return o.done();
    }

    /** Every row id renumbered (values and their order kept). */
    public DbIndex mapRows(IntUnaryOperator f) {
        DbIndex d = new DbIndex();
        for (Tuple t : tuples) {
            Tuple n = new Tuple(t.baseType);
            for (Value v : t.values) {
                int[] rows = v.rows.clone();
                for (int i = 0; i < rows.length; i++) rows[i] = f.applyAsInt(rows[i]);
                n.values.add(new Value(v.value, rows));
            }
            d.tuples.add(n);
        }
        return d;
    }

    /** All row ids referenced, in order. */
    public List<Integer> rows() {
        List<Integer> r = new ArrayList<>();
        for (Tuple t : tuples) for (Value v : t.values) for (int x : v.rows) r.add(x);
        return r;
    }
}
