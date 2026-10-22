package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Origin;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Which of the source (USER) cache's own interfaces and clientscripts the UI steps convert: by default every one that
 * VAN211 lacks (spec policy 8: GadScape's custom UI). With {@code --ui.interfaces=1600,1700-1702} only those
 * interfaces and the scripts in {@code --ui.scripts=...} (for scripts only the server runs), plus every custom script
 * they reach (hook scripts, gosub targets and hook-setter script constants, transitively). This is what {@code gadconv port-interface} uses to port
 * single interfaces from any incoming 211 cache. One scope (and one script analysis) per build.
 */
public final class UiScope {
    /** Custom interfaces and scripts in scope (211 ids), ascending. */
    public final TreeSet<Integer> interfaces = new TreeSet<>(), scripts = new TreeSet<>();
    /** The {@link #VANILLA_PORTS} in scope (USER has them, BASE reused the id). */
    public final TreeSet<Integer> vanillaPorts = new TreeSet<>();
    /** Every interface / script the source cache adds over VAN211 (in scope or not). */
    public final TreeSet<Integer> allInterfaces = new TreeSet<>(), allScripts = new TreeSet<>();
    /** True when the scope is the whole custom set (no selection). */
    public final boolean all;

    /**
     * Vanilla 211 interfaces the GadScape server still opens whose id 241 gave to an unrelated interface: their 211
     * version is ported like custom UI (converted, relocated into the reserved interface range, hook scripts that 241
     * changed ported too) and listed under {@code _vanilla} in idmap.json, so the server's constant follows the map.
     */
    public static final java.util.Map<Integer, String> VANILLA_PORTS = java.util.Map.of(
            436, "211 gnome cocktail shaker screen (GameInterface.GNOME_COCKTAIL); 241 reuses 436 for soul_wars_stats",
            310, "211 poll history screen (PollManager.HISTORY_INTERFACE_ID); 241 reuses 310 for gryphon_boss",
            345, "211 poll screen (PollManager.INTERFACE_ID); 241 reuses 345 for sailing_intro_hud",
            400, "211 poll voting screen (PollManager.VOTING_INTERFACE_ID); 241 reuses 400 for trr_display");

    /**
     * Vanilla 211 clientscripts only the GadScape server runs (RUNCLIENTSCRIPT) whose 241 script at the id is a different
     * one: ported like {@link #VANILLA_PORTS} (the poll screens' scripts), listed under {@code _vanilla}.
     */
    public static final java.util.Map<Integer, String> VANILLA_SCRIPT_PORTS = java.util.Map.of(
            603, "poll screen setup (PollManager); 241's 603 is another script",
            604, "poll screen setup (PollManager); 241's 604 is another script",
            609, "poll description layout (PollManager); 241's 609 is another script",
            610, "poll answer layout (PollManager); 241's 610 is another script");
    private ScriptSet set;
    private TreeMap<Integer, CsSim.Result> results;

    private static final Map<Ctx, UiScope> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    public static UiScope of(Ctx ctx) { return CACHE.computeIfAbsent(ctx, UiScope::new); }

    private UiScope(Ctx ctx) {
        var user = ctx.user();
        var van = ctx.van211();
        for (int id : user.groupIds(Arch.INTERFACES)) if (Origin.ofGroup(Arch.INTERFACES, id, user, van) == Origin.USER_ADDED) allInterfaces.add(id);
        for (int id : user.groupIds(Arch.SCRIPTS)) if (Origin.ofGroup(Arch.SCRIPTS, id, user, van) == Origin.USER_ADDED) allScripts.add(id);
        String ifs = ctx.opt.get("ui.interfaces", ""), css = ctx.opt.get("ui.scripts", "");
        all = ifs.isEmpty() && css.isEmpty();
        if (all) {
            interfaces.addAll(allInterfaces);
            scripts.addAll(allScripts);
            if (Boolean.parseBoolean(ctx.opt.get("ui.vanillaPorts", "true")))
                for (int id : new TreeSet<>(VANILLA_PORTS.keySet())) if (user.hasGroup(Arch.INTERFACES, id) && ctx.base.hasGroup(Arch.INTERFACES, id)) { interfaces.add(id); vanillaPorts.add(id); }
            return;
        }
        for (int id : ids(ifs)) {
            if (!user.hasGroup(Arch.INTERFACES, id)) throw new IllegalArgumentException("--ui.interfaces: interface " + id + " is not in the source cache");
            if (!allInterfaces.contains(id)) throw new IllegalArgumentException("--ui.interfaces: interface " + id + " is a vanilla interface (the UI merge handles edited vanilla interfaces)");
            interfaces.add(id);
        }
        for (int id : ids(css)) {
            if (!allScripts.contains(id)) throw new IllegalArgumentException("--ui.scripts: script " + id + " is not a custom script of the source cache");
            scripts.add(id);
        }
        closure(ctx);
    }

    /** Adds the custom scripts the selected interfaces and scripts reach, transitively. */
    private void closure(Ctx ctx) {
        ArrayDeque<Integer> todo = new ArrayDeque<>(scripts);
        for (int id : interfaces) {
            GroupData g = InterfaceConv.group(ctx.user().group(Arch.INTERFACES, id));
            for (int i = 0; i < g.fileCount(); i++) {
                try {
                    for (IfRefs.Ref r : IfRefs.parse(g.dataAt(i)).refs())
                        if (r.field() == IfRefs.Field.HOOK_SCRIPT && allScripts.contains(r.value()) && scripts.add(r.value())) todo.add(r.value());
                } catch (RuntimeException e) {
                    // reported by ui-convert
                }
            }
        }
        TreeMap<Integer, CsSim.Result> res = analysis(ctx);
        while (!todo.isEmpty()) {
            int id = todo.poll();
            CsSim.Result r = res.get(id);
            if (r == null) continue;
            var code = set.script(id).code;
            TreeSet<Integer> next = new TreeSet<>();
            for (CsSim.Call c : r.calls) next.add(c.target());
            for (var e : r.constType.entrySet()) if (e.getValue().equals("clientscript")) next.add(code.get(e.getKey()).i);
            for (int t : next) if (allScripts.contains(t) && scripts.add(t)) todo.add(t);
        }
    }

    /** The source cache's scripts analysed together (computed once per build). */
    public synchronized TreeMap<Integer, CsSim.Result> analysis(Ctx ctx) {
        if (results == null) { set = new ScriptSet(ctx.user()); results = set.analyseAll(); }
        return results;
    }

    /** The analysed script set ({@link #analysis} first). */
    public synchronized ScriptSet scriptSet(Ctx ctx) { analysis(ctx); return set; }

    /** "1600,1700-1702" -> ids. */
    public static TreeSet<Integer> ids(String spec) {
        TreeSet<Integer> r = new TreeSet<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-', 1);
            if (dash > 0) { int lo = Integer.parseInt(part.substring(0, dash).trim()), hi = Integer.parseInt(part.substring(dash + 1).trim()); for (int i = lo; i <= hi; i++) r.add(i); }
            else r.add(Integer.parseInt(part));
        }
        return r;
    }

    @Override public String toString() {
        return all ? "all custom UI (" + interfaces.size() + " interfaces, " + scripts.size() + " scripts)"
                : "selected " + interfaces.size() + " interfaces " + UiRemap.ids(interfaces) + ", " + scripts.size() + " scripts";
    }
}
