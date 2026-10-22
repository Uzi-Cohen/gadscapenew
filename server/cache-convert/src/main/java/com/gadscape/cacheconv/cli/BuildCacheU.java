package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Path;
import java.util.List;

/**
 * gadconv build-cacheu: copies BASE to --out, runs {@link Pipeline#steps} in order (committing the output after each
 * step, so the run is deterministic and memory stays bounded), then writes idmap.json and report.json to
 * --report-dir (default: the output directory; in --dry-run mode: WORK/dry-run). Exit code 1 if any step reported an
 * error or threw.
 */
final class BuildCacheU {
    private BuildCacheU() {}

    static int run(Options o) throws Exception {
        o.require("base", o.base);
        o.require("user", o.user);
        o.require("van211", o.van211);
        if (o.out == null) throw new IllegalArgumentException("--out is required");
        long t0 = System.currentTimeMillis();
        List<Step> steps = Pipeline.steps(o);
        System.out.println("gadconv build-cacheu: " + o.base + " + " + o.user + " -> " + o.out + (o.dryRun ? " (dry run)" : ""));
        Path reportDir = o.reportDir != null ? o.reportDir : o.dryRun ? (o.work != null ? o.work : Path.of("gadconv-work")).resolve("dry-run") : o.out;
        Ctx ctx = Ctx.open(o, true);
        boolean failed = false;
        try {
            for (Step s : steps) {
                if (!o.runs(s.name())) { System.out.println("- " + s.name() + " (skipped)"); continue; }
                long t = System.currentTimeMillis();
                System.out.println("> " + s.name());
                ctx.report.begin(s.name());
                try {
                    s.run(ctx);
                    ctx.out.commit();
                } catch (Exception e) {
                    ctx.report.error("step failed: " + e);
                    e.printStackTrace();
                    failed = true;
                    break;
                } finally {
                    ctx.report.end();
                }
                System.out.printf("  %s done in %.1f s%n", s.name(), (System.currentTimeMillis() - t) / 1000.0);
            }
        } finally {
            ctx.close();
            ctx.ids.save(reportDir.resolve("idmap.json"));
            ctx.report.save(reportDir.resolve("report.json"));
        }
        System.out.printf("%s in %.1f s: %d relocations, %d warnings, %d errors; report %s%n", failed ? "FAILED" : "done",
                (System.currentTimeMillis() - t0) / 1000.0, ctx.ids.size(), ctx.report.warnings(), ctx.report.errors(), reportDir.resolve("report.json"));
        return failed || ctx.report.errors() > 0 ? 1 : 0;
    }
}
