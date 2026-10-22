package com.gadscape.cacheconv.rebrand;

import com.gadscape.cacheconv.config.CfgCodec;
import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.OutCache;
import com.gadscape.cacheconv.core.Report;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.uimerge.IfComp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies a {@link RebrandTable} to the texts of a cache being built, format-aware, and reports every change
 * (decision {@code rebranded}):
 * <ul>
 *   <li><b>configs</b> (every group {@link CfgCodec} walks: obj/npc/loc names and ops, enum, param, struct and dbrow
 *       strings, ...): the rules are applied to each NUL-terminated string of the entry; the result must parse with
 *       the same fields and references as before, otherwise the entry is left alone and reported as an error;</li>
 *   <li><b>interfaces</b> (IF3 components through {@link IfComp}): text, op base, ops, target verb and string hook
 *       arguments; IF1 components are reported, not rewritten;</li>
 *   <li><b>clientscripts</b>: string constants (op 3); script names are never changed (lookups use their hashes).</li>
 * </ul>
 * Only entries whose bytes may match a rule are decoded. Other archives the branding scan reads (world map, defaults,
 * dbtable indexes) are not rewritten; {@code verify}'s brand check lists what remains.
 */
public final class Rebrander {
    private final RebrandTable t;
    private final OutCache out;
    private final Report report;
    /** Counts by area: changed entries, rejected entries. */
    public final TreeMap<String, Integer> counts = new TreeMap<>();

    public Rebrander(RebrandTable t, OutCache out, Report report) { this.t = t; this.out = out; this.report = report; }

    public void run() {
        if (t.isEmpty()) return;
        configs();
        interfaces();
        scripts();
        for (var e : counts.entrySet()) report.count(e.getKey(), e.getValue());
    }

    private void count(String k) { counts.merge(k, 1, Integer::sum); }

    // ------------------------------------------------------------------ configs

    void configs() {
        if (!out.hasArchive(Arch.CONFIGS)) return;
        for (int g : out.groupIds(Arch.CONFIGS)) {
            if (!CfgCodec.walked(g)) continue;
            GroupData d = out.group(Arch.CONFIGS, g);
            if (d == null) continue;
            for (int i = 0; i < d.fileCount(); i++) {
                byte[] b = d.dataAt(i);
                if (!t.mayMatch(b)) continue;
                int id = d.fileIdAt(i);
                List<String[]> changes = new ArrayList<>();
                byte[] nb = strings(b, changes);
                if (changes.isEmpty()) continue;
                String what = Arch.configName(g) + " " + id;
                String why = sameShape(g, id, b, nb);
                if (why != null) { report.error(Arch.configName(g), id, "rebrand: rewritten entry rejected (" + why + "), left as it was"); count("rebrand.config.rejected"); continue; }
                out.putConfig(g, id, nb);
                count("rebrand.config");
                report.add(Decision.REBRANDED, Arch.configName(g), id, describe(changes));
            }
            out.release(Arch.CONFIGS);
        }
    }

