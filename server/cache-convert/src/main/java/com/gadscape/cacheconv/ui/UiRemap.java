package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.ScriptTrailer;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How references inside GadScape's own UI content (211 ids) become build-241 ids, in one place so the custom
 * interface/script conversion and the three-way UI merge rewrite them the same way:
 * <ul>
 *   <li>scripts: a custom script relocated by {@link UiRelocateStep} -> its new id; a vanilla proc whose 241 signature
 *       differs -> its adapter ({@code ui/script-signatures.tsv}); other vanilla scripts keep their id (signature and
 *       name checked against the output);</li>
 *   <li>components: known wrong interface ids fixed first ({@code ui/custom-fixups.tsv}), then a relocated custom
 *       interface -> its new id, a vanilla component -> its 241 component ({@link UiData.ComponentRemap});</li>
 *   <li>typed constants: graphic/model/seq/obj/npc/loc/spotanim/enum/struct/param/dbrow/dbtable/dbcolumn/varp through
 *       {@code ctx.ids}.</li>
 * </ul>
 * Every rewrite is counted; problems are reported once per id.
 */
public final class UiRemap {
    public final Ctx ctx;
    public final CacheView out;
    /** What is converted (all custom UI, or the interfaces/scripts port-interface selected). */
    public final UiScope scope;
    public final TreeSet<Integer> customScripts = new TreeSet<>(), customInterfaces = new TreeSet<>();
    public final TreeMap<Integer, UiData.SigRule> rules = UiData.signatures();
    public final TreeSet<Integer> rulesUsed = new TreeSet<>();
    public final Map<String, List<UiData.Fixup>> fixups;
    public final UiData.ComponentRemap remap;
    /** Rewrite counters (reported with ctx.report.count by the steps). */
    public final TreeMap<String, Long> counts = new TreeMap<>();
    private final TreeMap<String, TreeSet<String>> problems = new TreeMap<>();
    /** Component rewrites per referrer ("script 30089" -> "162:5->162:6"), reported as one note each. */
    private final TreeMap<String, java.util.List<String>> rewrites = new TreeMap<>();
    private final TreeMap<Integer, Boolean> sigChecked = new TreeMap<>();
    private final TreeMap<Integer, Integer> componentCount = new TreeMap<>();

    public UiRemap(Ctx ctx) {
        this.ctx = ctx;
        this.out = ctx.out != null ? ctx.out : ctx.base;
        this.scope = UiScope.of(ctx);
        customScripts.addAll(scope.scripts);
        customInterfaces.addAll(scope.interfaces);
        this.fixups = Boolean.parseBoolean(ctx.opt.get("ui.fixups", "true")) ? UiData.fixups() : Map.of();
        this.remap = UiData.componentRemap(ctx.opt.get("ui.componentRemap", null));
    }

    void count(String key) { counts.merge(key, 1L, Long::sum); }

    /** Sprite / font ids (after remapping) the custom UI uses, with the first referrers. */
    public final TreeMap<Integer, TreeSet<String>> spriteUses = new TreeMap<>(), fontUses = new TreeMap<>();

    void use(TreeMap<Integer, TreeSet<String>> uses, int id, String where) {
        if (id < 0) return;
        TreeSet<String> w = uses.computeIfAbsent(id, k -> new TreeSet<>());
        if (w.size() < 4) w.add(where);
    }

    /** Records a problem once per key; {@link #flushProblems} reports them. */
    void problem(String key, String where) { problems.computeIfAbsent(key, k -> new TreeSet<>()).add(where); }

    /** Reports the collected problems as warnings (one per key, with up to 12 referrers). */
    public void flushProblems() {
        for (var e : problems.entrySet()) {
            List<String> w = List.copyOf(e.getValue());
            String refs = w.size() > 12 ? String.join(", ", w.subList(0, 12)) + " ... (" + w.size() + ")" : String.join(", ", w);
            ctx.report.warn(e.getKey() + " [from " + refs + "]");
        }
        problems.clear();
    }

    public void flushCounts() { for (var e : counts.entrySet()) ctx.report.count(e.getKey(), e.getValue()); }

    /** Reports the component rewrites as one note per script/interface. */
    public void flushRewrites() {
        for (var e : rewrites.entrySet()) {
            String[] w = e.getKey().split(" ");
            String kind = w[0].equals("interface") ? "interface" : "script";
            int id = Integer.parseInt(w[w.length - 1].split(":")[0]);
            ctx.report.add(com.gadscape.cacheconv.core.Report.Decision.NOTE, kind, id, "component refs rewritten: " + String.join(", ", e.getValue()));
        }
        rewrites.clear();
    }

