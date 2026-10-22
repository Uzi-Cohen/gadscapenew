package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Origin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which entries of a 211 cache (USER) reference a set of model ids: obj, npc, loc, spotanim and identkit configs
 * (converted to 241 and walked with {@link ModelRefs}), interface components (IF3/IF1 type 6), enums with model
 * values, and model-typed params (param defaults and struct params). Each user is classified as "user" (the user added
 * or changed that entry, so it is layered into the output and its references must be rewritten) or "vanilla" (BASE's
 * version is used).
 * <p>
 * Not covered: clientscript constants and dbrow columns (owned by the UI and config steps, which map them with
 * {@code ctx.ids.map(Kind.MODEL, id)} / {@link ModelRefs#remapModel}), and model-typed params inside obj/npc/loc entries.
 */
public final class RefCensus {
    private final Set<Integer> watch;
    private final TreeMap<Integer, TreeMap<String, List<String>>> users = new TreeMap<>();
    private final TreeMap<Integer, List<String>> vanilla = new TreeMap<>();
    private final TreeMap<String, Integer> totals = new TreeMap<>();

    private RefCensus(Set<Integer> watch) { this.watch = watch; }

    /** Scans every config/interface of {@code c} (a 211 cache) for references to {@code watch}; {@code van} classifies users. */
    public static RefCensus scan(CacheView c, CacheView van, Set<Integer> watch) {
        RefCensus r = new RefCensus(watch);
        int[] groups = {Arch.CFG_OBJ, Arch.CFG_NPC, Arch.CFG_LOC, Arch.CFG_SPOT, Arch.CFG_IDENTKIT};
        for (int g : groups) {
            ConfigConv.Fn fn = ConfigConv.forGroup(g);
            String kind = kind(g);
            for (int id : c.configIds(g)) {
                byte[] b = c.configRef(g, id);
                if (b == null) continue;
                int[] ms;
                try { ms = ModelRefs.models(g, id, fn.apply(id, b), false); }
                catch (RuntimeException e) { r.totals.merge("unreadable." + kind, 1, Integer::sum); continue; }
                for (int m : ms) if (watch.contains(m)) r.hit(m, kind, String.valueOf(id), Origin.ofConfig(g, id, c, van).isUser());
            }
        }
        for (int g : c.groupIds(Arch.INTERFACES)) {
            GroupData gd = c.group(Arch.INTERFACES, g);
            if (gd == null) continue;
            Boolean user = null;
            for (int i = 0; i < gd.fileCount(); i++) {
                int[] ms;
                try { ms = ModelRefs.componentModels(InterfaceConv.component(gd.dataAt(i))); }
                catch (RuntimeException e) { r.totals.merge("unreadable.if", 1, Integer::sum); continue; }
                for (int m : ms) {
                    if (!watch.contains(m)) continue;
                    if (user == null) user = Origin.ofGroup(Arch.INTERFACES, g, c, van).isUser();
                    r.hit(m, "if", g + ":" + gd.fileIdAt(i), user);
                }
            }
        }
        c.release(Arch.INTERFACES);
        r.enums(c, van);
        r.params(c, van);
        return r;
    }

    private static String kind(int cfg) {
        return switch (cfg) { case Arch.CFG_OBJ -> "obj"; case Arch.CFG_NPC -> "npc"; case Arch.CFG_LOC -> "loc"; case Arch.CFG_SPOT -> "spot"; case Arch.CFG_IDENTKIT -> "kit"; default -> Arch.configName(cfg); };
    }

    private void hit(int model, String kind, String who, boolean user) {
        totals.merge(kind + (user ? ".user" : ".vanilla"), 1, Integer::sum);
        List<String> l = user ? users.computeIfAbsent(model, k -> new TreeMap<>()).computeIfAbsent(kind, k -> new ArrayList<>())
                : vanilla.computeIfAbsent(model, k -> new ArrayList<>());
        String w = user ? who : kind + " " + who;
        if (l.isEmpty() || !l.get(l.size() - 1).equals(w)) l.add(w); // one entry per user even if it references the model twice
    }

    /** Enums (211 layout): keys/values of type 'm'. */
    private void enums(CacheView c, CacheView van) {
        for (int id : c.configIds(Arch.CFG_ENUM)) {
            byte[] b = c.configRef(Arch.CFG_ENUM, id);
            if (b == null) continue;
            try {
                Buf in = new Buf(b);
                int keyType = 0, valType = 0;
                List<int[]> ints = new ArrayList<>();
                int dflt = Integer.MIN_VALUE;
                while (true) {
                    int op = in.u8();
                    if (op == 0) break;
                    switch (op) {
                        case 1 -> keyType = in.u8();
                        case 2 -> valType = in.u8();
                        case 3 -> in.skipStr();
                        case 4 -> dflt = in.i32();
                        case 5 -> { int n = in.u16(); for (int i = 0; i < n; i++) { in.skip(4); in.skipStr(); } }
                        case 6 -> { int n = in.u16(); for (int i = 0; i < n; i++) ints.add(new int[]{in.i32(), in.i32()}); }
                        default -> throw new IllegalStateException("enum op " + op);
                    }
                }
                Boolean user = null;
                for (int[] kv : ints) {
                    boolean k = keyType == ModelRefs.MODEL_TYPE_CHAR && watch.contains(kv[0]), v = valType == ModelRefs.MODEL_TYPE_CHAR && watch.contains(kv[1]);
                    if (!k && !v) continue;
                    if (user == null) user = Origin.ofConfig(Arch.CFG_ENUM, id, c, van).isUser();
                    hit(k ? kv[0] : kv[1], "enum", id + "[" + kv[0] + "]", user);
                }
                if (valType == ModelRefs.MODEL_TYPE_CHAR && watch.contains(dflt)) hit(dflt, "enum", id + "[default]", Origin.ofConfig(Arch.CFG_ENUM, id, c, van).isUser());
            } catch (RuntimeException e) { totals.merge("unreadable.enum", 1, Integer::sum); }
        }
    }

    /** Params of type 'm': their defaults, and their values in structs. */
    private void params(CacheView c, CacheView van) {
        Map<Integer, Integer> type = new HashMap<>();
        for (int id : c.configIds(Arch.CFG_PARAM)) {
            byte[] b = c.configRef(Arch.CFG_PARAM, id);
            if (b == null) continue;
            try {
                Buf in = new Buf(b);
                int t = 0, dflt = Integer.MIN_VALUE;
                while (true) {
                    int op = in.u8();
                    if (op == 0) break;
                    switch (op) {
                        case 1 -> t = in.u8();
                        case 2 -> dflt = in.i32();
                        case 4 -> { }
                        case 5 -> in.skipStr();
                        default -> throw new IllegalStateException("param op " + op);
                    }
                }
                type.put(id, t);
                if (t == ModelRefs.MODEL_TYPE_CHAR) {
                    totals.merge("model-typed-params", 1, Integer::sum);
                    if (watch.contains(dflt)) hit(dflt, "param", id + "[default]", Origin.ofConfig(Arch.CFG_PARAM, id, c, van).isUser());
                }
            } catch (RuntimeException e) { totals.merge("unreadable.param", 1, Integer::sum); }
        }
        for (int id : c.configIds(Arch.CFG_STRUCT)) {
            byte[] b = c.configRef(Arch.CFG_STRUCT, id);
            if (b == null) continue;
            try {
                Buf in = new Buf(b);
                while (true) {
                    int op = in.u8();
                    if (op == 0) break;
                    if (op != 249) throw new IllegalStateException("struct op " + op);
                    int n = in.u8();
                    for (int i = 0; i < n; i++) {
                        int str = in.u8(), key = in.u24();
                        if (str == 1) { in.skipStr(); continue; }
                        if (str == 2) { in.skip(8); continue; }
                        int v = in.i32();
                        if (type.getOrDefault(key, 0) == ModelRefs.MODEL_TYPE_CHAR && watch.contains(v))
                            hit(v, "struct", id + "[param " + key + "]", Origin.ofConfig(Arch.CFG_STRUCT, id, c, van).isUser());
                    }
                }
            } catch (RuntimeException e) { totals.merge("unreadable.struct", 1, Integer::sum); }
        }
    }

    /** Reference counts per "kind.user" / "kind.vanilla" (references, not distinct models). */
    public Map<String, Integer> totals() { return totals; }

    /** How many user-origin entries reference the model. */
    public int refCount(int model) {
        int n = 0;
        TreeMap<String, List<String>> m = users.get(model);
        if (m != null) for (List<String> l : m.values()) n += l.size();
        return n;
    }

    /** Untouched vanilla entries that reference the model ("obj 1234", ...). */
    public List<String> vanillaUsers(int model) { return vanilla.getOrDefault(model, List.of()); }

    /** "obj 34715,34723; if 5004:12" (user-origin entries only, at most 6 per kind). */
    public String describe(int model) {
        TreeMap<String, List<String>> m = users.get(model);
        if (m == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            List<String> l = e.getValue();
            sb.append(e.getKey()).append(' ').append(String.join(",", l.subList(0, Math.min(6, l.size()))));
            if (l.size() > 6) sb.append(" +").append(l.size() - 6).append(" more");
        }
        return sb.toString();
    }
}
