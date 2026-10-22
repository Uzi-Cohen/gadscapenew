package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report;
import com.gadscape.cacheconv.core.Step;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converts GadScape's own interfaces (archive 3) and clientscripts (archive 12) to build 241 and writes them, at the
 * ids {@link UiRelocateStep} chose (spec policy 8; vanilla interfaces/scripts stay BASE's).
 * <p>
 * Scripts: every reference is rewritten in the 211 bytes first (ints keep their width), then the whole custom set is
 * converted with {@link ScriptConv#convertAll} (the ported CsAll rules) and read back:
 * <ul>
 *   <li>gosub targets and hook script ids: relocated custom scripts, adapters for vanilla procs whose 241 signature
 *       differs ({@code ui/script-signatures.tsv}: create_graphic 98 -> 46000, scrollbar_horizontal 6156 -> 46001,
 *       2064 -> 46002), other vanilla targets checked (exist, same arg counts, same name; otherwise the 211 version is
 *       ported into the reserved script range);</li>
 *   <li>constants {@link CsSim} typed (component, graphic, model, seq, enum, struct, param, dbrow/dbtable/dbcolumn, varp
 *       triggers, ...): through ctx.ids; component ids of vanilla interfaces through the 211 -> 241 component remap;
 *       known wrong interface ids fixed ({@code ui/custom-fixups.tsv});</li>
 *   <li>direct operands: varp (1/2), varbit (25/27), varc int (42/43) and varc string (49/50; the old varcstr ops 47/48
 *       address config 15, a separate id space nothing relocates).</li>
 * </ul>
 * Interfaces: {@link InterfaceConv} (type-6 model ids to ints), then sprites, models, seqs, hook script ids, hook
 * arguments (typed by the called script's argument types, or component-shaped values) and var triggers are rewritten,
 * and the zero padding after each component is trimmed ({@link UiCheckStep} then checks that every asset exists).
 * USER sprites/fonts the custom UI names that no earlier step put into the output are copied (--ui.copyAssets=false
 * turns that off). Unnamed custom scripts get the name "gadscape_script_ID" (archive 12 uses names).
 * <p>
 * Run it after every step that relocates ids custom UI uses (sprites, models, seqs, configs) and after
 * {@link UiRelocateStep}.
 */
public final class UiConvertStep implements Step {
    @Override public String name() { return "ui-convert"; }

    @Override public void run(Ctx ctx) {
        UiRelocateStep.ensure(ctx);
        UiRemap m = new UiRemap(ctx);
        ctx.report.input("ui.componentRemap", m.remap.source + " (" + m.remap.size() + " rows)");
        TreeMap<Integer, CsSim.Result> res = m.scope.analysis(ctx);
        ScriptSet set = m.scope.scriptSet(ctx);
        ctx.log("%s; %s; analysed %d USER scripts", m, m.scope, res.size());

        // interfaces first: their hooks can name vanilla scripts that must be ported
        TreeMap<Integer, GroupData> ifaces = new TreeMap<>();
        int comps = 0, trimmed = 0;
        for (int id : m.customInterfaces) {
            int to = ctx.ids.map(Kind.INTERFACE, id);
            GroupData g = InterfaceConv.group(ctx.user().group(Arch.INTERFACES, id));
            GroupData.Builder b = new GroupData.Builder(to).name(g.name);
            for (int i = 0; i < g.fileCount(); i++) {
                byte[] c = patchComponent(ctx, m, set, id, g.fileIdAt(i), g.dataAt(i).clone());
                if (c.length < g.dataAt(i).length) trimmed++;
                b.put(g.fileIdAt(i), g.fileNameAt(i), c);
                comps++;
            }
            ifaces.put(id, b.build());
        }

        // scripts: custom ones, then every vanilla 211 script they need ported (transitively)
        TreeMap<Integer, Integer> newId = new TreeMap<>();
        TreeMap<Integer, byte[]> patched = new TreeMap<>();
        for (int id : m.customScripts) {
            int to = ctx.ids.map(Kind.SCRIPT, id);
            newId.put(id, to);
            patched.put(to, patchScript(ctx, m, set, res.get(id), id));
        }
        while (!m.portQueue.isEmpty()) {
            int id = m.portQueue.poll();
            int to = ctx.ids.map(Kind.SCRIPT, id);
            newId.put(id, to);
            patched.put(to, patchScript(ctx, m, set, res.get(id), id));
        }
        checkArrayCalls(ctx, m, set, res);

        ScriptConv.Result conv = ScriptConv.convertAll(patched);
        ctx.report.count("scripts converted", conv.scripts.size());
        ctx.report.count("scripts with arrays moved to locals", conv.arrays);
        ctx.report.count("cc_create extra args", conv.ccCreate);
        ctx.report.count("hook descriptors changed", conv.descChanged);
        for (String n : conv.notes) ctx.report.note(n);

        for (var e : newId.entrySet()) {
            int id = e.getKey(), to = e.getValue();
            byte[] b = conv.scripts.get(to);
            GroupData src = ctx.user().group(Arch.SCRIPTS, id);
            boolean port = m.ported.contains(id);
            int name = scriptName(id, src.name, port);
            ctx.out.putGroup(Arch.SCRIPTS, new GroupData(to, name, 0, new int[]{src.fileIdAt(0)}, new int[]{src.fileNameAt(0)}, new byte[][]{b}));
            if (to == id) ctx.report.add(Report.Decision.USER_ADDED, Kind.SCRIPT, id, null);
        }
        writeAdapters(ctx, m);

        for (var e : ifaces.entrySet()) {
            ctx.out.putGroup(Arch.INTERFACES, e.getValue());
            if (e.getValue().id == e.getKey()) ctx.report.add(Report.Decision.USER_ADDED, Kind.INTERFACE, e.getKey(), e.getValue().fileCount() + " components");
        }
        copyMissingAssets(ctx, m);
        ctx.report.count("interface components converted", comps);
        ctx.report.count("interface components trimmed (zero padding)", trimmed);
        m.flushCounts();
        m.flushRewrites();
        m.flushProblems();
        ctx.log("wrote %d custom + %d ported scripts, %d adapters, %d interfaces (%d components); %s", m.customScripts.size(), m.ported.size(),
                m.rulesUsed.size(), m.customInterfaces.size(), comps, m.counts);
    }

    // ------------------------------------------------------------------ scripts

    /** Rewrites every reference of one custom 211 script in a copy of its bytes. */
    static byte[] patchScript(Ctx ctx, UiRemap m, ScriptSet set, CsSim.Result r, int id) {
        byte[] raw = set.raw(id).clone();
        Cs2.Script s = set.script(id);
        int[] off = ScriptPatch.operandOffsets211(raw);
        String where = (m.ported.contains(id) ? "ported 211 script " : "script ") + id, fixKey = "script:" + id;
        if (!r.complete && !r.problem.startsWith("gosub to missing script"))
            ctx.report.warn("script", id, "typing stopped at instruction " + r.stoppedAt + " (" + r.problem + "): constants after it are not rewritten");
        TreeMap<Integer, String> typed = r.constType;
        for (int k = 0; k < s.code.size(); k++) {
            Cs2.Ins in = s.code.get(k);
            int v = in.i, nv = v;
            switch (in.op) {
                case Cs2.GOSUB -> nv = m.script(v, false, where);
                case 1, 2 -> nv = m.kind(Kind.VARP, v);
                case 25, 27 -> nv = m.kind(Kind.VARBIT, v);
                case 42, 43, 49, 50 -> nv = m.kind(Kind.VARC, v);
                case Cs2.ICONST -> {
                    String t = typed.get(k);
                    if (t != null) nv = m.typed(t, v, fixKey, where);
                    if ("graphic".equals(t)) m.use(m.spriteUses, nv, where);
                    else if ("fontmetrics".equals(t)) m.use(m.fontUses, nv, where);
                }
                default -> { }
            }
            if (nv == UiRemap.DROP && v != UiRemap.DROP) { nv = -1; m.count("hook setters cleared (dangling script)"); m.problem("hook setter of script " + v + " cleared (missing on 211, a different script in BASE)", where + " @" + k); }
            if (nv != v) { ScriptPatch.set(raw, off, k, nv); m.count("script operands rewritten"); }
        }
        return raw;
    }

    /**
     * 211 arrays were globals; ScriptConv passes them between the custom scripts it converts, but a vanilla proc now
     * comes from BASE and takes no array arguments. A custom script calling a 211 vanilla proc that reads arrays it
     * does not define would silently lose them: report it.
     */
    static void checkArrayCalls(Ctx ctx, UiRemap m, ScriptSet set, TreeMap<Integer, CsSim.Result> res) {
        TreeMap<Integer, Boolean> reads = new TreeMap<>();
        for (int id : m.customScripts) {
            for (CsSim.Call c : res.get(id).calls) {
                if (m.customScripts.contains(c.target())) continue;
                boolean needs = reads.computeIfAbsent(c.target(), t -> {
                    Cs2.Script s = set.script(t);
                    if (s == null) return false;
                    TreeSet<Integer> def = new TreeSet<>(), use = new TreeSet<>();
                    for (Cs2.Ins in : s.code) { if (in.op == 44) def.add(in.i >> 16); else if (in.op == 45 || in.op == 46) use.add(in.i); }
                    use.removeAll(def);
                    return !use.isEmpty();
                });
                if (needs) ctx.report.error("script", id, "calls vanilla " + c.target() + " which reads 211 global arrays the caller defines; the 241 proc cannot receive them");
            }
        }
    }

    /** Adapter scripts for the signature rules custom content uses, validated against USER and the output. */
    static void writeAdapters(Ctx ctx, UiRemap m) {
        for (int t : m.rulesUsed) {
            UiData.SigRule r = m.rules.get(t);
            byte[] u = ctx.user().single(Arch.SCRIPTS, t), o = ctx.out.single(Arch.SCRIPTS, t);
            int[] a = u == null ? null : ScriptTrailer.args211(u), b = o == null ? null : ScriptTrailer.args241WithLongs(o);
            if (a == null || b == null || a[0] != r.int211() || a[1] != r.obj211() || b[0] != r.int241() || b[1] != r.obj241() || b[2] != r.long241()) {
                ctx.report.error("script", t, "signature rule is stale: 211 " + Arrays.toString(a) + ", 241 " + Arrays.toString(b) + " vs rule " + r.int211() + "/" + r.obj211() + " -> " + r.int241() + "/" + r.obj241() + "/" + r.long241());
                continue;
            }
            int un = ctx.user().groupName(Arch.SCRIPTS, t), on = ctx.out.groupName(Arch.SCRIPTS, t);
            if (un != -1 && on != -1 && un != on) { ctx.report.error("script", t, "signature rule names a proc that is a different script in 241 (name hash " + un + " -> " + on + ")"); continue; }
            byte[] ad = UiAdapters.build(r);
            byte[] ex = ctx.out.single(Arch.SCRIPTS, r.adapter());
            if (ex != null && !Arrays.equals(ex, ad)) { ctx.report.error("script", r.adapter(), "adapter id is taken in the output"); continue; }
            if (ctx.user().hasGroup(Arch.SCRIPTS, r.adapter())) { ctx.report.error("script", r.adapter(), "adapter id is a USER script"); continue; }
            ctx.out.putGroup(Arch.SCRIPTS, GroupData.single(r.adapter(), Bytes.nameHash("gadscape_adapter_" + t), ad));
            ctx.ids.reserve(Kind.SCRIPT, r.adapter());
            ctx.report.add(Report.Decision.NOTE, Kind.SCRIPT, r.adapter(), "adapter for vanilla " + t + (r.mapped() ? " (args " + String.join(",", r.intMap()) + (r.objMap().length > 0 ? "|" + String.join(",", r.objMap()) : "") + ")" : "") + ": " + r.note());
        }
    }

    /**
     * Name hash for a converted script group: the USER name when it has one; otherwise a unique deterministic name
     * ("gadscape_script_ID", ported vanilla: "gadscape_211_script_ID", ID = the 211 id), since archive 12 uses names and
     * the 211 cache leaves GadScape's own scripts unnamed (-1).
     */
    static int scriptName(int id211, int userName, boolean ported) {
        if (ported) return Bytes.nameHash("gadscape_211_script_" + id211);
        return userName != -1 ? userName : Bytes.nameHash("gadscape_script_" + id211);
    }

    /**
     * Sprites and fonts the custom UI names that no earlier step put into the output, copied from USER at the same id
     * (formats are unchanged between 211 and 241). Only ids that USER has, that are not the target of a relocation
     * (those belong to the step that moved them) and that BASE lacks. Option --ui.copyAssets=false turns it off.
     */
    static void copyMissingAssets(Ctx ctx, UiRemap m) {
        java.util.TreeMap<Integer, TreeSet<String>> sprites = m.spriteUses, fonts = m.fontUses;
        boolean copy = Boolean.parseBoolean(ctx.opt.get("ui.copyAssets", "true"));
        int n = 0;
        for (int[] spec : new int[][]{{Arch.SPRITES}, {Arch.FONTS}}) {
            int arch = spec[0];
            var want = arch == Arch.SPRITES ? sprites : fonts;
            String what = arch == Arch.SPRITES ? "sprite" : "font";
            for (var e : want.entrySet()) {
                int id = e.getKey();
                if (id < 0 || ctx.out.hasGroup(arch, id)) continue;
                GroupData g = copy && !ctx.base.hasGroup(arch, id) && (arch != Arch.SPRITES || ctx.ids.origin(Kind.SPRITE, id) < 0) ? ctx.user().group(arch, id) : null;
                if (g == null) {
                    // interface fields are checked (error/warning) by ui-check; script constants only here
                    for (String w : e.getValue()) if (!w.startsWith("interface")) m.problem(what + " " + id + " (a typed script constant) is not in the output" + (ctx.user().hasGroup(arch, id) ? "" : " nor in the 211 cache"), w);
                    continue;
                }
                int name = g.name != -1 ? g.name : Bytes.nameHash("gadscape_" + what + "_" + id);
                ctx.out.putGroup(arch, new GroupData(id, name, 0, g.fileIds(), names(g), files(g)));
                ctx.report.add(Report.Decision.USER_ADDED, what, id, "needed by custom UI (" + String.join(", ", e.getValue()) + "); no earlier step added it");
                n++;
            }
        }
        ctx.report.count("USER sprites/fonts copied for custom UI", n);
    }

    private static int[] names(GroupData g) { int[] r = new int[g.fileCount()]; for (int i = 0; i < r.length; i++) r[i] = g.fileNameAt(i); return r; }
    private static byte[][] files(GroupData g) { byte[][] r = new byte[g.fileCount()][]; for (int i = 0; i < r.length; i++) r[i] = g.dataAt(i).clone(); return r; }

    // ------------------------------------------------------------------ interfaces

    /** Rewrites the references of one converted (241-layout) component and trims its zero padding. */
    static byte[] patchComponent(Ctx ctx, UiRemap m, ScriptSet set, int iface, int file, byte[] b) {
        String where = "interface " + iface + ":" + file, fixKey = "interface:" + iface;
        IfRefs.Comp c;
        try {
            c = IfRefs.parse(b);
        } catch (RuntimeException e) {
            ctx.report.error("interface", iface, "component " + file + " does not decode: " + e.getMessage());
            return b;
        }
        if (c.end() > b.length) { ctx.report.error("interface", iface, "component " + file + " is truncated"); return b; }
        java.util.List<IfRefs.HookSpan> drop = new java.util.ArrayList<>();
        for (IfRefs.Ref r : c.refs()) {
            int v = r.value(), nv = v;
            switch (r.field()) {
                case SPRITE -> nv = m.kind(Kind.SPRITE, v);
                case MODEL -> nv = m.kind(Kind.MODEL, v);
                case SEQ -> {
                    nv = m.kind(Kind.SEQ, v);
                    if (nv > 65534) { ctx.report.error("interface", iface, "component " + file + ": seq " + v + " moved to " + nv + ", which a u16 field cannot hold"); nv = v; }
                }
                case HOOK_SCRIPT -> {
                    nv = m.script(v, true, where);
                    if (nv == UiRemap.DROP && v != UiRemap.DROP) { nv = v; for (IfRefs.HookSpan h : c.hooks()) if (h.hook() == r.hook()) drop.add(h); }
                }
                case HOOK_INT -> nv = hookArg(m, set, r, fixKey, where);
                case VAR_TRIGGER -> nv = m.kind(Kind.VARP, v);
                case CS1 -> ctx.report.warn("interface", iface, "component " + file + " has " + v + " old-style (cs1) scripts; their var references are not rewritten");
                default -> { }
            }
            if (nv != v) { IfRefs.patch(b, r, nv); m.count("interface fields rewritten"); }
            if (r.field() == IfRefs.Field.SPRITE) m.use(m.spriteUses, nv, where);
            else if (r.field() == IfRefs.Field.FONT) m.use(m.fontUses, nv, where);
        }
        int end = c.end();
        if (!drop.isEmpty()) {
            int before = b.length;
            b = IfRefs.dropHooks(b, drop);
            end -= before - b.length;
            for (IfRefs.HookSpan h : drop) ctx.report.add(Report.Decision.NOTE, "interface", iface, "component " + file + ": " + IfRefs.HOOKS[h.hook()] + " hook removed (its script exists only in BASE)");
        }
        if (end < b.length) {
            if (Bytes.isZero(b, end, b.length)) return Arrays.copyOf(b, end);
            ctx.report.warn("interface", iface, "component " + file + " has " + (b.length - end) + " non-zero bytes after its end (kept)");
        }
        return b;
    }

    /** A hook's int argument: typed by the called (211) script's argument types, else rewritten only if component-shaped. */
    static int hookArg(UiRemap m, ScriptSet set, IfRefs.Ref r, String fixKey, String where) {
        int v = r.value();
        if (v == -1 || CsSim.isEventConstant(v)) return v;
        String[] at = r.script() >= 0 ? set.argTypes(r.script()) : null;
        String t = at != null && r.argIndex() < at.length ? at[r.argIndex()] : null;
        if (t != null && !t.equals("int")) {
            m.count("hook args typed by the called script");
            int nv = m.typed(t, v, fixKey, where);
            if (t.equals("graphic")) m.use(m.spriteUses, nv, where);
            return nv == UiRemap.DROP && v != UiRemap.DROP ? -1 : nv;
        }
        if (m.looksLikeComponent(v)) {
            int nv = m.component(v, fixKey, where);
            if (nv != v) {
                m.count("untyped hook args rewritten as components");
                m.ctx.report.add(Report.Decision.NOTE, "interface", Integer.parseInt(fixKey.split(":")[1]), where + " " + IfRefs.HOOKS[r.hook()]
                        + " arg " + r.argIndex() + " of script " + r.script() + ": untyped component " + (v >>> 16) + ":" + (v & 0xffff) + " -> " + (nv >>> 16) + ":" + (nv & 0xffff));
            }
            return nv;
        }
        return v;
    }

    /** The custom script ids and where they went (for reports and other areas). */
    public static Map<Integer, Integer> scriptIds(Ctx ctx) {
        TreeMap<Integer, Integer> m = new TreeMap<>();
        for (int id : new UiRemap(ctx).customScripts) m.put(id, ctx.ids.map(Kind.SCRIPT, id));
        return m;
    }
}
