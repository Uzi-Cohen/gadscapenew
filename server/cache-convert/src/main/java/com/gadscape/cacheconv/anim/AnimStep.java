package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Animations: sequences (config 12), frame sets (archive 0), framemaps (archive 1) and skeletal keyframes (archive 22).
 * BASE's groups and records are never overwritten; the user's own animations are layered on top.
 * <p>
 * <b>Sequences</b>, per id (USER vs VAN211, ascending):
 * <ul>
 *   <li>vanilla (user never touched it): BASE's record stays. A vanilla 211 seq that BASE no longer has is ported
 *       (the server still plays some of them), unless {@code --anim.portRemovedVanilla=false}. A vanilla 211 seq whose
 *       BASE record animates another skeleton (another framemap id: Jagex reused the id, gnome walk 189 is the player's
 *       EMOTE_YES_LOOP in 241; or re-rigged it for new models, GNOME_CATCH 200 and 2904 on framemap 2402) is ported to the reserved range with its 211
 *       frames, as a vanilla port ({@link IdMap#markVanilla}): GadScape content that plays it on 211-era models (the
 *       user's gnome NPCs, the Herblore Store 16034) is rewritten to the copy, BASE's content keeps BASE's record
 *       ({@code --anim.portRiggedVanilla=false} to skip).</li>
 *   <li>user-added/changed and BASE has the id, "same thing" -> BASE's record stays (taken-from-base):
 *       <ul><li>skeletal in both on the same framemap (BOSS-X1: 211 keyframes live in archive 0, 241 reads archive 22;
 *       BASE has the same animation with its keyframes already in archive 22);</li>
 *       <li>classic in both and every frame is the same frame (same data, same framemap): the user imported the Jagex
 *       animation; record differences (sound frame indexes lost by the import, priorities Jagex added later) are
 *       reported;</li>
 *       <li>the user record points at frames USER does not have (it never played in 211 either).</li></ul></li>
 *   <li>any other user seq is ported: at its own id when BASE does not use the id, otherwise relocated to the
 *       reserved seq range (raid seqs 15000-15162 vs the user's 15000-15253, user anims over Jagex ids such as 10000).
 *       Its frame refs (op 1/12), keyframe ref (op 13) and hand objs (op 6/7) are rewritten.</li>
 *   <li>a user seq whose frames are missing in USER and whose id BASE does not use is dropped (reported).</li>
 * </ul>
 * <b>Assets</b> needed by the ported seqs only (user groups used by nothing else are reported as dropped):
 * <ul>
 *   <li>frame set G: its files with their framemap ids mapped; kept at G when BASE's group G is exactly that,
 *       written at G when G already lies in the reserved frame range (USER's own ids there), else copied into the
 *       reserved frame range. This covers vanilla 211 frame sets too: Jagex renumbered frame sets by 241, so a user
 *       seq built from vanilla 211 frames needs the 211 frames, not BASE's group at that id (B3, IC-15).</li>
 *   <li>framemap F: kept when BASE's F is identical, or for classic frames when the 211 transform groups are a
 *       prefix of BASE's; otherwise the USER framemap is copied into the reserved framemap range (BOSS-X4: 112
 *       vanilla framemaps changed incompatibly by 241). Frames made for the newer base keep BASE's F: frames with
 *       more transforms than the USER framemap has, and frames of the user's own groups that are byte-identical to a
 *       BASE frame on F and to no VAN211 frame (exported from a 241-era cache: USER seq 10173's 15 player frames).</li>
 *   <li>keyframes of a ported skeletal seq: the 211 archive-0 group is copied into archive 22 at a reserved
 *       keyframe id (framemap mapped) and op 13 is rewritten.</li>
 * </ul>
 * Every move is recorded in the id map (seq, frame, framemap, keyframe) and every decision in the report.
 */
public final class AnimStep implements Step {
    @Override public String name() { return "anim"; }

    @Override public void run(Ctx ctx) {
        AnimStep.Port p = new Port(ctx);
        p.classifySeqs();
        p.collectAssets();
        p.placeFramemaps();
        p.placeFrames();
        p.placeKeyframes();
        p.writeSeqs();
        p.reportUnused();
        ctx.report.count("relocated-seqs", ctx.ids.all(Kind.SEQ).size());
        ctx.report.count("relocated-frame-groups", ctx.ids.all(Kind.FRAME).size());
        ctx.report.count("relocated-framemaps", ctx.ids.all(Kind.FRAMEMAP).size());
        ctx.report.count("relocated-keyframe-groups", ctx.ids.all(Kind.KEYFRAME).size());
        p.release();
    }

    /** What happens to one sequence id. */
    public enum Action {
        /** Vanilla in USER: BASE's record stays (counted only). */
        BASE_VANILLA,
        /** The user deleted a vanilla seq: BASE's record stays (counted). */
        USER_REMOVED,
        /** User record is the same animation as BASE's: BASE's record stays. */
        TAKE_BASE,
        /** Ported at its own id. */
        PORT,
        /** Ported to the reserved seq range (BASE uses the id for something else). */
        PORT_RELOCATE,
        /** Dropped: the user record is broken (frames missing in USER) and BASE has nothing at the id. */
        DROP
    }

    public record SeqCase(int id, Origin origin, Action action, String detail, byte[] conv, SeqRec rec) {}

    /** Per-framemap use by the ported frames/keyframes. */
    private static final class FmUse {
        boolean classicFitsUser, skeletal;
        int madeForBase; // frames made for BASE's version of the framemap (they keep BASE's id)
        final TreeSet<Integer> overUserBy = new TreeSet<>(); // transform counts above the USER framemap's group count
    }

    /** The planning and writing state of one run (package-private for the self-check). */
    static final class Port {
        final Ctx ctx;
        final CacheView base, user, van;
        final boolean portRemovedVanilla, portRiggedVanilla;
        /** USER frames (group << 16 | file) made for BASE's version of their framemap (keep BASE's framemap id). */
        final java.util.HashSet<Integer> newerBaseFrames = new java.util.HashSet<>();
        /** framemap -> rig comparison memo for the vanilla seq check (USER framemap id + BASE framemap id). */
        final java.util.HashMap<Long, Boolean> sameRig = new java.util.HashMap<>();
        /** Testing aid ({@code --anim.forcePort=9419,10045-10050}): user seqs ported even when BASE has the same animation. */
        final TreeSet<Integer> forcePort;
        /** {@code --anim.takeBase=11008-11020}: user seqs at BASE ids that keep BASE's record even though they differ. */
        final TreeSet<Integer> takeBase;
        final TreeMap<Integer, SeqCase> seqs = new TreeMap<>();
        /** USER archive-0 groups needed as classic frame sets / as 211 keyframe groups. */
        final TreeSet<Integer> frameGroups = new TreeSet<>(), keyGroups = new TreeSet<>();
        final TreeMap<Integer, FmUse> fmUse = new TreeMap<>();
        /** USER framemap id -> output framemap id (identity when kept). */
        final TreeMap<Integer, Integer> fmTo = new TreeMap<>();
        /** Every USER archive-0 group referenced by any user seq (for the dropped-asset report). */
        final TreeMap<Integer, String> referencedBy = new TreeMap<>();

        Port(Ctx ctx) {
            this.ctx = ctx;
            this.base = ctx.base;
            this.user = ctx.user();
            this.van = ctx.van211();
            this.portRemovedVanilla = Boolean.parseBoolean(ctx.opt.get("anim.portRemovedVanilla", "true"));
            this.portRiggedVanilla = Boolean.parseBoolean(ctx.opt.get("anim.portRiggedVanilla", "true"));
            this.forcePort = parseIds(ctx.opt.get("anim.forcePort", ""));
            this.takeBase = parseIds(ctx.opt.get("anim.takeBase", ""));
        }

        // -------------------------------------------------------------- 1. sequences

        void classifySeqs() {
            TreeSet<Integer> ids = new TreeSet<>();
            for (int id : user.configIds(Arch.CFG_SEQ)) ids.add(id);
            for (int id : van.configIds(Arch.CFG_SEQ)) ids.add(id);
            for (int id : ids) {
                SeqCase c = classify(id);
                seqs.put(id, c);
                ctx.report.count("seq-" + c.action.name().toLowerCase().replace('_', '-'));
            }
        }

        SeqCase classify(int id) {
            Origin o = Origin.ofConfig(Arch.CFG_SEQ, id, user, van);
            byte[] bb = base.configRef(Arch.CFG_SEQ, id);
            if (o == Origin.USER_REMOVED) return new SeqCase(id, o, Action.USER_REMOVED, null, null, null);
            if (o == Origin.VANILLA) {
                if (bb != null && portRiggedVanilla) {
                    byte[] conv = ConfigConv.seq(id, user.configRef(Arch.CFG_SEQ, id));
                    SeqRec u = SeqRec.parse(id, conv);
                    String why = rigChange(u, SeqRec.parse(id, bb));
                    if (why != null && missing(u) == null) return new SeqCase(id, o, Action.PORT_RELOCATE, "vanilla 211 seq " + id + ": " + why, conv, u);
                }
                if (bb != null || !portRemovedVanilla) return new SeqCase(id, o, Action.BASE_VANILLA, bb == null ? "vanilla 211 seq missing from BASE, not ported" : null, null, null);
                byte[] conv = ConfigConv.seq(id, user.configRef(Arch.CFG_SEQ, id));
                SeqRec u = SeqRec.parse(id, conv);
                String miss = missing(u);
                if (miss != null) return new SeqCase(id, o, Action.DROP, "vanilla 211 seq missing from BASE; " + miss, conv, u);
                return new SeqCase(id, o, Action.PORT, "vanilla 211 seq missing from BASE (the server may still play it): ported", conv, u);
            }
            byte[] conv = ConfigConv.seq(id, user.configRef(Arch.CFG_SEQ, id));
            SeqRec u = SeqRec.parse(id, conv);
            String miss = missing(u);
            if (bb == null) {
                if (miss != null) return new SeqCase(id, o, Action.DROP, miss + " (and BASE has no seq " + id + ")", conv, u);
                return new SeqCase(id, o, Action.PORT, null, conv, u);
            }
            SeqRec b = SeqRec.parse(id, bb);
            if (forcePort.contains(id) && miss == null) return new SeqCase(id, o, Action.PORT_RELOCATE, "forced by --anim.forcePort", conv, u);
            if (u.skeletal() && b.skeletal()) {
                byte[] uk = user.fileRef(Arch.FRAMES, u.keyframe >>> 16, u.keyframe & 0xffff);
                byte[] bk = base.fileRef(Arch.KEYFRAMES, b.keyframe >>> 16, b.keyframe & 0xffff);
                if (uk != null && bk != null) {
                    int uf = AnimFiles.keyframeFramemap(uk), bf = AnimFiles.keyframeFramemap(bk);
                    boolean sameFm = uf == bf || Arrays.equals(user.fileRef(Arch.FRAMEMAPS, uf, 0), base.fileRef(Arch.FRAMEMAPS, bf, 0));
                    if (sameFm) return new SeqCase(id, o, Action.TAKE_BASE, "skeletal on the same framemap " + bf + (AnimFiles.sameKeyframeData(uk, bk) ? " (same keyframes)" : " (keyframes differ)")
                            + "; BASE keyframes in archive 22 group " + (b.keyframe >>> 16), conv, u);
                }
            }
            if (!u.skeletal() && !b.skeletal() && miss == null) {
                String diff = sameFrames(u, b);
                if (diff != null) return new SeqCase(id, o, Action.TAKE_BASE, diff, conv, u);
            }
            if (miss != null) return new SeqCase(id, o, Action.TAKE_BASE, miss + " (it never played in 211); BASE's seq kept", conv, u);
            if (takeBase.contains(id)) return new SeqCase(id, o, Action.TAKE_BASE, "differs from BASE's but listed in --anim.takeBase", conv, u);
            String why = u.skeletal() != b.skeletal() ? (u.skeletal() ? "user seq is skeletal, BASE's is classic" : "user seq is classic, BASE's is skeletal")
                    : u.skeletal() ? "skeletal on another framemap than BASE's"
                    : u.frames.length != b.frames.length ? "frame count " + u.frames.length + " vs BASE " + b.frames.length : "frames differ from BASE's";
            return new SeqCase(id, o, Action.PORT_RELOCATE, why, conv, u);
        }

        /**
         * Null when BASE's record animates the same skeleton as the 211 record (the same framemap id, which Jagex may
         * have updated in place together with its models and animations; or another framemap that is identical, or for
         * classic frames one whose 211 transform groups are a prefix of BASE's), else what changed: Jagex reused the seq
         * id for an animation of another skeleton (gnome walk 189 -> player emote on framemap 0), or re-rigged it onto a
         * new skeleton (GNOME_CATCH 200: framemap 319 -> 2402).
         */
        String rigChange(SeqRec u, SeqRec b) {
            int[] uref = u.realFrameRefs(), bref = b.realFrameRefs();
            int ufm, bfm;
            if (u.skeletal()) {
                byte[] k = user.fileRef(Arch.FRAMES, u.keyframe >>> 16, u.keyframe & 0xffff);
                if (k == null) return null;
                ufm = AnimFiles.keyframeFramemap(k);
            } else {
                if (uref.length == 0) return null;
                byte[] f = user.fileRef(Arch.FRAMES, uref[0] >>> 16, uref[0] & 0xffff);
                if (f == null) return null;
                ufm = AnimFiles.frameFramemap(f);
            }
            if (b.skeletal()) {
                byte[] k = base.fileRef(Arch.KEYFRAMES, b.keyframe >>> 16, b.keyframe & 0xffff);
                if (k == null) return null;
                bfm = AnimFiles.keyframeFramemap(k);
            } else {
                if (bref.length == 0) return null;
                byte[] f = base.fileRef(Arch.FRAMES, bref[0] >>> 16, bref[0] & 0xffff);
                if (f == null) return null;
                bfm = AnimFiles.frameFramemap(f);
            }
            // the same framemap id in both: Jagex updated that skeleton in place (the 241 player skeleton 0) and re-exported
            // its animations with it; BASE's record is the same animation, kept like any other vanilla seq
            if (u.skeletal() == b.skeletal() && ufm == bfm) return null;
            boolean classic = !u.skeletal() && !b.skeletal();
            long key = ((long) ufm << 32) | ((long) bfm << 1) | (classic ? 1 : 0);
            boolean same = sameRig.computeIfAbsent(key, x -> {
                byte[] uf = user.fileRef(Arch.FRAMEMAPS, ufm, 0), bf = base.hasGroup(Arch.FRAMEMAPS, bfm) ? base.fileRef(Arch.FRAMEMAPS, bfm, 0) : null;
                if (uf == null || bf == null) return true; // cannot tell: leave BASE's record
                return Arrays.equals(uf, bf) || (classic && AnimFiles.classicCompatible(uf, bf));
            });
            if (same) return null;
            String kind = u.skeletal() != b.skeletal() ? (b.skeletal() ? "BASE made it skeletal" : "BASE made it classic") + " (framemap " + ufm + " -> " + bfm + ")"
                    : "BASE's record is on framemap " + bfm + ", 211's on " + ufm;
            return kind + " (Jagex reused or re-rigged it); the 211 animation is ported for GadScape content";
        }

        /** Null if every frame/keyframe ref of the record resolves in USER, else a description. */
        String missing(SeqRec u) {
            int bad = 0, first = -1;
            for (int r : u.realFrameRefs()) if (!user.hasFile(Arch.FRAMES, r >>> 16, r & 0xffff)) { if (bad++ == 0) first = r; }
            if (u.skeletal() && !user.hasFile(Arch.FRAMES, u.keyframe >>> 16, u.keyframe & 0xffff)) { if (bad++ == 0) first = u.keyframe; }
            return bad == 0 ? null : bad + " frame ref(s) missing in USER, e.g. " + (first >>> 16) + ":" + (first & 0xffff);
        }

        /**
         * Non-null (a description of the record differences) when every frame of the user record is the same frame as
         * BASE's at the same position: same frame data and the same framemap (id or content).
         */
        String sameFrames(SeqRec u, SeqRec b) {
            int[] ur = u.allFrameRefs(), br = b.allFrameRefs();
            if (u.frames.length != b.frames.length || u.extra.length != b.extra.length) return null;
            for (int i = 0; i < ur.length; i++) {
                if (SeqRec.isNullRef(ur[i]) || SeqRec.isNullRef(br[i])) { if (SeqRec.isNullRef(ur[i]) != SeqRec.isNullRef(br[i])) return null; continue; }
                byte[] x = user.fileRef(Arch.FRAMES, ur[i] >>> 16, ur[i] & 0xffff), y = base.fileRef(Arch.FRAMES, br[i] >>> 16, br[i] & 0xffff);
                if (!AnimFiles.sameFrameData(x, y)) return null;
                int fx = AnimFiles.frameFramemap(x), fy = AnimFiles.frameFramemap(y);
                if (fx != fy && !Arrays.equals(user.fileRef(Arch.FRAMEMAPS, fx, 0), base.fileRef(Arch.FRAMEMAPS, fy, 0))) return null;
            }
            return "same " + ur.length + " frames as BASE" + recordDiff(u, b);
        }

        // -------------------------------------------------------------- 2. assets the ported seqs need

        void collectAssets() {
            for (SeqCase c : seqs.values()) {
                if (c.rec == null) continue;
                String tag = c.action == Action.PORT || c.action == Action.PORT_RELOCATE ? "ported" : "base";
                for (int g : c.rec.frameGroups()) referencedBy.merge(g, tag, AnimStep::mergeTag);
                if (c.rec.skeletal()) referencedBy.merge(c.rec.keyframe >>> 16, tag + "-keyframes", AnimStep::mergeTag);
                if (c.action != Action.PORT && c.action != Action.PORT_RELOCATE) continue;
                if (c.rec.skeletal()) keyGroups.add(c.rec.keyframe >>> 16);
                for (int g : c.rec.frameGroups()) frameGroups.add(g);
            }
            for (int g : frameGroups) {
                if (Origin.ofGroup(Arch.FRAMES, g, user, van) == Origin.VANILLA) continue;
                GroupData gd = user.group(Arch.FRAMES, g);
                for (int i = 0; i < gd.fileCount(); i++) userFrames.computeIfAbsent(AnimFiles.frameFramemap(gd.dataAt(i)), k -> new ArrayList<>()).add((g << 16) | gd.fileIdAt(i));
            }
            findNewerBaseFrames();
            for (int g : frameGroups) {
                GroupData gd = user.group(Arch.FRAMES, g);
                for (int i = 0; i < gd.fileCount(); i++) {
                    byte[] f = gd.dataAt(i);
                    int fm = AnimFiles.frameFramemap(f), n = AnimFiles.frameTransforms(f);
                    FmUse u = fmUse.computeIfAbsent(fm, k -> new FmUse());
                    byte[] uf = user.fileRef(Arch.FRAMEMAPS, fm, 0);
                    if (newerBaseFrames.contains((g << 16) | gd.fileIdAt(i))) u.madeForBase++;
                    else if (uf != null && n > AnimFiles.framemapCount(uf)) u.overUserBy.add(n);
                    else u.classicFitsUser = true;
                }
            }
            for (int g : keyGroups) {
                GroupData gd = user.group(Arch.FRAMES, g);
                for (int i = 0; i < gd.fileCount(); i++) fmUse.computeIfAbsent(AnimFiles.keyframeFramemap(gd.dataAt(i)), k -> new FmUse()).skeletal = true;
            }
            user.release(Arch.FRAMES);
            base.release(Arch.FRAMES);
            ctx.report.count("frame-groups-needed", frameGroups.size());
            ctx.report.count("keyframe-groups-needed", keyGroups.size());
            ctx.report.count("framemaps-needed", fmUse.size());
        }

        /** framemap -> user-origin frames (group << 16 | file) of the needed groups that use it. */
        final TreeMap<Integer, List<Integer>> userFrames = new TreeMap<>();

        /**
         * Frames of the user's own groups that were made for BASE's version of their framemap: byte-identical to a
         * BASE frame on the same framemap id and to no VAN211 frame there, on a framemap whose USER (211) version
         * BASE changed. They keep BASE's framemap id ({@link #frameFramemapTo}); after the 211 copy of the framemap
         * they would play garbled (USER seq 10173: 15 frames exported for the 241 player skeleton).
         */
        void findNewerBaseFrames() {
            TreeSet<Integer> fms = new TreeSet<>();
            for (int fm : userFrames.keySet()) {
                byte[] uf = user.fileRef(Arch.FRAMEMAPS, fm, 0), bf = base.hasGroup(Arch.FRAMEMAPS, fm) ? base.fileRef(Arch.FRAMEMAPS, fm, 0) : null;
                if (uf != null && bf != null && !Arrays.equals(uf, bf) && !AnimFiles.classicCompatible(uf, bf)) fms.add(fm);
            }
            if (fms.isEmpty()) return;
            java.util.HashSet<java.nio.ByteBuffer> inBase = frameSet(base, fms), inVan = frameSet(van, fms);
            int n = 0;
            for (int fm : fms) for (int ref : userFrames.get(fm)) {
                byte[] f = user.fileRef(Arch.FRAMES, ref >>> 16, ref & 0xffff);
                java.nio.ByteBuffer k = java.nio.ByteBuffer.wrap(f);
                if (inBase.contains(k) && !inVan.contains(k)) { newerBaseFrames.add(ref); n++; }
            }
            user.release(Arch.FRAMES);
            if (n > 0) {
                ctx.report.count("user-frames-made-for-base-framemap", n);
                TreeMap<Integer, Integer> perGroup = new TreeMap<>();
                for (int r : newerBaseFrames) perGroup.merge(r >>> 16, 1, Integer::sum);
                for (var e : perGroup.entrySet())
                    ctx.report.add(Decision.NOTE, Kind.FRAME, e.getKey(), e.getValue() + " frames are byte-identical to BASE frames on BASE's framemap and to no 211 frame: made for the 241 skeleton, they keep BASE's framemap");
            }
        }

        /** Bytes of every frame of {@code c} (archive 0) whose framemap is one of {@code fms}. */
        static java.util.HashSet<java.nio.ByteBuffer> frameSet(CacheView c, java.util.Set<Integer> fms) {
            java.util.HashSet<java.nio.ByteBuffer> s = new java.util.HashSet<>();
            int n = 0;
            for (int g : c.groupIds(Arch.FRAMES)) {
                GroupData gd = c.group(Arch.FRAMES, g);
                if (gd != null) for (int i = 0; i < gd.fileCount(); i++) { byte[] f = gd.dataAt(i); if (f.length >= 2 && fms.contains(AnimFiles.frameFramemap(f))) s.add(java.nio.ByteBuffer.wrap(f.clone())); }
                if ((++n & 255) == 0) c.release(Arch.FRAMES);
            }
            c.release(Arch.FRAMES);
            return s;
        }

        // -------------------------------------------------------------- 3. framemaps (archive 1)

        void placeFramemaps() {
            IdMap.Range reserved = ctx.ids.range(Kind.FRAMEMAP);
            for (Map.Entry<Integer, FmUse> e : fmUse.entrySet()) {
                int f = e.getKey();
                FmUse use = e.getValue();
                byte[] uf = user.fileRef(Arch.FRAMEMAPS, f, 0);
                byte[] bf = base.hasGroup(Arch.FRAMEMAPS, f) ? base.fileRef(Arch.FRAMEMAPS, f, 0) : null;
                if (uf == null) {
                    ctx.report.error(Kind.FRAMEMAP.key, f, "framemap used by ported frames is missing in USER");
                    fmTo.put(f, f);
                    continue;
                }
                if (!use.overUserBy.isEmpty()) {
                    int need = use.overUserBy.last();
                    if (bf == null || AnimFiles.framemapCount(bf) < need)
                        ctx.report.error(Kind.FRAMEMAP.key, f, "frames with " + use.overUserBy + " transforms exceed USER's framemap (" + AnimFiles.framemapCount(uf) + " groups) and BASE's (" + (bf == null ? "absent" : AnimFiles.framemapCount(bf) + " groups") + ")");
                    else ctx.report.add(Decision.NOTE, Kind.FRAMEMAP, f, "frames with " + use.overUserBy + " transforms were made for the newer framemap: they keep BASE's framemap " + f);
                }
                if (!use.classicFitsUser && !use.skeletal) { fmTo.put(f, f); continue; } // only newer-base frames use it
                boolean identical = bf != null && Arrays.equals(uf, bf);
                boolean compatible = bf != null && !use.skeletal && AnimFiles.classicCompatible(uf, bf);
                if (identical || compatible) {
                    fmTo.put(f, f);
                    ctx.report.count(identical ? "framemaps-same-as-base" : "framemaps-compatible-with-base");
                    continue;
                }
                Origin o = Origin.ofGroup(Arch.FRAMEMAPS, f, user, van);
                GroupData g = user.group(Arch.FRAMEMAPS, f);
                if (bf == null && reserved.contains(f)) {
                    fmTo.put(f, f);
                    ctx.out.putGroup(Arch.FRAMEMAPS, g);
                    ctx.report.add(o == Origin.USER_CHANGED ? Decision.USER_CHANGED : Decision.USER_ADDED, Kind.FRAMEMAP, f, "already in the reserved framemap range");
                    continue;
                }
                String why = (o == Origin.VANILLA ? "vanilla 211 framemap" : "user framemap") + (bf == null ? "" : ", BASE's framemap " + f + " differs" + (use.skeletal ? "" : " incompatibly"))
                        + " (used by " + (use.skeletal ? "keyframes" : "ported frames") + ")";
                int to = ctx.ids.relocate(Kind.FRAMEMAP, f, why);
                fmTo.put(f, to);
                ctx.out.putGroup(Arch.FRAMEMAPS, withId(g, to));
                ctx.report.relocated(Kind.FRAMEMAP, f, to, why);
            }
        }

        /** Output framemap for a frame file of USER (frame ref group << 16 | file, framemap f, n transforms). */
        int frameFramemapTo(int ref, int f, int n) {
            if (newerBaseFrames.contains(ref)) return f; // made for BASE's framemap f
            byte[] uf = user.fileRef(Arch.FRAMEMAPS, f, 0);
            if (uf != null && n > AnimFiles.framemapCount(uf)) return f; // made for the newer base: BASE's framemap
            Integer to = fmTo.get(f);
            if (to == null) throw new IllegalStateException("framemap " + f + " was not placed");
            return to;
        }

        // -------------------------------------------------------------- 4. frame sets (archive 0)

        void placeFrames() {
            IdMap.Range reserved = ctx.ids.range(Kind.FRAME);
            int same = 0;
            for (int g : frameGroups) {
                GroupData gd = user.group(Arch.FRAMES, g);
                GroupData.Builder b = new GroupData.Builder(g).name(gd.name);
                for (int i = 0; i < gd.fileCount(); i++) {
                    byte[] f = gd.dataAt(i);
                    int fm = AnimFiles.frameFramemap(f), to = frameFramemapTo((g << 16) | gd.fileIdAt(i), fm, AnimFiles.frameTransforms(f));
                    b.put(gd.fileIdAt(i), gd.fileNameAt(i), to == fm ? f : AnimFiles.withFrameFramemap(f, to));
                }
                GroupData r = b.build();
                GroupData bg = base.hasGroup(Arch.FRAMES, g) ? base.group(Arch.FRAMES, g) : null;
                Origin o = Origin.ofGroup(Arch.FRAMES, g, user, van);
                if (bg != null && r.sameContent(bg)) { same++; continue; }
                if (bg == null && reserved.contains(g)) {
                    ctx.out.putGroup(Arch.FRAMES, r);
                    ctx.report.add(o == Origin.USER_CHANGED ? Decision.USER_CHANGED : Decision.USER_ADDED, Kind.FRAME, g, "already in the reserved frame range");
                    continue;
                }
                String why = (o == Origin.VANILLA ? "vanilla 211 frame set" : "user frame set") + (bg == null ? "" : ", BASE's group " + g + " holds other frames");
                int to = ctx.ids.relocate(Kind.FRAME, g, why);
                ctx.out.putGroup(Arch.FRAMES, withId(r, to));
                ctx.report.relocated(Kind.FRAME, g, to, why);
                if ((g & 63) == 0) { user.release(Arch.FRAMES); base.release(Arch.FRAMES); }
            }
            ctx.report.count("frame-groups-same-as-base", same);
            user.release(Arch.FRAMES);
            base.release(Arch.FRAMES);
        }

        // -------------------------------------------------------------- 5. keyframes (211 archive 0 -> 241 archive 22)

        void placeKeyframes() {
            for (int g : keyGroups) {
                GroupData gd = user.group(Arch.FRAMES, g);
                GroupData.Builder b = new GroupData.Builder(g).name(gd.name);
                for (int i = 0; i < gd.fileCount(); i++) {
                    byte[] k = gd.dataAt(i);
                    int fm = AnimFiles.keyframeFramemap(k);
                    Integer to = fmTo.get(fm);
                    if (to == null) throw new IllegalStateException("framemap " + fm + " of keyframe group " + g + " was not placed");
                    b.put(gd.fileIdAt(i), gd.fileNameAt(i), to == fm ? k : AnimFiles.withKeyframeFramemap(k, to));
                }
                GroupData r = b.build();
                GroupData bg = base.hasGroup(Arch.KEYFRAMES, g) ? base.group(Arch.KEYFRAMES, g) : null;
                if (bg != null && r.sameContent(bg)) {
                    ctx.ids.record(Kind.KEYFRAME, g, g, "211 archive-0 keyframe group equals BASE archive-22 group " + g);
                    ctx.report.add(Decision.SAME, Kind.KEYFRAME, g, "BASE archive 22 already holds these keyframes");
                    continue;
                }
                String why = "211 keyframes (archive 0 group " + g + ") moved to archive 22";
                int to = ctx.ids.relocate(Kind.KEYFRAME, g, why);
                ctx.out.putGroup(Arch.KEYFRAMES, withId(r, to));
                ctx.report.relocated(Kind.KEYFRAME, g, to, why);
            }
            user.release(Arch.FRAMES);
        }

        // -------------------------------------------------------------- 6. sequences (config 12)

        void writeSeqs() {
            for (SeqCase c : seqs.values()) {
                int id = c.id;
                switch (c.action) {
                    case BASE_VANILLA -> { if (c.detail != null) ctx.report.warn(Kind.SEQ.key, id, c.detail); }
                    case USER_REMOVED -> ctx.report.add(Decision.TAKEN_FROM_BASE, Kind.SEQ, id, "the user deleted this vanilla seq; BASE's record kept");
                    case TAKE_BASE -> ctx.report.add(Decision.TAKEN_FROM_BASE, Kind.SEQ, id, c.origin.name().toLowerCase() + ": " + c.detail);
                    case DROP -> ctx.report.add(Decision.DROPPED, Kind.SEQ, id, c.detail);
                    case PORT, PORT_RELOCATE -> {
                        byte[] out = rewrite(c);
                        int to = id;
                        if (c.action == Action.PORT_RELOCATE) {
                            String why = c.origin == Origin.VANILLA ? c.detail : "BASE seq " + id + " is another animation (" + c.detail + ")";
                            to = ctx.ids.relocate(Kind.SEQ, id, why);
                            if (c.origin == Origin.VANILLA) { ctx.ids.markVanilla(Kind.SEQ, id); ctx.report.count("vanilla-seqs-rigged-ported"); }
                            ctx.report.relocated(Kind.SEQ, id, to, why);
                        } else if (c.origin == Origin.VANILLA) {
                            ctx.report.add(Decision.NOTE, Kind.SEQ, id, c.detail);
                        } else {
                            ctx.report.add(c.origin == Origin.USER_CHANGED ? Decision.USER_CHANGED : Decision.USER_ADDED, Kind.SEQ, id, c.rec.skeletal() ? "skeletal" : null);
                        }
                        ctx.out.putConfig(Arch.CFG_SEQ, to, out);
                    }
                }
            }
        }

        byte[] rewrite(SeqCase c) {
            IdMap ids = ctx.ids;
            return SeqRec.rewrite(c.id, c.conv, ids::mapFrameRef,
                    k -> (ids.map(Kind.KEYFRAME, k >>> 16) << 16) | (k & 0xffff),
                    obj -> ids.map(Kind.OBJ, obj));
        }

        // -------------------------------------------------------------- 7. what was not carried over

        void reportUnused() {
            // user-added archive-0/1 content that no ported seq needs
            TreeMap<String, List<Integer>> dropped = new TreeMap<>();
            for (int g : user.groupIds(Arch.FRAMES)) {
                Origin o = Origin.ofGroup(Arch.FRAMES, g, user, van);
                if (!o.isUser() || frameGroups.contains(g) || keyGroups.contains(g)) continue;
                String tag = referencedBy.get(g);
                String why = tag == null ? "unreferenced" : tag.contains("keyframes") ? "211 keyframes of seqs taken from BASE (BASE has them in archive 22)" : "frames of seqs taken from BASE";
                dropped.computeIfAbsent(why, k -> new ArrayList<>()).add(g);
                if ((g & 255) == 0) { user.release(Arch.FRAMES); van.release(Arch.FRAMES); }
            }
            for (var e : dropped.entrySet()) {
                ctx.report.count("user-frame-groups-not-carried: " + e.getKey(), e.getValue().size());
                ctx.report.add(Decision.DROPPED, Kind.FRAME.key, Long.MIN_VALUE, null, e.getValue().size() + " user archive-0 groups not carried over (" + e.getKey() + "): " + ranges(e.getValue()));
            }
            List<Integer> fms = new ArrayList<>();
            for (int f : user.groupIds(Arch.FRAMEMAPS)) {
                if (fmUse.containsKey(f) || !Origin.ofGroup(Arch.FRAMEMAPS, f, user, van).isUser()) continue;
                byte[] bf = base.hasGroup(Arch.FRAMEMAPS, f) ? base.fileRef(Arch.FRAMEMAPS, f, 0) : null;
                if (bf != null && Arrays.equals(bf, user.fileRef(Arch.FRAMEMAPS, f, 0))) { ctx.report.count("user-framemaps-same-as-base"); continue; }
                fms.add(f);
            }
            if (!fms.isEmpty()) {
                ctx.report.count("user-framemaps-not-carried", fms.size());
                ctx.report.add(Decision.DROPPED, Kind.FRAMEMAP.key, Long.MIN_VALUE, null, fms.size() + " user framemaps used by no ported frame: " + ranges(fms));
            }
        }

        void release() { user.release(Arch.FRAMES); base.release(Arch.FRAMES); van.release(Arch.FRAMES); }

        static GroupData withId(GroupData g, int id) {
            GroupData.Builder b = g.toBuilder();
            b.id = id;
            b.version = 0;
            return b.build();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String mergeTag(String a, String b) {
        if (a.equals(b)) return a;
        TreeSet<String> s = new TreeSet<>(Arrays.asList(a.split("\\+")));
        s.addAll(Arrays.asList(b.split("\\+")));
        return String.join("+", s);
    }

    /** Parses "1,5-7,9" (empty: none). */
    static TreeSet<Integer> parseIds(String s) {
        TreeSet<Integer> r = new TreeSet<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int d = part.indexOf('-');
            if (d < 0) r.add(Integer.parseInt(part));
            else for (int i = Integer.parseInt(part.substring(0, d)), e = Integer.parseInt(part.substring(d + 1)); i <= e; i++) r.add(i);
        }
        return r;
    }

    /** "1-3 7 9-12" for an ascending list. */
    public static String ranges(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < ids.size()) {
            int s = ids.get(i), e = s;
            while (i + 1 < ids.size() && ids.get(i + 1) == e + 1) { e++; i++; }
            i++;
            if (sb.length() > 0) sb.append(' ');
            sb.append(s == e ? String.valueOf(s) : s + "-" + e);
        }
        return sb.toString();
    }

    /**
     * Fields (opcodes) whose values differ between two classic records, ignoring the frame refs themselves, opcodes
     * 211 cannot express (16 vertical offset, 18 debug name, 19) and sound weights (the 211 -> 241 conversion writes a
     * default weight). Empty string when none differ.
     */
    static String recordDiff(SeqRec u, SeqRec b) {
        TreeMap<Integer, String> cu = u.fields(), cb = b.fields();
        TreeSet<Integer> ops = new TreeSet<>(cu.keySet());
        ops.addAll(cb.keySet());
        ops.removeIf(op -> java.util.Objects.equals(cu.get(op), cb.get(op)));
        return ops.isEmpty() ? "" : "; record fields differ: ops " + ops;
    }
}