    private void logRewrite(String where, int from, int to) {
        String key = where.startsWith("interface") ? "interface " + where.split(" ")[1].split(":")[0] : where;
        rewrites.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add((from >>> 16) + ":" + (from & 0xffff) + "->" + (to >>> 16) + ":" + (to & 0xffff));
    }

    // ------------------------------------------------------------------ scripts

    /** {@link #script} result for a hook whose target must be removed (see there). */
    public static final int DROP = Integer.MIN_VALUE;

    /** Vanilla 211 scripts ported for custom content (old id; new id in ctx.ids), and those still to convert. */
    public final TreeSet<Integer> ported = new TreeSet<>();
    public final java.util.ArrayDeque<Integer> portQueue = new java.util.ArrayDeque<>();

    /**
     * The id custom content must use for script {@code t} (gosub target or hook script). {@code hook} = called as a
     * hook (arguments arrive in locals) rather than by gosub (arguments on the stack).
     * <ul>
     *   <li>custom (or already ported) script: its id in ctx.ids;</li>
     *   <li>vanilla proc with a row in ui/script-signatures.tsv: the adapter;</li>
     *   <li>vanilla script that is gone in 241, has another name there (a different script) or another signature: the
     *       211 version is ported for GadScape content into the reserved script range (recorded in ctx.ids, converted
     *       like a custom script by {@link UiConvertStep}); a warning names it;</li>
     *   <li>a script the 211 cache does not have (a dangling reference, a no-op on 211) but BASE does: {@link #DROP}
     *       for hooks (the caller removes the hook), left as is and reported as an error for gosubs;</li>
     *   <li>otherwise the id itself (BASE's script, same name and signature).</li>
     * </ul>
     */
    public int script(int t, boolean hook, String where) {
        if (t < 0) return t;
        if (customScripts.contains(t) || ported.contains(t)) {
            int to = ctx.ids.map(Kind.SCRIPT, t);
            if (to != t) count(ported.contains(t) ? "script refs to ported 211 scripts" : "script refs to relocated custom scripts");
            return to;
        }
        if (scope.allScripts.contains(t)) {                              // custom, but outside a port-interface selection
            problem("custom script " + t + " is not part of this port (reference kept)", where);
            return t;
        }
        UiData.SigRule rule = rules.get(t);
        if (rule != null) { rulesUsed.add(t); count(hook ? "hooks redirected to adapters" : "gosubs redirected to adapters"); return rule.adapter(); }
        byte[] u = ctx.user().single(Arch.SCRIPTS, t);
        byte[] o = out.single(Arch.SCRIPTS, t);
        if (u == null) {
            if (o == null) { problem("script " + t + " is called but exists in no cache (a no-op, as on 211)", where); return t; }
            if (hook) {
                problem("hook script " + t + " is missing in the 211 cache (a no-op there) but BASE has a different script at that id: hook removed", where);
                count("dangling hooks removed");
                return DROP;
            }
            if (sigChecked.put(t, true) == null) ctx.report.error("script", t, "gosub target is missing in the 211 cache but BASE has a script at that id (called by " + where + ")");
            return t;
        }
        String why = null;
        if (o == null) why = "is gone in 241";
        else {
            int nu = ctx.user().groupName(Arch.SCRIPTS, t), no = out.groupName(Arch.SCRIPTS, t);
            int[] a = ScriptTrailer.args211(u), b = ScriptTrailer.args241WithLongs(o);
            if (nu != -1 && no != -1 && nu != no) why = "is a different script in 241 (name hash " + nu + " -> " + no + ")";
            else if (a[0] != b[0] || a[1] != b[1] || b[2] != 0)
                why = "takes " + b[0] + " int/" + b[1] + " obj/" + b[2] + " long args in 241 but " + a[0] + "/" + a[1] + " in 211 (no adapter rule)";
        }
        if (why == null) return t;
        int to = ctx.ids.relocate(Kind.SCRIPT, t, "211 vanilla script " + why + ": its 211 version is ported for GadScape content (211-world references only)");
        ported.add(t);
        portQueue.add(t);
        ctx.report.relocated(Kind.SCRIPT, t, to, "vanilla 211 script " + why + "; 211 version ported (first used by " + where + ")");
        ctx.report.warn("script", t, "vanilla 211 script " + why + ": GadScape content now calls a port of the 211 version at " + to + " (review)");
        count("vanilla 211 scripts ported");
        return to;
    }

    // ------------------------------------------------------------------ components

