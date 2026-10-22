package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sprite group (archive 8, file 0) as the client decodes it ({@code SpriteBuffer_decode}): pixel data first, then
 * the palette (count - 1 RGB triplets, index 0 is transparent), then a trailer of max width, max height (u16), palette
 * size - 1 (u8), the x offsets, y offsets, widths and heights of every frame (u16 arrays) and the frame count (u16)
 * as the last two bytes. Each frame's data is a flags byte (bit 0 = stored column-major, bit 1 = an alpha plane
 * follows), the palette indices, and with bit 1 the alpha values. The format is the same in builds 211 and 241.
 * <p>
 * {@code encode(parse(b))} gives b back for every sprite of BASE and USER ({@link SpriteSelfCheck}). Frames from
 * different sheets can be combined with {@link #compose}, which merges the palettes (and maps colours to the nearest
 * one only when the picked frames draw more than 255 colours, the same way the old MapsceneFix tool did).
 */
public final class SpriteSheet {
    public int width, height;
    /** Palette as stored; index 0 is transparent (its value is never written). */
    public int[] palette;
    public final List<Frame> frames = new ArrayList<>();

    /** One frame: offsets, size, flags as stored, palette indices (row-major) and optional alpha (row-major). */
    public static final class Frame {
        public int ox, oy, w, h, flags;
        public byte[] idx, alpha;
        /** The sheet the indices refer to (for composing frames of different sheets). */
        SpriteSheet owner;

        Frame copy() {
            Frame f = new Frame();
            f.ox = ox; f.oy = oy; f.w = w; f.h = h; f.flags = flags; f.idx = idx.clone(); f.alpha = alpha == null ? null : alpha.clone(); f.owner = owner;
            return f;
        }
    }

    public int frameCount() { return frames.size(); }
    public Frame frame(int i) { return frames.get(i); }

    // ------------------------------------------------------------------ codec

    public static SpriteSheet parse(byte[] b) {
        if (b == null || b.length < 7) throw new IllegalStateException("sprite: " + (b == null ? "null" : b.length + " bytes"));
        SpriteSheet s = new SpriteSheet();
        int n = Bytes.u16(b, b.length - 2);
        int t = b.length - 2 - n * 8 - 5;
        if (t < 0) throw new IllegalStateException("sprite: trailer of " + n + " frames does not fit in " + b.length + " bytes");
        s.width = Bytes.u16(b, t);
        s.height = Bytes.u16(b, t + 2);
        int pal = (b[t + 4] & 255) + 1;
        int q = t + 5;
        int pp = t - (pal - 1) * 3;
        if (pp < 0) throw new IllegalStateException("sprite: palette of " + pal + " colours does not fit");
        s.palette = new int[pal];
        for (int i = 1; i < pal; i++) s.palette[i] = Bytes.u24(b, pp + (i - 1) * 3);
        int p = 0;
        for (int i = 0; i < n; i++) {
            Frame f = new Frame();
            f.owner = s;
            f.ox = Bytes.u16(b, q + i * 2);
            f.oy = Bytes.u16(b, q + n * 2 + i * 2);
            f.w = Bytes.u16(b, q + n * 4 + i * 2);
            f.h = Bytes.u16(b, q + n * 6 + i * 2);
            int area = f.w * f.h;
            if (p >= pp) throw new IllegalStateException("sprite: frame " + i + " starts in the palette");
            f.flags = b[p++] & 255;
            if ((f.flags & ~3) != 0) throw new IllegalStateException("sprite: frame " + i + " has unknown flags " + f.flags);
            if (p + area > pp) throw new IllegalStateException("sprite: frame " + i + " pixels run into the palette");
            f.idx = plane(b, p, f.w, f.h, (f.flags & 1) != 0);
            p += area;
            if ((f.flags & 2) != 0) {
                if (p + area > pp) throw new IllegalStateException("sprite: frame " + i + " alpha runs into the palette");
                f.alpha = plane(b, p, f.w, f.h, (f.flags & 1) != 0);
                p += area;
            }
            s.frames.add(f);
        }
        if (p != pp) throw new IllegalStateException("sprite: pixel data ends at " + p + ", palette starts at " + pp);
        return s;
    }

