package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.core.Bytes;

import java.util.Arrays;

/**
 * Byte-level helpers for the three animation asset kinds.
 * <ul>
 *   <li><b>Frame</b> file (archive 0, group = frame set, file = frame): {@code u16 framemap, u8 transform count,
 *       count x u8 flags, data}. The transform count indexes the framemap's transform groups.</li>
 *   <li><b>Keyframe</b> file (skeletal "animaya", archive 22 in build 241, archive 0 in build 211):
 *       {@code u8 version, u16 framemap, ...}.</li>
 *   <li><b>Framemap</b> (archive 1, file 0 of the group; also called skeleton/base): {@code u8 n, n x u8 type,
 *       n x u8 label count, labels...}, then optionally {@code u16 size} and the skeletal bone data when size > 0.</li>
 * </ul>
 */
public final class AnimFiles {
    private AnimFiles() {}

    // ------------------------------------------------------------------ frames

    public static int frameFramemap(byte[] frame) { return Bytes.u16(frame, 0); }

    /** Number of transform groups the frame addresses (it must not exceed the framemap's group count). */
    public static int frameTransforms(byte[] frame) { return frame.length > 2 ? frame[2] & 255 : 0; }

    /** Copy of a frame file pointing at another framemap. */
    public static byte[] withFrameFramemap(byte[] frame, int framemap) {
        checkU16(framemap);
        byte[] c = frame.clone();
        Bytes.w16(c, 0, framemap);
        return c;
    }

    /** Same frame data apart from the framemap id. */
    public static boolean sameFrameData(byte[] a, byte[] b) {
        return a != null && b != null && a.length == b.length && a.length >= 2 && Arrays.equals(a, 2, a.length, b, 2, b.length);
    }

    // ------------------------------------------------------------------ keyframes

    public static int keyframeFramemap(byte[] key) { return Bytes.u16(key, 1); }

    public static byte[] withKeyframeFramemap(byte[] key, int framemap) {
        checkU16(framemap);
        byte[] c = key.clone();
        Bytes.w16(c, 1, framemap);
        return c;
    }

    public static boolean sameKeyframeData(byte[] a, byte[] b) {
        return a != null && b != null && a.length == b.length && a.length >= 3 && a[0] == b[0] && Arrays.equals(a, 3, a.length, b, 3, b.length);
    }

    private static void checkU16(int v) { if (v < 0 || v > 0xffff) throw new IllegalArgumentException("id " + v + " does not fit 2 bytes"); }

    // ------------------------------------------------------------------ framemaps

    /** The classic part of a framemap (transform types and their label lists) plus whether bone data follows. */
    public record Framemap(int[] types, int[][] labels, int classicEnd, boolean skeletal) {
        public int count() { return types.length; }
    }

    public static Framemap framemap(byte[] b) {
        int p = 0, n = b[p++] & 255;
        int[] types = new int[n];
        for (int i = 0; i < n; i++) types[i] = b[p++] & 255;
        int[][] labels = new int[n][];
        for (int i = 0; i < n; i++) labels[i] = new int[b[p++] & 255];
        for (int i = 0; i < n; i++) for (int j = 0; j < labels[i].length; j++) labels[i][j] = b[p++] & 255;
        boolean skel = p + 2 <= b.length && Bytes.u16(b, p) > 0;
        return new Framemap(types, labels, p, skel);
    }

    /** Transform group count of a framemap file (first byte). */
    public static int framemapCount(byte[] b) { return b[0] & 255; }

    /**
     * Whether frames made for framemap {@code older} play the same on framemap {@code newer}: the first
     * {@code older.count} transform groups have the same types and label lists (later groups may be appended).
     * Bone data is not compared (classic frames do not use it).
     */
    public static boolean classicCompatible(byte[] older, byte[] newer) {
        if (older == null || newer == null) return false;
        if (Arrays.equals(older, newer)) return true;
        Framemap a = framemap(older), b = framemap(newer);
        if (a.count() > b.count()) return false;
        for (int i = 0; i < a.count(); i++) if (a.types[i] != b.types[i] || !Arrays.equals(a.labels[i], b.labels[i])) return false;
        return true;
    }
}
