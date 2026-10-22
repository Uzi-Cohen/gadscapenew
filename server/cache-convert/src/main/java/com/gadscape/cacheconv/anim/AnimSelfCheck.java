package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Self-checks of the animation codecs (no output cache is written):
 * <pre>  dev/run.sh anim.AnimSelfCheck</pre>
 * <ol>
 *   <li>synthetic records: {@link SeqRec} parse/rewrite (refs mapped, placeholders kept, every other byte kept),
 *       canonical fields, {@link AnimFiles} framemap compatibility;</li>
 *   <li>when the cache layout is available (-Dgadconv.root): every seq of BASE, and every seq of USER and VAN211
 *       after the 211 -> 241 conversion, parses and survives an identity rewrite byte for byte, and a rewrite with a
 *       shifted frame group maps exactly the frame refs.</li>
 * </ol>
 * Exit code 1 on any failure.
 */
public final class AnimSelfCheck {
    private AnimSelfCheck() {}

    private static int failures;

    static void expect(boolean ok, String what) {
        if (!ok) { failures++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        synthetic();
        Options o = Options.defaults();
        if (o.root != null && Files.isDirectory(o.base) && Files.isDirectory(o.user) && Files.isDirectory(o.van211)) {
            try (JagCache base = JagCache.open("BASE", o.base); JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211)) {
                caches("BASE", base, false);
                caches("USER", user, true);
                caches("VAN211", van, true);
            }
        } else System.out.println("cache layout not available: synthetic checks only");
        System.out.println(failures == 0 ? "anim self-check: OK" : "anim self-check: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A 241 seq record with every reference kind. */
    public static byte[] sample(int group, int keyframe) {
        BufOut b = new BufOut();
        b.u8(1).u16(2).u16(4).u16(5).u16(7).u16(8).u16(group).u16(group + 1);   // 2 frames: group:7, (group+1):8
        b.u8(2).u16(1);
        b.u8(5).u8(6);
        b.u8(6).u16(512 + 1234).u8(7).u16(0);                                    // left hand obj 1234, no right hand
        b.u8(12).u8(2).u16(3).u16(0xffff).u16(group).u16(77);                    // extra: group:3 and a placeholder 77:65535
        b.u8(13).i32(keyframe);
        b.u8(14).u16(1).u16(0).u16(2000).u8(100).u8(1).u8(0).u8(0);
        b.u8(15).u16(0).u16(30);
        b.u8(18).str("TEST_SEQ");
        b.u8(0);
        return b.done();
    }

    static void synthetic() {
        System.out.println("synthetic records");
        byte[] s = sample(100, (5 << 16) | 2);
        SeqRec r = SeqRec.parse(1, s);
        expect(Arrays.equals(r.frames, new int[]{(100 << 16) | 7, (101 << 16) | 8}), "op 1 frame refs");
        expect(Arrays.equals(r.lengths, new int[]{4, 5}), "op 1 lengths");
        expect(Arrays.equals(r.extra, new int[]{(100 << 16) | 3, (77 << 16) | 0xffff}), "op 12 refs");
        expect(Arrays.equals(r.realFrameRefs(), new int[]{(100 << 16) | 7, (101 << 16) | 8, (100 << 16) | 3}), "placeholders left out");
        expect(Arrays.equals(r.frameGroups(), new int[]{100, 101}), "frame groups");
        expect(r.keyframe == ((5 << 16) | 2) && r.skeletal(), "op 13");
        expect(r.leftHand == 1234 && r.rightHand == -1, "hand objs");
        expect(r.rangeStart == 0 && r.rangeEnd == 30, "op 15");
        expect(Arrays.equals(SeqRec.rewrite(1, s, null, null, null), s), "identity rewrite keeps every byte");
        byte[] m = SeqRec.rewrite(1, s, ref -> ((ref >>> 16) + 30000) << 16 | (ref & 0xffff), k -> (20000 << 16) | (k & 0xffff), obj -> obj + 1);
        SeqRec q = SeqRec.parse(1, m);
        expect(Arrays.equals(q.frames, new int[]{(30100 << 16) | 7, (30101 << 16) | 8}), "frames mapped");
        expect(Arrays.equals(q.extra, new int[]{(30100 << 16) | 3, (77 << 16) | 0xffff}), "extra mapped, placeholder kept");
        expect(q.keyframe == ((20000 << 16) | 2), "keyframe mapped");
        expect(q.leftHand == 1235 && q.rightHand == -1, "hand obj mapped, 0 kept");
        expect(m.length == s.length, "same length");
        expect(Arrays.equals(SeqRec.rewrite(1, m, ref -> ((ref >>> 16) - 30000) << 16 | (ref & 0xffff), k -> (5 << 16) | (k & 0xffff), obj -> obj - 1), s), "inverse rewrite restores the record");
        expect(r.fields().equals(q.fields()) == false, "hand obj is a field");
        TreeMap<Integer, String> fr = SeqRec.parse(1, sample(100, 1)).fields(), fq = SeqRec.parse(1, sample(200, 9)).fields();
        expect(fr.equals(fq), "fields ignore frame/keyframe refs");
        byte[] w = sample(100, 1);
        int p = indexOf(w, new byte[]{14, 0, 1}) + 7;  // weight byte of the sound
        w[p] = 55;
        expect(SeqRec.parse(1, w).fields().equals(fr), "fields ignore sound weights");
        try { SeqRec.parse(1, new byte[]{99, 0}); expect(false, "unknown opcode throws"); } catch (IllegalStateException e) { expect(true, ""); }
        try { SeqRec.parse(1, new byte[]{4, 0, 4}); expect(false, "bytes after 0 throw"); } catch (IllegalStateException e) { expect(true, ""); }
        try { SeqRec.parse(1, new byte[]{4}); expect(false, "missing 0 throws"); } catch (IllegalStateException e) { expect(true, ""); }

        // framemaps: 2 groups (types 0, 1; labels [1,2], [3]); a newer version appends a group
        byte[] fm2 = {2, 0, 1, 2, 1, 1, 2, 3, 0, 0};
        byte[] fm3 = {3, 0, 1, 2, 2, 1, 1, 1, 2, 3, 4, 0, 0};
        byte[] fmReordered = {2, 0, 1, 2, 1, 2, 1, 3, 0, 0};
        byte[] fmSkel = {2, 0, 1, 2, 1, 1, 2, 3, 0, 1, 9};
        expect(AnimFiles.classicCompatible(fm2, fm3), "appended group is compatible");
        expect(!AnimFiles.classicCompatible(fm3, fm2), "fewer groups are not");
        expect(!AnimFiles.classicCompatible(fm2, fmReordered), "label order differs: not compatible");
        expect(AnimFiles.classicCompatible(fm2, fmSkel) && AnimFiles.framemap(fmSkel).skeletal() && !AnimFiles.framemap(fm2).skeletal(), "bone data ignored, detected");
        expect(AnimFiles.framemapCount(fm3) == 3, "framemap count");
        byte[] frame = {0, 9, 2, 1, 0, 5};
        expect(AnimFiles.frameFramemap(frame) == 9 && AnimFiles.frameTransforms(frame) == 2, "frame header");
        byte[] f2 = AnimFiles.withFrameFramemap(frame, 30002);
        expect(AnimFiles.frameFramemap(f2) == 30002 && AnimFiles.sameFrameData(frame, f2) && frame[1] == 9, "frame framemap rewritten on a copy");
        byte[] key = {2, 0, 9, 7, 7};
        byte[] k2 = AnimFiles.withKeyframeFramemap(key, 31000);
        expect(AnimFiles.keyframeFramemap(k2) == 31000 && AnimFiles.sameKeyframeData(key, k2), "keyframe framemap rewritten");
        expect(AnimStep.ranges(java.util.List.of(1, 2, 3, 7, 9, 10)).equals("1-3 7 9-10"), "ranges");
        expect(AnimStep.parseIds("1,5-7").equals(new java.util.TreeSet<>(java.util.List.of(1, 5, 6, 7))), "parseIds");
    }

    static void caches(String label, JagCache c, boolean is211) {
        int n = 0, bad = 0;
        for (int id : c.configIds(Arch.CFG_SEQ)) {
            byte[] b = c.configRef(Arch.CFG_SEQ, id);
            try {
                byte[] s = is211 ? ConfigConv.seq(id, b) : b;
                SeqRec r = SeqRec.parse(id, s);
                boolean ok = Arrays.equals(SeqRec.rewrite(id, s, null, null, null), s);
                byte[] m = SeqRec.rewrite(id, s, ref -> ref + (1 << 16), k -> k, o -> o);
                SeqRec q = SeqRec.parse(id, m);
                int[] a = r.allFrameRefs(), e = q.allFrameRefs();
                for (int i = 0; i < a.length; i++) ok &= SeqRec.isNullRef(a[i]) ? e[i] == a[i] : e[i] == a[i] + (1 << 16);
                ok &= r.fields().equals(q.fields());
                if (!ok) { bad++; if (bad < 5) System.out.println("  FAIL " + label + " seq " + id + " rewrite"); }
            } catch (RuntimeException ex) {
                bad++;
                if (bad < 5) System.out.println("  FAIL " + label + " seq " + id + ": " + ex.getMessage());
            }
            n++;
        }
        c.release(Arch.CONFIGS);
        failures += bad;
        System.out.println(label + ": " + n + " seqs" + (is211 ? " (converted to 241)" : "") + " parse and rewrite exactly" + (bad > 0 ? ", " + bad + " FAILED" : ""));
    }

    static int indexOf(byte[] a, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= a.length; i++) {
            for (int j = 0; j < pat.length; j++) if (a[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }
}
