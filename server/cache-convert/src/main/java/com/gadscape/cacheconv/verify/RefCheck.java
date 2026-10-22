package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.verify.CfgRefs.Target;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Reference checks of a build-241 cache (every failure is a finding):
 * <ul>
 *   <li>{@code refs/<config>-><target>}: every id a config entry refers to exists (obj/npc/loc/spotanim/idkit models
 *       and seqs, seq frames/keyframes/hand objs, transforms and their varbits/varps, params, typed param values, enum
 *       keys/values, struct params, dbrow tables and cells, inventory stock, varbit varps, healthbar/npc-head sprites);
 *       {@code cfg/parse}: every walked entry parses exactly.</li>
 *   <li>{@code anim/frame->framemap}, {@code anim/frame-transforms}, {@code anim/keyframe->framemap}: the frames and
 *       keyframes seqs use point at framemaps that exist and fit (enough transform groups; skeletal data for
 *       keyframes).</li>
 *   <li>{@code if/...}: every interface component parses; its sprites, models, seqs, fonts and parent exist; every hook
 *       script exists and gets no more arguments than it has locals ({@code if/hook-locals}) and the number it takes
 *       ({@code if/hook-args}, a warning).</li>
 *   <li>{@code maps/...}: every map square's loc file decodes and its loc ids exist.</li>
 * </ul>
 */
public final class RefCheck {
    private RefCheck() {}

    /** Config groups walked, in order. */
    static final int[] GROUPS = {Arch.CFG_IDENTKIT, Arch.CFG_INV, Arch.CFG_LOC, Arch.CFG_ENUM, Arch.CFG_NPC, Arch.CFG_OBJ, Arch.CFG_PARAM,
            Arch.CFG_SEQ, Arch.CFG_SPOT, Arch.CFG_VARBIT, Arch.CFG_HEALTHBAR, Arch.CFG_STRUCT, Arch.CFG_DBROW, Arch.CFG_DBTABLE};

    public static void configs(CacheView c, Exists ex, CacheTypes types, Findings f) {
        for (int g : GROUPS) {
            String subject = Arch.configName(g);
            for (int id : c.configIds(g)) {
                byte[] b = c.configRef(g, id);
                if (b == null) continue;
                List<Object[]> refs = new ArrayList<>();
                try {
                    CfgRefs.walk(g, id, b, types, (t, v, field) -> refs.add(new Object[]{t, v, field}));
                    f.ok("cfg/parse", Severity.ERROR);
                } catch (IllegalStateException e) {
                    f.bad("cfg/parse", Severity.ERROR, subject + " " + id, e.getMessage());
                    continue;
                }
                for (Object[] r : refs) {
                    Target t = (Target) r[0];
                    int v = (Integer) r[1];
                    String name = "refs/" + subject + "->" + t.key;
                    boolean ok = ex.exists(t, v);
                    if (ok) f.ok(name, Severity.ERROR);
                    else f.bad(name, Severity.ERROR, subject + " " + id + " -> " + Exists.show(t, v), subject + " " + id + " " + r[2] + " -> " + Exists.show(t, v));
                }
            }
            c.release(Arch.CONFIGS);
        }
    }

    // ------------------------------------------------------------------ animation assets

