package com.gadscape.cacheconv.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Documented exceptions: bad items that are understood and accepted, so they do not count as errors (they are still
 * listed, with their reason, in verify.json and report.json). Each one must name why it is acceptable, typically a
 * defect that is already in GadScape's 211 cache and that the converter carries over unchanged.
 * <p>
 * File format (UTF-8, {@code #} comments): {@code CHECK<TAB>KEY_REGEX<TAB>REASON}; CHECK is a check name such as
 * {@code refs/npc->seq}, KEY_REGEX must match the whole item key (for example {@code npc 15171 -> seq 15174},
 * {@code script 2186 @21 gosub-underflow}). Default source: the resource {@value #RESOURCE}; option
 * {@code --verify.known=FILE|none} (pipeline) or {@code --known=FILE|none} (verify command).
 */
public final class KnownExceptions {
    public static final String RESOURCE = "verify/known-exceptions.tsv";

    record Rule(String check, Pattern key, String reason) {}

    private final List<Rule> rules;
    public final String source;

    private KnownExceptions(List<Rule> rules, String source) { this.rules = List.copyOf(rules); this.source = source; }

    public static KnownExceptions none() { return new KnownExceptions(List.of(), "none"); }

    public static KnownExceptions parse(List<String> lines, String source) {
        List<Rule> rules = new ArrayList<>();
        int n = 0;
        for (String line : lines) {
            n++;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length != 3 || f[2].isBlank()) throw new IllegalArgumentException(source + ":" + n + ": expected CHECK<TAB>KEY_REGEX<TAB>REASON");
            rules.add(new Rule(f[0].trim(), Pattern.compile(f[1].trim()), f[2].trim()));
        }
        return new KnownExceptions(rules, source);
    }

    /** {@code spec}: null = the resource, "none" = no exceptions, else a file. */
    public static KnownExceptions load(String spec) throws IOException {
        if ("none".equals(spec)) return none();
        if (spec != null) return parse(Files.readAllLines(Path.of(spec), StandardCharsets.UTF_8), spec);
        try (InputStream in = KnownExceptions.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) return none();
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), "classpath:" + RESOURCE);
        }
    }

    public int size() { return rules.size(); }

    /** The reason when the item key of the check is a documented exception, else null. */
    public String reason(String check, String key) {
        for (Rule r : rules) if (r.check.equals(check) && r.key.matcher(key).matches()) return r.reason;
        return null;
    }
}
