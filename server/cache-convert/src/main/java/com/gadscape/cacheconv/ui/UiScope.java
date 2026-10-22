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
    /** Every interface / script the source cache adds over VAN211 (in scope or not). */
    public final TreeSet<Integer> allInterfaces = new TreeSet<>(), allScripts = new TreeSet<>();
    /** True when the scope is the whole custom set (no selection). */
    public final boolean all;
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
        if (all) { interfaces.addAll(allInterfaces); scripts.addAll(allScripts); return; }
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
    static TreeSet<Integer> ids(String spec) {
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