    /**
     * Frames and keyframes the seqs of the cache use: each frame file names an existing framemap with at least as
     * many transform groups as the frame addresses; each keyframe names an existing framemap that has skeletal data.
     */
    public static void anims(CacheView c, Exists ex, CacheTypes types, Findings f) {
        TreeSet<Long> frames = new TreeSet<>(), keyframes = new TreeSet<>();
        for (int id : c.configIds(Arch.CFG_SEQ)) {
            byte[] b = c.configRef(Arch.CFG_SEQ, id);
            if (b == null) continue;
            try {
                CfgRefs.walk(Arch.CFG_SEQ, id, b, types, (t, v, field) -> {
                    if (t == Target.FRAME && ex.exists(t, v)) frames.add(((long) v << 20) | id);
                    if (t == Target.KEYFRAME && ex.exists(t, v)) keyframes.add(((long) v << 20) | id);
                });
            } catch (IllegalStateException ignored) {
                // reported by configs()
            }
        }
        // framemaps: transform count and whether skeletal data follows
        int[] fmIds = c.groupIds(Arch.FRAMEMAPS);
        int max = fmIds.length == 0 ? 0 : fmIds[fmIds.length - 1] + 1;
        int[] fmCount = new int[max];
        BitSet fmSkel = new BitSet();
        java.util.Arrays.fill(fmCount, -1);
        for (int id : fmIds) {
            byte[] b = c.single(Arch.FRAMEMAPS, id);
            if (b == null || b.length == 0) continue;
            try {
                int p = 0, n = b[p++] & 255;
                p += n;
                int labels = 0;
                for (int i = 0; i < n; i++) labels += b[p++] & 255;
                p += labels;
                fmCount[id] = n;
                if (p + 2 <= b.length && Bytes.u16(b, p) > 0) fmSkel.set(id);
            } catch (ArrayIndexOutOfBoundsException e) {
                f.bad("anim/framemap-parse", Severity.ERROR, "framemap " + id, "framemap " + id + " is truncated");
            }
        }
        c.release(Arch.FRAMEMAPS);
        // frames, group by group
        long lastGroup = -1;
        GroupData gd = null;
        TreeSet<Long> seenFrame = new TreeSet<>();
        int released = 0;
        for (long key : frames) {
            int ref = (int) (key >>> 20), seq = (int) (key & 0xfffff);
            if (!seenFrame.add((long) ref)) continue;
            int group = ref >>> 16, file = ref & 0xffff;
            if (group != lastGroup) {
                gd = c.group(Arch.FRAMES, group);
                lastGroup = group;
                if (++released % 2000 == 0) c.release(Arch.FRAMES);
            }
            byte[] d = gd == null ? null : gd.dataRef(file);
            if (d == null || d.length < 3) { f.bad("anim/frame-parse", Severity.ERROR, "frame " + group + ":" + file, "frame " + group + ":" + file + " (seq " + seq + ") is empty"); continue; }
            int fm = Bytes.u16(d, 0), n = d[2] & 255;
            boolean exists = fm < fmCount.length && fmCount[fm] >= 0;
            f.check("anim/frame->framemap", Severity.ERROR, exists, "frame " + group + ":" + file + " -> framemap " + fm,
                    "frame " + group + ":" + file + " (seq " + seq + ") -> framemap " + fm);
            if (exists) f.check("anim/frame-transforms", Severity.ERROR, n <= fmCount[fm], "frame " + group + ":" + file,
                    "frame " + group + ":" + file + " (seq " + seq + ") uses " + n + " transforms, framemap " + fm + " has " + fmCount[fm]);
        }
        c.release(Arch.FRAMES);
        TreeSet<Long> seenKey = new TreeSet<>();
        for (long key : keyframes) {
            int ref = (int) (key >>> 20), seq = (int) (key & 0xfffff);
            if (!seenKey.add((long) ref)) continue;
            byte[] d = c.fileRef(Arch.KEYFRAMES, ref >>> 16, ref & 0xffff);
            String what = "keyframe " + (ref >>> 16) + ":" + (ref & 0xffff);
            if (d == null || d.length < 3) { f.bad("anim/keyframe-parse", Severity.ERROR, what, what + " (seq " + seq + ") is empty"); continue; }
            int fm = Bytes.u16(d, 1);
            boolean exists = fm < fmCount.length && fmCount[fm] >= 0;
            f.check("anim/keyframe->framemap", Severity.ERROR, exists && fmSkel.get(fm), what + " -> framemap " + fm,
                    what + " (seq " + seq + ") -> framemap " + fm + (exists ? " (no skeletal data)" : " (missing)"));
        }
        c.release(Arch.KEYFRAMES);
        f.info("anim.frameRefs", seenFrame.size());
        f.info("anim.keyframeRefs", seenKey.size());
    }

    // ------------------------------------------------------------------ interfaces

