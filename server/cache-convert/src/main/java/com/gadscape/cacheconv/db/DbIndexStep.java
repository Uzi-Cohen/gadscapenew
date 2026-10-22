package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * db-index: archive 21 (dbtable indexes) rebuilt from the rows the output actually holds, for BASE's and the user's
 * tables alike ({@link DbIndexBuilder}, byte-exact with Jagex's packer), so every lookup returns rows of its own
 * table (CRIT-23: cacheU mixed stock index groups with user rows and the user's 211 index groups 0/15/18/25/34).
 * <ul>
 *   <li>Which tables have a group, and which files: BASE's groups and file sets for BASE tables, the USER group's
 *       for user tables (the GadScape server's packer writes the master and one column file). Jagex indexes only some
 *       tables (156 of BASE's 271), so a table without a group in BASE or USER gets none.</li>
 *   <li>A BASE table whose rows are still BASE's must come out byte-identical to BASE (checked: an error otherwise);
 *       one whose rows changed is rebuilt and reported. Every BASE index group is compared with BASE at the end and
 *       the groups the audit named (37, 38, 39, 40, 41, 44, 65, plus 0, 15, 18, 25, 34) are listed in the report.</li>
 *   <li>User tables: written at their (mapped) table id from the rows in the output. Differences from the USER group
 *       with its row ids mapped are reported (the user's index of table 1000 lists rows 5000-5203 that exist in no
 *       USER table, and the server packer's column file holds column 1's values under column 0's file id).</li>
 *   <li>Consistency: every row an index lists exists and belongs to that table, and every row of a table is in its
 *       master; violations are errors.</li>
 *   <li>Obj copies: the universal-shop rows hold their item twice, typed obj (column 2) and as a plain int (column 3,
 *       {@code UnivShopTable}); configs rewrites only the typed one when an obj moves. Before building, every user
 *       row whose int value equalled an obj value of the same row in USER, where the obj moved and the int did not,
 *       gets the new obj id in the int field too ({@code --db.syncObjCopies=false} turns it off; reported).</li>
 * </ul>
 * Run it after {@code configs} (which writes the rows) and after every step that writes dbrows. Writes
 * {@code WORK/sprites-db/db-index.tsv}.
 */
public final class DbIndexStep implements Step {
    public static final String NAME = "db-index";
    /** Index groups the audit (CRIT-23 / PVP-23) found wrong in cacheU. */
    public static final int[] AUDITED = {0, 15, 18, 25, 34, 37, 38, 39, 40, 41, 44, 65};

    @Override public String name() { return NAME; }

    /** Rows of a cache by table, ascending ids. Rows that do not decode are reported and skipped. */
    public static TreeMap<Integer, List<DbIndexBuilder.Row>> rowsByTable(CacheView c, Ctx ctx) {
        TreeMap<Integer, List<DbIndexBuilder.Row>> m = new TreeMap<>();
        GroupData g = c.group(Arch.CONFIGS, Arch.CFG_DBROW);
        if (g == null) return m;
        for (int i = 0; i < g.fileCount(); i++) {
            DbRow r;
            try { r = DbRow.row(g.dataAt(i)); }
            catch (RuntimeException e) { if (ctx != null) ctx.report.error("dbrow", g.fileIdAt(i), c.label() + " row does not decode: " + e.getMessage()); continue; }
            m.computeIfAbsent(r.tableId, k -> new ArrayList<>()).add(new DbIndexBuilder.Row(g.fileIdAt(i), r));
        }
        return m;
    }

    @Override public void run(Ctx ctx) throws IOException {
        var user = ctx.user();
        var van = ctx.van211();
        var out = ctx.out;
        if (Boolean.parseBoolean(ctx.opt.get("db.syncObjCopies", "true"))) syncObjCopies(ctx);
        TreeMap<Integer, List<DbIndexBuilder.Row>> outRows = rowsByTable(out, ctx);
        TreeMap<Integer, List<DbIndexBuilder.Row>> baseRows = rowsByTable(ctx.base, null);
        // user tables at their output ids
        TreeMap<Integer, Integer> userTableAt = new TreeMap<>(); // output table id -> USER table id
        for (int t : user.configIds(Arch.CFG_DBTABLE)) if (Origin.ofConfig(Arch.CFG_DBTABLE, t, user, van).isUser()) userTableAt.put(ctx.ids.map(Kind.DBTABLE, t), t);
        TreeSet<Integer> tables = new TreeSet<>();
        for (int g : ctx.base.groupIds(Arch.DBTABLE_INDEX)) tables.add(g);
        tables.addAll(userTableAt.keySet());

        StringBuilder tsv = new StringBuilder("# table\tsource\trows\tfiles\tresult\tdetail\n");
        int same = 0, rebuiltBase = 0, writtenUser = 0, written = 0, problems = 0;
        for (int t : tables) {
            List<DbIndexBuilder.Row> rows = outRows.getOrDefault(t, List.of());
            byte[] defBytes = out.configRef(Arch.CFG_DBTABLE, t);
            DbRow def = null;
            if (defBytes != null) {
                try { def = DbRow.tableDef(defBytes); }
                catch (RuntimeException e) { ctx.report.error("dbtable", t, "definition does not decode: " + e.getMessage()); }
            } else if (!rows.isEmpty()) ctx.report.warn("dbtable", t, rows.size() + " rows name table " + t + ", which has no definition");
            Integer userTable = userTableAt.get(t);
            GroupData template;
            String source;
            if (userTable != null) { template = user.group(Arch.DBTABLE_INDEX, userTable); source = "user table " + userTable; }
            else if (ctx.base.hasGroup(Arch.DBTABLE_INDEX, t)) { template = ctx.base.group(Arch.DBTABLE_INDEX, t); source = "BASE"; }
            else continue; // Jagex indexes only some tables: a table without a group in BASE or USER stays without one
            if (template == null) {
                if (!rows.isEmpty()) ctx.report.note("user dbtable " + userTable + " has " + rows.size() + " rows but no index group in USER: none written");
                continue;
            }
            int[] fileIds = template.fileIds();
            int[] names = new int[fileIds.length];
            for (int i = 0; i < fileIds.length; i++) names[i] = template.fileNameAt(i);
            int groupName = userTable != null ? user.groupName(Arch.DBTABLE_INDEX, userTable) : ctx.base.groupName(Arch.DBTABLE_INDEX, t);
            List<Integer> skipped = new ArrayList<>();
            GroupData built = DbIndexBuilder.group(t, groupName, fileIds, names, rows, def, skipped);
            if (!skipped.isEmpty()) ctx.report.warn("dbtable", t, "index files " + skipped + " name columns the table does not define: left out");
            GroupData cur = out.group(Arch.DBTABLE_INDEX, t);
            boolean isBaseTable = userTable == null && ctx.base.hasGroup(Arch.DBTABLE_INDEX, t);
            String result;
            if (isBaseTable) {
                GroupData bg = ctx.base.group(Arch.DBTABLE_INDEX, t);
                boolean rowsAreBase = sameRows(rows, baseRows.getOrDefault(t, List.of()));
                if (rowsAreBase && !built.sameContent(trimmed(bg))) {
                    ctx.report.error("dbtable", t, "index rebuilt from BASE's own rows differs from BASE's index (builder bug): BASE's kept");
                    if (cur == null || !cur.sameContent(bg)) out.putGroup(Arch.DBTABLE_INDEX, bg);
                    result = "error"; problems++;
                } else if (rowsAreBase) {
                    if (cur == null || !cur.sameContent(bg)) { out.putGroup(Arch.DBTABLE_INDEX, bg); result = "restored BASE"; written++; }
                    else result = "BASE";
                    same++;
                } else {
                    if (cur == null || !cur.sameContent(built)) { out.putGroup(Arch.DBTABLE_INDEX, built); written++; }
                    ctx.report.add(Decision.MERGED, Kind.DBTABLE, t, "BASE table index rebuilt: its rows differ from BASE's (" + rowDelta(rows, baseRows.getOrDefault(t, List.of())) + ")");
                    result = "rebuilt (rows changed)"; rebuiltBase++;
                }
            } else {
                String detail = "";
                if (userTable != null) detail = compareWithUser(ctx, template, built);
                if (cur == null || !cur.sameContent(built)) { out.putGroup(Arch.DBTABLE_INDEX, built); written++; }
                ctx.report.add(Decision.USER_ADDED, Kind.DBTABLE, t,
                        "index of " + source + " built from " + rows.size() + " rows" + (detail.isEmpty() ? "" : "; " + detail));
                result = "built"; writtenUser++;
            }
            tsv.append(t).append('\t').append(source).append('\t').append(rows.size()).append('\t').append(Arrays.toString(fileIds)).append('\t').append(result).append('\t')
                    .append(rows.isEmpty() ? "" : rows.get(0).id() + "-" + rows.get(rows.size() - 1).id()).append('\n');
        }
        problems += consistency(ctx, outRows);
        TreeMap<Integer, String> audited = auditedStatus(ctx);
        ctx.report.note("db-index: audited BASE index groups vs BASE: " + audited);
        for (var e : audited.entrySet()) if (!e.getValue().equals("= BASE")) ctx.report.warn("dbtable", e.getKey(), "audited BASE index group is " + e.getValue());
        int baseEqual = 0, baseDiff = 0;
        for (int g : ctx.base.groupIds(Arch.DBTABLE_INDEX)) { if (sameAsBase(ctx, g)) baseEqual++; else baseDiff++; }
        ctx.report.count("dbindex.base-groups-equal-to-BASE", baseEqual);
        ctx.report.count("dbindex.base-groups-rebuilt", baseDiff);
        ctx.report.count("dbindex.user-groups-built", writtenUser);
        ctx.report.count("dbindex.groups-written", written);
        if (problems > 0) ctx.report.count("dbindex.problems", problems);
        ctx.out.release(Arch.DBTABLE_INDEX);
        Files.writeString(ctx.work("sprites-db").resolve("db-index.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        ctx.log("db-index: %d BASE groups equal to BASE, %d BASE groups rebuilt, %d user groups built, %d problems", baseEqual, rebuiltBase, writtenUser, problems);
    }

    /** Script var type ids of obj and namedobj values, and of plain ints. */
    static final int TYPE_INT = 0, TYPE_OBJ = 33, TYPE_NAMEDOBJ = 13;

    /** Int fields of user rows that copied an obj field of the same row follow the obj's move. Returns rows rewritten. */
    static int syncObjCopies(Ctx ctx) {
        var user = ctx.user();
        int fixed = 0;
        for (int id : user.configIds(Arch.CFG_DBROW)) {
            if (!Origin.ofConfig(Arch.CFG_DBROW, id, user, ctx.van211()).isUser()) continue;
            int to = ctx.ids.map(Kind.DBROW, id);
            byte[] ob = ctx.out.configRef(Arch.CFG_DBROW, to);
            if (ob == null) continue;
            DbRow u, o;
            try { u = DbRow.row(user.configRef(Arch.CFG_DBROW, id)); o = DbRow.row(ob); }
            catch (RuntimeException e) { continue; }
            // obj values of the USER row and where they ended up in the output
            java.util.HashMap<Integer, Integer> objMoves = new java.util.HashMap<>();
            for (var uc : u.columns.values()) {
                DbRow.Column oc = o.columns.get(uc.id);
                if (oc == null || oc.values.length != uc.values.length) continue;
                for (int k = 0; k < uc.values.length; k++) {
                    int t = uc.types[k % uc.types.length];
                    if ((t == TYPE_OBJ || t == TYPE_NAMEDOBJ) && !uc.values[k].equals(oc.values[k])) objMoves.put((Integer) uc.values[k], (Integer) oc.values[k]);
                }
            }
            if (objMoves.isEmpty()) continue;
            boolean changed = false;
            for (var uc : u.columns.values()) {
                DbRow.Column oc = o.columns.get(uc.id);
                if (oc == null || oc.values.length != uc.values.length) continue;
                for (int k = 0; k < uc.values.length; k++) {
                    if (uc.types[k % uc.types.length] != TYPE_INT || !uc.values[k].equals(oc.values[k])) continue;
                    Integer moved = objMoves.get((Integer) uc.values[k]);
                    if (moved == null) continue;
                    oc.values[k] = moved;
                    changed = true;
                    ctx.report.add(Decision.NOTE, Kind.DBROW, to, "int copy of obj " + uc.values[k] + " in column " + uc.id + " follows the obj to " + moved);
                }
            }
            if (changed) { ctx.out.putConfig(Arch.CFG_DBROW, to, o.encode()); fixed++; }
        }
        ctx.report.count("dbrows.obj-copies-synced", fixed);
        return fixed;
    }

    /** Status of the audited index groups: "= BASE" or how they differ. */
    public static TreeMap<Integer, String> auditedStatus(Ctx ctx) {
        TreeMap<Integer, String> m = new TreeMap<>();
        for (int g : AUDITED) {
            if (!ctx.base.hasGroup(Arch.DBTABLE_INDEX, g)) { m.put(g, "absent in BASE"); continue; }
            m.put(g, sameAsBase(ctx, g) ? "= BASE" : ctx.out.hasGroup(Arch.DBTABLE_INDEX, g) ? "differs from BASE" : "missing");
        }
        return m;
    }

    static boolean sameAsBase(Ctx ctx, int g) {
        GroupData o = ctx.out.group(Arch.DBTABLE_INDEX, g), b = ctx.base.group(Arch.DBTABLE_INDEX, g);
        return o != null && b != null && o.sameContent(b);
    }

    /** BASE's group without zero padding in its files (BASE has none; kept for other inputs). */
    private static GroupData trimmed(GroupData g) {
        GroupData.Builder b = g.toBuilder();
        for (int i = 0; i < g.fileCount(); i++) {
            DbIndexFile f = DbIndexFile.parse(g.dataAt(i));
            if (f.padding > 0) b.put(g.fileIdAt(i), g.fileNameAt(i), f.encode());
        }
        return b.build();
    }

    private static boolean sameRows(List<DbIndexBuilder.Row> a, List<DbIndexBuilder.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).id() != b.get(i).id()) return false;
            if (!Arrays.equals(a.get(i).row().encode(), b.get(i).row().encode())) return false;
        }
        return true;
    }

    private static String rowDelta(List<DbIndexBuilder.Row> out, List<DbIndexBuilder.Row> base) {
        TreeSet<Integer> o = new TreeSet<>(), b = new TreeSet<>();
        for (var r : out) o.add(r.id());
        for (var r : base) b.add(r.id());
        TreeSet<Integer> added = new TreeSet<>(o); added.removeAll(b);
        TreeSet<Integer> gone = new TreeSet<>(b); gone.removeAll(o);
        int changed = 0;
        TreeMap<Integer, DbRow> bm = new TreeMap<>();
        for (var r : base) bm.put(r.id(), r.row());
        for (var r : out) { DbRow x = bm.get(r.id()); if (x != null && !Arrays.equals(x.encode(), r.row().encode())) changed++; }
        return added.size() + " added " + abbreviate(added) + ", " + gone.size() + " gone " + abbreviate(gone) + ", " + changed + " changed";
    }

    private static String abbreviate(TreeSet<Integer> s) {
        if (s.isEmpty()) return "";
        return s.size() <= 4 ? s.toString() : "[" + s.first() + ".." + s.last() + "]";
    }

    /** How the built group differs from the USER group with mapped row ids ("" if equal apart from padding). */
    private static String compareWithUser(Ctx ctx, GroupData userGroup, GroupData built) {
        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < userGroup.fileCount(); i++) {
            int f = userGroup.fileIdAt(i);
            DbIndexFile u;
            try { u = DbIndexFile.parse(userGroup.dataAt(i)).mapRows(r -> ctx.ids.map(Kind.DBROW, r)); }
            catch (RuntimeException e) { diffs.add("USER file " + f + " unreadable"); continue; }
            byte[] b = built.data(f);
            if (b == null) { diffs.add("file " + f + " dropped"); continue; }
            DbIndexFile n = DbIndexFile.parse(b);
            if (u.equals(n)) continue;
            int uk = u.tuples.isEmpty() ? 0 : u.tuples.get(0).entries.size(), nk = n.tuples.isEmpty() ? 0 : n.tuples.get(0).entries.size();
            TreeSet<Integer> ur = new TreeSet<>(u.rows()), nr = new TreeSet<>(n.rows());
            String what = f == 0 ? "master" : "column " + (f - 1);
            if (!ur.equals(nr)) {
                TreeSet<Integer> stale = new TreeSet<>(ur); stale.removeAll(nr);
                diffs.add(what + ": USER lists " + ur.size() + " rows, the table has " + nr.size() + (stale.isEmpty() ? "" : " (USER lists rows of no row of this table: " + abbreviate(stale) + ")"));
            } else diffs.add(what + ": USER had " + uk + " keys, rebuilt " + nk + " (USER's file did not index this column's values)");
        }
        return String.join("; ", diffs);
    }

    /** Every index row exists and belongs to the table; every row is in its table's master. Returns the number of errors. */
    static int consistency(Ctx ctx, TreeMap<Integer, List<DbIndexBuilder.Row>> outRows) {
        TreeMap<Integer, Integer> tableOf = new TreeMap<>();
        for (var e : outRows.entrySet()) for (var r : e.getValue()) tableOf.put(r.id(), e.getKey());
        int errors = 0;
        for (int g : ctx.out.groupIds(Arch.DBTABLE_INDEX)) {
            GroupData gd = ctx.out.group(Arch.DBTABLE_INDEX, g);
            if (gd == null) continue;
            TreeSet<Integer> foreign = new TreeSet<>(), missing = new TreeSet<>();
            for (int i = 0; i < gd.fileCount(); i++) {
                DbIndexFile f;
                try { f = DbIndexFile.parse(gd.dataAt(i)); }
                catch (RuntimeException e) { ctx.report.error("dbtable", g, "index file " + gd.fileIdAt(i) + " does not parse: " + e.getMessage()); errors++; continue; }
                for (int r : f.rows()) { Integer t = tableOf.get(r); if (t == null) missing.add(r); else if (t != g) foreign.add(r); }
                if (gd.fileIdAt(i) == 0) {
                    TreeSet<Integer> listed = new TreeSet<>(f.rows());
                    for (var r : outRows.getOrDefault(g, List.of())) if (!listed.contains(r.id())) { ctx.report.error("dbtable", g, "row " + r.id() + " is not in its table's master index"); errors++; break; }
                }
            }
            if (!missing.isEmpty()) { ctx.report.error("dbtable", g, "index lists rows that do not exist: " + abbreviate(missing)); errors++; }
            if (!foreign.isEmpty()) { ctx.report.error("dbtable", g, "index lists rows of other tables: " + abbreviate(foreign)); errors++; }
        }
        return errors;
    }
}
