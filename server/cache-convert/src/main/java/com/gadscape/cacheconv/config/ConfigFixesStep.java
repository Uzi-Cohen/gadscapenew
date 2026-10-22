package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;
import com.gadscape.cacheconv.ui.UiScope;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * config-fixes: the data-driven fixes of GadScape's own config entries that QA and the content areas asked for
 * ({@value #RESOURCE}; {@code --configs.fixes=FILE|none}), applied after {@link ConfigStep} to the entries it wrote,
 * so a rebuild re-applies them (no hand-patched cache bytes). Rows: {@code KIND<TAB>IDS<TAB>ACTION<TAB>ARG<TAB>FINDING<TAB>NOTE}.
 * <ul>
 *   <li>IDS are USER ids, mapped through the id map (an obj the build moved is fixed at its new id); {@code =ID} names
 *       an output id as it is (the new objs).</li>
 *   <li>{@code set OP PAYLOAD} sets a plain opcode ({@link ConfigEdit#payload}: {@code 2 str:Name}, {@code 13 u16:808});
 *       {@code unset OP[,OP]} removes opcodes; {@code copy base:ID|out:ID OP,OP,...} copies opcodes from a BASE entry
 *       or an output entry (an opcode the source lacks is removed); {@code recolour FROM=TO} adds a recolour pair;
 *       {@code unset-param P[,P]} removes params;</li>
 *   <li>{@code new base:ID NAME} (objs): a new entry, a copy of the BASE entry, at the id {@link #claim} reserved before
 *       configs-plan (listed under {@code _new} in idmap.json as {@code NAME});</li>
 *   <li>{@code enum-copy KEY=FROMKEY} (enums): pair {@code KEY} gets the value of {@code FROMKEY} (KEY is a USER obj
 *       id, mapped; FROMKEY is the output's key as it is);</li>
 *   <li>{@code enum-component FROM=TO} (enums of components): every value on interface FROM moves to interface TO (a USER
 *       interface id, mapped): GadScape's enums that still name the old duel/tournament interfaces.</li>
 * </ul>
 * A row whose entry is missing, whose source is missing or whose result does not decode with the server's strict
 * decoder is an error. Every applied row is a {@code fixed} (or {@code added}) decision in report.json.
 */
public final class ConfigFixesStep implements Step {
    public static final String NAME = "config-fixes";
    public static final String RESOURCE = "config/fixes.tsv";

    @Override public String name() { return NAME; }

    /** One row of the table. */
    public record Row(int line, Kind kind, String ids, String action, String arg, String finding, String note) {}

    /** The rows of {@value #RESOURCE} or {@code --configs.fixes=FILE}; empty with {@code none}. */
    public static List<Row> rows(Ctx ctx) {
        String opt = ctx.opt.get("configs.fixes", null);
        if ("none".equals(opt)) return List.of();
        List<String> lines;
        try {
            if (opt != null) lines = Files.readAllLines(Path.of(opt), StandardCharsets.UTF_8);
            else try (InputStream in = ConfigFixesStep.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) return List.of();
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        List<Row> rows = new ArrayList<>();
        int n = 0;
        for (String line : lines) {
            n++;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            if (c.length < 5) throw new IllegalArgumentException(RESOURCE + ":" + n + ": expected KIND<TAB>IDS<TAB>ACTION<TAB>ARG<TAB>FINDING[<TAB>NOTE]: " + line);
            rows.add(new Row(n, Kind.byKey(c[0].trim()), c[1].trim(), c[2].trim(), c[3], c[4].trim(), c.length > 5 ? c[5].trim() : ""));
        }
        return rows;
    }

    /**
     * Reserves the ids of the {@code new} rows (called by configs-plan before anything allocates objs, so server constants
     * that already name them stay right): the name in {@code _new} is the row's FINDING plus the new entry's name.
     */
    public static void claim(Ctx ctx) {
        for (Row r : rows(ctx)) {
            if (!r.action.equals("new")) continue;
            if (!r.ids.startsWith("=")) throw new IllegalArgumentException(RESOURCE + ":" + r.line + ": a new entry needs an output id (=ID)");
            int id = Integer.parseInt(r.ids.substring(1));
            String[] a = r.arg.split(" ", 2);
            ctx.ids.claimNew(r.kind, newName(r, a.length > 1 ? a[1] : a[0]), id, r.finding + ": " + r.note);
        }
    }

    static String newName(Row r, String entryName) { return r.finding.toLowerCase() + ":" + entryName; }

    @Override public void run(Ctx ctx) {
        List<Row> rows = rows(ctx);
        ctx.report.input("configs.fixes", rows.size() + " rows");
        int applied = 0, failed = 0;
        int rev = mgi.tools.jagcached.CacheFormat.revision();
        boolean strict = mgi.tools.jagcached.CacheFormat.isStrict();
        mgi.tools.jagcached.CacheFormat.setRevision(mgi.tools.jagcached.CacheFormat.REV_241);
        mgi.tools.jagcached.CacheFormat.setStrict(true);
        java.io.PrintStream so = System.out, se = System.err;
        try {
            java.io.PrintStream quiet = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            System.setOut(quiet); System.setErr(quiet);   // the server decoders log unknown opcodes
            for (Row r : rows) {
                try {
                    applied += apply(ctx, r);
                } catch (RuntimeException e) {
                    failed++;
                    ctx.report.error(r.kind.key, Long.MIN_VALUE, "config-fixes line " + r.line + " (" + r.finding + " " + r.action + " " + r.ids + "): " + e.getMessage());
                }
            }
        } finally {
            System.setOut(so); System.setErr(se);
            mgi.tools.jagcached.CacheFormat.setRevision(rev);
            mgi.tools.jagcached.CacheFormat.setStrict(strict);
        }
        ctx.report.count("config fixes applied", applied);
        ctx.log("%d rows, %d entries fixed, %d rows failed", rows.size(), applied, failed);
    }

    /** Applies one row; returns how many entries it changed. */
    static int apply(Ctx ctx, Row r) {
        int g = r.kind.configGroup;
        if (g < 0) throw new IllegalArgumentException("not a config kind");
        ParamTypeTable types = new ParamTypeTable(ctx.out);
        int n = 0;
        for (int target : targets(ctx, r)) {
            Entry before = r.action.equals("new") ? null : entry(ctx.out, g, target, types);
            if (before == null && !r.action.equals("new")) throw new IllegalStateException(Arch.configName(g) + " " + target + " is not in the output");
            Entry after = edit(ctx, r, g, target, before, types);
            byte[] b = after.encode();
            if (before != null && java.util.Arrays.equals(b, before.encode())) continue;
            String problem = ConfigOracle.decoded(g) ? ConfigOracle.decode(g, target, b) : null;
            if (problem != null) throw new IllegalStateException(Arch.configName(g) + " " + target + ": the fixed entry does not decode: " + problem);
            ctx.out.putConfig(g, target, b);
            n++;
            String what = r.action + " " + r.arg.trim() + (r.note.isEmpty() ? "" : " (" + r.note + ")");
            ctx.report.add(r.action.equals("new") ? Decision.ADDED : Decision.FIXED, r.kind.key, target, r.finding + ": " + what);
        }
        return n;
    }

    static Entry entry(CacheView c, int g, int id, ParamTypeTable types) {
        byte[] b = c.config(g, id);
        return b == null ? null : CfgCodec.parse(g, id, b, types);
    }

    /** The output ids a row names. */
    static List<Integer> targets(Ctx ctx, Row r) {
        List<Integer> t = new ArrayList<>();
        if (r.ids.startsWith("=")) { for (int id : UiScope.ids(r.ids.substring(1))) t.add(id); return t; }
        for (int id : UiScope.ids(r.ids)) t.add(ctx.ids.map(r.kind, id));
        return t;
    }

    static Entry edit(Ctx ctx, Row r, int g, int target, Entry e, ParamTypeTable types) {
        String arg = r.arg.trim();
        switch (r.action) {
            case "set" -> {
                int sp = arg.indexOf(' ');
                return ConfigEdit.set(e, ConfigEdit.payload(Integer.parseInt(arg.substring(0, sp)), arg.substring(sp + 1)));
            }
            case "unset" -> {
                Entry x = e;
                for (String op : arg.split(",")) x = ConfigEdit.unset(x, Integer.parseInt(op.trim()));
                return x;
            }
            case "copy" -> {
                String[] a = arg.split(" ");
                Entry src = source(ctx, r, g, a[0], types);
                String[] ops = a[1].split(",");
                int[] o = new int[ops.length];
                for (int i = 0; i < ops.length; i++) o[i] = Integer.parseInt(ops[i].trim());
                return ConfigEdit.copy(e, src, o);
            }
            case "unset-param" -> {
                String[] ps = arg.split(",");
                int[] p = new int[ps.length];
                for (int i = 0; i < ps.length; i++) p[i] = Integer.parseInt(ps[i].trim());
                return ConfigEdit.unsetParams(e, p);
            }
            case "recolour" -> {
                String[] p = arg.split("=");
                return ConfigEdit.recolour(e, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
            }
            case "new" -> {
                String[] a = arg.split(" ", 2);
                if (ctx.ids.newId(r.kind, newName(r, a.length > 1 ? a[1] : a[0])) != target)
                    throw new IllegalStateException("new " + Arch.configName(g) + " " + target + " was not claimed by configs-plan");
                if (ctx.out.hasConfig(g, target)) throw new IllegalStateException(Arch.configName(g) + " " + target + " already exists");
                Entry src = source(ctx, r, g, a[0], types);
                return ConfigEdit.clone(src, target);
            }
            case "enum-copy" -> {
                String[] p = arg.split("=");
                int key = ctx.ids.map(Kind.OBJ, Integer.parseInt(p[0].trim())), from = Integer.parseInt(p[1].trim());
                byte[] v = ConfigEdit.enumGet(e, from);
                if (v == null) throw new IllegalStateException("enum " + target + " has no key " + from + " to copy");
                return ConfigEdit.enumPut(e, key, v);
            }
            case "enum-component" -> {
                String[] p = arg.split("=");
                int from = Integer.parseInt(p[0].trim()), to = ctx.ids.map(Kind.INTERFACE, Integer.parseInt(p[1].trim()));
                int[] et = CfgCodec.enumTypes(e);
                if (et == null || et[1] != 'I') throw new IllegalStateException("enum " + target + " does not map to components");
                Entry x = e;
                int n = 0;
                for (var pair : EnumMerge.pairsOf(e).entrySet()) {
                    int v = com.gadscape.cacheconv.core.Bytes.i32(pair.getValue(), 0);
                    if (v == -1 || v >>> 16 != from) continue;
                    byte[] nv = new byte[4];
                    com.gadscape.cacheconv.core.Bytes.w32(nv, 0, (to << 16) | (v & 0xffff));
                    x = ConfigEdit.enumPut(x, pair.getKey(), nv);
                    n++;
                }
                if (n == 0) throw new IllegalStateException("enum " + target + " has no component of interface " + from);
                return x;
            }
            default -> throw new IllegalArgumentException("unknown action '" + r.action + "'");
        }
    }

    /** "base:ID" (BASE's entry, id as it is) or "out:ID" (the output's entry at the USER id's mapped id). */
    static Entry source(Ctx ctx, Row r, int g, String spec, ParamTypeTable types) {
        String[] s = spec.split(":");
        int id = Integer.parseInt(s[1]);
        Entry e = switch (s[0]) {
            case "base" -> entry(ctx.base, g, id, new ParamTypeTable(ctx.base));
            case "out" -> entry(ctx.out, g, ctx.ids.map(r.kind, id), types);
            default -> throw new IllegalArgumentException("source must be base:ID or out:ID, got " + spec);
        };
        if (e == null) throw new IllegalStateException("source " + spec + " is missing");
        return e;
    }

    /** The fixes' entries per kind, for checks: USER id -> rows. */
    public static TreeMap<String, List<Row>> byKind(Ctx ctx) {
        TreeMap<String, List<Row>> m = new TreeMap<>();
        for (Row r : rows(ctx)) m.computeIfAbsent(r.kind.key, k -> new ArrayList<>()).add(r);
        return m;
    }
}
