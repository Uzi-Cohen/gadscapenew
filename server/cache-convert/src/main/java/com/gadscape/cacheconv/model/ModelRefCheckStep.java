package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Step;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Checks the model references of the output; meant to run after the config and UI steps.
 * <ul>
 *   <li>error: a model group above 65535 (the 241 client cannot fetch it), or a reference to one;</li>
 *   <li>error: an entry that differs from BASE (so a step wrote it) still references a model id that
 *       {@link ModelsStep} moved: its references were not rewritten with {@link ModelRefs#remap};</li>
 *   <li>error: an identkit with chathead opcodes 65-69 or 75-79 (the 241 client has 5 chathead slots and crashes);</li>
 *   <li>warning: a reference to a model that does not exist in the output (counted per kind; entries equal to
 *       BASE are counted separately, since those are stock data);</li>
 *   <li>count: 2-byte model opcodes of the 211 layout left in 241 entries (the 241 client drops their value).</li>
 * </ul>
 * Covers obj, npc, loc, spotanim and identkit configs and interface components (IF3/IF1 type 6).
 */
public final class ModelRefCheckStep implements Step {
    public static final String NAME = "models-refcheck";
    private static final int SHOW = 15;

    @Override public String name() { return NAME; }

    private final TreeMap<String, Integer> shown = new TreeMap<>();

    @Override public void run(Ctx ctx) {
        BitSet have = new BitSet();
        int above = 0;
        for (int id : ctx.out.groupIds(Arch.MODELS)) {
            if (id > ModelsStep.MAX_CLIENT_MODEL) { above++; if (above <= SHOW) ctx.report.error(Kind.MODEL.key, id, "model group above " + ModelsStep.MAX_CLIENT_MODEL + ": the 241 client cannot fetch it"); }
            else have.set(id);
        }
        ctx.report.count("models", have.cardinality() + above);
        if (above > 0) ctx.report.count("models.above-65535", above);

        for (int cfg : new int[]{Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT}) {
            String kind = Arch.configName(cfg);
            for (int id : ctx.out.configIds(cfg)) {
                byte[] b = ctx.out.configRef(cfg, id);
                if (b == null) continue;
                List<ModelRefs.Ref> refs;
                try { refs = ModelRefs.refs(cfg, id, b); }
                catch (RuntimeException e) { problem(ctx, true, kind + ".unreadable", kind, id, "not a valid 241 entry: " + e.getMessage()); continue; }
                byte[] base = ctx.base.configRef(cfg, id);
                boolean stock = base != null && Arrays.equals(base, b);
                check(ctx, kind, id, String.valueOf(id), refs, stock, have);
            }
        }
        for (int g : ctx.out.groupIds(Arch.INTERFACES)) {
            GroupData gd = ctx.out.group(Arch.INTERFACES, g);
            if (gd == null) continue;
            GroupData bg = ctx.base.group(Arch.INTERFACES, g);
            for (int i = 0; i < gd.fileCount(); i++) {
                byte[] d = gd.dataAt(i);
                int f = gd.fileIdAt(i);
                List<ModelRefs.Ref> refs;
                try { refs = ModelRefs.componentRefs(d); }
                catch (RuntimeException e) { problem(ctx, true, "if.unreadable", "interface", g, "component " + f + ": " + e.getMessage()); continue; }
                if (refs.isEmpty()) continue;
                byte[] bd = bg == null ? null : bg.dataRef(f);
                check(ctx, "if", g, g + ":" + f, refs, bd != null && Arrays.equals(bd, d), have);
            }
        }
        ctx.out.release(Arch.INTERFACES);
    }

    private void check(Ctx ctx, String kind, int id, String who, List<ModelRefs.Ref> refs, boolean stock, BitSet have) {
        for (ModelRefs.Ref r : refs) {
            if (kind.equals("identkit") && ((r.opcode() >= 65 && r.opcode() < 70) || (r.opcode() >= 75 && r.opcode() < 80)))
                problem(ctx, true, "identkit.chathead-slot", kind, id, "chathead opcode " + r.opcode() + ": the 241 client has 5 chathead slots and throws");
            if (r.legacy()) { ctx.report.count("refs.legacy-2-byte." + kind + ".op" + r.opcode()); continue; }
            int m = r.model();
            if (m < 0) continue;
            ctx.report.count("refs." + kind);
            if (m > ModelsStep.MAX_CLIENT_MODEL) { problem(ctx, true, "refs.above-65535." + kind, kind, id, who + " references model " + m + ", above what the 241 client can fetch"); continue; }
            if (!stock && ctx.ids.moved(Kind.MODEL, m))
                problem(ctx, true, "refs.unrewritten." + kind, kind, id, who + " references user model " + m + ", which moved to " + ctx.ids.map(Kind.MODEL, m) + " (apply ModelRefs.remap)");
            if (!have.get(m)) problem(ctx, false, "refs.missing." + kind + (stock ? ".stock" : ""), kind, id, who + " references model " + m + ", which the output does not have" + (stock ? " (stock entry)" : ""));
        }
    }

    private void problem(Ctx ctx, boolean error, String key, String kind, long id, String msg) {
        ctx.report.count(key);
        int n = shown.merge(key, 1, Integer::sum);
        if (n > SHOW) return;
        if (error) ctx.report.error(kind, id, msg);
        else ctx.report.warn(kind, id, msg + (n == SHOW ? " (further cases only counted: " + key + ")" : ""));
    }
}
