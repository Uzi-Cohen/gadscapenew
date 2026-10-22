package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.config.CfgCodec;
import com.gadscape.cacheconv.config.ParamTypeTable;
import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;

/**
 * models-prune: removes the vanilla model ports ({@link ModelsStep}: old copies of vanilla / rev-225 models Jagex
 * reused) that nothing in the finished output references, and takes them out of the id map again. {@code models}
 * ports a model when GadScape content in USER uses it; the configs step may still resolve that content to BASE's own
 * entry (the "same thing" rules), which leaves the copy unused. Each one would cost one of the scarce model ids below
 * 65536 (audit: 575 of the first build's 1,317 moved models were unreferenced).
 * <p>
 * A port counts as used when any output config (every walked group: obj, npc, loc, spotanim, identkit, enum, struct,
 * param, dbrow with model-typed values), any interface component, or any int constant of a clientscript that is not
 * BASE's names its new id. User models the user made or edited are never pruned (the server or later content may use
 * them). Runs after every writer and before the checks; {@code --models.prune=false} keeps every port.
 */
public final class ModelsPruneStep implements Step {
    public static final String NAME = "models-prune";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        if (!Boolean.parseBoolean(ctx.opt.get("models.prune", "true"))) return;
        TreeMap<Integer, Integer> ports = new TreeMap<>(); // new id -> vanilla id (written copies only, not twins)
        for (int from : ctx.ids.vanillaPorts(Kind.MODEL)) if (!ctx.ids.isAlias(Kind.MODEL, from)) ports.put(ctx.ids.map(Kind.MODEL, from), from);
        List<Integer> aliases = new ArrayList<>();
        for (int from : ctx.ids.vanillaPorts(Kind.MODEL)) if (ctx.ids.isAlias(Kind.MODEL, from)) aliases.add(from);
        if (ports.isEmpty() && aliases.isEmpty()) return;
        BitSet used = usedModels(ctx.out, ctx.base);
        int pruned = 0, kept = 0;
        for (var e : ports.entrySet()) {
            int to = e.getKey(), from = e.getValue();
            if (used.get(to)) { kept++; continue; }
            ctx.out.removeGroup(Arch.MODELS, to);
            ctx.ids.unrecord(Kind.MODEL, from);
            pruned++;
            ctx.report.add(Decision.DROPPED, Kind.MODEL.key, from, (long) to, "vanilla model port " + from + " -> " + to
                    + " unused in the output (the GadScape entries that used model " + from + " resolved to BASE's own): removed, id " + to + " free again");
        }
        int aliasDropped = 0;
        for (int from : aliases) {
            if (used.get(from) || used.get(ctx.ids.map(Kind.MODEL, from))) continue;
            ctx.ids.unrecord(Kind.MODEL, from);
            aliasDropped++;
        }
        ctx.report.count("vanilla-ports.kept", kept);
        ctx.report.count("vanilla-ports.pruned", pruned);
        ctx.report.count("vanilla-twins.unused-mapping-dropped", aliasDropped);
        ctx.log("vanilla model ports: %d used, %d unused removed (%d unused twin mappings dropped)", kept, pruned, aliasDropped);
    }

    /** Every model id the output references (configs, interfaces, int constants of non-BASE clientscripts). */
    public static BitSet usedModels(com.gadscape.cacheconv.core.CacheView out, com.gadscape.cacheconv.core.CacheView base) {
        BitSet used = new BitSet();
        ParamTypeTable types = new ParamTypeTable(out);
        for (int g : out.groupIds(Arch.CONFIGS)) {
            if (!CfgCodec.walked(g) || g == Arch.CFG_SEQ) continue;
            for (int id : out.configIds(g)) {
                byte[] b = out.configRef(g, id);
                if (b == null) continue;
                try { for (CfgCodec.Ref r : CfgCodec.parse(g, id, b, types).allRefs()) if (r.kind() == Kind.MODEL && r.value() >= 0) used.set(r.value()); }
                catch (RuntimeException e) { for (int m : safeModels(g, id, b)) used.set(m); }
            }
        }
        out.release(Arch.CONFIGS);
        for (int g : out.groupIds(Arch.INTERFACES)) {
            GroupData gd = out.group(Arch.INTERFACES, g);
            if (gd == null) continue;
            for (int i = 0; i < gd.fileCount(); i++) {
                try { for (int m : ModelRefs.componentModels(gd.dataAt(i))) if (m >= 0) used.set(m); } catch (RuntimeException ignored) { }
            }
        }
        out.release(Arch.INTERFACES);
        for (int g : out.groupIds(Arch.SCRIPTS)) {
            byte[] b = out.single(Arch.SCRIPTS, g);
            if (b == null || (base.hasGroup(Arch.SCRIPTS, g) && Arrays.equals(b, base.single(Arch.SCRIPTS, g)))) continue;
            try { for (Cs2.Ins in : Cs2.parse241(b).code) if (in.op == Cs2.ICONST && in.i >= 0) used.set(in.i); } catch (RuntimeException ignored) { }
        }
        out.release(Arch.SCRIPTS);
        base.release(Arch.SCRIPTS);
        return used;
    }

    private static int[] safeModels(int g, int id, byte[] b) {
        if (!ModelRefs.hasModels(g)) return new int[0];
        try { return ModelRefs.models(g, id, b, false); } catch (RuntimeException e) { return new int[0]; }
    }
}
