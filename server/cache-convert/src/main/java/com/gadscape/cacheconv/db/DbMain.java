package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone run of the database steps, a dry run by default (nothing is written; reads see BASE plus the pending
 * changes):
 * <pre>
 *   dev/run.sh db.DbMain [--db.with=configs,sprites] [--report-dir DIR] [--write --out DIR --force]
 * </pre>
 * Runs [fonts, sprites], db-plan, then the rows: with {@code --db.with=configs} the configs area's
 * {@code configs-plan} and {@code configs} steps, otherwise {@link DbRowsStep}; then [mapscene] and db-index. Other
 * areas' steps are loaded by reflection, so this package compiles without them. Writes idmap.json and report.json
 * to --report-dir (default WORK/sprites-db/db-dry-run or db-run).
 * Exit code 1 when a step failed or reported an error.
 */
public final class DbMain {
    private DbMain() {}

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        o.dryRun = !o.flag("write");
        Path work = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("sprites-db");
        Path reportDir = o.reportDir != null ? o.reportDir : work.resolve(o.dryRun ? "db-dry-run" : "db-run");
        if (o.dryRun && o.out == null) o.out = work.resolve("dry-out");
        Files.createDirectories(reportDir);
        List<String> with = List.of(o.get("db.with", "").split(","));
        List<Step> steps = new ArrayList<>();
        if (with.contains("sprites")) { steps.add(step("sprite.FontsStep")); steps.add(step("sprite.SpritesStep")); }
        steps.add(new DbPlanStep());
        if (with.contains("configs")) {
            steps.add(step("config.ConfigPlanStep"));
            steps.add(step("config.ConfigStep"));
        } else steps.add(new DbRowsStep());
        if (with.contains("sprites")) steps.add(step("sprite.MapsceneStep"));
        steps.add(new DbIndexStep());
        System.exit(run(o, steps, reportDir));
    }

    /** Runs steps like build-cacheu does (commit after each step), saves idmap.json and report.json; 1 on failure/errors. */
    public static int run(Options o, List<Step> steps, Path reportDir) throws Exception {
        long t0 = System.currentTimeMillis();
        int status = 0;
        Ctx ctx = Ctx.open(o, true);
        try {
            for (Step s : steps) {
                if (!o.runs(s.name())) continue;
                long t = System.currentTimeMillis();
                System.out.println("> " + s.name());
                ctx.report.begin(s.name());
                try { s.run(ctx); ctx.out.commit(); }
                catch (Exception e) { ctx.report.error("step failed: " + e); e.printStackTrace(); status = 1; break; }
                finally { ctx.report.end(); }
                System.out.printf("  %s done in %.1f s%n", s.name(), (System.currentTimeMillis() - t) / 1000.0);
            }
        } finally {
            ctx.close();
            ctx.ids.save(reportDir.resolve("idmap.json"));
            ctx.report.save(reportDir.resolve("report.json"));
        }
        System.out.printf("%d relocations, %d warnings, %d errors in %.1f s; %s%n", ctx.ids.size(), ctx.report.warnings(), ctx.report.errors(),
                (System.currentTimeMillis() - t0) / 1000.0, reportDir);
        return status != 0 || ctx.report.errors() > 0 ? 1 : 0;
    }

    /** A Step class of another area by name (relative to com.gadscape.cacheconv or fully qualified). */
    public static Step step(String cls) throws ReflectiveOperationException {
        String fq = cls.startsWith("com.") ? cls : "com.gadscape.cacheconv." + cls;
        return (Step) Class.forName(fq).getDeclaredConstructor().newInstance();
    }
}
