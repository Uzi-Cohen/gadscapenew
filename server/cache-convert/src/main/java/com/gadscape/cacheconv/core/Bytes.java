package com.gadscape.cacheconv.core;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;

/** Big-endian random-access helpers on byte arrays (the JS5/config byte order). */
public final class Bytes {
    private Bytes() {}

    public static int u8(byte[] b, int p) { return b[p] & 255; }
    public static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    public static int u24(byte[] b, int p) { return ((b[p] & 255) << 16) | ((b[p + 1] & 255) << 8) | (b[p + 2] & 255); }
    public static int i32(byte[] b, int p) { return ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255); }
    public static long i64(byte[] b, int p) { return ((long) i32(b, p) << 32) | (i32(b, p + 4) & 0xffffffffL); }

    public static void w16(byte[] b, int p, int v) { b[p] = (byte) (v >> 8); b[p + 1] = (byte) v; }
    public static void w24(byte[] b, int p, int v) { b[p] = (byte) (v >> 16); b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) v; }
    public static void w32(byte[] b, int p, int v) { b[p] = (byte) (v >> 24); b[p + 1] = (byte) (v >> 16); b[p + 2] = (byte) (v >> 8); b[p + 3] = (byte) v; }

    /** Appends a big-endian int / short to a stream (what the old tools called w() and s()). */
    public static void w32(ByteArrayOutputStream o, int v) { o.write(v >> 24); o.write(v >> 16); o.write(v >> 8); o.write(v); }
    public static void w16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }

    /** Whether b[from, to) is all zero. */
    public static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    /** a equals b, or a is b followed only by zero bytes (zero padding), or the other way round. */
    public static boolean equalsIgnoringZeroTail(byte[] a, byte[] b) {
        if (a == null || b == null) return a == b;
        byte[] s = a.length <= b.length ? a : b, l = a.length <= b.length ? b : a;
        return Arrays.equals(s, 0, s.length, l, 0, s.length) && isZero(l, s.length, l.length);
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n]; int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    public static String hex(byte[] b) { return b == null ? "null" : HexFormat.of().formatHex(b); }
    public static String hex(byte[] b, int from, int to) { return HexFormat.of().formatHex(b, Math.max(0, from), Math.min(b.length, to)); }
    /** Inverse of {@link #hex(byte[])}. */
    public static byte[] unhex(String s) { return HexFormat.of().parseHex(s); }

    /** Classic 16-bytes-per-line hex dump with an ASCII column. */
    public static String dump(byte[] b, int max) {
        StringBuilder s = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i += 16) {
            s.append(String.format("%06x  ", i));
            for (int j = 0; j < 16; j++) s.append(i + j < n ? String.format("%02x ", b[i + j] & 255) : "   ");
            s.append(' ');
            for (int j = 0; j < 16 && i + j < n; j++) { int c = b[i + j] & 255; s.append(c >= 32 && c < 127 ? (char) c : '.'); }
            s.append('\n');
        }
        if (n < b.length) s.append("... ").append(b.length - n).append(" more bytes\n");
        return s.toString();
    }

    /** First index where a and b differ, or -1 if equal. */
    public static int firstDiff(byte[] a, byte[] b) {
        int m = Arrays.mismatch(a, b);
        return m;
    }

    /** Jagex string hash (same as String.hashCode for the chars used in names), as group/file names use. */
    public static int nameHash(String s) {
        if (s == null) return -1;
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return h;
    }
}