    /** Applies the table to every NUL-terminated run of printable bytes; records (before, after) pairs. */
    byte[] strings(byte[] b, List<String[]> changes) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(b.length + 16);
        int i = 0;
        while (i < b.length) {
            if ((b[i] & 255) < 32) { o.write(b[i]); i++; continue; }
            int j = i;
            while (j < b.length && (b[j] & 255) >= 32) j++;
            String s = new String(b, i, j - i, StandardCharsets.ISO_8859_1);
            String r = j < b.length && b[j] == 0 ? t.apply(s) : s;   // only NUL-terminated runs are strings
            if (!r.equals(s)) changes.add(new String[]{s, r});
            byte[] w = r.getBytes(StandardCharsets.ISO_8859_1);
            o.write(w, 0, w.length);
            i = j;
        }
        return o.toByteArray();
    }

    /** Null when both versions parse to the same fields and references, else why not. */
    static String sameShape(int g, int id, byte[] a, byte[] b) {
        try {
            List<String> x = shape(CfgCodec.parse(g, id, a, CfgCodec.UNTYPED)), y = shape(CfgCodec.parse(g, id, b, CfgCodec.UNTYPED));
            return x.equals(y) ? null : "fields or references differ";
        } catch (RuntimeException e) {
            return "does not parse: " + e.getMessage();
        }
    }

    private static List<String> shape(CfgCodec.Entry e) {
        List<String> l = new ArrayList<>();
        for (CfgCodec.Item it : e.items) shape(it, l);
        return l;
    }

    private static void shape(CfgCodec.Item it, List<String> l) {
        l.add(it.key + it.refs);
        if (it.isContainer()) { l.add("[" + it.children.size()); for (CfgCodec.Item c : it.children) shape(c, l); l.add("]"); }
    }

    // ------------------------------------------------------------------ interfaces

    void interfaces() {
        if (!out.hasArchive(Arch.INTERFACES)) return;
        for (int g : out.groupIds(Arch.INTERFACES)) {
            GroupData d = out.group(Arch.INTERFACES, g);
            if (d == null) continue;
            for (int i = 0; i < d.fileCount(); i++) {
                byte[] b = d.dataAt(i);
                if (!t.mayMatch(b)) continue;
                int f = d.fileIdAt(i);
                String where = g + ":" + f;
                IfComp c;
                try { c = IfComp.decode(b); } catch (RuntimeException e) { report.error("component", (long) g << 16 | f, "rebrand: component " + where + " does not decode: " + e.getMessage()); count("rebrand.component.rejected"); continue; }
                if (!c.if3) { report.warn("component", (long) g << 16 | f, "rebrand: IF1 component " + where + " may hold old branding; IF1 texts are not rewritten"); count("rebrand.component.if1"); continue; }
                if (!Arrays.equals(c.encode(), b)) { report.error("component", (long) g << 16 | f, "rebrand: component " + where + " does not re-encode exactly; left as it was"); count("rebrand.component.rejected"); continue; }
                List<String[]> changes = new ArrayList<>();
                for (Map.Entry<String, Object> e : c.f.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof String s) {
                        String r = t.apply(s);
                        if (!r.equals(s)) { changes.add(new String[]{s, r}); e.setValue(r); }
                    } else if (v instanceof List<?> list) {
                        @SuppressWarnings("unchecked") List<Object> l = (List<Object>) list;
                        for (int k = 0; k < l.size(); k++) {
                            if (!(l.get(k) instanceof String s)) continue;
                            String r = t.apply(s);
                            if (!r.equals(s)) { changes.add(new String[]{s, r}); l.set(k, r); }
                        }
                    }
                }
                if (changes.isEmpty()) continue;
                out.putFile(Arch.INTERFACES, g, f, c.encode());
                count("rebrand.component");
                report.add(Decision.REBRANDED, "component", (long) g << 16 | f, where + ": " + describe(changes));
            }
            out.release(Arch.INTERFACES);
        }
    }

    // ------------------------------------------------------------------ scripts

    void scripts() {
        if (!out.hasArchive(Arch.SCRIPTS)) return;
        for (int g : out.groupIds(Arch.SCRIPTS)) {
            byte[] b = out.single(Arch.SCRIPTS, g);
            if (b == null || !t.mayMatch(b)) continue;
            Cs2.Script s;
            try { s = Cs2.parse241(b); } catch (RuntimeException e) { report.error("script", g, "rebrand: script does not parse: " + e.getMessage()); count("rebrand.script.rejected"); continue; }
            if (!Arrays.equals(Cs2.write241(s), b)) { report.error("script", g, "rebrand: script does not re-encode exactly; left as it was"); count("rebrand.script.rejected"); continue; }
            List<String[]> changes = new ArrayList<>();
            for (Cs2.Ins in : s.code) {
                if (in.op != Cs2.SCONST) continue;
                String r = t.apply(in.s);
                if (!r.equals(in.s)) { changes.add(new String[]{in.s, r}); in.s = r; }
            }
            if (changes.isEmpty()) continue;
            out.putSingle(Arch.SCRIPTS, g, Cs2.write241(s));
            count("rebrand.script");
            report.add(Decision.REBRANDED, "script", g, describe(changes));
        }
        out.release(Arch.SCRIPTS);
    }

    static String describe(List<String[]> changes) {
        StringBuilder sb = new StringBuilder();
        for (String[] c : changes) {
            if (sb.length() > 0) sb.append("; ");
            sb.append('"').append(c[0]).append("\" -> \"").append(c[1]).append('"');
        }
        return sb.toString();
    }
}
