package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks of the database steps on the real caches (a dry run: nothing is written):
 * <ol>
 *   <li>codecs: every BASE and USER dbrow and dbtable re-encodes to its bytes (minus the user's zero padding), every
 *       archive-21 file too;</li>
 *   <li>the index builder reproduces every BASE index group byte for byte from BASE's rows;</li>
 *   <li>after db-plan + db-rows + db-index: BASE's rows 2342-2928 are BASE's, every user row moved into the reserved
 *       range with its table's order kept, each user table's master lists its rows in the order of the USER master,
 *       and every BASE index group (the audited 37, 38, 39, 40, 41, 44, 65 among them) equals BASE.</li>
 * </ol>
 * <pre>  dev/run.sh db.DbSelfCheck</pre>  Exit code 1 on any failure; {@link #check} is also run by the unit tests when the
 * cache layout is available.
 */
public final class DbSelfCheck {
    private final List<String> failures = new ArrayList<>();
    private int checks;

    private void expect(boolean ok, String what) { checks++; if (!ok && failures.size() < 1000) failures.add(what); }

    public static void main(String[] a) throws Exception {
        List<String> f = check(Options.parse(a, 0));
        System.out.println(f.isEmpty() ? "db self-check: OK" : "db self-check: " + f.size() + " FAILURES");
        f.stream().limit(40).forEach(x -> System.out.println("  " + x));
        System.exit(f.isEmpty() ? 0 : 1);
    }

    public static List<String> check(Options o) throws Exception {
        DbSelfCheck c = new DbSelfCheck();
        for (Path p : new Path[]{o.base, o.user}) {
            JagCache jc = JagCache.open(p.equals(o.base) ? "BASE" : "USER", p);
            c.codecs(jc);
            if (p.equals(o.base)) c.rebuildBase(jc);
            jc.close();
        }
        c.steps(o);
        System.out.println("db self-check: " + c.checks + " checks");
        return c.failures;
    }

    void codecs(JagCache c) {
        int rows = 0, tables = 0, files = 0;
        for (int[] g : new int[][]{{Arch.CFG_DBROW, 0}, {Arch.CFG_DBTABLE, 1}}) {
            GroupData gd = c.group(Arch.CONFIGS, g[0]);
            for (int i = 0; i < gd.fileCount(); i++) {
                byte[] b = gd.dataAt(i);
                try {
                    DbRow r = g[1] == 1 ? DbRow.tableDef(b) : DbRow.row(b);
                    expect(Arrays.equals(r.encode(), Arrays.copyOf(b, b.length - r.padding)), c.label() + " " + Arch.configName(g[0]) + " " + gd.fileIdAt(i) + " re-encodes differently");
                } catch (RuntimeException e) { expect(false, c.label() + " " + Arch.configName(g[0]) + " " + gd.fileIdAt(i) + ": " + e.getMessage()); }
                if (g[1] == 1) tables++; else rows++;
            }
        }
        for (int t : c.groupIds(Arch.DBTABLE_INDEX)) {
            GroupData gd = c.group(Arch.DBTABLE_INDEX, t);
            for (int i = 0; i < gd.fileCount(); i++) {
                byte[] b = gd.dataAt(i);
                DbIndexFile f = DbIndexFile.parse(b);
                expect(Arrays.equals(f.encode(), Arrays.copyOf(b, b.length - f.padding)), c.label() + " index " + t + ":" + gd.fileIdAt(i) + " re-encodes differently");
                files++;
            }
        }
        c.release(Arch.CONFIGS);
        System.out.printf("codecs: %s %d rows, %d tables, %d index files%n", c.label(), rows, tables, files);
    }

    void rebuildBase(JagCache base) {
        TreeMap<Integer, List<DbIndexBuilder.Row>> rows = DbIndexStep.rowsByTable(base, null);
        int groups = 0, same = 0;
        for (int t : base.groupIds(Arch.DBTABLE_INDEX)) {
            GroupData g = base.group(Arch.DBTABLE_INDEX, t);
            byte[] def = base.config(Arch.CFG_DBTABLE, t);
            int[] names = new int[g.fileCount()];
            for (int i = 0; i < names.length; i++) names[i] = g.fileNameAt(i);
            GroupData built = DbIndexBuilder.group(t, g.name, g.fileIds(), names, rows.getOrDefault(t, List.of()), def == null ? null : DbRow.tableDef(def), null);
            groups++;
            boolean ok = built.sameContent(g);
            if (ok) same++;
            expect(ok, "BASE index group " + t + " is not reproduced from BASE's rows");
        }
        System.out.printf("index builder: %d of %d BASE index groups reproduced byte for byte%n", same, groups);
    }

    void steps(Options o) throws Exception {
        o.dryRun = true;
        o.out = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("sprites-db/selfcheck-out"); // never written (dry run)
        try (Ctx ctx = Ctx.open(o, true)) {
            ctx.report.echo = false;
            for (Step s : List.of(new DbPlanStep(), new DbRowsStep(), new DbIndexStep())) { ctx.report.begin(s.name()); s.run(ctx); ctx.report.end(); }
            var out = ctx.out;
            var user = ctx.user();
            Map<Integer, Integer> moved = ctx.ids.all(Kind.DBROW);
            for (int r = 2342; r <= 2928; r++) {
                byte[] b = ctx.base.configRef(Arch.CFG_DBROW, r);
                if (b != null) expect(Arrays.equals(out.configRef(Arch.CFG_DBROW, r), b), "dbrow " + r + " is not BASE's");
            }
            int prev = -1, prevFrom = -1;
            for (var e : moved.entrySet()) {
                expect(e.getValue() >= 60000 && e.getValue() <= 64999, "dbrow " + e.getKey() + " moved outside 60000-64999");
                expect(e.getValue() > prev && e.getKey() > prevFrom, "dbrow moves are not in ascending order at " + e.getKey());
                prev = e.getValue(); prevFrom = e.getKey();
                byte[] ob = out.configRef(Arch.CFG_DBROW, e.getValue());
                expect(ob != null && DbRow.row(ob).tableId == DbRow.row(user.configRef(Arch.CFG_DBROW, e.getKey())).tableId, "moved dbrow " + e.getValue() + " missing or in another table");
            }
            for (int t = 1001; t <= 1014; t++) {
                GroupData ug = user.group(Arch.DBTABLE_INDEX, t), og = out.group(Arch.DBTABLE_INDEX, ctx.ids.map(Kind.DBTABLE, t));
                if (ug == null) continue;
                expect(og != null, "user table " + t + " has no index group");
                if (og == null) continue;
                List<Integer> um = DbIndexFile.parse(ug.data(0)).mapRows(r -> ctx.ids.map(Kind.DBROW, r)).rows(), om = DbIndexFile.parse(og.data(0)).rows();
                expect(um.equals(om), "user table " + t + ": master rows " + om.size() + " differ from the USER master mapped (" + um.size() + ")");
            }
            for (var e : DbIndexStep.auditedStatus(ctx).entrySet()) expect(e.getValue().equals("= BASE"), "audited index group " + e.getKey() + " is " + e.getValue());
            int equal = 0;
            for (int g : ctx.base.groupIds(Arch.DBTABLE_INDEX)) { boolean eq = out.group(Arch.DBTABLE_INDEX, g).sameContent(ctx.base.group(Arch.DBTABLE_INDEX, g)); if (eq) equal++; expect(eq, "BASE index group " + g + " differs from BASE"); }
            expect(ctx.report.errors() == 0, ctx.report.errors() + " errors reported by the steps");
            System.out.printf("steps: %d rows moved (%s), %d of %d BASE index groups equal to BASE, audited %s%n", moved.size(),
                    moved.isEmpty() ? "-" : moved.values().stream().min(Integer::compare).get() + "-" + moved.values().stream().max(Integer::compare).get(),
                    equal, ctx.base.groupIds(Arch.DBTABLE_INDEX).length, DbIndexStep.auditedStatus(ctx));
        }
    }
}
