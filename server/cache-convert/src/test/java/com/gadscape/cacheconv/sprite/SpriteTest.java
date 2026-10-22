package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the sprite area on hand-made sheets (no caches needed), plus the real-cache self-check when available. */
class SpriteTest {
    static final int R = 0xffc03020, G = 0xff20c030, B = 0xff2030c0, W = 0xffffffff, K = 0xff000000, T = 0;

    /** A 4x4 sheet with one frame per picture. */
    static SpriteSheet sheet(int[]... frames) {
        int[] w = new int[frames.length], h = new int[frames.length];
        Arrays.fill(w, 4); Arrays.fill(h, 4);
        return SpriteSheet.fromArgb(4, 4, List.of(frames), w, h);
    }

    static int[] fill(int c) { int[] p = new int[16]; Arrays.fill(p, c); return p; }
    static int[] ring(int c, int centre) { int[] p = fill(c); p[5] = p[6] = p[9] = p[10] = centre; return p; }
    static int[] shift(int[] p, int d) { int[] r = p.clone(); for (int i = 0; i < r.length; i++) if (r[i] != 0) r[i] = (r[i] & 0xff000000) | (Math.min(255, ((r[i] >> 16) & 255) + d) << 16) | (r[i] & 0xffff); return r; }

    @Test void codecRoundTrip() {
        SpriteSheet s = sheet(ring(R, G), fill(B), ring(T, W));
        byte[] b = s.encode();
        assertArrayEquals(b, SpriteSheet.parse(b).encode());
        assertEquals(3, SpriteSheet.parse(b).frameCount());
        assertTrue(SpriteSheet.samePicture(s, SpriteSheet.parse(b)));
    }

    @Test void columnMajorAndAlphaFramesRoundTrip() {
        // one 2x3 frame stored column-major with an alpha plane: flags 3
        BufOut o = new BufOut();
        o.u8(3);
        o.raw(new byte[]{1, 2, 1, 2, 1, 2});            // indices, column-major
        o.raw(new byte[]{(byte) 255, 10, (byte) 255, 20, (byte) 255, 30}); // alpha, column-major
        o.u24(0xff0000).u24(0x00ff00);                    // palette 1..2
        o.u16(2).u16(3).u8(2);                            // canvas 2x3, palette size - 1
        o.u16(0).u16(0).u16(2).u16(3).u16(1);             // ox, oy, w, h, count
        byte[] b = o.done();
        SpriteSheet s = SpriteSheet.parse(b);
        assertArrayEquals(b, s.encode());
        int[] px = s.argb(0);
        assertEquals(0xffff0000, px[0]);                  // (0,0) index 1 alpha 255
        assertEquals((10 << 24) | 0x00ff00, px[2]);       // (0,1): column-major second value
    }

    @Test void blackIsColourOneAndCroppingDoesNotMatter() {
        SpriteSheet a = sheet(ring(K, W));
        a.palette[1] = 0;                                 // black stored as 0x000000: the client draws it as 0x000001
        SpriteSheet b = sheet(ring(0xff000001, W));
        assertTrue(SpriteSheet.sameFrame(a, 0, b, 0));
        // the same picture stored cropped (the 2x2 centre only, at offset 1,1) over a transparent border
        SpriteSheet full = sheet(ring(T, W));
        SpriteSheet crop = SpriteSheet.fromArgb(4, 4, List.<int[]>of(fill(W).clone()), new int[]{4}, new int[]{4});
        crop.frames.get(0).w = 2; crop.frames.get(0).h = 2; crop.frames.get(0).ox = 1; crop.frames.get(0).oy = 1;
        crop.frames.get(0).idx = Arrays.copyOf(crop.frames.get(0).idx, 4);
        assertTrue(SpriteSheet.sameFrame(full, 0, crop, 0));
    }

    @Test void recolourIsTheSameThingAnEditIsNot() {
        SpriteSheet base = sheet(ring(B, W));
        assertTrue(SpriteSheet.diff(base, 0, sheet(shift(ring(B, W), 12)), 0).sameThing(), "a small re-colour");
        int[] edited = ring(B, W);
        edited[5] = B;                                    // one pixel of the letter changed: a deliberate edit
        assertFalse(SpriteSheet.diff(base, 0, sheet(edited), 0).sameThing());
        int[] reshaped = ring(B, W);
        reshaped[0] = T; reshaped[3] = T;                 // shape changed by 2 of 16 pixels (> 1%)
        assertFalse(SpriteSheet.diff(base, 0, sheet(reshaped), 0).sameThing());
    }

