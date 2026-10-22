package com.gadscape.cacheconv.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line options shared by every command. Input paths default to the standard layout under a root directory
 * taken from {@code -Dgadconv.root} or the {@code GADCONV_ROOT} environment variable (the scratch directory that holds
 * audit/, c211/, osrs241/, rev241/, gadconv-work/); without a root every path must be passed explicitly.
 * <pre>
 *   --base DIR      stock 241 cache the output starts from (BASE, openrs2 2746)
 *   --user DIR      GadScape's own 211 cache (USER)
 *   --van211 DIR    vanilla 211 cache (VAN211), to tell what the user changed
 *   --old241 DIR    older stock 241 (2740) (OLD241), what the hand-built cacheU used
 *   --ref DIR       hand-built cacheU (reference only)
 *   --xteas FILE    the user's xteas.json (map loc keys of USER/VAN211)
 *   --out DIR       output cache directory (never an input)
 *   --report-dir D  where report.json / idmap.json go (default: --out)
 *   --work DIR      scratch directory for experiments (default ROOT/gadconv-work)
 *   --force         replace an existing output cache
 *   --dry-run       run every step but write nothing (reads see BASE plus the pending changes)
 *   --only a,b      run only these steps;  --skip a,b  skip these steps
 *   --set k=v       free-form option for steps (repeatable), read with {@link #get}
 *   --name=value    any other option (command-specific), same as --set name=value; a bare --name means "true"
 * </pre>
 */
public final class Options {
    public Path root;
    public Path base, user, van211, old241, ref, xteas, out, reportDir, work;
    public boolean force, dryRun;
    public final Set<String> only = new LinkedHashSet<>(), skip = new LinkedHashSet<>();
    public final Map<String, String> extra = new LinkedHashMap<>();
    /** Arguments that are not options (command operands), in order. */
    public final List<String> args = new ArrayList<>();

    public static Path defaultRoot() {
        String r = System.getProperty("gadconv.root");
        if (r == null || r.isEmpty()) r = System.getenv("GADCONV_ROOT");
        return r == null || r.isEmpty() ? null : Path.of(r);
    }

    public static Options defaults() {
        Options o = new Options();
        o.root = defaultRoot();
        if (o.root != null) {
            Path R = o.root.resolve("rev241");
            o.base = o.root.resolve("audit/raids4/c2746/cache");
            o.user = R.resolve("inputs/gadcache/x/cache/data/cache");
            o.van211 = o.root.resolve("c211/cache");
            o.old241 = o.root.resolve("osrs241/cache");
            o.ref = R.resolve("inputs/cacheU");
            o.xteas = R.resolve("inputs/gadcache/x/cache/data/objects/xteas.json");
            o.out = R.resolve("inputs/cacheV");
            o.work = o.root.resolve("gadconv-work");
        }
        return o;
    }

    /** Parses options from args[from..]; non-option words go to {@link #args}. */
    public static Options parse(String[] a, int from) {
        Options o = defaults();
        for (int i = from; i < a.length; i++) {
            String s = a[i];
            switch (s) {
                case "--base" -> o.base = Path.of(a[++i]);
                case "--user" -> o.user = Path.of(a[++i]);
                case "--van211" -> o.van211 = Path.of(a[++i]);
                case "--old241" -> o.old241 = Path.of(a[++i]);
                case "--ref" -> o.ref = Path.of(a[++i]);
                case "--xteas" -> o.xteas = Path.of(a[++i]);
                case "--out" -> o.out = Path.of(a[++i]);
                case "--report-dir" -> o.reportDir = Path.of(a[++i]);
                case "--work" -> o.work = Path.of(a[++i]);
                case "--force" -> o.force = true;
                case "--dry-run" -> o.dryRun = true;
                case "--only" -> o.only.addAll(List.of(a[++i].split(",")));
                case "--skip" -> o.skip.addAll(List.of(a[++i].split(",")));
                case "--set" -> { String kv = a[++i]; int e = kv.indexOf('='); o.extra.put(e < 0 ? kv : kv.substring(0, e), e < 0 ? "true" : kv.substring(e + 1)); }
                default -> {
                    if (s.startsWith("--")) {
                        // command-specific options: --name=value or a boolean --name (read with get/flag)
                        int e = s.indexOf('=');
                        o.extra.put(e < 0 ? s.substring(2) : s.substring(2, e), e < 0 ? "true" : s.substring(e + 1));
                    } else o.args.add(s);
                }
            }
        }
        return o;
    }

    public String get(String key, String def) { return extra.getOrDefault(key, def); }
    public boolean flag(String key) { return Boolean.parseBoolean(extra.getOrDefault(key, "false")); }

    public Path reportDir() { return reportDir != null ? reportDir : out; }

    /** Directories nothing may ever write into: every input cache, plus the live run/base trees next to them. */
    public List<Path> protectedDirs() {
        List<Path> p = new ArrayList<>();
        for (Path x : new Path[]{base, user, van211, old241, ref}) if (x != null) p.add(x);
        if (xteas != null && xteas.getParent() != null) p.add(xteas.getParent());
        if (root != null) { p.add(root.resolve("rev241/run")); p.add(root.resolve("rev241/base")); }
        return p;
    }

    public boolean runs(String step) { return (only.isEmpty() || only.contains(step)) && !skip.contains(step); }

    public void require(String what, Path p) {
        if (p == null) throw new IllegalArgumentException("--" + what + " is required (or set -Dgadconv.root / GADCONV_ROOT)");
        if (!Files.exists(p)) throw new IllegalArgumentException("--" + what + " " + p + " does not exist");
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("base", String.valueOf(base)); m.put("user", String.valueOf(user)); m.put("van211", String.valueOf(van211));
        m.put("old241", String.valueOf(old241)); m.put("xteas", String.valueOf(xteas)); m.put("out", String.valueOf(out));
        if (!only.isEmpty()) m.put("only", List.copyOf(only));
        if (!skip.isEmpty()) m.put("skip", List.copyOf(skip));
        if (!extra.isEmpty()) m.put("set", extra);
        return m;
    }
}
