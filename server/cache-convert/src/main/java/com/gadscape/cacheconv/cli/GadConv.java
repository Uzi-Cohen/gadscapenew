package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.convert.SelfCheck;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * gadconv: builds GadScape's build-241 cache from stock 241 + GadScape's 211 cache, and converts incoming 211-era
 * content. Every command takes the common {@link Options}; see README.md.
 */
public final class GadConv {
    private GadConv() {}

    /** A subcommand: gets the parsed options (operands in {@code o.args}) and returns the exit code. */
    @FunctionalInterface
    public interface Command { int run(Options o) throws Exception; }

    record Entry(String usage, String help, Command cmd) {}

    static final Map<String, Entry> COMMANDS = new LinkedHashMap<>();
    static {
        COMMANDS.put("inspect", new Entry("inspect [CACHE] [ARCHIVE [GROUP [FILE]]] | inspect CACHE cfg KIND ID [--hex] [--list]",
                "show archives, groups, files or bytes of a cache (CACHE = base|user|van211|old241|ref|out|DIR)", Inspect::run));
        COMMANDS.put("diff", new Entry("diff CACHE_A CACHE_B [ARCHIVE...] [--content] [--ids]",
                "compare two caches group by group (reference-table CRCs; --content decodes groups whose CRC differs)", Diff::run));
        COMMANDS.put("build-cacheu", new Entry("build-cacheu [--base DIR] [--user DIR] [--van211 DIR] [--out DIR] [--force] [--dry-run] [--only a,b] [--skip a,b]",
                "copy BASE to --out and run the pipeline steps; writes report.json and idmap.json", BuildCacheU::run));
        COMMANDS.put("port", new Entry("port ...", "convert 211 config entries / groups into a 241 cache (not implemented yet)", PortCmd::run));
        COMMANDS.put("port-interface", new Entry("port-interface ...", "port a 211 interface and its scripts into a 241 cache (not implemented yet)", PortInterfaceCmd::run));
        COMMANDS.put("port-map", new Entry("port-map ...", "port 211 map squares into a 241 cache (not implemented yet)", PortMapCmd::run));
        COMMANDS.put("rebrand", new Entry("rebrand ...", "replace old branding in cache texts (not implemented yet)", RebrandCmd::run));
        COMMANDS.put("verify", new Entry("verify ...", "reference checks, client-decode oracles, branding scan (not implemented yet)", VerifyCmd::run));
        COMMANDS.put("selfcheck", new Entry("selfcheck [--user DIR] [--ref DIR] [--full]",
                "golden check: ported 211->241 converters vs the hand-built cacheU", o -> SelfCheck.run(argv(o))));
    }

    public static void main(String[] a) {
        int code;
        try {
            code = run(a);
        } catch (IllegalArgumentException e) {
            System.err.println("gadconv: " + e.getMessage());
            code = 2;
        } catch (java.io.IOException e) {
            System.err.println("gadconv: " + e.getMessage());
            if (Boolean.getBoolean("gadconv.debug")) e.printStackTrace();
            code = 1;
        } catch (Exception e) {
            e.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    public static int run(String[] a) throws Exception {
        if (a.length == 0 || a[0].equals("help") || a[0].equals("--help") || a[0].equals("-h")) { usage(); return a.length == 0 ? 2 : 0; }
        Entry e = COMMANDS.get(a[0]);
        if (e == null) { System.err.println("unknown command " + a[0]); usage(); return 2; }
        Options o = Options.parse(a, 1);
        return e.cmd.run(o);
    }

    static void usage() {
        System.out.println("usage: gadconv <command> [options]\n");
        for (var e : COMMANDS.entrySet()) System.out.printf("  %-15s %s%n  %15s   %s%n", e.getKey(), e.getValue().help, "", e.getValue().usage);
        System.out.println("\ncommon options: --base --user --van211 --old241 --ref --xteas --out --report-dir --work --force --dry-run --only --skip --set k=v");
        System.out.println("input paths default to the standard layout under -Dgadconv.root / $GADCONV_ROOT");
    }

    /** Resolves a cache operand: base|user|van211|old241|ref|out, or a directory. */
    public static Path cacheDir(Options o, String name) {
        Path p = switch (name.toLowerCase()) {
            case "base" -> o.base;
            case "user" -> o.user;
            case "van211", "vanilla", "211" -> o.van211;
            case "old241", "2740" -> o.old241;
            case "ref", "cacheu" -> o.ref;
            case "out", "cachev" -> o.out;
            default -> Path.of(name);
        };
        if (p == null) throw new IllegalArgumentException("cache '" + name + "' is not configured (pass its option or set -Dgadconv.root)");
        return p;
    }

    /** Re-serialises options back into argv form (for commands that parse their own). */
    static String[] argv(Options o) {
        java.util.List<String> l = new java.util.ArrayList<>(o.args);
        if (o.user != null) { l.add("--user"); l.add(o.user.toString()); }
        if (o.ref != null) { l.add("--ref"); l.add(o.ref.toString()); }
        for (var e : o.extra.entrySet()) l.add("--" + e.getKey() + "=" + e.getValue());
        return l.toArray(new String[0]);
    }

    static int notImplemented(String cmd, Options o) {
        System.err.println("gadconv " + cmd + ": not implemented yet (args " + Arrays.toString(o.args.toArray()) + ")");
        return 2;
    }
}
