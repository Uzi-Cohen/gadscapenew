package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Path;

/**
 * Standalone run of the models area, a dry run by default (nothing is written; reads see BASE plus the pending
 * changes):
 * <pre>
 *   dev/run.sh model.ModelsMain [--report-dir DIR] [--check] [--models-range=LO-HI] [--models-dedup=false]
 *                               [--models-pack=bottom] [--models-prev-idmap=FILE] [--write --out DIR --force]
 * </pre>
 * Writes idmap.json, report.json and moves.tsv to --report-dir (default WORK/models/dry-run). {@code --check} also
 * runs {@link ModelRefCheckStep} over the result (in a dry run that checks BASE's own configs and interfaces, which
 * validates the 241 walkers). {@code --write} builds a real output cache (BASE copy + models only).
 */
public final class ModelsMain {
    private ModelsMain() {}

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        o.dryRun = !o.flag("write");
        Path work = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("models");
        Path reportDir = o.reportDir != null ? o.reportDir : work.resolve(o.dryRun ? "dry-run" : "run");
        long t0 = System.currentTimeMillis();
        ModelsStep step = new ModelsStep();
        int status = 0;
        Ctx ctx = Ctx.open(o, true);
        try {
            status |= run(ctx, step);
            ctx.out.commit();
            if (o.flag("check")) status |= run(ctx, new ModelRefCheckStep());
        } finally {
            ctx.close();
            ctx.ids.save(reportDir.resolve("idmap.json"));
            ctx.report.save(reportDir.resolve("report.json"));
        }
        ModelsStep.Summary s = step.summary();
        if (s != null) {
            ModelsStep.writeTsv(s, reportDir.resolve("moves.tsv"));
            System.out.println("range " + s.lo + "-" + s.hi);
            s.counts.forEach((k, v) -> System.out.println("  " + k + " = " + v));
            ModelsStep.moveKinds(s).forEach((k, v) -> System.out.println("  moves " + k + " = " + v));
            if (s.census != null) s.census.totals().forEach((k, v) -> System.out.println("  refs " + k + " = " + v));
        }
        System.out.printf("%d relocations, %d warnings, %d errors in %.1f s; %s%n", ctx.ids.size(), ctx.report.warnings(), ctx.report.errors(),
                (System.currentTimeMillis() - t0) / 1000.0, reportDir);
        System.exit(status != 0 || ctx.report.errors() > 0 ? 1 : 0);
    }

    private static int run(Ctx ctx, Step s) {
        ctx.report.begin(s.name());
        try { s.run(ctx); return 0; }
        catch (Exception e) { ctx.report.error("step failed: " + e); e.printStackTrace(); return 1; }
        finally { ctx.report.end(); }
    }
}