    private int components(int iface) {
        return componentCount.computeIfAbsent(iface, g -> {
            int[] f = ctx.user().fileIds(Arch.INTERFACES, g);
            return f.length == 0 ? 0 : f[f.length - 1] + 1;
        });
    }

    /** Per vanilla interface without remap rows: "identical" / "same shape" per 211 component, or null (changed). */
    private final TreeMap<Integer, String[]> unchanged = new TreeMap<>();

    /**
     * For a vanilla interface the remap table does not cover: whether 211 component {@code c} is still the same
     * component in 241 at the same id, either byte-identical after the 211 -> 241 conversion ("identical"), or with the
     * same kind, type, parent and position ("same shape": only texts, colours or the like changed). Null otherwise.
     */
    String unchanged(int g, int c) {
        String[] st = unchanged.computeIfAbsent(g, k -> {
            com.gadscape.cacheconv.core.GroupData v = ctx.van211().group(Arch.INTERFACES, k), b = out.group(Arch.INTERFACES, k);
            if (v == null || b == null) return new String[0];
            com.gadscape.cacheconv.core.GroupData vc = com.gadscape.cacheconv.convert.InterfaceConv.group(v);
            String[] r = new String[vc.fileIdAt(vc.fileCount() - 1) + 1];
            for (int i = 0; i < vc.fileCount(); i++) {
                int f = vc.fileIdAt(i);
                byte[] x = vc.dataAt(i), y = b.dataRef(f);
                if (y == null) continue;
                IfRefs.Comp px;
                try { px = IfRefs.parse(x); } catch (RuntimeException e) { continue; }
                byte[] t = px.end() < x.length && com.gadscape.cacheconv.core.Bytes.isZero(x, px.end(), x.length) ? Arrays.copyOf(x, px.end()) : x;
                if (Arrays.equals(t, y)) r[f] = "identical";
                else if (Arrays.equals(IfRefs.shape(x), IfRefs.shape(y))) r[f] = "same shape";
            }
            return r;
        });
        return c < st.length ? st[c] : null;
    }

    /** Whether a value names an existing component of a 211 interface (custom or vanilla) in USER. */
    public boolean looksLikeComponent(int v) {
        if (v < 0 || CsSim.isEventConstant(v)) return false;
        int g = v >>> 16, c = v & 0xffff;
        return g > 0 && ctx.user().hasGroup(Arch.INTERFACES, g) && c < components(g);
    }

    /**
     * Maps a 211 component id used by custom content ({@code where} = "script:ID" / "interface:ID" for fixups).
     */
    public int component(int v, String fixKey, String where) {
        if (v < 0) return v;                                             // null / event values are never component ids
        int g = v >>> 16, c = v & 0xffff;
        List<UiData.Fixup> fx = fixups.get(fixKey);
        if (fx != null) for (UiData.Fixup f : fx) {
            if (f.from() != g) continue;
            if (c < components(f.to())) {
                count("component fixups (" + f.finding() + ")");
                String[] k = fixKey.split(":");
                ctx.report.add(com.gadscape.cacheconv.core.Report.Decision.NOTE, k[0], Integer.parseInt(k[1]), f.finding() + ": " + g + ":" + c + " -> " + f.to() + ":" + c);
                g = f.to();
            } else problem("fixup " + f.finding() + " skipped: " + f.to() + ":" + c + " does not exist", where);
            break;
        }
        if (customInterfaces.contains(g)) {
            int to = ctx.ids.map(Kind.INTERFACE, g);
            if (to != g) count("component refs to relocated custom interfaces");
            if (((to << 16) | c) != v) logRewrite(where, v, (to << 16) | c);
            return (to << 16) | c;
        }
        if (scope.allInterfaces.contains(g)) {                         // custom, but outside a port-interface selection
            problem("custom interface " + g + " is not part of this port (component " + g + ":" + c + " kept)", where);
            return (g << 16) | c;
        }
        if (ctx.van211().hasGroup(Arch.INTERFACES, g)) {
            if (c >= components(g)) {
                problem("component " + g + ":" + c + " does not exist in the 211 interface (" + components(g) + " components; dangling there, a no-op): kept, "
                        + (c < out.fileIds(Arch.INTERFACES, g).length ? "but 241's " + g + ":" + c + " exists" : "still dangling on 241"), where);
                count("dangling vanilla component refs kept");
                return (g << 16) | c;
            }
            UiData.CompRow r = remap.get((g << 16) | c);
            if (r == null && !remap.covers(g)) {
                String how = unchanged(g, c);
                if (how != null) { count("vanilla component refs unchanged (" + how + " in 241, no remap row)"); return (g << 16) | c; }
            }
            if (r == null) {
                if (!out.hasGroup(Arch.INTERFACES, g)) problem("vanilla interface " + g + " is gone in 241", where);
                else problem("vanilla component " + g + ":" + c + " has no 211->241 remap row" + (remap.covers(g) ? "" : " and changed in 241") + " (kept as is, unverified)", where);
                return (g << 16) | c;
            }
            if (r.to() < 0) { problem("vanilla component " + g + ":" + c + " has no 241 counterpart (" + r.confidence() + ", kept)", where); return (g << 16) | c; }
            if (r.packed() == ((g << 16) | c)) { count("vanilla component refs unchanged"); return (g << 16) | c; }
            count("vanilla component refs remapped (" + r.confidence() + ")");
            logRewrite(where, v, r.packed());
            return r.packed();
        }
        if (!ctx.user().hasGroup(Arch.INTERFACES, g)) problem("component " + g + ":" + c + " names an interface that is in no 211 cache", where);
        return (g << 16) | c;
    }

