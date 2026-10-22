package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * db-plan: decides where the user's database rows and tables go (PVP-23, CRIT-23), writing nothing. BASE's dbrows and
 * dbtables keep their ids (BASE's scripts and engine features read them; the hand-built cacheU had the user's
 * universal-shop rows over 587 stock rows 2342-2928 of tables 0, 1, 15, 18, 25, 34, 37-42, 44, 49 and 65).
 * <ul>
 *   <li><b>User tables</b> (user-added dbtables, e.g. the universal shop's 1000-1014): a table id BASE also defines
 *       for different content moves (to {@code --db.tableRange}, default 4000-4999; scripts' table and column
 *       constants, row opcode 4 and the index group follow through the id map). If any row of a user table sits at
 *       an id BASE uses, <i>every</i> row of that table moves to the reserved dbrow range (60000-64999), so the table
 *       stays together.</li>
 *   <li><b>User rows in vanilla tables</b>: only the rows at ids BASE uses move.</li>
 *   <li>Rows move in ascending id order to ascending new ids, so every table keeps its row order: the client's
 *       {@code db_listall}/{@code db_findbyindex} and the server's {@code DBRowDefinition.tableRows} (row index = rank
 *       of the id within its table) see the same order as before. A user row identical to BASE's row stays.</li>
 * </ul>
 * Run it before {@code configs-plan} (which then leaves these rows alone) and before every step that rewrites dbrow
 * or dbtable references; {@code configs} writes the rows at their new ids and {@link DbIndexStep} rebuilds archive 21.
 * Writes {@code WORK/sprites-db/dbrow-moves.tsv} (table, row index in the table, old id, new id) for the server's
 * universal-shop packer.
 */
