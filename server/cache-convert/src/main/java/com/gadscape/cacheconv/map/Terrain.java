package com.gadscape.cacheconv.map;

import java.util.Arrays;

/**
 * Map square terrain (file 0 of a 241 square, the "m{x}_{y}" group of a 211 square). Both builds store 4 levels x
 * 64 x 64 tiles, each a list of u16 opcodes (0 end, 1 height + end, 2-49 overlay + shape, 50-81 settings, 82+ underlay)
 * ending the tile; build 241 appends one flags byte (bit 0: an underwater level follows), which 211 lacks.
 */
public final class Terrain {
    private Terrain() {}

    /** 211 terrain -> 241 terrain: the same tiles plus a flags byte of 0 (no underwater level). */
    public static byte[] to241(byte[] t211) { return Arrays.copyOf(t211, t211.length + 1); }

    /** Byte length of the 4 x 64 x 64 tile block, or -1 if the bytes end before it does. */
    public static int tilesLength(byte[] b) {
        int p = 0;
        try {
            for (int i = 0; i < 4 * 64 * 64; i++) {
                while (true) {
                    int a = (b[p] & 255) << 8 | (b[p + 1] & 255);
                    p += 2;
                    if (a == 0) break;
                    if (a == 1) { p++; break; }
                    if (a <= 49) p += 2;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return -1;
        }
        return p > b.length ? -1 : p;
    }

    /** Whether the bytes are exactly one 211 terrain (the tile block and nothing else). */
    public static boolean valid211(byte[] b) { return b != null && tilesLength(b) == b.length; }

    /** Whether the bytes are a 241 terrain: the tile block, a flags byte, and (flags bit 0) more data after it. */
    public static boolean valid241(byte[] b) {
        if (b == null) return false;
        int n = tilesLength(b);
        if (n < 0 || n >= b.length) return false;
        int flags = b[n] & 255;
        return (flags & 1) != 0 ? b.length > n + 1 : b.length == n + 1;
    }

    /**
     * Per-tile comparison of two 211 tile blocks: how many of the 16384 tiles differ (-1 if either does not parse).
     */
    public static int tilesDiffering(byte[] a, byte[] b) {
        int[] oa = offsets(a), ob = offsets(b);
        if (oa == null || ob == null) return -1;
        int n = 0;
        for (int i = 0; i < 16384; i++)
            if (!Arrays.equals(a, oa[i], oa[i + 1], b, ob[i], ob[i + 1])) n++;
        return n;
    }

    /** Start offset of every tile (16385 entries, the last is the end of the block), or null. */
    static int[] offsets(byte[] b) {
        if (b == null) return null;
        int[] o = new int[16385];
        int p = 0;
        try {
            for (int i = 0; i < 16384; i++) {
                o[i] = p;
                while (true) {
                    int a = (b[p] & 255) << 8 | (b[p + 1] & 255);
                    p += 2;
                    if (a == 0) break;
                    if (a == 1) { p++; break; }
                    if (a <= 49) p += 2;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
        o[16384] = p;
        return p > b.length ? null : o;
    }
}