    private static byte[] plane(byte[] b, int p, int w, int h, boolean columnMajor) {
        byte[] r = new byte[w * h];
        if (!columnMajor) System.arraycopy(b, p, r, 0, r.length);
        else { int k = 0; for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) r[y * w + x] = b[p + k++]; }
        return r;
    }

    private static void writePlane(BufOut o, byte[] v, int w, int h, boolean columnMajor) {
        if (!columnMajor) { o.raw(v); return; }
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) o.u8(v[y * w + x]);
    }

    public byte[] encode() {
        if (palette.length < 1 || palette.length > 256) throw new IllegalStateException("sprite: palette of " + palette.length + " entries");
        BufOut o = new BufOut();
        for (Frame f : frames) {
            o.u8(f.flags);
            writePlane(o, f.idx, f.w, f.h, (f.flags & 1) != 0);
            if ((f.flags & 2) != 0) writePlane(o, f.alpha, f.w, f.h, (f.flags & 1) != 0);
        }
        for (int i = 1; i < palette.length; i++) o.u24(palette[i]);
        o.u16(width).u16(height).u8(palette.length - 1);
        for (Frame f : frames) o.u16(f.ox);
        for (Frame f : frames) o.u16(f.oy);
        for (Frame f : frames) o.u16(f.w);
        for (Frame f : frames) o.u16(f.h);
        o.u16(frames.size());
        return o.done();
    }

    // ------------------------------------------------------------------ pixels

    /** The colour the client draws for a palette entry: 0 is transparent, and the client turns colour 0 into 1. */
    private int colour(int index) { int c = palette[index]; return c == 0 ? 1 : c; }

    /** ARGB of frame i, row-major, 0 = transparent. */
    public int[] argb(int i) {
        Frame f = frames.get(i);
        int[] r = new int[f.idx.length];
        for (int k = 0; k < r.length; k++) {
            int ix = f.idx[k] & 255;
            int a = f.alpha != null ? f.alpha[k] & 255 : (ix == 0 ? 0 : 255);
            r[k] = a == 0 || ix == 0 && f.alpha == null ? 0 : (a << 24) | (ix == 0 ? 0 : colour(ix));
        }
        return r;
    }

    /** Frame i drawn at its offset on a W x H canvas (pixels outside the canvas are dropped). */
    public int[] canvas(int i, int W, int H) {
        Frame f = frames.get(i);
        int[] c = new int[W * H], px = argb(i);
        for (int y = 0; y < f.h; y++)
            for (int x = 0; x < f.w; x++) {
                int X = f.ox + x, Y = f.oy + y;
                if (X < W && Y < H) c[Y * W + X] = px[y * f.w + x];
            }
        return c;
    }

    public boolean blankFrame(int i) { for (int v : argb(i)) if (v != 0) return false; return true; }
    /** Every frame fully transparent (or empty): a placeholder. */
    public boolean blank() { for (int i = 0; i < frames.size(); i++) if (!blankFrame(i)) return false; return true; }

    /** How two frames compare when drawn. */
    public record FrameDiff(int maskXor, int union, double meanDist, int over96) {
        /** Drawn identically. */
        public boolean identical() { return maskXor == 0 && over96 == 0 && meanDist == 0; }
        /**
         * The same picture with Jagex re-quantised colours: the same opaque shape (at most 1% of the pixels differ in
         * coverage), no pixel more than 96 RGB units apart and a mean distance below 20. On the real inputs, re-coloured
         * Jagex sprites stay below a 91-unit maximum while deliberate edits (a re-lettered icon) always have pixels
         * over 400 units apart.
         */
        public boolean sameThing() { return identical() || maskXor * 100 <= union && over96 == 0 && meanDist < 20; }
    }

    public static FrameDiff diff(SpriteSheet a, int i, SpriteSheet b, int j) {
        Frame fa = a.frames.get(i), fb = b.frames.get(j);
        int W = Math.max(Math.max(a.width, b.width), Math.max(fa.ox + fa.w, fb.ox + fb.w));
        int H = Math.max(Math.max(a.height, b.height), Math.max(fa.oy + fa.h, fb.oy + fb.h));
        int[] x = a.canvas(i, W, H), y = b.canvas(j, W, H);
        int xor = 0, union = 0, both = 0, over = 0;
        double dist = 0;
        for (int k = 0; k < x.length; k++) {
            boolean ox = x[k] != 0, oy = y[k] != 0;
            if (ox || oy) union++;
            if (ox != oy) { xor++; continue; }
            if (!ox || x[k] == y[k]) { if (ox) both++; continue; }
            both++;
            int dr = ((x[k] >> 16) & 255) - ((y[k] >> 16) & 255), dg = ((x[k] >> 8) & 255) - ((y[k] >> 8) & 255), db = (x[k] & 255) - (y[k] & 255);
            int da = ((x[k] >>> 24) & 255) - ((y[k] >>> 24) & 255);
            double d = Math.sqrt(dr * dr + dg * dg + db * db + da * da);
            dist += d;
            if (d > 96) over++;
        }
        return new FrameDiff(xor, union, both == 0 ? 0 : dist / both, over);
    }

    public static boolean sameFrame(SpriteSheet a, int i, SpriteSheet b, int j) { return diff(a, i, b, j).identical(); }

    /** Same frame count and every frame drawn identically (a re-encode). */
    public static boolean samePicture(SpriteSheet a, SpriteSheet b) {
        if (a.frames.size() != b.frames.size()) return false;
        for (int i = 0; i < a.frames.size(); i++) if (!sameFrame(a, i, b, i)) return false;
        return true;
    }

    /** Same frame count and every frame the same thing ({@link FrameDiff#sameThing}). */
    public static boolean sameThing(SpriteSheet a, SpriteSheet b) {
        if (a.frames.size() != b.frames.size()) return false;
        for (int i = 0; i < a.frames.size(); i++) if (!diff(a, i, b, i).sameThing()) return false;
        return true;
    }

    // ------------------------------------------------------------------ composing

    /** Where a composed frame comes from. */
    public record Pick(SpriteSheet sheet, int frame) {}

    /** Result of {@link #compose}: the sheet, and how many colours had to be mapped to a near one. */
    public record Composed(SpriteSheet sheet, int approximated, int coloursWanted) {}

    /**
     * A sheet made of frames picked from other sheets. The palette holds exactly the colours the picked frames draw
     * (colour 0 counts as 1, as the client reads it): first those of {@code primary}'s picked frames in {@code primary}'s
     * palette order, then the others, most used first (ties keep first-seen order). When more than 255 colours are
     * needed the rest map to the nearest palette colour (weighted RGB distance 2/4/3, as MapsceneFix did). Frames keep
     * offsets, sizes, storage order and alpha; the canvas is the largest of the sources'. Deterministic.
     */
    public static Composed compose(SpriteSheet primary, List<Pick> picks) {
        SpriteSheet out = new SpriteSheet();
        // colours used by the picked frames, primary's first in palette order
        LinkedHashMap<Integer, Integer> primaryUse = new LinkedHashMap<>(), otherUse = new LinkedHashMap<>();
        if (primary != null) {
            boolean[] used = new boolean[primary.palette.length];
            for (Pick p : picks) if (p.sheet == primary) for (byte v : primary.frames.get(p.frame).idx) used[v & 255] = true;
            for (int i = 1; i < used.length; i++) if (used[i]) primaryUse.merge(norm(primary.palette[i]), 1, Integer::sum);
        }
        for (Pick p : picks) {
            if (p.sheet == primary) continue;
            for (byte v : p.sheet.frames.get(p.frame).idx) {
                int ix = v & 255;
                if (ix == 0) continue;
                int c = norm(p.sheet.palette[ix]);
                if (!primaryUse.containsKey(c)) otherUse.merge(c, 1, Integer::sum);
            }
        }
        List<Integer> order = new ArrayList<>(primaryUse.keySet());
        List<Integer> others = new ArrayList<>(otherUse.keySet());
        others.sort((x, y) -> otherUse.get(y) - otherUse.get(x)); // stable: ties keep first-seen order
        order.addAll(others);
        List<Integer> pal = new ArrayList<>();
        pal.add(0);
        Map<Integer, Integer> at = new HashMap<>();
        int exact = Math.min(255, order.size());
        for (int k = 0; k < exact; k++) { at.put(order.get(k), pal.size()); pal.add(order.get(k)); }
        int approx = 0;
        for (int k = exact; k < order.size(); k++) { at.put(order.get(k), nearest(pal, order.get(k))); approx++; }
        out.palette = pal.stream().mapToInt(Integer::intValue).toArray();
        for (Pick p : picks) {
            Frame f = p.sheet.frames.get(p.frame).copy();
            f.owner = out;
            int[] map = new int[p.sheet.palette.length];
            for (int i = 1; i < map.length; i++) { Integer x = at.get(norm(p.sheet.palette[i])); map[i] = x == null ? 0 : x; }
            for (int k = 0; k < f.idx.length; k++) { int ix = f.idx[k] & 255; f.idx[k] = (byte) (ix == 0 ? 0 : map[ix]); }
            out.frames.add(f);
            out.width = Math.max(out.width, p.sheet.width);
            out.height = Math.max(out.height, p.sheet.height);
        }
        if (primary != null) { out.width = Math.max(out.width, primary.width); out.height = Math.max(out.height, primary.height); }
        return new Composed(out, approx, order.size());
    }

    private static int norm(int c) { return c == 0 ? 1 : c; }

    private static int nearest(List<Integer> pal, int c) {
        int best = 1;
        long bd = Long.MAX_VALUE;
        for (int j = 1; j < pal.size(); j++) {
            int d = pal.get(j);
            long dr = ((c >> 16) & 255) - ((d >> 16) & 255), dg = ((c >> 8) & 255) - ((d >> 8) & 255), db = (c & 255) - (d & 255);
            long dist = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (dist < bd) { bd = dist; best = j; }
        }
        return best;
    }

    /**
     * A sheet from ARGB pictures (row-major, 0 = transparent; alpha other than 0/255 gives the frame an alpha plane),
     * each frame at offset 0,0 of a {@code width x height} canvas. For tests and for importing redrawn art.
     */
    public static SpriteSheet fromArgb(int width, int height, List<int[]> pixels, int[] frameW, int[] frameH) {
        SpriteSheet src = new SpriteSheet();
        src.width = width; src.height = height;
        LinkedHashMap<Integer, Integer> idx = new LinkedHashMap<>();
        List<Integer> pal = new ArrayList<>();
        pal.add(0);
        for (int[] px : pixels) for (int v : px) if ((v >>> 24) != 0) { int c = norm(v & 0xffffff); if (!idx.containsKey(c)) { idx.put(c, pal.size()); pal.add(c); } }
        if (pal.size() > 256) throw new IllegalArgumentException(pal.size() - 1 + " colours: use compose to reduce");
        src.palette = pal.stream().mapToInt(Integer::intValue).toArray();
        for (int i = 0; i < pixels.size(); i++) {
            int[] px = pixels.get(i);
            Frame f = new Frame();
            f.owner = src; f.w = frameW[i]; f.h = frameH[i];
            if (px.length != f.w * f.h) throw new IllegalArgumentException("frame " + i + ": " + px.length + " pixels for " + f.w + "x" + f.h);
            f.idx = new byte[px.length];
            boolean partial = false;
            for (int v : px) { int a = v >>> 24; if (a != 0 && a != 255) partial = true; }
            if (partial) f.alpha = new byte[px.length];
            for (int k = 0; k < px.length; k++) {
                int a = px[k] >>> 24;
                f.idx[k] = (byte) (a == 0 ? 0 : idx.get(norm(px[k] & 0xffffff)));
                if (partial) f.alpha[k] = (byte) a;
            }
            f.flags = partial ? 2 : 0;
            src.frames.add(f);
        }
        return src;
    }

    /** Short description for reports: frames, canvas, palette size. */
    public String describe() { return frames.size() + " frame" + (frames.size() == 1 ? "" : "s") + " " + width + "x" + height + ", " + (palette.length - 1) + " colours"; }

    @Override public String toString() { return describe(); }

    /** Equal bytes after encoding (for tests). */
    public static boolean sameBytes(SpriteSheet a, SpriteSheet b) { return Arrays.equals(a.encode(), b.encode()); }
}