    public static void interfaces(CacheView c, Exists ex, CsWorld scripts, Findings f) {
        for (int g : c.groupIds(Arch.INTERFACES)) {
            GroupData gd = c.group(Arch.INTERFACES, g);
            if (gd == null) { f.bad("if/parse", Severity.ERROR, "interface " + g, "interface " + g + " does not decode"); continue; }
            for (int i = 0; i < gd.fileCount(); i++) {
                int file = gd.fileIdAt(i);
                byte[] b = gd.dataAt(i);
                String who = "component " + g + ":" + file;
                if (b.length == 0) continue;
                IfWalk.Comp comp;
                try {
                    comp = IfWalk.parse(b);
                    f.ok("if/parse", Severity.ERROR);
                } catch (IllegalStateException e) {
                    f.bad("if/parse", Severity.ERROR, who, who + ": " + e.getMessage());
                    continue;
                }
                if (comp.parent != -1) f.check("if/parent", Severity.ERROR, gd.has(comp.parent & 0xffff) && (comp.parent >>> 16 == 0 || comp.parent >>> 16 == g),
                        who + " -> parent " + comp.parent, who + " parent " + comp.parent + " is not a component of interface " + g);
                for (int v : comp.sprites) f.check("if/sprite", Severity.ERROR, ex.exists(Target.SPRITE, v), who + " -> sprite " + v, who + " -> sprite " + v);
                for (int v : comp.models) f.check("if/model", Severity.ERROR, ex.exists(Target.MODEL, v), who + " -> model " + v, who + " -> model " + v);
                for (int v : comp.seqs) f.check("if/seq", Severity.ERROR, ex.exists(Target.SEQ, v), who + " -> seq " + v, who + " -> seq " + v);
                for (int v : comp.fonts) f.check("if/font", Severity.ERROR, ex.exists(Target.FONT, v), who + " -> font " + v, who + " -> font " + v);
                for (IfWalk.Hook h : comp.hooks) {
                    if (h.script() == -1) continue;
                    var s = scripts.scripts.get(h.script());
                    String hk = who + " " + h.name() + " -> script " + h.script();
                    if (s == null) { f.bad("if/hook-script", Severity.ERROR, hk, hk + " (missing)"); continue; }
                    f.ok("if/hook-script", Severity.ERROR);
                    int ints = h.ints(), strs = h.strings();
                    f.check("if/hook-locals", Severity.ERROR, ints <= s.localInt && strs <= s.localObj, hk,
                            hk + " passes " + ints + " ints / " + strs + " strings, the script has " + s.localInt + " / " + s.localObj + " locals");
                    f.check("if/hook-args", Severity.WARNING, ints == s.intArgs && strs == s.objArgs, hk,
                            hk + " passes " + ints + " ints / " + strs + " strings, the script takes " + s.intArgs + " / " + s.objArgs);
                }
            }
            if (g % 200 == 0) c.release(Arch.INTERFACES);
        }
        c.release(Arch.INTERFACES);
    }

    // ------------------------------------------------------------------ maps

    /** Loc ids of a 241 loc file (region group, file 1): id delta smarts, position smarts, attribute bytes. */
    public static List<int[]> locs(byte[] b) {
        Buf in = new Buf(b);
        List<int[]> out = new ArrayList<>();
        int id = -1;
        while (true) {
            int d = extSmart(in);
            if (d == 0) break;
            id += d;
            int pos = 0;
            while (true) {
                int pd = in.smart();
                if (pd == 0) break;
                pos += pd - 1;
                int attr = in.u8();
                out.add(new int[]{id, pos, attr});
            }
        }
        if (in.p != b.length) throw new IllegalStateException((b.length - in.p) + " bytes after the loc list");
        return out;
    }

    /** Unsigned smart that continues past 32767 (client readUnsignedIntSmartShortCompat). */
    static int extSmart(Buf in) {
        int v = 0, s;
        while ((s = in.smart()) == 32767) v += 32767;
        return v + s;
    }

    public static void maps(CacheView c, Exists ex, Findings f) {
        int squares = 0, locs = 0;
        for (int g : c.groupIds(Arch.MAPS)) {
            int[] files = c.fileIds(Arch.MAPS, g);
            if (files.length != 5) continue; // region squares have files 0-4 (terrain, locs, lighting/environment)
            GroupData gd;
            try {
                gd = c.group(Arch.MAPS, g);
            } catch (IllegalStateException e) {
                f.bad("maps/decode", Severity.ERROR, "square " + g, "square " + g + ": " + e.getMessage());
                continue;
            }
            if (gd == null) continue;
            byte[] lb = gd.dataRef(1);
            if (lb == null) continue;
            List<int[]> list;
            try {
                list = locs(lb);
                f.ok("maps/loc-parse", Severity.ERROR);
            } catch (RuntimeException e) {
                f.bad("maps/loc-parse", Severity.ERROR, "square " + g, "square " + g + " (" + (g >> 8) + "," + (g & 255) + "): " + e.getMessage());
                continue;
            }
            squares++;
            int lastBad = -1;
            for (int[] l : list) {
                locs++;
                if (ex.exists(Target.LOC, l[0])) f.ok("maps/loc-exists", Severity.ERROR);
                else if (l[0] != lastBad) { f.bad("maps/loc-exists", Severity.ERROR, "square " + g + " -> loc " + l[0], "square " + g + " (" + (g >> 8) + "," + (g & 255) + ") places loc " + l[0]); lastBad = l[0]; }
            }
            if (squares % 300 == 0) c.release(Arch.MAPS);
        }
        c.release(Arch.MAPS);
        f.info("maps.squares", squares);
        f.info("maps.locs", locs);
    }
}
