package com.gadscape.cacheconv.rebrand;

import com.gadscape.cacheconv.core.Options;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The branding replacement table (spec rule 12). The exact table comes from the branding task; until then the build
 * runs with an empty table and changes nothing.
 * <p>
 * Format: UTF-8 text, one rule per line, {@code FROM<TAB>TO[<TAB>FLAGS]}; blank lines and lines starting with
 * {@code #} are ignored. FLAGS: {@code i} = ignore case, {@code w} = whole words only (no letter or digit on either
 * side). Rules are literal (no regular expressions) and are applied in file order, each to the result of the previous
 * one. Cache strings are cp1252: both sides are encoded to cp1252 bytes and matched against the cache bytes.
 * <p>
 * Source: {@code --rebrand.table=FILE}, else the classpath resource {@value #RESOURCE} when present, else empty.
 * {@code --rebrand.table=none} forces an empty table.
 */
public final class RebrandTable {
    public static final String RESOURCE = "rebrand/table.tsv";
    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** One rule; {@code from}/{@code to} are cp1252 bytes held as ISO-8859-1 chars (one char per byte). */
    public record Rule(String from, String to, boolean ignoreCase, boolean word, Pattern pattern) {
        String apply(String s) { return pattern.matcher(s).replaceAll(Matcher.quoteReplacement(to)); }
    }

    public final List<Rule> rules;
    /** Where the rules came from (file, resource or "none"). */
    public final String source;
    private final Pattern any;

    private RebrandTable(List<Rule> rules, String source) {
        this.rules = List.copyOf(rules);
        this.source = source;
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) { if (sb.length() > 0) sb.append('|'); sb.append("(?:").append(r.pattern.pattern()).append(')'); }
        this.any = rules.isEmpty() ? null : Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    public static RebrandTable empty() { return new RebrandTable(List.of(), "none"); }

    /** Parses table lines; a malformed line is an {@link IllegalArgumentException} naming it. */
    public static RebrandTable parse(List<String> lines, String source) {
        List<Rule> rules = new ArrayList<>();
        int n = 0;
        for (String line : lines) {
            n++;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 2 || p.length > 3 || p[0].isEmpty())
                throw new IllegalArgumentException(source + ":" + n + ": expected FROM<TAB>TO[<TAB>FLAGS], got: " + line);
            String flags = p.length == 3 ? p[2].trim() : "";
            for (char c : flags.toCharArray()) if (c != 'i' && c != 'w') throw new IllegalArgumentException(source + ":" + n + ": unknown flag '" + c + "'");
            String from = bytes(p[0]), to = bytes(p[1]);
            boolean ic = flags.indexOf('i') >= 0, word = flags.indexOf('w') >= 0;
            String re = Pattern.quote(from);
            if (word) re = "(?<![A-Za-z0-9])" + re + "(?![A-Za-z0-9])";
            rules.add(new Rule(from, to, ic, word, Pattern.compile(re, ic ? Pattern.CASE_INSENSITIVE : 0)));
        }
        return new RebrandTable(rules, source);
    }

    /** The table named by the options (see the class comment). */
    public static RebrandTable load(Options o) throws IOException {
        String opt = o.get("rebrand.table", null);
        if ("none".equals(opt)) return empty();
        if (opt != null) {
            Path f = Path.of(opt);
            return parse(Files.readAllLines(f, StandardCharsets.UTF_8), f.toString());
        }
        try (InputStream in = RebrandTable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) return empty();
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), "classpath:" + RESOURCE);
        }
    }

    public boolean isEmpty() { return rules.isEmpty(); }

    /** Cheap pre-filter: whether any rule could match somewhere in the bytes (case-insensitive). */
    public boolean mayMatch(byte[] b) { return any != null && any.matcher(new String(b, StandardCharsets.ISO_8859_1)).find(); }

    /** The string with every rule applied (the same instance when nothing matched). */
    public String apply(String s) {
        if (s == null || any == null || !any.matcher(s).find()) return s;
        String r = s;
        for (Rule rule : rules) r = rule.apply(r);
        return r.equals(s) ? s : r;
    }

    /** A text as cp1252 bytes held one char per byte (the form cache strings are decoded into). */
    static String bytes(String text) { return new String(text.getBytes(CP1252), StandardCharsets.ISO_8859_1); }
}
