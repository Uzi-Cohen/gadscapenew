package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.verify.Findings.Severity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the server's strict cache checker ({@code com.gadscape.cache.verify.CacheVerifier}, the server's
 * {@code :cache:verifyCache} task: reference tables, the server's runtime loaders, every config/interface/script/map
 * decoded in strict mode) on a cache directory, in a separate JVM (it calls System.exit and sets global strict mode).
 * <p>
 * The checker lives in the server's cache test sources. Classpath, first that exists:
 * {@code --strict.cp=...}; the classpath file the Gradle task writes ({@code server/cache/build/verify-cache-classpath.txt});
 * or the sources ({@code --strict.src=DIR}, else {@code server/cache/src/test/java} found from the working directory
 * or {@code ROOT/rev241/base}) compiled with javac against this JVM's classpath into the work directory.
 * The cache files' sizes and timestamps are compared before and after: the checker must not write.
 */
public final class StrictChecker {
    private StrictChecker() {}

    public static final String MAIN = "com.gadscape.cache.verify.CacheVerifier";
    static final String REL = "cache/src/test/java/com/gadscape/cache/verify";

    /** Locates or builds the checker's classpath; null (reason in why[0]) if not possible. */
    public static String classpath(java.util.Map<String, String> opt, Path root, Path work, String[] why) throws IOException, InterruptedException {
        String cp = opt.get("strict.cp");
        if (cp != null) return cp;
        List<Path> serverDirs = new ArrayList<>();
        if (opt.get("strict.server") != null) serverDirs.add(Path.of(opt.get("strict.server")));
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) { serverDirs.add(p); serverDirs.add(p.resolve("server")); }
        if (root != null) serverDirs.add(root.resolve("rev241/base/server"));
        for (Path s : serverDirs) {
            Path f = s.resolve("cache/build/verify-cache-classpath.txt");
            if (Files.isRegularFile(f)) return Files.readString(f).trim();
        }
        Path src = opt.get("strict.src") != null ? Path.of(opt.get("strict.src")) : null;
        if (src == null) for (Path s : serverDirs) if (Files.isRegularFile(s.resolve(REL).resolve("CacheVerifier.java"))) { src = s.resolve(REL); break; }
        if (src == null) { why[0] = "CacheVerifier sources not found (pass --strict.src=DIR or --strict.cp=CLASSPATH)"; return null; }
        Path out = work.resolve("strict-classes");
        Files.createDirectories(out);
        List<String> cmd = new ArrayList<>(List.of(javac(), "-nowarn", "-encoding", "UTF-8", "-proc:none", "-d", out.toString(), "-cp", System.getProperty("java.class.path")));
        try (Stream<Path> s = Files.list(src)) { s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> cmd.add(p.toString())); }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(work.resolve("strict-javac.log").toFile()).start();
        if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) { why[0] = "javac of " + src + " failed (see " + work.resolve("strict-javac.log") + ")"; return null; }
        return out + File.pathSeparator + System.getProperty("java.class.path");
    }

    static String javac() {
        Path j = Path.of(System.getProperty("java.home"), "bin", "javac");
        return Files.isExecutable(j) ? j.toString() : "javac";
    }

    static String java() {
        Path j = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(j) ? j.toString() : "java";
    }

    /** Size + mtime of every main_file_cache file (to prove the checker did not write). */
    static TreeMap<String, String> stamp(Path dir) throws IOException {
        TreeMap<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator)
                if (p.getFileName().toString().startsWith("main_file_cache")) m.put(p.getFileName().toString(), Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
        }
        return m;
    }

    public static void run(Path cacheDir, java.util.Map<String, String> opt, Path root, Path work, Findings f) throws IOException, InterruptedException {
        String[] why = new String[1];
        String cp = classpath(opt, root, work, why);
        if (cp == null) { f.bad("strict/run", Severity.WARNING, "not run", "strict checker not run: " + why[0]); return; }
        Path abs = cacheDir.toAbsolutePath().normalize();
        String tag = (abs.getParent() != null ? abs.getParent().getFileName() + "_" : "") + abs.getFileName();
        Path log = work.resolve("strict-" + tag.replaceAll("[^A-Za-z0-9]+", "_") + ".log");
        TreeMap<String, String> before = stamp(cacheDir);
        List<String> cmd = List.of(java(), opt.getOrDefault("strict.xmx", "-Xmx3g"), "-cp", cp, MAIN, cacheDir.toAbsolutePath().toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = p.waitFor(Long.parseLong(opt.getOrDefault("strict.timeout", "1800")), TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); f.bad("strict/run", Severity.ERROR, "timeout", "strict checker timed out (log " + log + ")"); return; }
        TreeMap<String, String> after = stamp(cacheDir);
        f.check("strict/read-only", Severity.ERROR, before.equals(after), "cache files changed", "the strict checker changed the cache files: " + before + " -> " + after);
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        int errors = -1;
        Pattern err = Pattern.compile("=== Errors: (\\d+)");
        List<String> errLines = new ArrayList<>();
        for (String l : lines) {
            Matcher m = err.matcher(l);
            if (m.find()) errors = Integer.parseInt(m.group(1));
            if (l.startsWith("ERROR ")) errLines.add(l.substring(6));
        }
        boolean ok = p.exitValue() == 0 && lines.stream().anyMatch(l -> l.startsWith("RESULT: OK"));
        f.info("strict.log", log.toString());
        f.info("strict.exit", p.exitValue());
        f.info("strict.errors", errors);
        if (ok) f.ok("strict/result", Severity.ERROR);
        else {
            if (errLines.isEmpty()) f.bad("strict/result", Severity.ERROR, "exit " + p.exitValue(), "strict checker failed with exit " + p.exitValue() + ", " + errors + " errors (log " + log + ")");
            for (String e : errLines) f.bad("strict/result", Severity.ERROR, e, e);
        }
    }
}