    @Test void threeWayMerge() {
        SpriteSheet van = sheet(fill(R), fill(G), fill(B), fill(W));
        SpriteSheet user = sheet(fill(R), ring(G, W), ring(B, K), fill(T), ring(R, G));     // edits 1, 2, blanks 3, adds 4
        SpriteSheet base = sheet(shift(fill(R), 40), fill(G), ring(B, R), fill(K), fill(G), fill(W)); // changes 0, 2, 3, adds 4, 5
        SpriteMerge.Result r = SpriteMerge.merge(van, user, base);
        assertEquals(List.of(SpriteMerge.Source.BASE, SpriteMerge.Source.USER, SpriteMerge.Source.USER, SpriteMerge.Source.BASE, SpriteMerge.Source.USER, SpriteMerge.Source.BASE), r.picks);
        assertEquals(List.of(2, 4), r.conflictFrames);   // both changed frame 2; both added a different frame 4
        assertEquals(1, r.placeholders);
        SpriteSheet m = SpriteMerge.build(r, user, base).sheet();
        assertEquals(6, m.frameCount());
        for (int f = 0; f < 6; f++) {
            SpriteSheet src = r.picks.get(f) == SpriteMerge.Source.BASE ? base : user;
            assertTrue(SpriteSheet.sameFrame(m, f, src, f), "frame " + f);
        }
        assertArrayEquals(m.encode(), SpriteSheet.parse(m.encode()).encode());
    }

    @Test void untouchedOrJagexVariantsResolveToBase() {
        SpriteSheet van = sheet(fill(R), fill(G));
        SpriteSheet user = sheet(fill(R), shift(fill(G), 10));   // a newer Jagex re-colour of frame 1
        SpriteSheet base = sheet(shift(fill(R), 20), shift(fill(G), 12));
        SpriteMerge.Result r = SpriteMerge.merge(van, user, base);
        assertTrue(r.allBase(2));
        assertEquals(1, r.jagexVariants);
    }

    @Test void composeKeepsEveryColourUpTo255AndApproximatesBeyond() {
        List<int[]> a = new ArrayList<>(), b = new ArrayList<>();
        for (int f = 0; f < 10; f++) { int[] p = new int[16], q = new int[16]; for (int k = 0; k < 16; k++) { p[k] = 0xff000000 | (f * 16 + k + 1); q[k] = 0xff800000 | (f * 16 + k + 1); } a.add(p); b.add(q); }
        int[] w = new int[10], h = new int[10]; Arrays.fill(w, 4); Arrays.fill(h, 4);
        SpriteSheet sa = SpriteSheet.fromArgb(4, 4, a, w, h), sb = SpriteSheet.fromArgb(4, 4, b, w, h); // 160 colours each
        List<SpriteSheet.Pick> picks = new ArrayList<>();
        for (int f = 0; f < 10; f++) picks.add(new SpriteSheet.Pick(sa, f));
        for (int f = 0; f < 5; f++) picks.add(new SpriteSheet.Pick(sb, f));   // 160 + 80 = 240 colours: exact
        SpriteSheet.Composed c = SpriteSheet.compose(sa, picks);
        assertEquals(0, c.approximated());
        for (int i = 0; i < 15; i++) assertTrue(SpriteSheet.sameFrame(c.sheet(), i, picks.get(i).sheet(), picks.get(i).frame()));
        for (int f = 5; f < 10; f++) picks.add(new SpriteSheet.Pick(sb, f));  // 320 colours: 65 approximated
        c = SpriteSheet.compose(sa, picks);
        assertEquals(65, c.approximated());
        assertEquals(256, c.sheet().palette.length);
        for (int i = 0; i < 10; i++) assertTrue(SpriteSheet.sameFrame(c.sheet(), i, sa, i), "primary frames stay exact");
    }

    @Test void mapsceneOpcodeOf241Locs() {
        // 241 loc: op 1 with a big-smart model above 32767, name, op 68 = 105, end
        byte[] loc = new BufOut().u8(1).u8(1).i32(0x80000000 | 70000).u8(10).u8(2).str("Gad shrine").u8(68).u16(105).u8(19).u8(1).u8(0).done();
        assertEquals(105, MapsceneStep.mapscene(loc));
        byte[] none = new BufOut().u8(2).str("x").u8(0).done();
        assertEquals(-1, MapsceneStep.mapscene(none));
        // custom frames: USER frame 1 differs from BASE's, frame 2 is new, frame 3 is empty
        SpriteSheet base = sheet(fill(R), fill(G));
        SpriteSheet user = sheet(shift(fill(R), 5), ring(G, W), fill(B), fill(T));
        assertEquals(List.of(1, 2), new ArrayList<>(MapsceneStep.customFrames(user, base)));
    }

    /** The real-cache self-check, only when the cache layout is available (-Dgadconv.root or GADCONV_ROOT). */
    @Test void selfCheckOnTheRealCaches(@TempDir Path work) throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.base) && Files.isDirectory(o.user) && Files.isDirectory(o.van211), "cache layout not available");
        o.work = work;
        assertEquals(List.of(), SpriteSelfCheck.check(o));
    }
}
