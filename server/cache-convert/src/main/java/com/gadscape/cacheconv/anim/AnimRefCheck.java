package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Json;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.OutCache;
import com.gadscape.cacheconv.core.Report;
import com.gadscape.cacheconv.core.Step;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reference check of the animation data of an output cache ("anim-refcheck"); every failure is a report error.
 * <ol>
 *   <li><b>seq refs</b>: for every seq of the output, every frame ref (op 1/12) names an existing archive-0 file whose
 *       framemap exists in archive 1 and has at least as many transform groups as the frame uses (frames in groups BASE
 *       does not have are also decoded with RuneLite's FrameLoader on that framemap); every keyframe ref
 *       (op 13) names an existing archive-22 file whose framemap exists and carries bone data; hand objs exist.</li>
 *   <li><b>BASE intact</b>: every BASE seq record and every BASE group of archives 0, 1 and 22 is unchanged.</li>
 *   <li><b>user seqs carried over</b>: every user seq (USER vs VAN211) is either BASE's record at the same id, or a
 *       record (at the id the id map gives) with the same fields as the converted USER record whose frames/keyframes
 *       are the same frames on an equivalent framemap as in USER.</li>
 *   <li><b>regression lists</b> (resources anim/): the 549 skeletal seqs of BOSS-X1 resolve in archive 22, and the 277
 *       NPCs of BOSS-X1 have stand/walk seqs that exist and resolve, both in the output's NPC record and in the USER
 *       NPC record with its seq ids mapped through the id map.</li>
 *   <li>every NPC (movement seqs: stand, walk, turns, run, crawl) and spotanim (op 2) of the output, and every
 *       user-added/changed NPC and spotanim of USER with its seq ids mapped through the id map: the seqs exist and
 *       resolve (refs that were already dead in USER are counted apart).</li>
 *   <li><b>skeletons</b>: every user-added/changed NPC and spotanim whose output record plays the user's own seqs
 *       (the USER seq ids mapped through the id map; rev-225 imports, which take BASE's record, excluded): each output
 *       seq animates the skeleton the USER seq animated (same framemap, or a 211 framemap whose transform groups are a
 *       prefix of the output's; a vanilla seq BASE kept on the same framemap id, which Jagex updated in place, is
 *       counted apart). This catches vanilla seq ids Jagex reused for another skeleton (gnome walk 189 is a player
 *       emote in 241), which the reference checks above cannot see.</li>
 *   <li>vanilla seq ports (id map {@code _vanilla}) play the vanilla 211 animation.</li>
 * </ol>
 */
public final class AnimRefCheck implements Step {
    @Override public String name() { return "anim-refcheck"; }

    @Override public void run(Ctx ctx) throws IOException {
        Checker c = new Checker(ctx.out, ctx.base, ctx.user, ctx.van211, ctx.ids);
        c.imported = ctx::isRev225Import;
        Result r = c.run();
        r.toReport(ctx.report);
        Path w = ctx.work("anim");
        Json.write(w.resolve("refcheck.json"), r.toJson());
        r.print();
    }

    /**
     * Runs every check on an output view (for the verify command). {@code user}/{@code van211} may be null (the user
     * seq and user NPC/spotanim checks are then skipped); {@code ids} is the build's id map.
     */
    public static Result check(CacheView out, CacheView base, CacheView user, CacheView van211, IdMap ids) {
        return new Checker(out, base, user, van211, ids).run();
    }

    /** --check-only mode of {@link AnimMain}: checks an existing output directory with its idmap.json. */
    static int standalone(Options o, Path reportDir) throws IOException {
        if (o.out == null) throw new IllegalArgumentException("--out DIR is required");
        Path idmap = Path.of(o.get("idmap", o.out.resolve("idmap.json").toString()));
        IdMap ids = Files.isRegularFile(idmap) ? IdMap.load(idmap) : new IdMap();
        if (!Files.isRegularFile(idmap)) System.out.println("no " + idmap + ": checking without relocations");
        try (JagCache out = JagCache.open("OUT", o.out); JagCache base = JagCache.open("BASE", o.base);
             JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211)) {
            Checker c = new Checker(out, base, user, van, ids);
            com.gadscape.cacheconv.core.Rev225 t = com.gadscape.cacheconv.core.Rev225.load(o);
            c.imported = (g, id) -> t.isImport(g, id, user.configRef(g, id));
            Result r = c.run();
            Json.write(reportDir.resolve("refcheck.json"), r.toJson());
            r.print();
            return r.bad() == 0 ? 0 : 1;
        }
    }

    // ------------------------------------------------------------------ result

    public static final class Result {
        /** category -> {ok, bad}; examples of bad refs per category. */
        final TreeMap<String, long[]> stats = new TreeMap<>();
        final TreeMap<String, List<String>> examples = new TreeMap<>();
        final TreeMap<String, Long> info = new TreeMap<>();

        void ok(String cat) { stats.computeIfAbsent(cat, k -> new long[2])[0]++; }
        void bad(String cat, String what) {
            stats.computeIfAbsent(cat, k -> new long[2])[1]++;
            List<String> l = examples.computeIfAbsent(cat, k -> new ArrayList<>());
            if (l.size() < 12) l.add(what);
        }
        void check(String cat, boolean ok, String what) { if (ok) ok(cat); else bad(cat, what); }
        void info(String k, long v) { info.merge(k, v, Long::sum); }
        void example(String k, String what) { List<String> l = examples.computeIfAbsent(k, x -> new ArrayList<>()); if (l.size() < 12) l.add(what); }

        public long bad() { long n = 0; for (long[] v : stats.values()) n += v[1]; return n; }
        public long okCount(String cat) { long[] v = stats.get(cat); return v == null ? 0 : v[0]; }
        public long badCount(String cat) { long[] v = stats.get(cat); return v == null ? 0 : v[1]; }

        /** Adds the counts to the current step of the report, and one error per failing check. */
        public void toReport(Report rep) {
            for (var e : stats.entrySet()) {
                rep.count(e.getKey() + " ok", e.getValue()[0]);
                if (e.getValue()[1] > 0) {
                    rep.count(e.getKey() + " BAD", e.getValue()[1]);
                    rep.error("anim-refcheck " + e.getKey() + ": " + e.getValue()[1] + " bad, e.g. " + examples.get(e.getKey()));
                }
            }
            for (var e : info.entrySet()) rep.count(e.getKey(), e.getValue());
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> s = new LinkedHashMap<>();
            for (var e : stats.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ok", e.getValue()[0]);
                row.put("bad", e.getValue()[1]);
                if (examples.containsKey(e.getKey())) row.put("examples", examples.get(e.getKey()));
                s.put(e.getKey(), row);
            }
            m.put("checks", s);
            Map<String, Object> in = new LinkedHashMap<>();
            for (var e : info.entrySet()) {
                if (!examples.containsKey(e.getKey())) { in.put(e.getKey(), e.getValue()); continue; }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("count", e.getValue());
                row.put("examples", examples.get(e.getKey()));
                in.put(e.getKey(), row);
            }
            m.put("info", in);
            m.put("bad", bad());
            return m;
        }

        public void print() {
            System.out.println("anim-refcheck:");
            for (var e : stats.entrySet())
                System.out.printf("  %-58s ok %8d  BAD %6d%s%n", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[1] > 0 ? "  e.g. " + examples.get(e.getKey()) : "");
            for (var e : info.entrySet()) System.out.printf("  %-58s %d%s%n", e.getKey(), e.getValue(), examples.containsKey(e.getKey()) ? "  e.g. " + examples.get(e.getKey()) : "");
            System.out.println(bad() == 0 ? "  ALL OK" : "  " + bad() + " BAD");
        }
    }

    // ------------------------------------------------------------------ checker

    static final class Checker {
        final CacheView out, base, user, van;
        final IdMap ids;
        final Result r = new Result();
        /** Seq ids (output) with a bad reference. */
        final TreeSet<Integer> badSeqs = new TreeSet<>();
        final TreeMap<Integer, SeqRec> outSeqs = new TreeMap<>();
        /** (config group, id) of USER entries that are rev-225 imports (BASE's record is used), or null. */
        java.util.function.BiPredicate<Integer, Integer> imported;
        /** framemap id -> bytes of every BASE frame on it (for frames the anim step kept on BASE's framemap). */
        final TreeMap<Integer, java.util.HashSet<java.nio.ByteBuffer>> baseFrames = new TreeMap<>();

        Checker(CacheView out, CacheView base, CacheView user, CacheView van, IdMap ids) {
            this.out = out; this.base = base; this.user = user; this.van = van; this.ids = ids;
        }

        Result run() {
            seqRefs();
            baseIntact();
            if (user != null && van != null) userSeqs();
            regressionLists();
            npcs();
            if (user != null && van != null) skeletons();
            return r;
        }

        // 1. every seq's refs
        void seqRefs() {
            TreeMap<Integer, TreeMap<Integer, Integer>> frames = new TreeMap<>(), keys = new TreeMap<>(); // group -> file -> first seq
            int[] objs = out.configIds(Arch.CFG_OBJ);
            for (int id : out.configIds(Arch.CFG_SEQ)) {
                SeqRec s;
                try { s = SeqRec.parse(id, out.configRef(Arch.CFG_SEQ, id)); }
                catch (RuntimeException e) { r.bad("seq records parse (241 layout)", id + ": " + e.getMessage()); badSeqs.add(id); continue; }
                r.ok("seq records parse (241 layout)");
                outSeqs.put(id, s);
                for (int ref : s.allFrameRefs()) if (SeqRec.isNullRef(ref)) r.info("seq frame placeholders (file 65535, never loaded)", 1);
                for (int ref : s.realFrameRefs()) frames.computeIfAbsent(ref >>> 16, k -> new TreeMap<>()).putIfAbsent(ref & 0xffff, id);
                if (s.skeletal()) keys.computeIfAbsent(s.keyframe >>> 16, k -> new TreeMap<>()).putIfAbsent(s.keyframe & 0xffff, id);
                for (int obj : new int[]{s.leftHand, s.rightHand})
                    if (obj >= 0) r.check("seq hand objs (op 6/7) exist", Arrays.binarySearch(objs, obj) >= 0, "seq " + id + " obj " + obj);
                if (s.skeletal()) r.info("seqs skeletal", 1); else r.info("seqs classic", 1);
            }
            out.release(Arch.CONFIGS);
            // resolve per group, each group read once
            TreeMap<Integer, Boolean> fmOk = new TreeMap<>();
            TreeMap<Integer, net.runelite.cache.definitions.FramemapDefinition> fmDefs = new TreeMap<>();
            TreeSet<Long> badRefs = new TreeSet<>();
            int n = 0;
            for (var e : frames.entrySet()) {
                int g = e.getKey();
                GroupData gd = out.hasGroup(Arch.FRAMES, g) ? out.group(Arch.FRAMES, g) : null;
                for (var f : e.getValue().entrySet()) {
                    String what = "seq " + f.getValue() + " -> " + g + ":" + f.getKey();
                    byte[] d = gd == null ? null : gd.dataRef(f.getKey());
                    if (d == null) { r.bad("seq frame refs resolve (archive 0 file)", what); badRefs.add((long) g << 16 | f.getKey()); continue; }
                    r.ok("seq frame refs resolve (archive 0 file)");
                    int fm = AnimFiles.frameFramemap(d), t = AnimFiles.frameTransforms(d);
                    byte[] fmb = out.fileRef(Arch.FRAMEMAPS, fm, 0);
                    if (fmb == null) { r.bad("frame framemap refs resolve (archive 1)", what + " framemap " + fm); badRefs.add((long) g << 16 | f.getKey()); continue; }
                    r.ok("frame framemap refs resolve (archive 1)");
                    boolean fits = t <= AnimFiles.framemapCount(fmb);
                    r.check("frame transforms fit their framemap", fits, what + " uses " + t + " groups of framemap " + fm + " (" + AnimFiles.framemapCount(fmb) + ")");
                    if (!fits) badRefs.add((long) g << 16 | f.getKey());
                    fmOk.putIfAbsent(fm, true);
                    if (fits && !base.hasGroup(Arch.FRAMES, g)) {
                        // frames this build added: decode them like the client does (RuneLite's loaders)
                        try {
                            var def = fmDefs.computeIfAbsent(fm, k -> new net.runelite.cache.definitions.loaders.FramemapLoader().load(k, fmb));
                            new net.runelite.cache.definitions.loaders.FrameLoader().load(def, f.getKey(), d);
                            r.ok("added frames decode on their framemap (RuneLite FrameLoader)");
                        } catch (RuntimeException ex) {
                            r.bad("added frames decode on their framemap (RuneLite FrameLoader)", what + ": " + ex);
                            badRefs.add((long) g << 16 | f.getKey());
                        }
                    }
                }
                if (++n % 128 == 0) out.release(Arch.FRAMES);
            }
            out.release(Arch.FRAMES);
            r.info("frame groups referenced by seqs", frames.size());
            r.info("framemaps referenced by frames", fmOk.size());
            for (var e : keys.entrySet()) {
                int g = e.getKey();
                GroupData gd = out.hasGroup(Arch.KEYFRAMES, g) ? out.group(Arch.KEYFRAMES, g) : null;
                for (var f : e.getValue().entrySet()) {
                    String what = "seq " + f.getValue() + " -> archive 22 " + g + ":" + f.getKey();
                    byte[] d = gd == null ? null : gd.dataRef(f.getKey());
                    if (d == null) { r.bad("seq keyframe refs resolve (archive 22 file)", what); badRefs.add(-(((long) g << 16) | f.getKey()) - 1); continue; }
                    r.ok("seq keyframe refs resolve (archive 22 file)");
                    int fm = AnimFiles.keyframeFramemap(d);
                    byte[] fmb = out.fileRef(Arch.FRAMEMAPS, fm, 0);
                    boolean ok = fmb != null && AnimFiles.framemap(fmb).skeletal();
                    r.check("keyframe framemap refs resolve (archive 1, with bones)", ok, what + " framemap " + fm + (fmb == null ? " missing" : " has no bones"));
                    if (!ok) badRefs.add(-(((long) g << 16) | f.getKey()) - 1);
                }
            }
            out.release(Arch.KEYFRAMES);
            r.info("keyframe groups referenced by seqs", keys.size());
            // seqs with any bad ref
            for (var e : outSeqs.entrySet()) {
                SeqRec s = e.getValue();
                boolean bad = false;
                for (int ref : s.realFrameRefs()) if (badRefs.contains((long) (ref >>> 16) << 16 | (ref & 0xffff))) bad = true;
                if (s.skeletal() && badRefs.contains(-(((long) (s.keyframe >>> 16) << 16) | (s.keyframe & 0xffff)) - 1)) bad = true;
                if (bad) badSeqs.add(e.getKey());
                r.check("seqs whose refs all resolve", !bad, "seq " + e.getKey());
            }
        }

        // 2. BASE groups/records untouched
        void baseIntact() {
            for (int id : base.configIds(Arch.CFG_SEQ))
                r.check("BASE seq records unchanged", Arrays.equals(base.configRef(Arch.CFG_SEQ, id), out.configRef(Arch.CFG_SEQ, id)), "seq " + id);
            for (int a : new int[]{Arch.FRAMES, Arch.FRAMEMAPS, Arch.KEYFRAMES}) {
                String cat = "BASE " + Arch.name(a) + " groups unchanged";
                for (int g : base.groupIds(a)) {
                    if (!out.hasGroup(a, g)) { r.bad(cat, "group " + g + " missing"); continue; }
                    int bc = crc(base, a, g), oc = crc(out, a, g);
                    if (bc != 0 && oc != 0) { r.check(cat, bc == oc, "group " + g); continue; }
                    r.check(cat, base.group(a, g).sameContent(out.group(a, g)), "group " + g);
                    if ((g & 127) == 0) { base.release(a); out.release(a); }
                }
                base.release(a); out.release(a);
            }
        }

        /** Packed CRC from the reference table when the group is on disk unchanged, else 0 (compare content). */
        static int crc(CacheView v, int a, int g) {
            if (v instanceof JagCache j) return j.groupCrc(a, g);
            if (v instanceof OutCache o) return o.isPending(a, g) ? 0 : o.committed().groupCrc(a, g);
            return 0;
        }

        // 3. user seqs carried over with the same content
        void userSeqs() {
            TreeSet<Integer> idsAll = new TreeSet<>();
            for (int id : user.configIds(Arch.CFG_SEQ)) idsAll.add(id);
            for (int id : idsAll) {
                Origin o = Origin.ofConfig(Arch.CFG_SEQ, id, user, van);
                byte[] bb = base.configRef(Arch.CFG_SEQ, id);
                if (o == Origin.VANILLA && bb != null && !ids.isVanillaPort(Kind.SEQ, id)) continue;
                int to = ids.map(Kind.SEQ, id);
                byte[] ob = out.configRef(Arch.CFG_SEQ, to);
                String what = "seq " + id + (to != id ? " (-> " + to + ")" : "");
                if (ob == null) {
                    // dropped by the anim step: only allowed for records that were broken in USER
                    SeqRec u = SeqRec.parse(id, ConfigConv.seq(id, user.configRef(Arch.CFG_SEQ, id)));
                    boolean broken = false;
                    for (int ref : u.realFrameRefs()) if (!user.hasFile(Arch.FRAMES, ref >>> 16, ref & 0xffff)) broken = true;
                    if (u.skeletal() && !user.hasFile(Arch.FRAMES, u.keyframe >>> 16, u.keyframe & 0xffff)) broken = true;
                    r.check("user seqs present in the output", broken, what + " missing");
                    if (broken) r.info("user seqs dropped (frames missing in USER)", 1);
                    continue;
                }
                r.ok("user seqs present in the output");
                if (to == id && bb != null && Arrays.equals(ob, bb)) { r.info("user seqs served by BASE's record", 1); continue; }
                SeqRec u = SeqRec.parse(id, ConfigConv.seq(id, user.configRef(Arch.CFG_SEQ, id)));
                SeqRec s = SeqRec.parse(to, ob);
                String why = sameAnimation(u, s);
                r.check("ported user seqs equal their USER animation", why == null, what + ": " + why);
                r.info(to != id ? "user seqs ported (relocated)" : "user seqs ported (same id)", 1);
            }
            user.release(Arch.FRAMES); out.release(Arch.FRAMES); out.release(Arch.KEYFRAMES);
        }

        /** Null when the output record s plays the same animation as the USER record u, else why not. */
        String sameAnimation(SeqRec u, SeqRec s) {
            TreeMap<Integer, String> fu = u.fields(), fs = s.fields();
            // hand objs may be relocated: compare mapped
            if (u.leftHand >= 0) fu.put(6, String.valueOf(ids.map(Kind.OBJ, u.leftHand) + 512));
            if (u.rightHand >= 0) fu.put(7, String.valueOf(ids.map(Kind.OBJ, u.rightHand) + 512));
            if (!fu.equals(fs)) return "fields differ " + fu + " vs " + fs;
            int[] ur = u.allFrameRefs(), sr = s.allFrameRefs();
            if (ur.length != sr.length) return "frame count";
            for (int i = 0; i < ur.length; i++) {
                if (SeqRec.isNullRef(ur[i]) || SeqRec.isNullRef(sr[i])) { if (ur[i] != sr[i]) return "frame " + i + " placeholder changed"; continue; }
                byte[] x = user.fileRef(Arch.FRAMES, ur[i] >>> 16, ur[i] & 0xffff), y = out.fileRef(Arch.FRAMES, sr[i] >>> 16, sr[i] & 0xffff);
                if (!AnimFiles.sameFrameData(x, y)) return "frame " + i + " data differs (" + (ur[i] >>> 16) + ":" + (ur[i] & 0xffff) + " vs " + (sr[i] >>> 16) + ":" + (sr[i] & 0xffff) + ")";
                int fx = AnimFiles.frameFramemap(x), fy = AnimFiles.frameFramemap(y);
                byte[] mx = user.fileRef(Arch.FRAMEMAPS, fx, 0), my = out.fileRef(Arch.FRAMEMAPS, fy, 0);
                boolean newer = mx != null && AnimFiles.frameTransforms(x) > AnimFiles.framemapCount(mx) && fx == fy;
                // a frame made for BASE's version of the framemap: byte-identical to a BASE frame on it
                if (!newer && fx == fy && Arrays.equals(my, base.fileRef(Arch.FRAMEMAPS, fy, 0)))
                    newer = baseFrames.computeIfAbsent(fy, k -> AnimStep.Port.frameSet(base, java.util.Set.of(k))).contains(java.nio.ByteBuffer.wrap(x));
                if (!newer && !AnimFiles.classicCompatible(mx, my)) return "frame " + i + " framemap " + fx + " -> " + fy + " not equivalent";
            }
            if (u.skeletal() != s.skeletal()) return "skeletal flag";
            if (u.skeletal()) {
                byte[] x = user.fileRef(Arch.FRAMES, u.keyframe >>> 16, u.keyframe & 0xffff), y = out.fileRef(Arch.KEYFRAMES, s.keyframe >>> 16, s.keyframe & 0xffff);
                if (!AnimFiles.sameKeyframeData(x, y)) return "keyframes differ";
                if (!Arrays.equals(user.fileRef(Arch.FRAMEMAPS, AnimFiles.keyframeFramemap(x), 0), out.fileRef(Arch.FRAMEMAPS, AnimFiles.keyframeFramemap(y), 0)))
                    return "keyframe framemap differs";
            }
            return null;
        }

        // 4. BOSS-X1 lists
        void regressionLists() {
            List<String> seqList = resource("anim/skeletal-seqs-boss-x1.txt");
            if (seqList == null) { r.info("regression list skeletal-seqs-boss-x1.txt missing", 1); }
            else for (String line : seqList) {
                int id = Integer.parseInt(line.trim());
                int to = ids.map(Kind.SEQ, id);
                SeqRec s = outSeqs.get(to);
                r.check("BOSS-X1 skeletal seqs resolve in archive 22", s != null && s.skeletal() && !badSeqs.contains(to), "seq " + id + (s == null ? " missing" : !s.skeletal() ? " not skeletal" : " has a bad ref"));
            }
            List<String> npcList = resource("anim/npcs-skeletal-standwalk.tsv");
            if (npcList == null) { r.info("regression list npcs-skeletal-standwalk.tsv missing", 1); return; }
            for (String line : npcList) {
                String[] f = line.split("\t", -1);
                int npc = Integer.parseInt(f[0]);
                String name = f[1].isEmpty() ? "npc " + npc : f[1] + " (" + npc + ")";
                byte[] ob = out.configRef(Arch.CFG_NPC, npc);
                if (ob == null) { r.bad("BOSS-X1 NPCs: stand/walk resolve (output record)", name + " missing"); continue; }
                NpcAnims a = NpcAnims.parse(npc, ob);
                r.check("BOSS-X1 NPCs: stand/walk resolve (output record)", a != null && resolves(a.stand) && resolves(a.walk), name + (a == null ? " unparseable" : " stand " + a.stand + " walk " + a.walk));
                if (user != null) {
                    byte[] ub = user.configRef(Arch.CFG_NPC, npc);
                    if (ub == null) continue;
                    NpcAnims b = NpcAnims.parse(npc, ConfigConv.npc(npc, ub));
                    int st = b == null || b.stand < 0 ? -1 : ids.map(Kind.SEQ, b.stand), wk = b == null || b.walk < 0 ? -1 : ids.map(Kind.SEQ, b.walk);
                    r.check("BOSS-X1 NPCs: stand/walk resolve (USER record, ids mapped)", b != null && resolves(st) && resolves(wk), name + " stand " + st + " walk " + wk);
                }
            }
        }

        boolean resolves(int seq) { return seq == -1 || (outSeqs.containsKey(seq) && !badSeqs.contains(seq)); }

        // 5. NPC and spotanim seq refs: every output record, and every user record (USER vs VAN211) with ids mapped
        void npcs() {
            for (int id : out.configIds(Arch.CFG_NPC)) {
                NpcAnims a = NpcAnims.parse(id, out.configRef(Arch.CFG_NPC, id));
                if (a == null) { r.info("npc records not parsed by the anim check", 1); continue; }
                for (int s : a.all()) {
                    if (s == -1) continue;
                    String cat = "NPC movement seqs exist and resolve (all output NPCs)";
                    if (!resolves(s) && !outSeqs.containsKey(s) && deadInUser(s)) {
                        // a user NPC naming a user seq whose frames USER does not have (it never played on 211 either;
                        // the anim step drops such seqs): counted apart, not a conversion error
                        String k = cat + ": refs to seqs already dead in USER (frames missing there; dropped)";
                        r.info(k, 1);
                        r.example(k, "npc " + id + " seq " + s);
                        continue;
                    }
                    r.check(cat, resolves(s), "npc " + id + " seq " + s + (outSeqs.containsKey(s) ? " (bad ref)" : " (missing)"));
                }
            }
            for (int id : out.configIds(Arch.CFG_SPOT)) {
                int s = spotSeq(out.configRef(Arch.CFG_SPOT, id));
                if (s == -2) { r.info("spotanim records not parsed by the anim check", 1); continue; }
                if (s != -1) r.check("spotanim seqs exist and resolve (all output spotanims)", resolves(s), "spotanim " + id + " seq " + s);
            }
            if (user == null || van == null) return;
            // user records: whatever area writes them, their seq ids go through the id map
            for (int id : user.configIds(Arch.CFG_NPC)) {
                if (!Origin.ofConfig(Arch.CFG_NPC, id, user, van).isUser()) continue;
                NpcAnims a = NpcAnims.parse(id, ConfigConv.npc(id, user.configRef(Arch.CFG_NPC, id)));
                if (a == null) { r.info("user npc records not parsed by the anim check", 1); continue; }
                for (int s : a.all()) userRef("user NPCs (added/changed): movement seqs resolve with ids mapped", "npc " + id, s);
            }
            for (int id : user.configIds(Arch.CFG_SPOT)) {
                if (!Origin.ofConfig(Arch.CFG_SPOT, id, user, van).isUser()) continue;
                int s = spotSeq(ConfigConv.spot(id, user.configRef(Arch.CFG_SPOT, id)));
                if (s == -2) { r.info("user spotanim records not parsed by the anim check", 1); continue; }
                userRef("user spotanims (added/changed): seq resolves with ids mapped", "spotanim " + id, s);
            }
            out.release(Arch.CONFIGS);
        }

        // 6. user NPC / spotanim seqs animate the skeleton they animated in USER
        void skeletons() {
            String cat = "user NPCs/spotanims: seqs animate the skeleton they had in USER";
            TreeMap<Long, String> memo = new TreeMap<>();
            for (int id : user.configIds(Arch.CFG_NPC)) {
                if (!Origin.ofConfig(Arch.CFG_NPC, id, user, van).isUser() || (imported != null && imported.test(Arch.CFG_NPC, id))) continue;
                NpcAnims a = NpcAnims.parse(id, ConfigConv.npc(id, user.configRef(Arch.CFG_NPC, id)));
                byte[] ob = out.configRef(Arch.CFG_NPC, ids.map(Kind.NPC, id));
                NpcAnims o = ob == null ? null : NpcAnims.parse(id, ob);
                if (a == null || o == null) continue;
                int[] us = a.all(), os = o.all();
                if (us.length != os.length) continue;
                boolean users = true;
                for (int i = 0; i < us.length; i++) if (os[i] != (us[i] < 0 ? us[i] : ids.map(Kind.SEQ, us[i]))) users = false;
                if (!users) { r.info(cat + ": NPC records with BASE's seqs (merged), not compared", 1); continue; }
                for (int i = 0; i < us.length; i++) if (us[i] >= 0) sameSkeleton(cat, "npc " + id, us[i], os[i], memo);
            }
            for (int id : user.configIds(Arch.CFG_SPOT)) {
                if (!Origin.ofConfig(Arch.CFG_SPOT, id, user, van).isUser() || (imported != null && imported.test(Arch.CFG_SPOT, id))) continue;
                int su = spotSeq(ConfigConv.spot(id, user.configRef(Arch.CFG_SPOT, id)));
                byte[] ob = out.configRef(Arch.CFG_SPOT, ids.map(Kind.SPOT, id));
                int so = ob == null ? -2 : spotSeq(ob);
                if (su < 0 || so < 0 || so != ids.map(Kind.SEQ, su)) continue;
                sameSkeleton(cat, "spotanim " + id, su, so, memo);
            }
            user.release(Arch.FRAMES); out.release(Arch.FRAMES); out.release(Arch.KEYFRAMES);
        }

        void sameSkeleton(String cat, String owner, int su, int so, TreeMap<Long, String> memo) {
            String why = memo.computeIfAbsent(((long) su << 32) | (so & 0xffffffffL), k -> skeletonDiff(su, so));
            if (why == null) { r.ok(cat); return; }
            if (why.startsWith("=")) { r.info(cat + ": BASE's record of a vanilla seq on the same framemap id, which Jagex updated in place (accepted)", 1); return; }
            if (why.startsWith("?")) { r.info(cat + ": not comparable (" + why.substring(1) + ")", 1); return; }
            r.bad(cat, owner + " seq " + su + (so != su ? " -> " + so : "") + ": " + why);
        }

        /** Null when output seq so animates the skeleton USER seq su animated; "?..." when one of them cannot be read. */
        String skeletonDiff(int su, int so) {
            byte[] ub = user.configRef(Arch.CFG_SEQ, su), ob = out.configRef(Arch.CFG_SEQ, so);
            if (ub == null || ob == null) return "?seq missing";
            SeqRec u = SeqRec.parse(su, ConfigConv.seq(su, ub)), s = SeqRec.parse(so, ob);
            byte[] uf = framemapOf(user, u, true), of = framemapOf(out, s, false);
            if (uf == null || of == null) return "?frames missing";
            if (Arrays.equals(uf, of)) return null;
            if (!u.skeletal() && !s.skeletal() && AnimFiles.classicCompatible(uf, of)) return null;
            // a vanilla seq BASE kept (not ported: AnimStep ports only those whose framemap id changed) on the same
            // framemap id: Jagex updated that skeleton in place (the 241 player skeleton 0) with its animations
            if (su == so && u.skeletal() == s.skeletal() && Arrays.equals(ob, base.configRef(Arch.CFG_SEQ, so))
                    && Origin.ofConfig(Arch.CFG_SEQ, su, user, van) == Origin.VANILLA && framemapIdOf(user, u, true) == framemapIdOf(out, s, false)) return "=same framemap id";
            if (!u.skeletal() && !s.skeletal() && u.realFrameRefs().length > 0) {
                // frames made for BASE's version of their framemap keep BASE's (the anim step's newer-base rule)
                int r0 = u.realFrameRefs()[0];
                byte[] x = user.fileRef(Arch.FRAMES, r0 >>> 16, r0 & 0xffff);
                int fm = AnimFiles.frameFramemap(x);
                if (Arrays.equals(of, base.fileRef(Arch.FRAMEMAPS, fm, 0))
                        && baseFrames.computeIfAbsent(fm, k -> AnimStep.Port.frameSet(base, java.util.Set.of(k))).contains(java.nio.ByteBuffer.wrap(x))) return null;
            }
            return (u.skeletal() ? "skeletal" : "classic") + " in USER, " + (s.skeletal() ? "skeletal" : "classic") + " on another framemap in the output";
        }

        /** The framemap id a seq animates (as {@link #framemapOf}), or -1. */
        static int framemapIdOf(CacheView c, SeqRec s, boolean is211) {
            if (s.skeletal()) {
                byte[] k = c.fileRef(is211 ? Arch.FRAMES : Arch.KEYFRAMES, s.keyframe >>> 16, s.keyframe & 0xffff);
                return k == null ? -1 : AnimFiles.keyframeFramemap(k);
            }
            int[] refs = s.realFrameRefs();
            if (refs.length == 0) return -1;
            byte[] f = c.fileRef(Arch.FRAMES, refs[0] >>> 16, refs[0] & 0xffff);
            return f == null ? -1 : AnimFiles.frameFramemap(f);
        }

        /** The framemap bytes a seq animates (first real frame, or the keyframes; 211 keyframes live in archive 0). */
        static byte[] framemapOf(CacheView c, SeqRec s, boolean is211) {
            if (s.skeletal()) {
                byte[] k = c.fileRef(is211 ? Arch.FRAMES : Arch.KEYFRAMES, s.keyframe >>> 16, s.keyframe & 0xffff);
                return k == null ? null : c.fileRef(Arch.FRAMEMAPS, AnimFiles.keyframeFramemap(k), 0);
            }
            int[] refs = s.realFrameRefs();
            if (refs.length == 0) return null;
            byte[] f = c.fileRef(Arch.FRAMES, refs[0] >>> 16, refs[0] & 0xffff);
            return f == null ? null : c.fileRef(Arch.FRAMEMAPS, AnimFiles.frameFramemap(f), 0);
        }

        /** A user record's seq ref: fine when it resolves after mapping; a ref that was already dead in USER is counted apart. */
        void userRef(String cat, String owner, int s) {
            if (s == -1) return;
            int to = ids.map(Kind.SEQ, s);
            if (resolves(to)) { r.ok(cat); return; }
            if (!brokenInUser(s)) { r.bad(cat, owner + " seq " + s + (to != s ? " -> " + to : "")); return; }
            String k = cat + ": refs already dead in USER (seq missing or its frames missing)";
            r.info(k, 1);
            r.example(k, owner + " seq " + s + (user.configRef(Arch.CFG_SEQ, s) == null ? " (no such seq in USER)" : " (frames missing in USER)"));
        }

        /** USER has the seq (not relocated) but its frames/keyframes are missing there. */
        boolean deadInUser(int s) {
            return user != null && ids.map(Kind.SEQ, s) == s && user.configRef(Arch.CFG_SEQ, s) != null && brokenInUser(s);
        }

        /** The seq does not exist in USER, or its frames/keyframes are missing there. */
        boolean brokenInUser(int s) {
            byte[] b = user.configRef(Arch.CFG_SEQ, s);
            if (b == null) return true;
            SeqRec u = SeqRec.parse(s, ConfigConv.seq(s, b));
            for (int ref : u.realFrameRefs()) if (!user.hasFile(Arch.FRAMES, ref >>> 16, ref & 0xffff)) return true;
            return u.skeletal() && !user.hasFile(Arch.FRAMES, u.keyframe >>> 16, u.keyframe & 0xffff);
        }

        /** Seq of a 241 spotanim record (-1 none, -2 unknown opcode). */
        static int spotSeq(byte[] b) {
            Buf in = new Buf(b);
            int seq = -1;
            try {
                while (true) {
                    int op = in.u8();
                    if (op == 0) return seq;
                    switch (op) {
                        case 2 -> { int v = in.u16(); seq = v == 65535 ? -1 : v; }
                        case 1, 4, 5, 6, 42 -> in.skip(2);
                        case 3 -> in.skip(4);
                        case 7, 8 -> in.skip(1);
                        case 9 -> in.skipStr();
                        case 10 -> { }
                        case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                        default -> { return -2; }
                    }
                }
            } catch (RuntimeException e) { return -2; }
        }

        static List<String> resource(String path) {
            InputStream in = AnimRefCheck.class.getClassLoader().getResourceAsStream(path);
            if (in == null) return null;
            List<String> l = new ArrayList<>();
            try (BufferedReader rd = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String s; (s = rd.readLine()) != null; ) if (!s.isBlank() && !s.startsWith("#")) l.add(s);
            } catch (IOException e) { throw new IllegalStateException(e); }
            return l;
        }
    }

    /** Movement seqs of a build-241 NPC record (stand 13, walk 14/17, turns 15/16/17, run 114/115, crawl 116/117). */
    record NpcAnims(int stand, int walk, int[] other) {
        int[] all() { int[] a = Arrays.copyOf(other, other.length + 2); a[other.length] = stand; a[other.length + 1] = walk; return a; }

        static int n16(int v) { return v == 65535 ? -1 : v; }

        /** Null when the record has an opcode this walker does not know. */
        static NpcAnims parse(int id, byte[] b) {
            Buf in = new Buf(b);
            int stand = -1, walk = -1;
            List<Integer> o = new ArrayList<>();
            try {
                while (true) {
                    int op = in.u8();
                    if (op == 0) break;
                    switch (op) {
                        case 1, 60 -> { int n = in.u8(); in.skip(n * 2); }
                        case 61, 62 -> { int n = in.u8(); in.skip(n * 4); }
                        case 2, 30, 31, 32, 33, 34 -> in.skipStr();
                        case 12, 100, 101 -> in.skip(1);
                        case 13 -> stand = n16(in.u16());
                        case 14 -> walk = n16(in.u16());
                        case 15, 16, 114, 116 -> o.add(n16(in.u16()));
                        case 17 -> { walk = n16(in.u16()); for (int i = 0; i < 3; i++) o.add(n16(in.u16())); }
                        case 115, 117 -> { for (int i = 0; i < 4; i++) o.add(n16(in.u16())); }
                        case 18, 42, 74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 124, 126, 146 -> in.skip(2);
                        case 40, 41 -> { int n = in.u8(); in.skip(n * 4); }
                        case 93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147 -> { }
                        case 102 -> { int mask = in.u8(); for (int i = 0; mask >> i != 0; i++) if ((mask & 1 << i) != 0) { in.skipBigSmart(); in.skipSmart(); } }
                        case 106, 118 -> { in.skip(4); if (op == 118) in.skip(2); int n = in.u8(); in.skip((n + 1) * 2); }
                        case 249 -> in.skipParams();
                        case 251 -> { in.skip(2); in.skipStr(); }
                        case 252 -> { in.skip(13); in.skipStr(); }
                        case 253 -> { in.skip(15); in.skipStr(); }
                        default -> { return null; }
                    }
                }
            } catch (RuntimeException e) { return null; }
            return new NpcAnims(stand, walk, o.stream().mapToInt(Integer::intValue).toArray());
        }
    }
}
