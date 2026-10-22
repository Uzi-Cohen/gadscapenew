package com.gadscape.cacheconv.anim;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone run of the animation steps (no other area), for dry runs and experiments:
 * <pre>
 *   dev/run.sh anim.AnimMain [--dry-run] [--out DIR] [--force] [--report-dir DIR] [--anim.portRemovedVanilla=false]
 *   dev/run.sh anim.AnimMain --check-only --out DIR      (only the reference check of an existing output cache)
 * </pre>
 * Copies BASE to --out (like build-cacheu, never into an input), runs {@link AnimStep} and {@link AnimRefCheck},
 * committing after each, and writes idmap.json and report.json to --report-dir (default WORK/anim, or the output
 * directory's parent when --out is given). Exit code 1 when a step reported an error.
 */
public final class AnimMain {
    private AnimMain() {}

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args, 0);
        boolean checkOnly = o.flag("check-only");
        Path reportDir = o.reportDir != null ? o.reportDir : (o.work != null ? o.work : Path.of("gadconv-work")).resolve("anim").resolve(o.dryRun ? "dry-run" : "run");
        Files.createDirectories(reportDir);
        if (checkOnly) {
            // open the existing output read-only as BASE-like view: refcheck only needs USER/VAN211/BASE + the cache
            System.exit(AnimRefCheck.standalone(o, reportDir));
        }
        List<Step> steps = List.of(new AnimStep(), new AnimRefCheck());
        long t0 = System.currentTimeMillis();
        Ctx ctx = Ctx.open(o, true);
        boolean failed = false;
        try {
            for (Step s : steps) {
                if (!o.runs(s.name())) continue;
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
        System.exit(failed || ctx.report.errors() > 0 ? 1 : 0);
    }
}
