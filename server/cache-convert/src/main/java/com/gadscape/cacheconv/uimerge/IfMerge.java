package com.gadscape.cacheconv.uimerge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

/**
 * Three-way merge of one interface, field by field: what GadScape changed (VAN211 -> USER) is re-applied onto BASE's
 * version of the interface.
 * <ol>
 *   <li>USER's components are paired with VAN211's (by id, or by content for an interface the user re-laid out) and
 *       put into VAN211 numbering (parents and same-interface component arguments);</li>
 *   <li>every changed field is applied to the BASE component the 211 -> BASE remap names, when BASE still has VAN211's
 *       value there; when BASE changed the field too, content fields (texts, ops, hooks, sprites, flags) take USER's
 *       value and layout fields keep BASE's, and either way the conflict is logged. ops and clickMask merge per
 *       element / bit;</li>
 *   <li>components the user added are appended after BASE's last component (parents and arguments renumbered);</li>
 *   <li>components the user removed stay in BASE (logged).</li>
 * </ol>
 * Fields set from USER values are listed per component ({@link Comp#refs}) when they hold ids that can be relocated
 * (sprites, models, seqs, hook scripts and typed hook arguments, var triggers) so the build step maps them through the
 * id map.
 */
public final class IfMerge {
    public enum Align { ID, CONTENT }

    /** Per-interface options (decided by hand in {@link UiMergeSpecs}). */
    public static final class Options {
        public Align align = Align.ID;
        /** Only append the components the user added (the rest of USER's interface is an older vanilla layout). */
        public boolean onlyAdded;
        /** Edits not to apply: "COMP211.field" or "*.field" (VAN211 numbering). */
        public final Set<String> skip = new HashSet<>();
        /** Layout fields where USER wins a conflict too: "COMP211.field" or "*.field". */
        public final Set<String> userWins = new HashSet<>();
        /** Extra edits on BASE components that carry the user's intent onto 241-only components: BASE comp -> field -> value. */
        public final TreeMap<Integer, LinkedHashMap<String, Object>> extra = new TreeMap<>();
        /** USER components never paired with a VAN211 one (always treated as added): GadScape's own components that look like vanilla ones. */
        public final Set<Integer> unpaired = new HashSet<>();
        /** Why (for the notes). */
        public final List<String> why = new ArrayList<>();
    }

    /** Services from the generator: component remap of other interfaces, hook script argument types, component test. */
    public interface Env {
        /** Maps a 211 component id (iface << 16 | comp) of another vanilla interface to BASE; the value itself if unknown. */
        int otherComponent(int packed);
        /** Types of a script's int arguments (USER analysis), or null. */
        String[] argTypes(int script);
        /** Whether a value looks like a component of an interface in USER. */
        boolean looksLikeComponent(int v);
    }

    /** A merged component that differs from BASE. */
    public static final class Comp {
        public final int id;
        public IfComp c;
        /** merged (edited BASE component), added (user component appended), user (whole USER component). */
        public final String origin;
        /** USER-sourced fields holding relocatable ids: "field:tag" or "hook#argIndex:tag". */
        public final java.util.TreeSet<String> refs = new java.util.TreeSet<>();
        public Comp(int id, IfComp c, String origin) { this.id = id; this.c = c; this.origin = origin; }
    }

    public static final class Result {
        public final int iface;
        public final TreeMap<Integer, Comp> comps = new TreeMap<>();
        /** USER component -> merged component (for the component remap TSV). */
        public final TreeMap<Integer, Integer> userToMerged = new TreeMap<>();
        /** USER component -> its VAN211 partner (components the user added have none). */
        public final TreeMap<Integer, Integer> userToVan = new TreeMap<>();
        public final List<String> applied = new ArrayList<>(), conflicts = new ArrayList<>(), skipped = new ArrayList<>(), added = new ArrayList<>();
        public Result(int iface) { this.iface = iface; }
    }

