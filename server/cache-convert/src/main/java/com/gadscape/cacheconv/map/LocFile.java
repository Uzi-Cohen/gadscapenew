package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.BufOut;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntUnaryOperator;

/**
 * A map square's loc file (file 1 of a 241 square, the "l{x}_{y}" group of a 211 square; the layout is the same in
 * both builds): loc ids ascending as extended-smart deltas, then per id the packed positions ascending as smart deltas
 * (+1, 0 ends the list) each followed by one attribute byte (type << 2 | rotation).
 * <p>
 * {@link #parse} reads the whole file and fails on trailing or missing bytes, so a wrongly decrypted group never
 * passes for a loc file. {@link #encode} writes the canonical layout; for every Jagex/editor file seen so far
 * {@code encode(parse(b))} gives back {@code b} (checked by {@link MapSelfCheck}).
 */
public final class LocFile {
    /** One placement. {@code pos} = level << 12 | localX << 6 | localY; {@code attr} = type << 2 | rotation. */
    public record Loc(int id, int pos, int attr) {
        public int level() { return pos >> 12 & 3; }
        public int x() { return pos >> 6 & 63; }
        public int y() { return pos & 63; }
        public int type() { return attr >> 2; }
        public int rot() { return attr & 3; }
        /** Placement key without the id (where and how). */
        public long where() { return (long) pos << 8 | attr; }
        @Override public String toString() { return id + "@" + level() + "," + x() + "," + y() + " t" + type() + " r" + rot(); }
    }

    public final List<Loc> locs;
    /** Zero bytes after the end marker in the parsed file (the client stops at the marker; packers that write into a
     *  fixed buffer leave them, e.g. TypeParser/Regions.inject for 12132). {@link #encode} drops them. */
    public final int padding;

    public LocFile(List<Loc> locs) { this(locs, 0); }
    private LocFile(List<Loc> locs, int padding) { this.locs = List.copyOf(locs); this.padding = padding; }

    public static final LocFile EMPTY = new LocFile(List.of());

    public int size() { return locs.size(); }

    /**
     * Parses a loc file; throws IllegalArgumentException if the bytes are not exactly one loc file (zero bytes after
     * the end marker are accepted and counted in {@link #padding}).
     */
    public static LocFile parse(byte[] b) {
        if (b == null) throw new IllegalArgumentException("no loc file");
        int[] p = {0};
        List<Loc> out = new ArrayList<>();
        int id = -1;
        while (true) {
            int d = extSmart(b, p);
            if (d == 0) break;
            id += d;
            int pos = 0;
            while (true) {
                int pd = smart(b, p);
                if (pd == 0) break;
                pos += pd - 1;
                if (pos > 0x3fff) throw new IllegalArgumentException("position out of range at byte " + p[0]);
                if (p[0] >= b.length) throw new IllegalArgumentException("truncated at byte " + p[0]);
                int attr = b[p[0]++] & 255;
                if ((attr >> 2) > 22) throw new IllegalArgumentException("loc type " + (attr >> 2) + " out of range at byte " + p[0]);
                out.add(new Loc(id, pos, attr));
            }
        }
        if (p[0] != b.length && !com.gadscape.cacheconv.core.Bytes.isZero(b, p[0], b.length))
            throw new IllegalArgumentException((b.length - p[0]) + " trailing bytes");
        return new LocFile(out, b.length - p[0]);
    }

    /** Parses, or returns null when the bytes are not a loc file. */
    public static LocFile tryParse(byte[] b) {
        if (b == null) return null;
        try { return parse(b); } catch (RuntimeException e) { return null; }
    }

    private static int smart(byte[] b, int[] p) {
        if (p[0] >= b.length) throw new IllegalArgumentException("truncated at byte " + p[0]);
        int v = b[p[0]] & 255;
        if (v < 128) { p[0]++; return v; }
        if (p[0] + 1 >= b.length) throw new IllegalArgumentException("truncated at byte " + p[0]);
        int r = ((b[p[0]] & 255) << 8 | (b[p[0] + 1] & 255)) - 32768;
        p[0] += 2;
        return r;
    }

    /** Client readUnsignedIntSmartShortCompat: smarts of 32767 add up until a smaller one ends the value. */
    private static int extSmart(byte[] b, int[] p) {
        int v = 0, s;
        while ((s = smart(b, p)) == 32767) v += 32767;
        return v + s;
    }

    private static void wSmart(BufOut o, int v) {
        if (v < 0 || v > 32767) throw new IllegalArgumentException("smart out of range: " + v);
        if (v < 128) o.u8(v); else o.u16(v + 32768);
    }

    private static void wExtSmart(BufOut o, int v) {
        while (v >= 32767) { wSmart(o, 32767); v -= 32767; }
        wSmart(o, v);
    }

    /** Encodes in the canonical order: ids ascending, per id positions ascending (equal positions keep their order). */
    public byte[] encode() {
        TreeMap<Integer, List<Loc>> byId = new TreeMap<>();
        for (Loc l : locs) byId.computeIfAbsent(l.id, k -> new ArrayList<>()).add(l);
        BufOut o = new BufOut(locs.size() * 4 + 4);
        int last = -1;
        for (var e : byId.entrySet()) {
            int id = e.getKey();
            if (id < 0) throw new IllegalArgumentException("negative loc id " + id);
            wExtSmart(o, id - last);
            last = id;
            List<Loc> ls = new ArrayList<>(e.getValue());
            ls.sort(Comparator.comparingInt(Loc::pos)); // stable: equal positions keep file order
            int pos = 0;
            for (Loc l : ls) {
                wSmart(o, l.pos - pos + 1);
                pos = l.pos;
                o.u8(l.attr);
            }
            wSmart(o, 0);
        }
        wSmart(o, 0);
        return o.done();
    }

    /** The same placements with every loc id mapped (ids that map to themselves stay). */
    public LocFile mapIds(IntUnaryOperator f) {
        List<Loc> out = new ArrayList<>(locs.size());
        for (Loc l : locs) out.add(new Loc(f.applyAsInt(l.id), l.pos, l.attr));
        return new LocFile(out);
    }

    /** Distinct loc ids, ascending. */
    public int[] ids() {
        TreeSet<Integer> s = new TreeSet<>();
        for (Loc l : locs) s.add(l.id);
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Placements as sorted keys (id, where) for multiset comparisons. */
    public long[] keys() {
        long[] k = new long[locs.size()];
        for (int i = 0; i < k.length; i++) { Loc l = locs.get(i); k[i] = (long) l.id << 24 | l.where(); }
        Arrays.sort(k);
        return k;
    }

    /** Same placements regardless of order. */
    public boolean samePlacements(LocFile o) { return o != null && Arrays.equals(keys(), o.keys()); }

    /**
     * Placements of this file that the other file does not have (multiset difference), as a count. Used to tell
     * whether one square's locs are a subset of another's.
     */
    public int countNotIn(LocFile o) {
        long[] a = keys(), b = o == null ? new long[0] : o.keys();
        int i = 0, j = 0, n = 0;
        while (i < a.length) {
            if (j >= b.length || a[i] < b[j]) { n++; i++; }
            else if (a[i] > b[j]) j++;
            else { i++; j++; }
        }
        return n;
    }

    /** Whether a placement with this id at the same position/type/rotation exists in the other file. */
    public static boolean contains(LocFile f, int id, int pos, int attr) {
        if (f == null) return false;
        for (Loc l : f.locs) if (l.id == id && l.pos == pos && l.attr == attr) return true;
        return false;
    }
}