    // ------------------------------------------------------------------ typed values

    /** Maps a constant of a cs2 type (as {@link CsSim} typed it). */
    public int typed(String type, int v, String fixKey, String where) {
        if (v == -1 || v == Integer.MIN_VALUE) return v;               // null values, never ids
        if (type.indexOf('|') >= 0) {                                  // used as several types: rewrite only if no reading moves it
            for (String t : type.split("\\|")) if (!t.equals("clientscript") && typedQuiet(t, v) != v) { problem("constant " + v + " is used as " + type + " and one of them moved: left as is", where); break; }
            return v;
        }
        return switch (type) {
            case "component" -> component(v, fixKey, where);
            case "interface" -> customInterfaces.contains(v) ? ctx.ids.map(Kind.INTERFACE, v) : v;
            case "clientscript" -> script(v, true, where);
            case "graphic" -> kind(Kind.SPRITE, v);
            case "model" -> kind(Kind.MODEL, v);
            case "seq" -> kind(Kind.SEQ, v);
            case "obj", "namedobj" -> kind(Kind.OBJ, v);
            case "npc" -> kind(Kind.NPC, v);
            case "loc" -> kind(Kind.LOC, v);
            case "spotanim" -> kind(Kind.SPOT, v);
            case "enum" -> kind(Kind.ENUM, v);
            case "struct" -> kind(Kind.STRUCT, v);
            case "param" -> kind(Kind.PARAM, v);
            case "dbrow" -> kind(Kind.DBROW, v);
            case "dbtable" -> kind(Kind.DBTABLE, v);
            case "varp" -> kind(Kind.VARP, v);
            case "dbcolumn" -> {
                int t = v >>> 12, nt = ctx.ids.map(Kind.DBTABLE, t);
                if (nt == t) yield v;
                count("dbcolumn refs to relocated dbtables");
                yield (nt << 12) | (v & 0xfff);
            }
            default -> v;
        };
    }

    /** What {@link #typed} would give, without counting or reporting (for ambiguous constants). */
    private int typedQuiet(String type, int v) {
        return switch (type) {
            case "component" -> { int g = v >>> 16; yield customInterfaces.contains(g) ? (ctx.ids.map(Kind.INTERFACE, g) << 16) | (v & 0xffff) : v; }
            case "graphic" -> ctx.ids.map(Kind.SPRITE, v);
            case "model" -> ctx.ids.map(Kind.MODEL, v);
            case "seq" -> ctx.ids.map(Kind.SEQ, v);
            case "enum" -> ctx.ids.map(Kind.ENUM, v);
            case "struct" -> ctx.ids.map(Kind.STRUCT, v);
            case "param" -> ctx.ids.map(Kind.PARAM, v);
            case "dbrow" -> ctx.ids.map(Kind.DBROW, v);
            case "varp" -> ctx.ids.map(Kind.VARP, v);
            default -> v;
        };
    }

    public int kind(Kind k, int v) {
        int to = ctx.ids.map(k, v);
        if (to != v) count(k.key + " refs rewritten");
        return to;
    }

    /** Checks that an asset a custom interface/script needs is in the output. */
    public void requireGroup(int archive, int id, String what, String where) {
        if (id < 0) return;
        if (!out.hasGroup(archive, id)) problem(what + " " + id + " is not in the output cache", where);
    }

    @Override public String toString() { return "UiRemap(scripts " + customScripts.size() + ", interfaces " + customInterfaces.size() + ", remap " + remap.size() + " rows from " + remap.source + ")"; }

    static String ids(java.util.Collection<Integer> c) { return Arrays.toString(c.stream().mapToInt(Integer::intValue).toArray()); }
}
