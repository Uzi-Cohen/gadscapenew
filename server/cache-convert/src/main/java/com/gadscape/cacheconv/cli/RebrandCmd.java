package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.OutCache;
import com.gadscape.cacheconv.core.Report;
import com.gadscape.cacheconv.rebrand.RebrandTable;
import com.gadscape.cacheconv.rebrand.Rebrander;
import com.gadscape.cacheconv.verify.BrandScan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * gadconv rebrand: replaces old branding (Near-Reality, Roat Pkz, OSNR, RuneSpawn...) with GadScape in config strings,
 * interface texts and script strings, and reports every change ({@link Rebrander}).
 * <pre>
 *   gadconv rebrand CACHE [--rebrand.table=FILE] --out DIR [--force] [--dry-run] [--report-dir DIR]
 *   gadconv rebrand CACHE                       (no table or no --out: scan only, lists the branding hits)
 * </pre>
 * The output starts as a copy of CACHE; report.json goes to --report-dir (default: the output directory, file
 * rebrand-report.json). Inside build-cacheu the same rewrite runs as the "rebrand" step.
 */
final class RebrandCmd {
    private RebrandCmd() {}

    static int run(Options o) throws Exception {
        if (o.args.isEmpty()) throw new IllegalArgumentException("usage: rebrand CACHE [--rebrand.table=FILE] [--out DIR --force]");
        Path src = GadConv.cacheDir(o, o.args.get(0));
        RebrandTable t = RebrandTable.load(o);
        if (t.isEmpty() || o.out == null) {
            System.out.println("rebrand: " + (t.isEmpty() ? "no replacement table (--rebrand.table=FILE)" : "no --out") + "; scanning " + src + " only");
            try (JagCache c = JagCache.open("CACHE", src)) {
                List<BrandScan.Hit> hits = BrandScan.scan(c);
                Map<String, Integer> byArchive = new TreeMap<>();
                for (BrandScan.Hit h : hits) byArchive.merge(com.gadscape.cacheconv.core.Arch.name(h.archive()), 1, Integer::sum);
                for (BrandScan.Hit h : hits.subList(0, Math.min(40, hits.size()))) System.out.println("  " + h.where() + ": " + h.text());
                System.out.println(hits.size() + " branding hits " + byArchive);
            }
            return 0;
        }
        Report report = new Report();
        report.input("options", o.describe());
        report.input("rebrandTable", t.source + " (" + t.rules.size() + " rules)");
        report.begin("rebrand");
        Rebrander r;
        try (OutCache out = OutCache.create(src, o.out, o.protectedDirs(), o.force, o.dryRun, report)) {
            r = new Rebrander(t, out, report);
            r.run();
            out.commit();
        } finally {
            report.end();
        }
        Path rd = o.reportDir != null ? o.reportDir : o.out;
        report.save(rd.resolve(o.reportDir != null ? "report.json" : "rebrand-report.json"));
        System.out.println("rebrand: " + r.counts + ", " + report.errors() + " errors; " + o.out + (o.dryRun ? " (dry run)" : ""));
        return report.errors() > 0 ? 1 : 0;
    }
}
