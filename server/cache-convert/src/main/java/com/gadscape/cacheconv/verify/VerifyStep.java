package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Json;
import com.gadscape.cacheconv.core.Step;

import java.nio.file.Path;
import java.util.List;

/**
 * Pipeline step "verify": runs the {@link Verifier} on the output (every pending change is committed by then) with
 * BASE as baseline, adds every check's counts to report.json, and one report error per error check with bad items
 * BASE does not have (so build-cacheu exits 1). Writes WORK/verify/verify.json. Run it last.
 * <p>
 * Bad items listed in {@link KnownExceptions} (documented, understood exceptions) are reported but are not errors.
 * Options: {@code --verify.known=FILE|none}, {@code --verify.checks=a,b} (default: refs, anims, interfaces, maps, scripts, brand, and strict when the
 * output is on disk; oracle when {@code --verify.oracle} is set), {@code --brand-errors} (branding hits are errors; the
 * default when a rebrand table is applied, {@code --brand-warnings} turns that off; the scan result goes to report.json
 * input {@code brandScan}),
 * {@code --oracle.*} / {@code --strict.*} as for the verify command.
 */
public final class VerifyStep implements Step {
    @Override public String name() { return "verify"; }

    @Override public void run(Ctx ctx) throws Exception {
        Verifier.Opts vo = new Verifier.Opts();
        String checks = ctx.opt.get("verify.checks", null);
        vo.checks.clear();
        if (checks != null) vo.checks.addAll(List.of(checks.split(",")));
        else {
            vo.checks.addAll(List.of("scripts", "refs", "anims", "interfaces", "maps", "brand"));
            if (!ctx.out.dryRun()) vo.checks.add("strict");
            if (ctx.opt.flag("verify.oracle")) vo.checks.add("oracle");
        }
        vo.base = ctx.base;
        vo.cacheDir = ctx.out.dryRun() ? null : ctx.out.dir;
        vo.root = ctx.opt.root;
        vo.work = ctx.work("verify");
        vo.log = ctx::log;
        if (ctx.opt.get("oracle.lib", null) != null) vo.clientLib = Path.of(ctx.opt.get("oracle.lib", null));
        for (var e : ctx.opt.extra.entrySet()) {
            if (e.getKey().startsWith("oracle.") && !e.getKey().equals("oracle.lib")) vo.oracle.put(e.getKey().substring(7), e.getValue());
            if (e.getKey().startsWith("strict.")) vo.strict.put(e.getKey(), e.getValue());
        }
        // branding hits are errors when the build applies a rebrand table (the table is meant to remove them all)
        boolean table = !com.gadscape.cacheconv.rebrand.RebrandTable.load(ctx.opt).isEmpty();
        if (ctx.opt.flag("brand-errors") || table && !ctx.opt.flag("brand-warnings")) vo.brandSeverity = Findings.Severity.ERROR;
        vo.known = KnownExceptions.load(ctx.opt.get("verify.known", null));
        Verifier.Result r = Verifier.run(ctx.out, vo);
        r.findings.toReport(ctx.report);
        java.util.Map<String, Object> brand = new java.util.TreeMap<>();
        for (var e : r.findings.checks().entrySet()) {
            if (!e.getKey().startsWith("brand/")) continue;
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ok", e.getValue().ok);
            m.put("hits", e.getValue().bad);
            m.put("examples", e.getValue().examples);
            brand.put(e.getKey(), m);
        }
        brand.put("patterns", "raw bytes: " + BrandScan.BRAND.pattern() + "; decoded strings: " + StringScan.BRAND.pattern() + " (case-insensitive) and " + StringScan.NR.pattern());
        brand.put("stringsScanned", r.findings.info().get("brand.strings.scanned"));
        ctx.report.input("brandScan", brand);
        Json.write(vo.work.resolve("verify.json"), r.toJson());
        r.findings.lines().forEach(l -> ctx.log("%s", l));
    }
}
