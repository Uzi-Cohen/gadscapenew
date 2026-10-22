package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone run of the UI steps ({@link UiRelocateStep}, {@link UiConvertStep}, {@link UiCheckStep}), for dry runs and experiments:
 * <pre>
 *   dev/run.sh ui.UiMain [--dry-run] [--out DIR] [--force] [--report-dir DIR]
 *                        [--ui.preload=IDMAP.json[,IDMAP.json]] [--ui.componentRemap=TSV] [--ui.fixups=false]
 *                        [--ui.interfaceRange=5200-5299] [--ui.interfaces=1600,1700-1702] [--ui.scripts=...]
 *                        [--ui.copyAssets=false]
 * </pre>
 * Copies BASE to --out (never into an input; nothing is written with --dry-run), runs the steps, committing after
 * each (skip one with --skip ui-check), and writes idmap.json and report.json to --report-dir (default
 * WORK/ui-convert/dry-run or .../run). {@code --ui.preload} replays relocations other areas recorded (their
 * idmap.json) first, so references to them are rewritten as in the full pipeline. Exit code 1 when a step reported
 * an error.
 */
public final class UiMain {
    private UiMain() {}

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args, 0);
        o.extra.putIfAbsent("ui.standalone", "true");
        Path reportDir = o.reportDir != null ? o.reportDir : (o.work != null ? o.work : Path.of("gadconv-work")).resolve("ui-convert").resolve(o.dryRun ? "dry-run" : "run");
        System.exit(run(o, reportDir));
    }

    /** Runs the three UI steps on {@code o} and saves idmap.json/report.json in {@code reportDir}; returns the exit code. */
    public static int run(Options o, Path reportDir) throws Exception {
        Files.createDirectories(reportDir);
        List<Step> steps = List.of(new UiRelocateStep(), new UiConvertStep(), new UiCheckStep());
        long t0 = System.currentTimeMillis();
        Ctx ctx = Ctx.open(o, true);
        boolean failed = false;
        try {
            String pre = o.get("ui.preload", "");
            if (!pre.isEmpty()) {
                int n = 0;
                for (String f : pre.split(",")) {
                    IdMap m = IdMap.load(Path.of(f.trim()));
                    for (Kind k : Kind.values()) for (var e : m.all(k).entrySet()) { ctx.ids.record(k, e.getKey(), e.getValue(), "preloaded: " + m.reason(k, e.getKey())); n++; }
                }
                System.out.println("preloaded " + n + " relocations");
            }
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
        return failed || ctx.report.errors() > 0 ? 1 : 0;
    }
}