public final class DbPlanStep implements Step {
    public static final String NAME = "db-plan";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) throws IOException {
        var user = ctx.user();
        var van = ctx.van211();
        // user tables
        TreeMap<Integer, Origin> userTables = new TreeMap<>();
        for (int t : user.configIds(Arch.CFG_DBTABLE)) {
            Origin o = Origin.ofConfig(Arch.CFG_DBTABLE, t, user, van);
            if (o.isUser()) userTables.put(t, o);
        }
        for (var e : userTables.entrySet()) {
            int t = e.getKey();
            if (ctx.ids.moved(Kind.DBTABLE, t)) continue;
            byte[] b = ctx.base.config(Arch.CFG_DBTABLE, t);
            if (b == null) { ctx.report.add(e.getValue() == Origin.USER_ADDED ? Decision.USER_ADDED : Decision.USER_CHANGED, Kind.DBTABLE, t, "user table (BASE has no table " + t + ")"); continue; }
            if (Arrays.equals(b, user.configRef(Arch.CFG_DBTABLE, t))) continue;
            if (e.getValue() == Origin.USER_CHANGED) { ctx.report.add(Decision.USER_CHANGED, Kind.DBTABLE, t, "user-edited vanilla table (left to configs)"); continue; }
            if (ctx.ids.range(Kind.DBTABLE) == null) {
                String[] r = ctx.opt.get("db.tableRange", "4000-4999").split("-");
                ctx.ids.setRange(Kind.DBTABLE, Integer.parseInt(r[0].trim()), Integer.parseInt(r[1].trim()));
            }
            String why = "user dbtable " + t + " clashes with BASE's table " + t;
            int to = ctx.ids.relocate(Kind.DBTABLE, t, why);
            ctx.report.relocated(Kind.DBTABLE, t, to, why);
        }

        // user rows, by table
        TreeMap<Integer, TreeSet<Integer>> rowsByTable = new TreeMap<>();
        TreeMap<Integer, Origin> rowOrigin = new TreeMap<>();
        int unreadable = 0;
        for (int id : user.configIds(Arch.CFG_DBROW)) {
            Origin o = Origin.ofConfig(Arch.CFG_DBROW, id, user, van);
            if (!o.isUser()) continue;
            int table;
            try { table = DbRow.tableOf(user.configRef(Arch.CFG_DBROW, id)); }
            catch (RuntimeException ex) { ctx.report.error("dbrow", id, "USER row does not decode: " + ex.getMessage()); unreadable++; continue; }
            rowsByTable.computeIfAbsent(table, k -> new TreeSet<>()).add(id);
            rowOrigin.put(id, o);
        }
        // which rows move
        TreeMap<Integer, Integer> moveTable = new TreeMap<>(); // row -> its table
        for (var e : rowsByTable.entrySet()) {
            int t = e.getKey();
            boolean userTable = userTables.containsKey(t);
            boolean anyClash = false;
            for (int r : e.getValue()) if (clashes(ctx, r)) { anyClash = true; break; }
            for (int r : e.getValue()) {
                if (ctx.ids.moved(Kind.DBROW, r)) continue;
                if (userTable ? anyClash && !sameAsBase(ctx, r) : clashes(ctx, r)) moveTable.put(r, t);
            }
        }
        // relocate in ascending row order: every table keeps its order
        TreeMap<Integer, Integer> indexInTable = new TreeMap<>();
        for (var e : rowsByTable.entrySet()) { int k = 0; for (int r : e.getValue()) indexInTable.put(r, k++); }
        StringBuilder tsv = new StringBuilder("# table\tindex\told\tnew\treason\n");
        TreeMap<Integer, int[]> perTable = new TreeMap<>(); // table -> {moved, first new, last new}
        for (var e : moveTable.entrySet()) {
            int r = e.getKey(), t = e.getValue();
            boolean userTable = userTables.containsKey(t);
            String why = (userTable ? "row " + indexInTable.get(r) + " of user dbtable " + t : "user row in vanilla dbtable " + t)
                    + (clashes(ctx, r) ? ": BASE uses dbrow " + r : ": its table moves with the rows that clash with BASE");
            int to = ctx.ids.relocate(Kind.DBROW, r, why);
            ctx.report.relocated(Kind.DBROW, r, to, why);
            tsv.append(ctx.ids.map(Kind.DBTABLE, t)).append('\t').append(indexInTable.get(r)).append('\t').append(r).append('\t').append(to).append('\t').append(why).append('\n');
            int[] s = perTable.computeIfAbsent(t, k -> new int[]{0, Integer.MAX_VALUE, -1});
            s[0]++; s[1] = Math.min(s[1], to); s[2] = Math.max(s[2], to);
        }
        for (var e : rowsByTable.entrySet()) {
            int[] s = perTable.get(e.getKey());
            int kept = e.getValue().size() - (s == null ? 0 : s[0]);
            if (kept > 0) for (int r : e.getValue()) if (!moveTable.containsKey(r) && !ctx.ids.moved(Kind.DBROW, r))
                ctx.report.add(rowOrigin.get(r) == Origin.USER_ADDED ? Decision.USER_ADDED : Decision.USER_CHANGED, Kind.DBROW, r,
                        "user row of table " + e.getKey() + (ctx.base.hasConfig(Arch.CFG_DBROW, r) ? " (identical to BASE's)" : " at a free id"));
            ctx.report.count("dbrows.table-" + e.getKey() + (s == null ? " kept" : " moved " + s[1] + "-" + s[2]), e.getValue().size());
        }
        ctx.report.count("dbrows.user", rowOrigin.size());
        ctx.report.count("dbrows.relocated", moveTable.size());
        ctx.report.count("dbtables.user", userTables.size());
        if (unreadable > 0) ctx.report.count("dbrows.unreadable", unreadable);
        user.release(Arch.CONFIGS);
        Files.writeString(ctx.work("sprites-db").resolve("dbrow-moves.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        IdMap.Range r = ctx.ids.range(Kind.DBROW);
        ctx.log("db: %d user rows in %d tables, %d moved into %s; %s", rowOrigin.size(), rowsByTable.size(), moveTable.size(),
                r == null ? "-" : r.lo() + "-" + r.hi(), ctx.work("sprites-db").resolve("dbrow-moves.tsv"));
    }

    /** The USER row's id is used by BASE for a different row. */
    static boolean clashes(Ctx ctx, int row) {
        return ctx.base.hasConfig(Arch.CFG_DBROW, row) && !sameAsBase(ctx, row);
    }

    static boolean sameAsBase(Ctx ctx, int row) {
        byte[] b = ctx.base.configRef(Arch.CFG_DBROW, row), u = ctx.user().configRef(Arch.CFG_DBROW, row);
        if (b == null || u == null) return false;
        if (Arrays.equals(b, u)) return true;
        try { return Arrays.equals(DbRow.row(b).encode(), DbRow.row(u).encode()); } // the user's packer pads rows with zeros
        catch (RuntimeException e) { return false; }
    }
}
