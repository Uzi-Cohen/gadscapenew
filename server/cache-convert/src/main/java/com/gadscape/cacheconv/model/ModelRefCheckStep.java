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
 *       {@link ModelsStep} moved: its references were not rewritten with {@link ModelRefs#remap} (a vanilla port's id
 *       that BASE's own version of the entry references is BASE's model, not a miss);</li>
 *   <li>error: an identkit with chathead opcodes 65-69 or 75-79 (the 241 client has 5 chathead slots and crashes);</li>
 *   <li>warning: a reference to a model that does not exist in the output (counted per kind; entries equal to
 *       BASE are counted separately, since those are stock data);</li>
 *   <li>count: 2-byte model opcodes of the 211 layout left in 241 entries (the 241 client drops their value).</li>
 * </ul>
 * Covers obj, npc, loc, spotanim and identkit configs and interface components (IF3/IF1 type 6).
 * <p>
 * Content check (with USER and VAN211): every user-added/changed obj/npc/loc/spotanim/identkit entry that the output
 * holds with the user's own models (its model list is the USER list mapped through the id map) must show the same
 * models the user saw: each referenced output model decodes equal to the USER model it replaces. The only accepted
 * difference is a vanilla model Jagex updated in place ({@link ModelsStep} decided BASE's version, shared as
 * {@link ModelsStep#UPDATED}); anything else (a vanilla id Jagex reused for another model) is an error. Entries
 * merged onto BASE's (other model lists) and rev-225 imports (BASE's entry) are not compared.
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
                baseModels = base == null || stock ? new int[0] : safeModels(cfg, id, base);
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
                baseModels = new int[0];
                if (bd != null && !Arrays.equals(bd, d)) try { baseModels = ModelRefs.componentModels(bd); } catch (RuntimeException ignored) { }
                check(ctx, "if", g, g + ":" + f, refs, bd != null && Arrays.equals(bd, d), have);
            }
        }
        ctx.out.release(Arch.INTERFACES);
        if (ctx.user != null && ctx.van211 != null) contentCheck(ctx);
    }

    /** USER-origin entries written with the user's model list show the models the user saw (see the class comment). */
    @SuppressWarnings("unchecked")
    private void contentCheck(Ctx ctx) {
        java.util.Set<Integer> updated = (java.util.Set<Integer>) ctx.shared.getOrDefault(ModelsStep.UPDATED, java.util.Set.of());
        java.util.HashMap<Long, Integer> memo = new java.util.HashMap<>(); // (user model, output model) -> 0 same, 1 differs, 2 user model missing
        int entries = 0, pairs = 0, merged = 0;
        for (int cfg : new int[]{Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT}) {
            String kind = Arch.configName(cfg);
            com.gadscape.cacheconv.convert.ConfigConv.Fn fn = com.gadscape.cacheconv.convert.ConfigConv.forGroup(cfg);
            Kind k = cfg == Arch.CFG_OBJ ? Kind.OBJ : cfg == Arch.CFG_NPC ? Kind.NPC : cfg == Arch.CFG_LOC ? Kind.LOC : cfg == Arch.CFG_SPOT ? Kind.SPOT : null;
            for (int id : ctx.user.configIds(cfg)) {
                if (!com.gadscape.cacheconv.core.Origin.ofConfig(cfg, id, ctx.user, ctx.van211).isUser() || ctx.isRev225Import(cfg, id)) continue;
                if (k != null && ctx.ids.isAlias(k, id)) continue;
                int to = k == null ? id : ctx.ids.map(k, id);
                byte[] ob = ctx.out.configRef(cfg, to);
                if (ob == null) continue;
                int[] mu, mo;
                try { mu = ModelRefs.models(cfg, id, fn.apply(id, ctx.user.configRef(cfg, id)), false); mo = ModelRefs.models(cfg, to, ob, false); }
                catch (RuntimeException e) { continue; }
                boolean users = mu.length == mo.length && mu.length > 0;
                for (int i = 0; users && i < mu.length; i++) if (mo[i] != ModelRefs.remapModel(ctx.ids, mu[i])) users = false;
                if (!users) { if (mu.length > 0) merged++; continue; }
                entries++;
                for (int i = 0; i < mu.length; i++) {
                    long key = ((long) mu[i] << 32) | (mo[i] & 0xffffffffL);
                    int r = memo.computeIfAbsent(key, x -> compare(ctx, (int) (x >> 32), (int) (long) x));
                    pairs++;
                    if (r == 0) continue;
                    if (r == 2) { ctx.report.count("content.user-model-missing-in-user." + kind); continue; }
                    if (mo[i] == mu[i] && updated.contains(mu[i])) { ctx.report.count("content.jagex-updated-model." + kind); continue; }
                    problem(ctx, true, "content.differs." + kind, kind, id, kind + " " + id + (to != id ? " (now " + to + ")" : "") + " shows model " + mo[i]
                            + ", which is not the model " + mu[i] + " the user's entry showed (Jagex reused the id?)");
                }
            }
        }
        ctx.user.release(Arch.CONFIGS);
        ctx.user.release(Arch.MODELS);
        ctx.report.count("content.entries-checked", entries);
        ctx.report.count("content.model-refs-checked", pairs);
        ctx.report.count("content.entries-with-base-models-not-compared", merged);
    }

    /** 0: the output model decodes like the converted USER model; 1: it differs; 2: USER has no such model. */
    private static int compare(Ctx ctx, int userModel, int outModel) {
        if (userModel < 0) return 0;
        byte[] um = ctx.user.hasGroup(Arch.MODELS, userModel) ? ctx.user.single(Arch.MODELS, userModel) : null;
        if (um == null) return 2;
        byte[] om = ctx.out.hasGroup(Arch.MODELS, outModel) ? ctx.out.single(Arch.MODELS, outModel) : null;
        if (om == null) return 0; // a missing model is reported by the reference check
        try {
            byte[] c = com.gadscape.cacheconv.convert.ModelConv.toRev241(userModel, um, false);
            return Arrays.equals(c, om) || com.gadscape.cacheconv.convert.ModelConv.sameDecoded(outModel, c, om) == null ? 0 : 1;
        } catch (RuntimeException e) {
            return 0; // not convertible: the models step reported it
        }
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
            // a vanilla port (the old copy of a model Jagex reused) moved only for GadScape's references: BASE's own
            // reference to the id, kept in a merged entry, means BASE's model
            boolean basesOwn = ctx.ids.isVanillaPort(Kind.MODEL, m) && contains(baseModels, m);
            if (!stock && ctx.ids.moved(Kind.MODEL, m) && !basesOwn)
                problem(ctx, true, "refs.unrewritten." + kind, kind, id, who + " references user model " + m + ", which moved to " + ctx.ids.map(Kind.MODEL, m) + " (apply ModelRefs.remap)");
            if (basesOwn) ctx.report.count("refs.base-own-ref-to-vanilla-port-id." + kind);
            if (!have.get(m)) problem(ctx, false, "refs.missing." + kind + (stock ? ".stock" : ""), kind, id, who + " references model " + m + ", which the output does not have" + (stock ? " (stock entry)" : ""));
        }
    }

    /** Models BASE's version of the entry being checked references (empty for stock or new entries). */
    private int[] baseModels = new int[0];

    private static boolean contains(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }

    private static int[] safeModels(int cfg, int id, byte[] b) {
        try { return ModelRefs.models(cfg, id, b, false); } catch (RuntimeException e) { return new int[0]; }
    }

    private void problem(Ctx ctx, boolean error, String key, String kind, long id, String msg) {
        ctx.report.count(key);
        int n = shown.merge(key, 1, Integer::sum);
        if (n > SHOW) return;
        if (error) ctx.report.error(kind, id, msg);
        else ctx.report.warn(kind, id, msg + (n == SHOW ? " (further cases only counted: " + key + ")" : ""));
    }
}
