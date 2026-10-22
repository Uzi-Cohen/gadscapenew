package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Verifies a build-241 cache (any {@link CacheView}: an output being built, or a cache directory). Checks, each
 * selectable by name:
 * <ul>
 *   <li>{@code refs}: config references ({@link RefCheck#configs});</li>
 *   <li>{@code anims}: seq frames / keyframes / framemaps ({@link RefCheck#anims});</li>
 *   <li>{@code interfaces}: component references and hook scripts ({@link RefCheck#interfaces});</li>
 *   <li>{@code maps}: map loc ids ({@link RefCheck#maps});</li>
 *   <li>{@code scripts}: whole-cache clientscript stack analysis, gosub targets and arg counts, hook setters, constant
 *       refs, signatures against BASE ({@link ScriptCheck});</li>
 *   <li>{@code brand}: the branding scan ({@link BrandScan});</li>
 *   <li>{@code oracle}: the real 241 client's decoders ({@link ClientOracle});</li>
 *   <li>{@code strict}: the server's strict cache checker ({@link StrictChecker}; needs a cache directory);</li>
 *   <li>{@code determinism}: group-by-group comparison with a second output ({@link Determinism}).</li>
 * </ul>
 * With a baseline (normally BASE), the cheap checks also run on the baseline and every bad item the baseline has too
 * is counted as stock ({@link Findings#subtractBaseline}); only the rest are errors.
 */
public final class Verifier {
    private Verifier() {}

    public static final List<String> ALL = List.of("refs", "anims", "interfaces", "maps", "scripts", "brand", "oracle", "strict", "determinism");
    /** What runs when nothing is selected (strict and determinism need extra inputs and run when those are given). */
    public static final List<String> DEFAULT = List.of("refs", "anims", "interfaces", "maps", "scripts", "brand", "oracle", "strict", "determinism");
    /** Checks that are re-run on the baseline. */
    static final Set<String> BASELINE_CHECKS = Set.of("refs", "anims", "interfaces", "maps", "scripts", "brand", "oracle");

    public static final class Opts {
        public final Set<String> checks = new LinkedHashSet<>(DEFAULT);
        /** Reference cache for signatures and the baseline (normally BASE); may be null. */
        public CacheView base;
        public boolean baseline = true;
        /** The cache's directory (strict checker); null for an in-memory view. */
        public Path cacheDir;
        /** Second output for the determinism check; null = skip. */
        public Path compare;
        public Path clientLib;
        public final Map<String, String> oracle = new LinkedHashMap<>();
        public final Map<String, String> strict = new LinkedHashMap<>();
        public Path root;
        public Path work = Path.of("gadconv-work/verify");
        public Severity brandSeverity = Severity.WARNING;
        public CsTable table;
        public Consumer<String> log = System.out::println;
        public int maxExamples = 15;
        /** Documented exceptions (not errors); null = none. */
        public KnownExceptions known;
    }

    /** Result: the findings plus the script summaries (gosub mismatches by callee, signature differences). */
    public static final class Result {
        public final Findings findings;
        public ScriptCheck.Out scripts;
        public Findings baseline;
        Result(Findings f) { this.findings = f; }

        public Map<String, Object> toJson() {
            Map<String, Object> m = findings.toJson();
            if (scripts != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                Map<String, Object> callees = new LinkedHashMap<>();
                scripts.mismatchedCallees.forEach((k, v) -> callees.put(String.valueOf(k), v));
                s.put("gosubMismatchesByCallee", callees);
                Map<String, Object> sig = new LinkedHashMap<>();
                scripts.signatureDiffs.forEach((k, v) -> sig.put(String.valueOf(k), v));
                s.put("signatureDiffsVsBase", sig);
                m.put("scripts", s);
            }
            return m;
        }
    }

    public static Result run(CacheView c, Opts o) throws IOException, InterruptedException {
        if (o.table == null) o.table = CsTable.load();
        Files.createDirectories(o.work);
        ScriptCheck.Out[] sc = new ScriptCheck.Out[1];
        Result res = new Result(run(c, o, true, sc));
        res.scripts = sc[0];
        if (o.base != null && o.baseline && o.base != c) {
            o.log.accept("baseline: the same checks on " + o.base.label());
            Opts b = copy(o);
            b.checks.retainAll(BASELINE_CHECKS);
            Findings fb = run(o.base, b, false, new ScriptCheck.Out[1]);
            res.baseline = fb;
            res.findings.subtractBaseline(fb);
        }
        if (o.known != null) {
            res.findings.applyKnown(o.known);
            res.findings.info("knownExceptions", o.known.source + " (" + o.known.size() + " rules)");
        }
        return res;
    }

    static Opts copy(Opts o) {
        Opts b = new Opts();
        b.checks.clear(); b.checks.addAll(o.checks);
        b.base = null; b.baseline = false; b.cacheDir = null; b.compare = null;
        b.clientLib = o.clientLib; b.oracle.putAll(o.oracle); b.root = o.root; b.work = o.work; b.brandSeverity = o.brandSeverity;
        b.table = o.table; b.log = o.log; b.maxExamples = o.maxExamples;
        return b;
    }

    static Findings run(CacheView c, Opts o, boolean main, ScriptCheck.Out[] scriptsOut) throws IOException, InterruptedException {
        Findings f = new Findings();
        f.maxExamples = o.maxExamples;
        Exists ex = new Exists(c);
        CacheTypes types = new CacheTypes(c);
        long t0 = System.currentTimeMillis();
        ScriptCheck.Out sc = null;
        if (o.checks.contains("scripts")) {
            o.log.accept("  scripts (" + c.label() + ")");
            sc = ScriptCheck.run(c, o.table, main ? o.base : null, ex, f);
        }
        scriptsOut[0] = sc;
        if (o.checks.contains("refs")) { o.log.accept("  config refs"); RefCheck.configs(c, ex, types, f); }
        if (o.checks.contains("anims")) { o.log.accept("  animation assets"); RefCheck.anims(c, ex, types, f); }
        if (o.checks.contains("interfaces")) {
            o.log.accept("  interfaces");
            CsWorld w = sc != null ? sc.world : new CsWorld(c);
            RefCheck.interfaces(c, ex, w, f);
        }
        if (o.checks.contains("maps")) { o.log.accept("  maps"); RefCheck.maps(c, ex, f); }
        if (o.checks.contains("brand")) { o.log.accept("  branding scan"); BrandScan.run(c, f, o.brandSeverity); }
        if (o.checks.contains("oracle")) {
            String[] why = new String[1];
            Path lib = o.clientLib != null ? o.clientLib : o.root != null ? o.root.resolve("rev241/inputs/runelite") : null;
            ClientOracle oc = ClientOracle.open(lib, o.oracle, why);
            if (oc == null) f.bad("oracle/run", Severity.WARNING, "not run", "client oracles not run: " + why[0]);
            else {
                o.log.accept("  client oracles (" + lib + ")");
                try {
                    oc.configs(c, f);
                    oc.interfaces(c, f);
                    oc.scripts(c, f);
                    oc.models(c, f);
                    if (!oc.skips().isEmpty()) f.info("oracle.harnessSkips", new java.util.TreeMap<>(oc.skips()));
                } finally {
                    oc.close();
                }
            }
        }
        if (o.checks.contains("strict") && o.cacheDir != null) { o.log.accept("  strict cache checker (separate JVM)"); StrictChecker.run(o.cacheDir, o.strict, o.root, o.work, f); }
        if (o.checks.contains("determinism") && o.compare != null && o.cacheDir != null) {
            o.log.accept("  determinism vs " + o.compare);
            Determinism.compare(o.cacheDir, o.compare, f);
        }
        o.log.accept(String.format("  %s checked in %.1f s", c.label(), (System.currentTimeMillis() - t0) / 1000.0));
        return f;
    }
}
