package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimTest {
    @Test void seqRecReadsEveryReference() {
        byte[] s = AnimSelfCheck.sample(100, (5 << 16) | 2);
        SeqRec r = SeqRec.parse(1, s);
        assertArrayEquals(new int[]{(100 << 16) | 7, (101 << 16) | 8}, r.frames);
        assertArrayEquals(new int[]{4, 5}, r.lengths);
        assertArrayEquals(new int[]{(100 << 16) | 3, (77 << 16) | 0xffff}, r.extra);
        assertArrayEquals(new int[]{100, 101}, r.frameGroups(), "placeholder 77:65535 is not a frame group");
        assertEquals((5 << 16) | 2, r.keyframe);
        assertEquals(1234, r.leftHand);
        assertEquals(-1, r.rightHand);
        assertEquals(30, r.rangeEnd);
    }

    @Test void seqRecRewriteMapsOnlyReferences() {
        byte[] s = AnimSelfCheck.sample(100, (5 << 16) | 2);
        assertArrayEquals(s, SeqRec.rewrite(1, s, null, null, null));
        byte[] m = SeqRec.rewrite(1, s, ref -> ((ref >>> 16) + 30000) << 16 | (ref & 0xffff), k -> (20000 << 16) | (k & 0xffff), obj -> obj + 1);
        SeqRec q = SeqRec.parse(1, m);
        assertArrayEquals(new int[]{(30100 << 16) | 7, (30101 << 16) | 8}, q.frames);
        assertArrayEquals(new int[]{(30100 << 16) | 3, (77 << 16) | 0xffff}, q.extra, "placeholder kept");
        assertEquals((20000 << 16) | 2, q.keyframe);
        assertEquals(1235, q.leftHand);
        assertEquals(s.length, m.length);
        // every non-reference field is unchanged
        SeqRec r = SeqRec.parse(1, s);
        var fr = r.fields();
        var fq = q.fields();
        fr.remove(6); fq.remove(6);
        assertEquals(fr, fq);
    }

    @Test void seqRecRejectsBadRecords() {
        assertThrows(IllegalStateException.class, () -> SeqRec.parse(1, new byte[]{99, 0}));
        assertThrows(IllegalStateException.class, () -> SeqRec.parse(1, new byte[]{4, 0, 4}));
        assertThrows(IllegalStateException.class, () -> SeqRec.parse(1, new byte[]{4}));
    }

    @Test void fieldsIgnoreRefsAndSoundWeights() {
        var a = SeqRec.parse(1, AnimSelfCheck.sample(100, 1)).fields();
        assertEquals(a, SeqRec.parse(1, AnimSelfCheck.sample(9000, 77)).fields());
        byte[] w = AnimSelfCheck.sample(100, 1);
        w[AnimSelfCheck.indexOf(w, new byte[]{14, 0, 1}) + 7] = 55;
        assertEquals(a, SeqRec.parse(1, w).fields());
        byte[] p = AnimSelfCheck.sample(100, 1);
        p[AnimSelfCheck.indexOf(p, new byte[]{5, 6}) + 1] = 9; // priority
        assertNotEquals(a, SeqRec.parse(1, p).fields());
    }

    @Test void framemapCompatibility() {
        byte[] fm2 = {2, 0, 1, 2, 1, 1, 2, 3, 0, 0};
        byte[] fm3 = {3, 0, 1, 2, 2, 1, 1, 1, 2, 3, 4, 0, 0};
        byte[] reordered = {2, 0, 1, 2, 1, 2, 1, 3, 0, 0};
        byte[] withBones = {2, 0, 1, 2, 1, 1, 2, 3, 0, 1, 9};
        assertTrue(AnimFiles.classicCompatible(fm2, fm3));
        assertFalse(AnimFiles.classicCompatible(fm3, fm2));
        assertFalse(AnimFiles.classicCompatible(fm2, reordered));
        assertTrue(AnimFiles.classicCompatible(fm2, withBones));
        assertTrue(AnimFiles.framemap(withBones).skeletal());
        assertFalse(AnimFiles.framemap(fm2).skeletal());
        assertEquals(3, AnimFiles.framemapCount(fm3));
    }

    @Test void frameAndKeyframeHeaders() {
        byte[] frame = {0, 9, 2, 1, 0, 5};
        byte[] f2 = AnimFiles.withFrameFramemap(frame, 30002);
        assertEquals(30002, AnimFiles.frameFramemap(f2));
        assertEquals(9, AnimFiles.frameFramemap(frame), "original untouched");
        assertEquals(2, AnimFiles.frameTransforms(f2));
        assertTrue(AnimFiles.sameFrameData(frame, f2));
        byte[] key = {2, 0, 9, 7, 7};
        byte[] k2 = AnimFiles.withKeyframeFramemap(key, 31000);
        assertEquals(31000, AnimFiles.keyframeFramemap(k2));
        assertTrue(AnimFiles.sameKeyframeData(key, k2));
        assertThrows(IllegalArgumentException.class, () -> AnimFiles.withFrameFramemap(frame, 70000));
    }

    @Test void helpers() {
        assertEquals("1-3 7 9-10", AnimStep.ranges(List.of(1, 2, 3, 7, 9, 10)));
        assertEquals(new TreeSet<>(List.of(1, 5, 6, 7)), AnimStep.parseIds("1, 5-7"));
        assertTrue(AnimStep.parseIds("").isEmpty());
    }

    /**
     * The anim step and its reference check on the real caches (dry run: nothing is written), only when the cache layout
     * is available (-Dgadconv.root or GADCONV_ROOT).
     */
    @Test void goldenDryRun(@TempDir Path tmp) throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.base) && Files.isDirectory(o.user) && Files.isDirectory(o.van211), "cache layout not available");
        o.dryRun = true;
        o.out = tmp.resolve("out");
        o.work = tmp.resolve("work");
        o.old241 = null;
        try (Ctx ctx = Ctx.open(o, true)) {
            ctx.report.echo = false;
            ctx.report.begin("anim");
            new AnimStep().run(ctx);
            ctx.report.begin("anim-refcheck");
            AnimRefCheck.Result r = new AnimRefCheck.Checker(ctx.out, ctx.base, ctx.user, ctx.van211, ctx.ids).run();
            assertEquals(0, r.bad(), () -> r.toJson().toString());
            assertEquals(549, r.okCount("BOSS-X1 skeletal seqs resolve in archive 22"));
            assertEquals(277, r.okCount("BOSS-X1 NPCs: stand/walk resolve (output record)"));
            assertEquals(0, ctx.report.errors());
            // relocations stay inside the reserved ranges
            for (Kind k : new Kind[]{Kind.SEQ, Kind.FRAME, Kind.FRAMEMAP, Kind.KEYFRAME})
                for (int to : ctx.ids.all(k).values()) assertTrue(ctx.ids.range(k).contains(to), k.key + " " + to);
            // raid seqs 15000-15162 of BASE are never replaced; the user's seqs there moved
            assertTrue(ctx.ids.moved(Kind.SEQ, 15000));
        }
    }
}
