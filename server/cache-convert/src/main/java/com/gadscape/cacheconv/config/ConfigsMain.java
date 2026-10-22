package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone run of the configs area, a dry run by default (nothing is written; reads see BASE plus the pending
 * changes):
 * <pre>
 *   dev/run.sh config.ConfigsMain [--report-dir DIR] [--configs.with=models,anim] [--configs.after=CLASS,...]
 *                                 [--configs.idmap=FILE] [--write --out DIR --force] [any --configs.* option]
 * </pre>
 * Runs, in pipeline order: [models] -> configs-plan -> [anim] -> configs -> [--configs.after steps] ->
 * configs-oracle -> configs-refcheck -> configs-expect, committing after each step. {@code --configs.with} adds the other areas'
 * steps that move ids configs refer to (models = {@code model.ModelsStep}, anim = {@code anim.AnimStep}), so the dry
 * run sees their relocations; {@code --configs.after} adds more steps by class name (relative to
 * {@code com.gadscape.cacheconv}, e.g. {@code ui.UiRelocateStep}). They are loaded by reflection, so this package
 * compiles without them. {@code --configs.idmap=FILE} instead preloads relocations from an idmap.json of another run.
 * Writes idmap.json and report.json to --report-dir (default WORK/configs/dry-run or WORK/configs/run).
 * Exit code 1 when a step failed or reported an error.
 */
public final class ConfigsMain {
    private ConfigsMain() {}

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        o.dryRun = !o.flag("write");
        Path work = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("configs");
        Path reportDir = o.reportDir != null ? o.reportDir : work.resolve(o.dryRun ? "dry-run" : "run");
        if (o.dryRun && o.out == null) o.out = work.resolve("dry-out");
        Files.createDirectories(reportDir);
        List<String> with = List.of(o.get("configs.with", "").split(","));
        List<Step> steps = new ArrayList<>();
        if (with.contains("models")) steps.add(step("model.ModelsStep"));
        steps.add(new ConfigPlanStep());
        if (with.contains("anim")) steps.add(step("anim.AnimStep"));
        steps.add(new ConfigStep());
        for (String c : o.get("configs.after", "").split(",")) if (!c.isBlank()) steps.add(step(c.trim()));
        steps.add(new ConfigOracle());
        steps.add(new ConfigRefCheck());
        steps.add(new ConfigExpect());
        long t0 = System.currentTimeMillis();
        int status = 0;
        Ctx ctx = Ctx.open(o, true);
        try {
            String pre = o.get("configs.idmap", null);
            if (pre != null) preload(ctx, Path.of(pre));
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
        System.exit(status != 0 || ctx.report.errors() > 0 ? 1 : 0);
    }

    /** A Step class of another area by name (relative to com.gadscape.cacheconv or fully qualified). */
    static Step step(String cls) throws ReflectiveOperationException {
        String fq = cls.startsWith("com.") ? cls : "com.gadscape.cacheconv." + cls;
        return (Step) Class.forName(fq).getDeclaredConstructor().newInstance();
    }

    /** Records every relocation of another run's idmap.json (and its ranges) into this run's id map. */
    static void preload(Ctx ctx, Path file) throws java.io.IOException {
        IdMap m = IdMap.load(file);
        int n = 0;
        for (Kind k : Kind.values()) {
            if (m.range(k) != null && ctx.ids.range(k) == null) ctx.ids.setRange(k, m.range(k).lo(), m.range(k).hi());
            for (var e : m.all(k).entrySet()) { ctx.ids.record(k, e.getKey(), e.getValue(), m.reason(k, e.getKey())); n++; }
        }
        ctx.report.note("preloaded " + n + " relocations from " + file);
    }
}
