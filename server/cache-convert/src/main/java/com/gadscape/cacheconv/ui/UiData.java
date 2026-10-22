package com.gadscape.cacheconv.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The UI area's data files (src/main/resources/ui): vanilla proc signature rules, fixups of known wrong interface ids
 * in GadScape's own content, and the 211 -> 241 component remap of vanilla interfaces.
 */
public final class UiData {
    private UiData() {}

    static List<String[]> rows(String resource) {
        try (InputStream in = UiData.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return rows(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String[]> rows(BufferedReader r) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String l; (l = r.readLine()) != null; ) {
            if (l.isBlank() || l.startsWith("#")) continue;
            out.add(l.split("\t", -1));
        }
        return out;
    }

    // ------------------------------------------------------------------ signature rules

    /**
     * A vanilla proc whose 241 signature differs: calls from custom content go through {@code adapter}.
     * Without an argument map the adapter passes the 211 int args and appends {@code extraInts}; with one,
     * {@code intMap}/{@code objMap} list what the adapter pushes for each 241 argument: "iN"/"oN" = the adapter's own
     * int/object argument N (the 211 caller's argument), a number = that int constant.
     */
    /**
     * The id of the adapter script for a rule: allocated by {@code ui-relocate} at the top of the reserved script range
     * ({@link com.gadscape.cacheconv.core.IdMap#addNew}, listed under {@code _new} in idmap.json as "adapter:TARGET").
     */
    public static int adapterId(com.gadscape.cacheconv.core.IdMap ids, SigRule r) {
        int id = ids.newId(com.gadscape.cacheconv.core.Kind.SCRIPT, adapterName(r));
        if (id < 0) throw new IllegalStateException("adapter for script " + r.target() + " was not allocated (ui-relocate runs first)");
        return id;
    }

    public static String adapterName(SigRule r) { return "adapter:" + r.target(); }

    /** Allocates the adapter ids of every rule (ascending targets, from the top of the reserved script range). */
    public static void allocateAdapters(com.gadscape.cacheconv.core.IdMap ids) {
        for (SigRule r : signatures().values())
            ids.addNew(com.gadscape.cacheconv.core.Kind.SCRIPT, adapterName(r), "adapter for vanilla proc " + r.target() + " (241 signature differs): " + r.note(), true);
    }

    /** {@code adapter} is unused (-1): adapter ids are allocated, see {@link #adapterId}. */
    public record SigRule(int target, int adapter, int int211, int obj211, int int241, int obj241, int long241, int[] extraInts, int dropIntReturns,
                          String[] intMap, String[] objMap, String note) {
        public boolean mapped() { return intMap != null; }

        /** Problems with the rule's own shape (argument map vs counts), or null. */
        public String shapeProblem() {
            if (!mapped()) {
                if (obj211 != 0 || obj241 != 0) return "object args need an argument map";
                if (int211 + extraInts.length != int241) return "211 int args + extra ints != 241 int args";
                return null;
            }
            if (intMap.length != int241 || objMap.length != obj241) return "argument map has " + intMap.length + " int/" + objMap.length + " obj entries, 241 takes " + int241 + "/" + obj241;
            for (String e : intMap) {
                if (e.startsWith("i")) { int k = Integer.parseInt(e.substring(1)); if (k < 0 || k >= int211) return "int arg " + e + " out of range"; }
                else if (e.startsWith("o")) return "int slot " + e + " takes an object arg";
                else Integer.parseInt(e);
            }
            for (String e : objMap) {
                if (!e.startsWith("o")) return "object slot " + e + " must name an object arg";
                int k = Integer.parseInt(e.substring(1)); if (k < 0 || k >= obj211) return "object arg " + e + " out of range";
            }
            return null;
        }
    }

    /**
     * Rows of {@code ui/script-signatures.tsv}: target, adapter, args211 (int,obj), args241 (int,obj,long), extraInts,
     * dropIntReturns, argmap ("-" or "INTS|OBJS", e.g. "i0,9,i1|o0"), note.
     */
    public static TreeMap<Integer, SigRule> signatures() {
        TreeMap<Integer, SigRule> m = new TreeMap<>();
        for (String[] f : rows("/ui/script-signatures.tsv")) {
            String[] a = f[2].split(","), b = f[3].split(",");
            String[] ex = f[4].isEmpty() || f[4].equals("-") ? new String[0] : f[4].split(",");
            int[] extra = new int[ex.length];
            for (int i = 0; i < ex.length; i++) extra[i] = Integer.parseInt(ex[i].trim());
            String[] im = null, om = null;
            String map = f.length > 6 ? f[6].trim() : "-";
            if (!map.isEmpty() && !map.equals("-")) {
                String[] io = map.split("\\|", -1);
                im = io[0].isEmpty() ? new String[0] : io[0].split(",");
                om = io.length < 2 || io[1].isEmpty() ? new String[0] : io[1].split(",");
                for (int i = 0; i < im.length; i++) im[i] = im[i].trim();
                for (int i = 0; i < om.length; i++) om[i] = om[i].trim();
            }
            SigRule r = new SigRule(Integer.parseInt(f[0]), f[1].trim().equals("auto") ? -1 : Integer.parseInt(f[1].trim()), Integer.parseInt(a[0]), Integer.parseInt(a[1]),
                    Integer.parseInt(b[0]), Integer.parseInt(b[1]), Integer.parseInt(b[2]), extra, Integer.parseInt(f[5]), im, om, f.length > 7 ? f[7] : "");
            String bad = r.shapeProblem();
            if (bad != null) throw new IllegalStateException("ui/script-signatures.tsv row " + r.target() + ": " + bad);
            m.put(r.target, r);
        }
        return m;
    }

    // ------------------------------------------------------------------ fixups

    /** In script/interface {@code id}, component references to interface {@code from} mean interface {@code to}. */
    public record Fixup(String where, int id, int from, int to, String finding, String note) {}

    /** Fixups by "script:id" / "interface:id". */
    public static Map<String, List<Fixup>> fixups() {
        Map<String, List<Fixup>> m = new HashMap<>();
        for (String[] f : rows("/ui/custom-fixups.tsv")) {
            for (String part : f[1].split(",")) {
                int lo, hi;
                if (part.contains("-")) { String[] r = part.split("-"); lo = Integer.parseInt(r[0].trim()); hi = Integer.parseInt(r[1].trim()); }
                else lo = hi = Integer.parseInt(part.trim());
                for (int id = lo; id <= hi; id++)
                    m.computeIfAbsent(f[0] + ":" + id, k -> new ArrayList<>()).add(new Fixup(f[0], id, Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4], f.length > 5 ? f[5] : ""));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ component remap

    /**
     * One remap row: the 241 interface and component (-1 = none) and confidence (high, medium, low, ...). The interface
     * is the row's own unless the table names another one ("IFACE:COMP", e.g. when a merged interface moved).
     */
    public record CompRow(int iface, int to, String confidence) {
        /** The packed 241 component id (iface << 16 | to), or -1. */
        public int packed() { return to < 0 ? -1 : (iface << 16) | to; }
    }

    /** 211 -> 241 component remap of vanilla interfaces: (interface << 16 | component211) -> row. */
    public static final class ComponentRemap {
        public final String source;
        private final Map<Integer, CompRow> rows = new HashMap<>();
        private final java.util.Set<Integer> interfaces = new java.util.TreeSet<>();

        ComponentRemap(String source, List<String[]> table) {
            this.source = source;
            for (String[] f : table) {
                int g = Integer.parseInt(f[0].trim()), c = Integer.parseInt(f[2].trim());
                String t = f[3].trim();
                int ti = g, to;
                if (t.indexOf(':') > 0) { String[] p = t.split(":"); ti = Integer.parseInt(p[0].trim()); to = Integer.parseInt(p[1].trim()); }
                else to = Integer.parseInt(t);
                rows.put((g << 16) | c, new CompRow(ti, to, f.length > 4 ? f[4].trim() : ""));
                interfaces.add(g);
            }
        }

        /** The row for a 211 component id (interface << 16 | component), or null. */
        public CompRow get(int comp211) { return rows.get(comp211); }
        public boolean covers(int iface) { return interfaces.contains(iface); }
        public int size() { return rows.size(); }
    }

    /**
     * The remap table: {@code path} if given; else the UI merge area's table (resource ui-merge/component-remap.tsv,
     * same columns) if present; else this area's copy of server/docs/rev241/interface-remap.tsv
     * (resource ui/interface-remap-211-241.tsv). Columns: interface, name, component211, component241, confidence, ...
     */
    public static ComponentRemap componentRemap(String path) {
        try {
            if (path != null && !path.isEmpty()) {
                try (BufferedReader r = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) { return new ComponentRemap(path, rows(r)); }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String[]> merge = rows("/ui-merge/component-remap.tsv");
        if (merge != null) return new ComponentRemap("resource ui-merge/component-remap.tsv", merge);
        return new ComponentRemap("resource ui/interface-remap-211-241.tsv", rows("/ui/interface-remap-211-241.tsv"));
    }
}
