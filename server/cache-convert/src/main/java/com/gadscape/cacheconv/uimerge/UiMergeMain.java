package com.gadscape.cacheconv.uimerge;

import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone entry point of the UI merge area (dev/run.sh uimerge.UiMergeMain ...):
 * <pre>
 *   gen   [--to=DIR] [--server=DIR] [--table=TSV]   regenerate resources/ui-merge (or DIR) from the caches and the specs
 *   check [--data=DIR]                              self-checks (codecs, stored data vs BASE, regeneration is identical)
 *   dry   [--data=DIR]                              run the build step against BASE in dry-run mode and print what it writes
 *   write --out DIR [--force]                       BASE + this step only, written to DIR (a test cache for the client/verifier)
 *   show  interface|script ID                       print one merged entry (components decoded / script text)
 * </pre>
 * The input caches come from the usual options (--base --user --van211, or -Dgadconv.root).
 */
public final class UiMergeMain {
    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        String cmd = o.args.isEmpty() ? "help" : o.args.get(0);
        int code = switch (cmd) {
            case "gen" -> gen(o);
            case "check" -> UiMergeSelfCheck.run(o);
            case "dry" -> UiMergeSelfCheck.dry(o);
            case "write" -> UiMergeSelfCheck.write(o);
            case "show" -> UiMergeSelfCheck.show(o);
            default -> { System.err.println("usage: UiMergeMain gen|check|dry|show (see class doc)"); yield 2; }
        };
        System.exit(code);
    }

    /** Default output: the module's src/main/resources/ui-merge (found from the module dir property or the working dir). */
    static Path resourcesDir(Options o) {
        String to = o.get("to", null);
        if (to != null) return Path.of(to);
        String mod = System.getProperty("gadconv.module");
        Path m = mod != null ? Path.of(mod) : Path.of("").toAbsolutePath();
        for (Path p = m; p != null; p = p.getParent()) {
            Path r = p.resolve("src/main/resources/ui-merge");
            if (Files.isDirectory(r)) return r;
            Path r2 = p.resolve("cache-convert/src/main/resources/ui-merge");
            if (Files.isDirectory(r2)) return r2;
        }
        throw new IllegalArgumentException("cannot find src/main/resources/ui-merge; pass --to=DIR");
    }

    static Path resourcesDirOrNull(Options o) {
        try { return resourcesDir(o); } catch (IllegalArgumentException e) { return null; }
    }

    static int gen(Options o) throws Exception {
        Path to = resourcesDir(o);
        String server = o.get("server", null);
        Path serverDir = server != null ? Path.of(server) : guessServer(to);
        String table = o.get("table", null);
        try (JagCache base = JagCache.open("BASE", o.base); JagCache user = JagCache.open("USER", o.user); JagCache van = JagCache.open("VAN211", o.van211)) {
            UiMergeGen g = new UiMergeGen(base, user, van, table == null ? null : Path.of(table), serverDir);
            g.run();
            g.write(to);
            System.out.println("ui-merge data written to " + to + " (" + g.files.size() + " files)");
            for (String p : g.problems) System.out.println("  PROBLEM " + p);
            return g.problems.isEmpty() ? 0 : 1;
        }
    }

    /** The server directory holding this module (…/server), if the output lies inside it. */
    static Path guessServer(Path to) {
        if (to == null) return null;
        for (Path p = to.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve("core/src/main/java/com/zenyte/game/GameInterface.java"))) return p;
        return null;
    }
}
