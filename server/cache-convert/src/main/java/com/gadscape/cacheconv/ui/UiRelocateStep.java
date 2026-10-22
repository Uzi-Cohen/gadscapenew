package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report;
import com.gadscape.cacheconv.core.Step;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Decides where GadScape's own interfaces and clientscripts go (spec policy 3 and 8): a custom id that BASE (or an
 * earlier step) already uses is relocated, unless the converted user content is byte-identical to what is there.
 * <ul>
 *   <li>scripts -> the reserved script range (47000-47999); e.g. the user's 10034-10121 against the Raids 4 scripts
 *       9973-10196 (finding R4-03);</li>
 *   <li>interfaces -> a reserved interface range ({@value #IF_LO}-{@value #IF_HI}, option {@code --ui.interfaceRange=LO-HI};
 *       Kind.INTERFACE has none in core): e.g. the user's 866 (Remnant Perks) against 241's colosseum_reward.</li>
 * </ul>
 * Only records relocations (ctx.ids, report); {@link UiConvertStep} writes the content. Run it early, before any step
 * that rewrites references to scripts or interfaces (the UI merge: modified vanilla script 2094 hooks custom 10100).
 */
public final class UiRelocateStep implements Step {
    public static final int IF_LO = 5200, IF_HI = 5299;

    private static final Set<Ctx> DONE = Collections.newSetFromMap(new WeakHashMap<>());

    @Override public String name() { return "ui-relocate"; }

    @Override public void run(Ctx ctx) { ensure(ctx); }

    /** Runs the relocation decisions once per build (idempotent; {@link UiConvertStep} calls it too). */
    public static synchronized void ensure(Ctx ctx) {
        if (!DONE.add(ctx)) return;
        if (ctx.ids.range(Kind.INTERFACE) == null) {
            String r = ctx.opt.get("ui.interfaceRange", IF_LO + "-" + IF_HI);
            String[] lh = r.split("-");
            ctx.ids.setRange(Kind.INTERFACE, Integer.parseInt(lh[0].trim()), Integer.parseInt(lh[1].trim()));
        }
        var user = ctx.user();
        var out = ctx.out != null ? ctx.out : ctx.base;
        // adapter scripts for vanilla procs whose 241 signature changed: new ids at the top of the reserved range
        UiData.allocateAdapters(ctx.ids);
        int ifs = 0, ifMoved = 0, cs = 0, csMoved = 0;
        UiScope scope = UiScope.of(ctx);
        if (!scope.all) ctx.report.note("UI scope: " + scope);
        for (int id : scope.interfaces) {
            ifs++;
            if (!out.hasGroup(Arch.INTERFACES, id)) continue;
            GroupData conv = InterfaceConv.group(user.group(Arch.INTERFACES, id));
            if (conv.sameContent(out.group(Arch.INTERFACES, id))) {
                ctx.report.add(Report.Decision.SAME, Kind.INTERFACE, id, "custom interface equals the 241 one");
                continue;
            }
            int to = ctx.ids.relocate(Kind.INTERFACE, id, "custom interface id is a 241 interface (" + out.fileIds(Arch.INTERFACES, id).length + " components)");
            ctx.report.relocated(Kind.INTERFACE, id, to, "id used by BASE interface with " + out.fileIds(Arch.INTERFACES, id).length + " components");
            ifMoved++;
        }
        for (int id : scope.scripts) {
            cs++;
            if (!out.hasGroup(Arch.SCRIPTS, id)) continue;
            byte[] conv = ScriptConv.convertAll(Map.of(id, user.single(Arch.SCRIPTS, id))).scripts.get(id);
            if (Arrays.equals(conv, out.single(Arch.SCRIPTS, id))) {
                ctx.report.add(Report.Decision.SAME, Kind.SCRIPT, id, "custom script equals the 241 one");
                continue;
            }
            int to = ctx.ids.relocate(Kind.SCRIPT, id, "custom script id is a 241 script");
            ctx.report.relocated(Kind.SCRIPT, id, to, "id used by BASE script (name hash " + out.groupName(Arch.SCRIPTS, id) + ")");
            csMoved++;
        }
        reserveVarIds(ctx);
        ctx.report.count("custom interfaces", ifs);
        ctx.report.count("custom interfaces relocated", ifMoved);
        ctx.report.count("custom scripts", cs);
        ctx.report.count("custom scripts relocated", csMoved);
        ctx.log("custom interfaces %d (%d relocated), custom scripts %d (%d relocated)", ifs, ifMoved, cs, csMoved);
    }

    /**
     * Var ids (varp 1/2, varbit 25/27, varc 42/43 and 49/50 operands and interface var triggers) that GadScape's own scripts and
     * interfaces use but no cache defines: one that lies in a reserved GadScape range is reserved, so no relocation
     * lands on it (the custom content already means something by it); the others are noted for the configs/server work.
     */
    static void reserveVarIds(Ctx ctx) {
        var user = ctx.user();
        UiScope scope = UiScope.of(ctx);
        java.util.TreeMap<Kind, java.util.TreeSet<Integer>> used = new java.util.TreeMap<>();
        for (int id : scope.scripts) {
            byte[] b = user.single(Arch.SCRIPTS, id);
            if (b == null || b.length < 14) continue;
            for (Cs2.Ins in : Cs2.parse211(b).code) {
                Kind k = switch (in.op) { case 1, 2 -> Kind.VARP; case 25, 27 -> Kind.VARBIT; case 42, 43, 49, 50 -> Kind.VARC; default -> null; };
                if (k != null) used.computeIfAbsent(k, x -> new java.util.TreeSet<>()).add(in.i);
            }
        }
        for (int id : scope.interfaces) {
            GroupData g = InterfaceConv.group(user.group(Arch.INTERFACES, id));
            for (int i = 0; i < g.fileCount(); i++) {
                try {
                    for (IfRefs.Ref r : IfRefs.parse(g.dataAt(i)).refs())
                        if (r.field() == IfRefs.Field.VAR_TRIGGER) used.computeIfAbsent(Kind.VARP, x -> new java.util.TreeSet<>()).add(r.value());
                } catch (RuntimeException e) {
                    // reported by ui-convert
                }
            }
        }
        var out = ctx.out != null ? ctx.out : ctx.base;
        int reserved = 0;
        for (var e : used.entrySet()) {
            Kind k = e.getKey();
            java.util.TreeSet<Integer> undefined = new java.util.TreeSet<>(), baseOnly = new java.util.TreeSet<>();
            for (int v : e.getValue()) {
                if (v < 0) continue;
                boolean u = k.exists(user, v), b = k.exists(ctx.base, v) || k.exists(out, v);
                if (u) continue;
                if (b) { if (!ctx.ids.moved(k, v)) baseOnly.add(v); continue; }
                undefined.add(v);
                var r = ctx.ids.range(k);
                if (r != null && r.contains(v)) {
                    if (ctx.ids.origin(k, v) >= 0) ctx.report.error(k.key, v, "used (undefined) by GadScape's own UI, but already the new id of " + k.key + " " + ctx.ids.origin(k, v));
                    else { ctx.ids.reserve(k, v); reserved++; ctx.report.add(Report.Decision.NOTE, k.key, v, "used by GadScape's own UI without a definition, inside the reserved range: reserved"); }
                }
            }
            if (!undefined.isEmpty()) ctx.report.add(Report.Decision.NOTE, k.key, Long.MIN_VALUE, "GadScape's own UI uses " + k.key + "s no cache defines (as on 211): " + undefined);
            // a note, not a warning: configs-plan (later) moves the varps and varcs among these to the reserved ranges, and
            // warns about the varbits it cannot move (no USER definition to port); the id map has the outcome
            if (!baseOnly.isEmpty()) ctx.report.add(Report.Decision.NOTE, k.key, Long.MIN_VALUE, "GadScape's own UI uses " + k.key + "s USER does not define but BASE does: " + baseOnly
                    + " (configs-plan moves the varps/varcs among them; varbits it cannot move read BASE's definition)");
        }
        ctx.report.count("undefined var ids reserved", reserved);
    }
}
