package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Step;

import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only check of what {@link UiConvertStep} wrote, on the output as committed (run it after ui-convert):
 * <ul>
 *   <li>every custom/ported script and adapter is in the output and reads back in the 241 layout;</li>
 *   <li>every gosub target of those scripts exists, and its 241 int/obj/long argument counts are what the caller
 *       pushes (the callee's 211 counts, plus the object args ScriptConv adds for 211 arrays); hook targets of the
 *       interfaces exist;</li>
 *   <li>every custom interface is in the output, each component decodes to its exact length, and the sprites, models,
 *       fonts and seqs it names exist.</li>
 * </ul>
 * A reference that was already dangling in the 211 cache is a warning; anything else is an error.
 */
public final class UiCheckStep implements Step {
    @Override public String name() { return "ui-check"; }

    private static boolean userHas(CacheView user, IfRefs.Field f, int v) {
        return switch (f) {
            case SPRITE -> user.hasGroup(Arch.SPRITES, v);
            case MODEL -> user.hasGroup(Arch.MODELS, v);
            case FONT -> user.hasGroup(Arch.FONTS, v);
            case SEQ -> user.hasConfig(Arch.CFG_SEQ, v);
            default -> false;
        };
    }

    @Override public void run(Ctx ctx) {
        CacheView out = ctx.out != null ? ctx.out : ctx.base;
        // --ui.standalone=true (UiMain): assets other areas add (user sprites/models) are warnings, not errors
        boolean standalone = Boolean.parseBoolean(ctx.opt.get("ui.standalone", "false"));
        var user = ctx.user();
        TreeMap<Integer, Integer> written = new TreeMap<>();        // new id -> 211 id (adapters: -target)
        UiScope scope = UiScope.of(ctx);
        for (int id : scope.scripts) written.put(ctx.ids.map(Kind.SCRIPT, id), id);
        for (var e : ctx.ids.all(Kind.SCRIPT).entrySet()) {
            String why = ctx.ids.reason(Kind.SCRIPT, e.getKey());
            if (why != null && why.startsWith("211 vanilla script")) written.put(e.getValue(), e.getKey());
        }
        TreeMap<Integer, UiData.SigRule> adapters = new TreeMap<>();
        for (UiData.SigRule r : UiData.signatures().values()) if (out.hasGroup(Arch.SCRIPTS, r.adapter())) { adapters.put(r.adapter(), r); written.put(r.adapter(), -r.target()); }

        int scripts = 0, calls = 0, errors = 0, warnings = 0;
        TreeSet<Integer> arrayScripts = new TreeSet<>();
        for (var e : written.entrySet()) {
            int id = e.getKey(), src = e.getValue();
            byte[] b = out.single(Arch.SCRIPTS, id);
            if (b == null) { ctx.report.error("script", id, "converted script is missing in the output (211 id " + src + ")"); errors++; continue; }
            if (!ScriptTrailer.reads241(id, b)) { ctx.report.error("script", id, "does not read back in the 241 layout"); errors++; continue; }
            scripts++;
            if (src >= 0) { int[] a211 = ScriptTrailer.args211(user.single(Arch.SCRIPTS, src)), a241 = ScriptTrailer.args241(b); if (a241[1] != a211[1]) arrayScripts.add(id); }
        }
        for (var e : written.entrySet()) {
            int id = e.getKey();
            byte[] b = out.single(Arch.SCRIPTS, id);
            if (b == null || !ScriptTrailer.reads241(id, b)) continue;
            Cs2.Script s = Cs2.parse241(b);
            for (Cs2.Ins in : s.code) {
                if (in.op != Cs2.GOSUB) continue;
                calls++;
                int t = in.i;
                byte[] callee = out.single(Arch.SCRIPTS, t);
                int orig = written.containsKey(t) ? written.get(t) : t;
                if (callee == null) {
                    boolean old = orig >= 0 && !user.hasGroup(Arch.SCRIPTS, orig);
                    if (old) { ctx.report.warn("script", id, "gosub " + t + ": missing (already missing in the 211 cache)"); warnings++; }
                    else { ctx.report.error("script", id, "gosub " + t + ": not in the output"); errors++; }
                    continue;
                }
                int[] want;
                if (written.get(id) < 0) { UiData.SigRule r = adapters.get(id); want = new int[]{r.int241(), r.obj241()}; }   // the adapter's own 241 call
                else if (orig < 0) { UiData.SigRule r = adapters.get(t); want = new int[]{r.int211(), r.obj211()}; }
                else { byte[] u = user.single(Arch.SCRIPTS, orig); if (u == null) continue; want = ScriptTrailer.args211(u); }
                int[] got = ScriptTrailer.args241WithLongs(callee);
                boolean objOk = got[1] == want[1] || arrayScripts.contains(t);
                if (got[0] != want[0] || !objOk || got[2] != 0) {
                    ctx.report.error("script", id, "gosub " + t + " takes " + got[0] + "/" + got[1] + "/" + got[2] + " args but the 211 caller passes " + want[0] + "/" + want[1]);
                    errors++;
                }
            }
        }
        int ifs = 0, comps = 0;
        for (int id : scope.interfaces) {
            int to = ctx.ids.map(Kind.INTERFACE, id);
            GroupData g = out.group(Arch.INTERFACES, to);
            if (g == null) { ctx.report.error("interface", to, "custom interface " + id + " is missing in the output"); errors++; continue; }
            ifs++;
            for (int i = 0; i < g.fileCount(); i++) {
                byte[] c = g.dataAt(i);
                comps++;
                IfRefs.Comp p;
                try { p = IfRefs.parse(c); } catch (RuntimeException ex) { ctx.report.error("interface", to, "component " + g.fileIdAt(i) + " does not decode: " + ex.getMessage()); errors++; continue; }
                if (p.end() != c.length) { ctx.report.error("interface", to, "component " + g.fileIdAt(i) + " decodes to " + p.end() + " of " + c.length + " bytes"); errors++; }
                for (IfRefs.Ref r : p.refs()) {
                    String what = null;
                    int v = r.value();
                    switch (r.field()) {
                        case HOOK_SCRIPT -> { if (v >= 0 && !out.hasGroup(Arch.SCRIPTS, v)) what = "hook script " + v; }
                        case SPRITE -> { if (v >= 0 && !out.hasGroup(Arch.SPRITES, v)) what = "sprite " + v; }
                        case MODEL -> { if (v >= 0 && !out.hasGroup(Arch.MODELS, v)) what = "model " + v; }
                        case FONT -> { if (v >= 0 && !out.hasGroup(Arch.FONTS, v)) what = "font " + v; }
                        case SEQ -> { if (v >= 0 && !out.hasConfig(Arch.CFG_SEQ, v)) what = "seq " + v; }
                        default -> { }
                    }
                    if (what == null) continue;
                    boolean old = r.field() == IfRefs.Field.HOOK_SCRIPT && !user.hasGroup(Arch.SCRIPTS, v);
                    Kind k = switch (r.field()) { case SPRITE -> Kind.SPRITE; case MODEL -> Kind.MODEL; case SEQ -> Kind.SEQ; default -> null; };
                    int origin = k == null ? -1 : ctx.ids.origin(k, v);
                    boolean pending = standalone && !old && (userHas(user, r.field(), v) || (origin >= 0 && userHas(user, r.field(), origin)));
                    String msg = "component " + g.fileIdAt(i) + ": " + what + " is not in the output" + (old ? " (already missing in the 211 cache)" : pending ? " (a USER asset another area adds; standalone run)" : "");
                    if (old || pending) { ctx.report.warn("interface", to, msg); warnings++; } else { ctx.report.error("interface", to, msg); errors++; }
                }
            }
        }
        ctx.report.count("scripts checked", scripts);
        ctx.report.count("gosubs checked", calls);
        ctx.report.count("interfaces checked", ifs);
        ctx.report.count("components checked", comps);
        ctx.log("checked %d scripts (%d gosubs), %d interfaces (%d components): %d errors, %d warnings", scripts, calls, ifs, comps, errors, warnings);
    }
}
