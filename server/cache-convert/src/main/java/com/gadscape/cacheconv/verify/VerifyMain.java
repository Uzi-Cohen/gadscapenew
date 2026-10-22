package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Json;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.verify.Findings.Severity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code gadconv verify} and its standalone main:
 * <pre>
 *   gadconv verify [CACHE] [--checks=refs,anims,interfaces,maps,scripts,brand,oracle,strict,determinism]
 *                  [--baseline=base|none|DIR] [--compare=DIR] [--json=FILE] [--brand-errors] [--known=FILE|none]
 *                  [--oracle.lib=DIR] [--oracle.configs=6:or,...] [--strict.server=DIR] [--strict.cp=CP] [--examples=N]
 *   dev/run.sh verify.VerifyMain [same arguments]
 * </pre>
 * CACHE is a directory or a name (base, user, ref/cacheu, out/cachev); default {@code --out}. The baseline (default:
 * BASE) is checked the same way and its own problems are reported as stock, not as errors; it also gives the script
 * signatures stock callers were compiled against. Writes {@code WORK/verify/verify-<cache>.json} (or {@code --json}).
 * Exit code 0 when no error check has a non-stock bad item, 1 otherwise.
 */
public final class VerifyMain {
    private VerifyMain() {}

    public static void main(String[] a) {
        int code;
        try {
            code = run(Options.parse(a, 0));
        } catch (IllegalArgumentException e) {
            System.err.println("verify: " + e.getMessage());
            code = 2;
        } catch (Exception e) {
            e.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    static Path resolve(Options o, String name) {
        return switch (name.toLowerCase()) {
            case "base" -> o.base;
            case "user" -> o.user;
            case "van211" -> o.van211;
            case "old241" -> o.old241;
            case "ref", "cacheu" -> o.ref;
            case "out", "cachev" -> o.out;
            default -> Path.of(name);
        };
    }

    /** Entry point of {@code gadconv verify} (cli.VerifyCmd delegates here). */
    public static int run(Options o) throws Exception {
        Path dir = o.args.isEmpty() ? o.out : resolve(o, o.args.get(0));
        if (dir == null || !Files.isRegularFile(dir.resolve("main_file_cache.dat2"))) throw new IllegalArgumentException("no cache at " + dir + " (pass a cache directory)");
        String bl = o.get("baseline", "base");
        Path baseDir = bl.equals("none") ? null : resolve(o, bl);
        Verifier.Opts vo = new Verifier.Opts();
        String checks = o.get("checks", null);
        if (checks != null) { vo.checks.clear(); vo.checks.addAll(List.of(checks.split(","))); for (String c : vo.checks) if (!Verifier.ALL.contains(c)) throw new IllegalArgumentException("unknown check " + c + " (" + Verifier.ALL + ")"); }
        vo.cacheDir = dir;
        vo.root = o.root;
        Path work = o.work != null ? o.work : Path.of("gadconv-work");
        vo.work = work.resolve("verify");
        if (o.get("compare", null) != null) vo.compare = resolve(o, o.get("compare", null));
        if (o.get("oracle.lib", null) != null) vo.clientLib = Path.of(o.get("oracle.lib", null));
        for (Map.Entry<String, String> e : o.extra.entrySet()) {
            if (e.getKey().startsWith("oracle.") && !e.getKey().equals("oracle.lib")) vo.oracle.put(e.getKey().substring(7), e.getValue());
            if (e.getKey().startsWith("strict.")) vo.strict.put(e.getKey(), e.getValue());
        }
        if (o.flag("brand-errors")) vo.brandSeverity = Severity.ERROR;
        vo.known = KnownExceptions.load(o.get("known", null));
        vo.maxExamples = Integer.parseInt(o.get("examples", "15"));
        String label = dir.toAbsolutePath().normalize().getFileName().toString();
        System.out.println("gadconv verify " + dir + (baseDir != null ? " (baseline " + baseDir + ")" : "") + ", checks " + vo.checks);
        try (JagCache c = JagCache.open(label, dir); JagCache base = baseDir != null && !baseDir.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize()) ? JagCache.open("BASE", baseDir) : null) {
            vo.base = base;
            Verifier.Result r = Verifier.run(c, vo);
            Path json = o.get("json", null) != null ? Path.of(o.get("json", null)) : vo.work.resolve("verify-" + label + ".json");
            Map<String, Object> m = r.toJson();
            m.put("cache", dir.toString());
            m.put("baseline", String.valueOf(baseDir));
            Json.write(json, m);
            r.findings.lines().forEach(System.out::println);
            if (r.scripts != null && !r.scripts.mismatchedCallees.isEmpty()) {
                System.out.println("gosub arg mismatches by callee (" + r.scripts.mismatchedCallees.size() + " callees):");
                r.scripts.mismatchedCallees.forEach((k, v) -> System.out.println("  script " + k + " takes " + v.get("takes") + ": " + v.get("badSites") + " bad call sites, e.g. " + v.get("examples")));
            }
            if (r.scripts != null && !r.scripts.signatureDiffs.isEmpty()) {
                System.out.println("signatures that differ from the baseline (" + r.scripts.signatureDiffs.size() + ", all in the JSON):");
                r.scripts.signatureDiffs.entrySet().stream().limit(40).forEach(e -> System.out.println("  script " + e.getKey() + " " + e.getValue()));
            }
            int errors = r.findings.errors();
            System.out.println((errors == 0 ? "VERIFY OK" : "VERIFY FAILED: " + errors + " error checks") + " -> " + json);
            return errors == 0 ? 0 : 1;
        }
    }
}