    private static final Set<String> LAYOUT = Set.of("x", "y", "width", "height", "widthMode", "heightMode", "xMode", "yMode",
            "scrollWidth", "scrollHeight", "lineHeight", "xAlign", "yAlign", "offX", "offY", "rotX", "rotY", "rotZ", "zoom", "m6a", "m6w", "m6h");

    public static Result merge(int g, TreeMap<Integer, IfComp> van, TreeMap<Integer, IfComp> user, TreeMap<Integer, IfComp> base,
                               TreeMap<Integer, CompRemap.Row> remap, Options o, Env env) {
        Result r = new Result(g);
        // 1. USER -> VAN211 pairing
        TreeMap<Integer, Integer> u2v = new TreeMap<>();
        if (o.align == Align.ID) { for (int c : user.keySet()) if (van.containsKey(c)) u2v.put(c, c); }
        else {
            TreeMap<Integer, IfComp> pool = new TreeMap<>(user);
            for (int c : o.unpaired) pool.remove(c);
            u2v.putAll(CompRemap.align(pool, van, 0.45));
        }
        for (int c : o.unpaired) u2v.remove(c);
        TreeMap<Integer, Integer> v2u = new TreeMap<>();
        for (var e : u2v.entrySet()) v2u.put(e.getValue(), e.getKey());
        r.userToVan.putAll(u2v);
        // merged id of every USER component: paired -> BASE counterpart (or -1); added -> appended after BASE
        int next = base.isEmpty() ? 0 : base.lastKey() + 1;
        for (int c : user.keySet()) {
            Integer v = u2v.get(c);
            if (v != null) { CompRemap.Row row = remap.get(v); r.userToMerged.put(c, row == null ? -1 : row.c241()); }
            else r.userToMerged.put(c, next++);
        }
        IntUnaryOperator userComp = c -> r.userToMerged.getOrDefault(c, -1);

        // 2. edits on paired components
        TreeMap<Integer, IfComp> work = new TreeMap<>();
        java.util.function.IntFunction<IfComp> edit = b -> work.computeIfAbsent(b, k -> base.get(k).copy());
        TreeMap<Integer, java.util.TreeSet<String>> refs = new TreeMap<>();
        if (!o.onlyAdded) {
            for (var e : u2v.entrySet()) {
                int uc = e.getKey(), vc = e.getValue();
                IfComp u = canonical(g, user.get(uc), u2v, user.keySet()), v = van.get(vc);
                List<String> diff = IfComp.diff(v, u);
                diff.remove("*tail");
                if (diff.isEmpty()) continue;
                CompRemap.Row row = remap.get(vc);
                int bc = row == null ? -1 : row.c241();
                String where = g + ":" + vc + (uc != vc ? " (USER " + uc + ")" : "");
                if (bc < 0 || !base.containsKey(bc)) { r.skipped.add(where + " " + diff + ": no BASE counterpart (" + (row == null ? "no remap row" : row.method()) + ")"); continue; }
                IfComp b0 = base.get(bc);
                double sim = CompRemap.similarity(v, b0);
                String to = " -> BASE " + g + ":" + bc + (sim < 0.5 ? String.format(" [VAN211/BASE similarity %.2f: check the pairing]", sim) : "");
                if (diff.contains("*raw") || diff.contains("type")) {
                    // whole component replaced (IF1, or the user changed its type)
                    if (Arrays.equals(b0.encode(), v.encode())) {
                        IfComp n = toMerged(g, u, vc2b(remap), userComp, env, true);
                        work.put(bc, n);
                        refs.computeIfAbsent(bc, k -> new java.util.TreeSet<>()).addAll(allRefs(n, env));
                        r.applied.add(where + to + ": whole component replaced (" + (diff.contains("type") ? "type " + v.i("type") + " -> " + u.i("type") : "IF1") + ")");
                    } else r.conflicts.add(where + to + ": user replaced the whole component but BASE changed it too: BASE kept");
                    continue;
                }
                for (String f : diff) {
                    if (o.skip.contains(vc + "." + f) || o.skip.contains("*." + f)) { r.skipped.add(where + " " + f + ": skipped by the merge spec"); continue; }
                    Object vv = v.f.get(f), uv = u.f.get(f), bv = b0.f.get(f);
                    Object want = mapValue(g, f, uv, vc2b(remap), userComp, env);
                    boolean baseKept = eq(norm(bv), norm(vv)) || eq(norm(bv), norm(mapValue(g, f, vv, vc2b(remap), c -> -1, env)));
                    String desc = f + ": " + IfComp.show(vv) + " -> " + IfComp.show(uv);
                    if (f.equals("ops")) {
                        List<Object> lv = listOf(vv), lu = listOf(uv), lb = listOf(bv);
                        List<Object> m = mergeList(lv, lu, lb);
                        List<String> clash = new ArrayList<>();
                        for (int i = 0; i < lu.size(); i++) {
                            Object vi = i < lv.size() ? lv.get(i) : null, ui = lu.get(i), bi = i < lb.size() ? lb.get(i) : null;
                            if (!Objects.equals(vi, ui) && !Objects.equals(bi, vi) && !Objects.equals(bi, ui) && !(bi instanceof String bs && bs.isEmpty() && vi == null))
                                clash.add("op" + (i + 1) + " BASE " + IfComp.show(bi) + " / USER " + IfComp.show(ui));
                        }
                        IfComp n = edit.apply(bc);
                        n.f.put("ops", m);
                        if (!clash.isEmpty()) r.conflicts.add(where + to + " " + desc + ": BASE changed the same op slot (" + String.join("; ", clash) + "): USER op used");
                        else r.applied.add(where + to + " " + desc + (eq(norm(m), norm(want)) ? "" : " (merged with BASE's ops " + IfComp.show(bv) + " -> " + IfComp.show(m) + ")"));
                        continue;
                    }
                    if (f.equals("clickMask")) {
                        int vi = (Integer) vv, ui = (Integer) uv, bi = (Integer) bv, flip = vi ^ ui;
                        int m = (bi & ~flip) | (ui & flip);
                        edit.apply(bc).f.put(f, m);
                        r.applied.add(where + to + " " + desc + (m != ui ? " (bits merged with BASE's " + bi + " -> " + m + ")" : ""));
                        continue;
                    }
                    if (eq(norm(bv), norm(want))) { r.applied.add(where + to + " " + desc + " (BASE already has it)"); continue; }
                    boolean layout = LAYOUT.contains(f) && !o.userWins.contains(vc + "." + f) && !o.userWins.contains("*." + f);
                    if (!baseKept) {
                        if (layout) { r.conflicts.add(where + to + " " + desc + ": BASE changed it too (" + IfComp.show(bv) + "), layout field: BASE kept"); continue; }
                        // hook with the same script and arity on both sides: merge the arguments one by one
                        if (Arrays.asList(IfComp.HOOKS).contains(f) && vv instanceof List<?> && uv instanceof List<?> && bv instanceof List<?>) {
                            List<Object> mv = mapHook(g, listOf(vv), vc2b(remap), c -> -1, env), mu = listOf(want), mb = listOf(bv);
                            if (mv.size() == mu.size() && mu.size() == mb.size() && Objects.equals(mv.get(0), mb.get(0))) {
                                List<Object> m = mergeList(mv, mu, mb);
                                edit.apply(bc).f.put(f, m);
                                refs.computeIfAbsent(bc, k -> new java.util.TreeSet<>()).addAll(hookRefs(f, m, env, changedArgs(mv, mu)));
                                r.applied.add(where + to + " " + desc + " (argument-wise merge with BASE's " + IfComp.show(bv) + ")");
                                continue;
                            }
                        }
                        r.conflicts.add(where + to + " " + desc + ": BASE changed it too (" + IfComp.show(bv) + "): USER value used");
                    } else r.applied.add(where + to + " " + desc);
                    IfComp n = edit.apply(bc);
                    n.f.put(f, want);
                    if (f.equals("widthMode") && n.i("type") == 6 && !n.f.containsKey("m6w")) n.f.put("m6w", 0);
                    if (f.equals("heightMode") && n.i("type") == 6 && !n.f.containsKey("m6h")) n.f.put("m6h", 0);
                    refs.computeIfAbsent(bc, k -> new java.util.TreeSet<>()).addAll(fieldRefs(f, want, env, null));
                }
            }
            for (int vc : van.keySet()) if (!v2u.containsKey(vc)) r.skipped.add(g + ":" + vc + " is not in USER's interface (removed or an older layout): BASE " + (remap.get(vc) == null ? "?" : g + ":" + remap.get(vc).c241()) + " kept");
        }
        for (var e : o.extra.entrySet()) {
            IfComp n = edit.apply(e.getKey());
            for (var f : e.getValue().entrySet()) { n.f.put(f.getKey(), f.getValue()); r.applied.add(g + ": BASE " + e.getKey() + " " + f.getKey() + " = " + IfComp.show(f.getValue()) + " (merge spec: carries the user's intent onto a 241-only component)"); }
        }
        for (var e : work.entrySet()) {
            Comp c = new Comp(e.getKey(), e.getValue(), "merged");
            java.util.TreeSet<String> rf = refs.get(e.getKey());
            if (rf != null) c.refs.addAll(rf);
            if (!Arrays.equals(c.c.encode(), base.get(e.getKey()).encode())) r.comps.put(e.getKey(), c);
        }
        // 3. components the user added
        for (int uc : user.keySet()) {
            if (u2v.containsKey(uc)) continue;
            int id = r.userToMerged.get(uc);
            IfComp n = toMerged(g, canonical(g, user.get(uc), u2v, user.keySet()), vc2b(remap), userComp, env, true);
            Comp c = new Comp(id, n, "added");
            c.refs.addAll(allRefs(n, env));
            r.comps.put(id, c);
            r.added.add(g + ":" + uc + " (USER) -> " + g + ":" + id + ": " + summary(n));
        }
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static IntUnaryOperator vc2b(TreeMap<Integer, CompRemap.Row> remap) {
        return vc -> { CompRemap.Row row = remap.get(vc); return row == null ? -1 : row.c241(); };
    }

    /** USER component in VAN211 numbering: parent and same-interface component arguments of paired components. */
    static IfComp canonical(int g, IfComp u, Map<Integer, Integer> u2v, Set<Integer> userIds) {
        if (!u.if3) return u;
        IfComp c = u.dup();
        int p = c.i("parent");
        if (p >= 0) c.f.put("parent", u2v.containsKey(p) ? u2v.get(p) : -1000 - p);   // -1000-p: a user-added parent (resolved later)
        for (String h : IfComp.HOOKS) {
            List<Object> a = c.list(h);
            if (a == null) continue;
            List<Object> n = new ArrayList<>(a);
            for (int k = 1; k < n.size(); k++) {
                if (n.get(k) instanceof Integer v && v >= 0 && (v >>> 16) == g && userIds.contains(v & 0xffff)) {
                    int cc = v & 0xffff;
                    n.set(k, u2v.containsKey(cc) ? (g << 16) | u2v.get(cc) : (g << 16) | (0x8000 | cc));  // 0x8000|c: a user-added component
                }
            }
            c.f.put(h, n);
        }
        return c;
    }

    /** A canonical USER component in BASE numbering (parent, same-interface and other vanilla component arguments). */
    static IfComp toMerged(int g, IfComp u, IntUnaryOperator vc2b, IntUnaryOperator userComp, Env env, boolean trim) {
        IfComp c = CompRemap.trimmed(u.dup());
        if (!c.if3) return c;
        for (String f : new ArrayList<>(c.f.keySet())) c.f.put(f, mapValue(g, f, c.f.get(f), vc2b, userComp, env));
        return c;
    }

    /** Maps a canonical (VAN211-numbered) USER field value into BASE numbering. */
    static Object mapValue(int g, String f, Object v, IntUnaryOperator vc2b, IntUnaryOperator userComp, Env env) {
        if (f.equals("parent") && v instanceof Integer p) {
            if (p == -1) return -1;
            if (p <= -1000) return userComp.applyAsInt(-1000 - p);
            int b = vc2b.applyAsInt(p);
            return b;
        }
        if (Arrays.asList(IfComp.HOOKS).contains(f) && v instanceof List<?> l) return mapHook(g, listOf(l), vc2b, userComp, env);
        return v;
    }

    static List<Object> mapHook(int g, List<Object> a, IntUnaryOperator vc2b, IntUnaryOperator userComp, Env env) {
        if (a == null) return null;
        List<Object> n = new ArrayList<>(a);
        for (int k = 1; k < n.size(); k++) {
            if (!(n.get(k) instanceof Integer v) || v < 0) continue;
            int h = v >>> 16, c = v & 0xffff;
            if (h == g && (c & 0x8000) != 0) { int m = userComp.applyAsInt(c & 0x7fff); n.set(k, m < 0 ? v : (g << 16) | m); }
            else if (h == g) { int b = vc2b.applyAsInt(c); if (b >= 0) n.set(k, (g << 16) | b); }
            else if (env.looksLikeComponent(v)) n.set(k, env.otherComponent(v));
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static List<Object> listOf(Object v) { return v == null ? new ArrayList<>() : new ArrayList<>((List<Object>) v); }

    /**
     * Element-wise three-way merge: positions USER changed take USER's element; a string BASE changed too gets the
     * user's text edit transferred when BASE's string still contains the replaced part ({@link #transfer}).
     */
    static List<Object> mergeList(List<Object> v, List<Object> u, List<Object> b) {
        int n = Math.max(Math.max(v.size(), u.size()), b.size());
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object vi = i < v.size() ? v.get(i) : null, ui = i < u.size() ? u.get(i) : null, bi = i < b.size() ? b.get(i) : null;
            boolean same = Objects.equals(vi, ui) || (vi == null && "".equals(ui));   // "" pads a USER list up to a later op
            Object m = same ? bi : ui;
            if (!same && vi instanceof String vs && ui instanceof String us && bi instanceof String bs && !bs.equals(vs)) {
                String t = transfer(vs, us, bs);
                if (t != null) m = t;
            }
            if (m == null) { if (i < u.size() || i < b.size()) m = ""; else continue; }
            out.add(m);
        }
        // drop trailing entries USER removed and BASE never had
        while (out.size() > Math.max(u.size(), b.size())) out.remove(out.size() - 1);
        return out;
    }

    /**
     * The user's edit of a text (VAN211 {@code v} -> USER {@code u}) applied to BASE's text {@code b}: the changed middle
     * part (after the common prefix and suffix) is replaced in {@code b} when {@code b} contains it exactly once and
     * it is a whole word or more; null otherwise (then USER's text is used).
     */
    static String transfer(String v, String u, String b) {
        int p = 0;
        while (p < v.length() && p < u.length() && v.charAt(p) == u.charAt(p)) p++;
        int s = 0;
        while (s < v.length() - p && s < u.length() - p && v.charAt(v.length() - 1 - s) == u.charAt(u.length() - 1 - s)) s++;
        // widen to word boundaries
        while (p > 0 && Character.isLetterOrDigit(v.charAt(p - 1))) p--;
        while (s > 0 && Character.isLetterOrDigit(v.charAt(v.length() - s))) s--;
        String oldMid = v.substring(p, v.length() - s), newMid = u.substring(p, u.length() - s);
        if (oldMid.isBlank()) return null;
        int at = b.indexOf(oldMid);
        if (at < 0 || b.indexOf(oldMid, at + 1) >= 0) return null;
        return b.substring(0, at) + newMid + b.substring(at + oldMid.length());
    }

    static Set<Integer> changedArgs(List<Object> v, List<Object> u) {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < Math.max(v.size(), u.size()); i++) if (!Objects.equals(i < v.size() ? v.get(i) : null, i < u.size() ? u.get(i) : null)) s.add(i);
        return s;
    }

    private static Object norm(Object v) { return v instanceof List<?> l && l.isEmpty() ? null : v; }
    private static boolean eq(Object a, Object b) { return Objects.equals(a, b); }

    /** Relocatable references of a whole USER-sourced component. */
    static List<String> allRefs(IfComp c, Env env) {
        List<String> r = new ArrayList<>();
        if (!c.if3) return r;
        for (var e : c.f.entrySet()) r.addAll(fieldRefs(e.getKey(), e.getValue(), env, null));
        return r;
    }

    static List<String> fieldRefs(String f, Object v, Env env, Set<Integer> only) {
        List<String> r = new ArrayList<>();
        if (v == null) return r;
        switch (f) {
            case "sprite" -> { if ((Integer) v >= 0) r.add("sprite:graphic"); }
            case "model" -> { if ((Integer) v >= 0) r.add("model:model"); }
            case "anim" -> { if ((Integer) v >= 0) r.add("anim:seq"); }
            case "varTriggers" -> { List<?> l = (List<?>) v; for (int k = 0; k < l.size(); k++) r.add("varTriggers#" + k + ":varp"); }
            default -> { if (Arrays.asList(IfComp.HOOKS).contains(f) && v instanceof List<?> l) r.addAll(hookRefs(f, listOf(l), env, only)); }
        }
        return r;
    }

    /** Hook references: the script (argument 0) and typed or component-shaped int arguments. */
    static List<String> hookRefs(String h, List<Object> a, Env env, Set<Integer> only) {
        List<String> r = new ArrayList<>();
        if (a == null || a.isEmpty() || !(a.get(0) instanceof Integer script)) return r;
        if (only == null || only.contains(0)) r.add(h + "#0:script");
        String[] types = env.argTypes(script);
        int intArg = 0;
        for (int k = 1; k < a.size(); k++) {
            if (!(a.get(k) instanceof Integer v)) continue;
            String t = types != null && intArg < types.length ? types[intArg] : null;
            intArg++;
            if (only != null && !only.contains(k)) continue;
            if (v == -1 || (v < 0 && v <= Integer.MIN_VALUE + 64)) continue;
            if (t != null && RefKinds.movable(t)) r.add(h + "#" + k + ":" + t);
            else if ((t == null || t.equals("int")) && env.looksLikeComponent(v)) r.add(h + "#" + k + ":component");
        }
        return r;
    }

    static String summary(IfComp c) {
        if (!c.if3) return "IF1 type " + c.type();
        StringBuilder sb = new StringBuilder("type " + c.i("type"));
        if (c.f.containsKey("text") && !c.s("text").isEmpty()) sb.append(" \"").append(c.s("text")).append('"');
        if (c.f.containsKey("sprite")) sb.append(" sprite ").append(c.i("sprite"));
        List<Object> ops = c.list("ops");
        if (ops != null && !ops.isEmpty()) sb.append(" ops ").append(IfComp.show(ops));
        for (String h : IfComp.HOOKS) { List<Object> a = c.list(h); if (a != null && !a.isEmpty()) sb.append(' ').append(h).append(' ').append(a.get(0)); }
        sb.append(" parent ").append(c.i("parent"));
        return sb.toString();
    }

    /** Applies "field:tag" / "hook#k:tag" references of a component through a mapping (build time). */
    public static IfComp mapRefs(IfComp c, Iterable<String> refs, java.util.function.BiFunction<String, Integer, Integer> map) {
        if (!c.if3) return c;
        IfComp n = c.copy();
        for (String r : refs) {
            int colon = r.lastIndexOf(':');
            String path = r.substring(0, colon), tag = r.substring(colon + 1);
            int hash = path.indexOf('#');
            if (hash < 0) {
                Object v = n.f.get(path);
                if (v instanceof Integer i) n.f.put(path, map.apply(tag, i));
                continue;
            }
            String f = path.substring(0, hash);
            int k = Integer.parseInt(path.substring(hash + 1));
            List<Object> l = listOf(n.f.get(f));
            if (k < l.size() && l.get(k) instanceof Integer i) { l.set(k, map.apply(tag, i)); n.f.put(f, l); }
        }
        return n;
    }
}
