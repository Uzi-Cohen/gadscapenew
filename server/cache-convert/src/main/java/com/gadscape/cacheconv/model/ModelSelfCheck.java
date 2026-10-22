package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.convert.ModelConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Json;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Self-checks of the models area on the real caches (needs -Dgadconv.root or explicit --base/--user/--van211):
 * <ol>
 *   <li>walkers: every obj/npc/loc/spotanim/identkit entry and interface component of BASE, and of USER after
 *       conversion, parses; rewriting with the identity returns the same array; rewriting with a shifted mapping
 *       changes exactly the model fields; every model id BASE references exists in BASE (which proves the field
 *       offsets and widths); opcode census of the model fields;</li>
 *   <li>the step: two dry runs give identical idmap.json and report.json; every target is at most 65535; the model
 *       written at each target decodes to the same geometry as the user's 211 model.</li>
 * </ol>
 * {@code dev/run.sh model.ModelSelfCheck [--skip-step]}; prints a summary and exits 1 on any failure.
 */
public final class ModelSelfCheck {
    private ModelSelfCheck() {}

    private static final List<String> out = new ArrayList<>();
    private static int failures;

    private static void say(String s) { System.out.println(s); out.add(s); }
    private static void fail(String s) { failures++; say("FAIL " + s); }

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        o.dryRun = true;
        try (Ctx ctx = Ctx.open(o, true)) {
            walkers(ctx.base, null, "BASE", true);
            walkers(ctx.user(), ctx.van211(), "USER(converted)", false);
        }
        if (!o.flag("skip-step")) step(o);
        Path w = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("models");
        Files.createDirectories(w);
        Files.write(w.resolve("selfcheck.txt"), out);
        say(failures == 0 ? "ALL OK" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Walks every model-carrying config and component of a cache (211 caches are converted first). */
    static void walkers(CacheView c, CacheView van, String label, boolean is241) {
        BitSet models = new BitSet();
        for (int id : c.groupIds(Arch.MODELS)) models.set(id);
        for (int cfg : new int[]{Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT}) {
            ConfigConv.Fn conv = is241 ? null : ConfigConv.forGroup(cfg);
            int entries = 0, refs = 0, legacy = 0, missing = 0, bad = 0, convFail = 0;
            TreeMap<Integer, Integer> ops = new TreeMap<>();
            List<String> missingEx = new ArrayList<>();
            for (int id : c.configIds(cfg)) {
                byte[] b = c.configRef(cfg, id);
                if (b == null) continue;
                if (conv != null) {
                    try { b = conv.apply(id, b); } catch (RuntimeException e) { convFail++; continue; }
                }
                entries++;
                List<ModelRefs.Ref> rs;
                try { rs = ModelRefs.refs(cfg, id, b); }
                catch (RuntimeException e) { if (bad++ < 5) fail(label + " " + Arch.configName(cfg) + " " + id + ": " + e.getMessage()); continue; }
                if (ModelRefs.rewrite(cfg, id, b, m -> m) != b) fail(label + " " + Arch.configName(cfg) + " " + id + ": identity rewrite changed the entry");
                boolean anyLegacy = false;
                for (ModelRefs.Ref r : rs) {
                    ops.merge(r.opcode(), 1, Integer::sum);
                    if (r.legacy()) { legacy++; anyLegacy = true; continue; }
                    if (r.model() < 0) continue;
                    refs++;
                    if (!models.get(r.model())) { missing++; if (missingEx.size() < 8) missingEx.add(id + "->" + r.model()); }
                }
                if (!anyLegacy && !rs.isEmpty()) {
                    byte[] shifted = ModelRefs.rewrite(cfg, id, b, m -> m + 100_003);
                    int[] before = ModelRefs.models(cfg, id, b, true), after = ModelRefs.models(cfg, id, shifted, true);
                    int diff = 0;
                    for (int i = 0; i < b.length; i++) if (b[i] != shifted[i]) diff++;
                    boolean ok = shifted.length == b.length && before.length == after.length && diff <= 4 * before.length;
                    for (int i = 0; ok && i < before.length; i++) ok = after[i] == before[i] + 100_003;
                    if (!ok) fail(label + " " + Arch.configName(cfg) + " " + id + ": shifted rewrite did not round-trip");
                }
            }
            say(String.format("%-16s %-9s entries %6d  model refs %6d  legacy 2-byte %4d  missing %4d %s unreadable %d%s  opcodes %s",
                    label, Arch.configName(cfg), entries, refs, legacy, missing, missingEx, bad, convFail > 0 ? "  (not convertible " + convFail + ")" : "", ops));
            if (is241 && missing > 0) fail(label + " " + Arch.configName(cfg) + ": " + missing + " stock references to missing models (walker offsets/widths wrong?)");
            if (is241 && legacy > 0) say("  note: " + label + " " + Arch.configName(cfg) + " still uses 2-byte model opcodes");
            c.release(Arch.CONFIGS);
        }
        int comps = 0, refs = 0, missing = 0, bad = 0, if1 = 0;
        for (int g : c.groupIds(Arch.INTERFACES)) {
            GroupData gd = c.group(Arch.INTERFACES, g);
            if (gd == null) continue;
            for (int i = 0; i < gd.fileCount(); i++) {
                byte[] d = gd.dataAt(i);
                if (d.length == 0) continue;
                if (!is241) {
                    try { d = InterfaceConv.component(d); } catch (RuntimeException e) { bad++; continue; }
                }
                comps++;
                List<ModelRefs.Ref> rs;
                try { rs = ModelRefs.componentRefs(d); } catch (RuntimeException e) { bad++; continue; }
                if (ModelRefs.rewriteComponent(d, m -> m) != d) fail(label + " component " + g + ":" + gd.fileIdAt(i) + ": identity rewrite changed it");
                if (!InterfaceConv.isIf3(d) && !rs.isEmpty()) if1++;
                for (ModelRefs.Ref r : rs) {
                    if (r.model() < 0) continue;
                    refs++;
                    if (!models.get(r.model())) missing++;
                }
            }
        }
        c.release(Arch.INTERFACES);
        say(String.format("%-16s %-9s components %6d  model refs %6d (IF1 type 6: %d)  missing %d  unreadable %d", label, "interface", comps, refs, if1, missing, bad));
        if (is241 && (missing > 0 || bad > 0)) fail(label + " interfaces: " + missing + " missing model refs, " + bad + " unreadable");
    }

    /** Two dry runs of the step: same idmap/report; targets <= 65535; written models decode like the user's. */
    static void step(Options o) throws Exception {
        String[] json = new String[2];
        for (int run = 0; run < 2; run++) {
            try (Ctx ctx = Ctx.open(o, true)) {
                ModelsStep s = new ModelsStep();
                ctx.report.begin(s.name());
                s.run(ctx);
                ctx.report.end();
                json[run] = Json.write(ctx.ids.toJson()) + "\n" + Json.write(ctx.report.toJson());
                if (run == 1) continue;
                ModelsStep.Summary sum = s.summary();
                int checked = 0;
                for (ModelsStep.Move m : sum.moves) {
                    if (m.to() > ModelsStep.MAX_CLIENT_MODEL) fail("model " + m.from() + " moved to " + m.to() + " (above 65535)");
                    byte[] src = ctx.user().single(Arch.MODELS, m.from()), dst = ctx.out.single(Arch.MODELS, m.to());
                    String why = dst == null ? "target missing" : ModelConv.sameDecoded(m.from(), src, dst);
                    if (why != null) fail("model " + m.from() + " -> " + m.to() + (m.twin() ? " (twin)" : "") + ": " + why);
                    checked++;
                }
                for (int id : sum.writtenAtId) {
                    String why = ModelConv.sameDecoded(id, ctx.user().single(Arch.MODELS, id), ctx.out.single(Arch.MODELS, id));
                    if (why != null) fail("model " + id + " written at its id: " + why);
                    checked++;
                }
                say("step: " + sum.moves.size() + " moves (" + sum.count("twin", 0) + " twins), " + sum.writtenAtId.size()
                        + " written at their id; " + checked + " written models decode like the user's 211 model; range " + sum.lo + "-" + sum.hi
                        + "; idmap model entries " + ctx.ids.all(Kind.MODEL).size());
            }
        }
        if (!json[0].equals(json[1])) fail("two dry runs gave different idmap/report");
        else say("step: two dry runs gave identical idmap.json and report.json (" + json[0].length() + " chars)");
    }

    static String hex(byte[] b) { return Arrays.toString(b); }
}
