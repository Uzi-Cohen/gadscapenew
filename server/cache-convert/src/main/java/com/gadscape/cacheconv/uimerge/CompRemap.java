package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.GroupData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 211 -> BASE (2746) component mapping of one vanilla interface.
 * <ol>
 *   <li>The server docs table (docs/rev241/interface-remap.tsv, a copy in resources ui/interface-remap-211-241.tsv),
 *       built against osrs-dumps rev 241 with hand-checked anchors: every row is checked against BASE's component names
 *       (gamevals archive 24); a row whose name moved is re-pointed by name.</li>
 *   <li>Components the table does not cover (interfaces the server code started using later, the user-modified ones):
 *       an order-preserving alignment of VAN211's components with BASE's by content ({@link #similarity}), then a parent
 *       consistency check.</li>
 * </ol>
 */
public final class CompRemap {
    /** One mapped component: BASE id (-1 = none), confidence and how it was found. */
    public record Row(int c211, int c241, String confidence, String method) {}

    /** Rows of the docs table: interface -> (component211 -> row). */
    public static final class Table {
        public final TreeMap<Integer, TreeMap<Integer, String[]>> rows = new TreeMap<>();
        public boolean covers(int g) { return rows.containsKey(g); }
    }

    public static Table loadTable(Path path) {
        try (InputStream in = path != null ? Files.newInputStream(path) : CompRemap.class.getResourceAsStream("/ui/interface-remap-211-241.tsv")) {
            if (in == null) throw new IllegalStateException("remap table not found (resource ui/interface-remap-211-241.tsv)");
            Table t = new Table();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) {
                if (l.isBlank() || l.startsWith("#")) continue;
                String[] f = l.split("\t", -1);
                t.rows.computeIfAbsent(Integer.parseInt(f[0].trim()), k -> new TreeMap<>()).put(Integer.parseInt(f[2].trim()), f);
            }
            return t;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decoded components of a 211 group (converted to the 241 layout, zero padding dropped). */
    public static TreeMap<Integer, IfComp> comps211(GroupData g) {
        TreeMap<Integer, IfComp> m = new TreeMap<>();
        if (g == null) return m;
        for (int k = 0; k < g.fileCount(); k++) m.put(g.fileIdAt(k), trimmed(IfComp.decode(InterfaceConv.component(g.dataAt(k)))));
        return m;
    }

    public static TreeMap<Integer, IfComp> comps241(GroupData g) {
        TreeMap<Integer, IfComp> m = new TreeMap<>();
        if (g == null) return m;
        for (int k = 0; k < g.fileCount(); k++) m.put(g.fileIdAt(k), IfComp.decode(g.dataAt(k)));
        return m;
    }

    /** Drops zero padding after the component (kept: the 2 bytes of the type-6 width/height quirk). */
    public static IfComp trimmed(IfComp c) {
        if (!c.if3 || c.tail.length == 0) return c;
        boolean quirk = c.i("type") == 6 && (c.i("widthMode") != 0) != (c.i("heightMode") != 0) && c.tail.length == 2;
        if (!quirk && com.gadscape.cacheconv.core.Bytes.isZero(c.tail, 0, c.tail.length)) c.tail = new byte[0];
        return c;
    }

    // ------------------------------------------------------------------ similarity

    private static final String[] GEOM = {"contentType", "x", "y", "width", "height", "widthMode", "heightMode", "xMode", "yMode", "hidden"};

    /** Content similarity of two components in [0, 1] (0 when the kind or type differs); parents are not compared. */
    public static double similarity(IfComp a, IfComp b) {
        if (a.if3 != b.if3) return 0;
        if (!a.if3) {
            if (Arrays.equals(a.raw, b.raw)) return 1;
            if (a.raw.length < 16 || b.raw.length < 16 || a.type() != b.type()) return 0;
            // IF1 header: type, menuType, contentType, x, y, width, height, opacity, parent
            int[][] spans = {{1, 2}, {2, 4}, {4, 6}, {6, 8}, {8, 10}, {10, 12}, {13, 15}};
            int eq = 0;
            for (int[] sp : spans) if (Arrays.equals(a.raw, sp[0], sp[1], b.raw, sp[0], sp[1])) eq++;
            return 0.3 + 0.6 * eq / spans.length;
        }
        if (a.i("type") != b.i("type")) return 0;
        double same = 0, total = 0;
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(a.f.keySet());
        keys.addAll(b.f.keySet());
        for (String k : keys) {
            if (k.equals("parent") || k.equals("type")) continue;
            double w = 1;
            if (Arrays.asList(GEOM).contains(k)) w = 0.5;
            if (k.equals("text") || k.equals("sprite") || k.equals("model") || k.equals("ops") || k.equals("opBase")) w = 2;
            Object x = a.f.get(k), y = b.f.get(k);
            if (x == null && y == null) continue;
            if (x instanceof List<?> lx && y instanceof List<?> ly && !lx.isEmpty() && !ly.isEmpty() && lx.get(0) instanceof Integer sx && ly.get(0) instanceof Integer sy && Arrays.asList(IfComp.HOOKS).contains(k)) {
                total += 2;
                if (lx.equals(ly)) same += 2;
                else if (sx.equals(sy)) same += 1.5;                     // same hook script, other arguments
                continue;
            }
            total += w;
            if (Objects.equals(norm(x), norm(y))) same += w;
        }
        return total == 0 ? 1 : same / total;
    }

    private static Object norm(Object v) { return v instanceof List<?> l && l.isEmpty() ? null : v; }

    /**
     * Order-preserving alignment of a's components with b's maximising the summed similarity (pairs below
     * {@code min} are never matched). Returns a -> b (missing = no partner).
     */
    public static TreeMap<Integer, Integer> align(TreeMap<Integer, IfComp> a, TreeMap<Integer, IfComp> b, double min) {
        Integer[] ka = a.keySet().toArray(new Integer[0]), kb = b.keySet().toArray(new Integer[0]);
        int n = ka.length, m = kb.length;
        double[][] s = new double[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) s[i][j] = similarity(a.get(ka[i]), b.get(kb[j]));
        double[][] D = new double[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--) {
                double best = Math.max(D[i + 1][j], D[i][j + 1]);
                if (s[i][j] >= min) best = Math.max(best, D[i + 1][j + 1] + s[i][j] + (ka[i].equals(kb[j]) ? 0.01 : 0));
                D[i][j] = best;
            }
        TreeMap<Integer, Integer> out = new TreeMap<>();
        int[] ai = new int[n], bj = new int[m];
        Arrays.fill(ai, -1); Arrays.fill(bj, -1);
        int i = 0, j = 0;
        while (i < n && j < m) {
            double take = s[i][j] >= min ? D[i + 1][j + 1] + s[i][j] + (ka[i].equals(kb[j]) ? 0.01 : 0) : -1;
            if (take >= 0 && Math.abs(take - D[i][j]) < 1e-9) { out.put(ka[i], kb[j]); ai[i] = j; bj[j] = i; i++; j++; }
            else if (Math.abs(D[i + 1][j] - D[i][j]) < 1e-9) i++;
            else j++;
        }
        // gaps: between two matched neighbours, as many unmatched components on both sides pair up in order when their
        // kinds and types agree (the same element whose content changed)
        int pi = -1, pj = -1;
        for (int x = 0; x <= n; x++) {
            if (x < n && ai[x] < 0) continue;
            int nj = x < n ? ai[x] : m;
            List<Integer> ga = new ArrayList<>(), gb = new ArrayList<>();
            for (int y = pi + 1; y < x; y++) ga.add(y);
            for (int y = pj + 1; y < nj; y++) if (bj[y] < 0) gb.add(y);
            if (!ga.isEmpty() && ga.size() == gb.size()) {
                boolean ok = true;
                for (int z = 0; z < ga.size(); z++) { IfComp p = a.get(ka[ga.get(z)]), q = b.get(kb[gb.get(z)]); if (p.if3 != q.if3 || p.type() != q.type()) ok = false; }
                if (ok) for (int z = 0; z < ga.size(); z++) out.put(ka[ga.get(z)], kb[gb.get(z)]);
            }
            pi = x; pj = nj;
        }
        return out;
    }

    // ------------------------------------------------------------------ one interface

    /**
     * The 211 -> BASE mapping of interface {@code g}: table rows (checked against BASE names), else content alignment.
     * {@code van} are VAN211's components (converted), {@code base} BASE's.
     */
    public static TreeMap<Integer, Row> build(int g, Table table, IfNames names, TreeMap<Integer, IfComp> van, TreeMap<Integer, IfComp> base) {
        TreeMap<Integer, Row> out = new TreeMap<>();
        TreeMap<Integer, String[]> t = table.rows.get(g);
        TreeMap<Integer, Integer> aligned = null;
        for (int c : van.keySet()) {
            String[] f = t == null ? null : t.get(c);
            if (f != null) {
                int to = Integer.parseInt(f[3].trim());
                String conf = f.length > 4 ? f[4].trim() : "";
                String nm = f.length > 5 ? f[5].trim() : "";
                if (to >= 0 && !nm.isEmpty() && names.has(g) && !nm.equals(names.comp(g, to))) {
                    int byName = names.compByName(g, nm);
                    if (byName >= 0) { out.put(c, new Row(c, byName, conf, "table+name")); continue; }
                    out.put(c, new Row(c, to, "low", "table (name " + nm + " not in BASE)"));
                    continue;
                }
                out.put(c, new Row(c, to, conf, "table"));
                continue;
            }
            if (aligned == null) aligned = align(van, base, 0.45);
            Integer to = aligned.get(c);
            if (to == null) { out.put(c, new Row(c, -1, "low", "content: no match")); continue; }
            double sim = similarity(van.get(c), base.get(to));
            boolean ident = Arrays.equals(van.get(c).encode(), base.get(to).encode());
            String conf = ident ? "high" : sim >= 0.85 ? "high" : sim >= 0.6 ? "medium" : "low";
            out.put(c, new Row(c, to, conf, ident ? "content: identical" : String.format("content: %.2f", sim)));
        }
        // parent consistency: a mapped component whose mapped parent is not BASE's parent is at most "medium"
        for (var e : new ArrayList<>(out.entrySet())) {
            Row r = e.getValue();
            if (r.c241 < 0 || !r.method.startsWith("content")) continue;
            int pv = van.get(r.c211).parent(), pb = base.get(r.c241).parent();
            int pvm = pv < 0 ? -1 : out.containsKey(pv & 0xffff) ? out.get(pv & 0xffff).c241 : -2;
            if ((pb < 0 ? -1 : pb & 0xffff) != pvm && r.confidence.equals("high"))
                out.put(e.getKey(), new Row(r.c211, r.c241, "medium", r.method + ", parent differs"));
        }
        return out;
    }

    /** Whether BASE and VAN211 have the same components (converted, padding dropped) at the same ids. */
    public static boolean unchanged(CacheView van211, CacheView base, int g) {
        TreeMap<Integer, IfComp> v = comps211(van211.group(Arch.INTERFACES, g)), b = comps241(base.group(Arch.INTERFACES, g));
        if (!v.keySet().equals(b.keySet())) return false;
        for (int c : v.keySet()) if (!Arrays.equals(v.get(c).encode(), b.get(c).encode())) return false;
        return true;
    }
}
