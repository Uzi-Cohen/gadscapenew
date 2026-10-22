package com.gadscape.cacheconv.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decisions log of a build, saved as report.json. Every step reports what it did per id with a {@link Decision};
 * bulk outcomes can be counted instead with {@link #count}. The file holds no timestamps or timings, so the same
 * inputs give the same report.
 * <pre>
 * {"format": 1, "inputs": {...}, "summary": {"relocated": 12, ...},
 *  "steps": [{"name": "...", "counts": {...}, "decisions": {"user-added": 3, ...}}],
 *  "decisions": [{"step": "...", "decision": "relocated", "kind": "seq", "id": 15000, "to": 40000, "detail": "..."}, ...]}
 * </pre>
 */
public final class Report {
    public enum Decision {
        TAKEN_FROM_BASE("taken-from-base"),
        USER_ADDED("user-added"),
        USER_CHANGED("user-changed"),
        MERGED("merged"),
        RELOCATED("relocated"),
        CLASH_KEPT("clash-kept"),
        SAME("same"),
        DROPPED("dropped"),
        REBRANDED("rebranded"),
        NOTE("note"),
        WARNING("warning"),
        ERROR("error");

        public final String key;
        Decision(String key) { this.key = key; }
    }

    private String step = "-";
    private final List<String> steps = new ArrayList<>();
    private final Map<String, TreeMap<String, Long>> counts = new LinkedHashMap<>();
    private final Map<String, TreeMap<String, Long>> stepDecisions = new LinkedHashMap<>();
    private final TreeMap<String, Long> summary = new TreeMap<>();
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private int errors, warnings;
    /** Echo warnings/errors to stdout as they happen. */
    public boolean echo = true;

    public void input(String key, Object value) { inputs.put(key, value); }

    public void begin(String stepName) {
        step = stepName;
        if (!steps.contains(stepName)) steps.add(stepName);
        counts.computeIfAbsent(stepName, k -> new TreeMap<>());
        stepDecisions.computeIfAbsent(stepName, k -> new TreeMap<>());
    }
    public void end() { step = "-"; }
    public String step() { return step; }

    /** One decision about one id. {@code kind} is a {@link Kind} key or another short noun ("interface", "square", ...). */
    public void add(Decision d, String kind, long id, String detail) { add(d, kind, id, null, detail); }

    public void add(Decision d, Kind kind, long id, String detail) { add(d, kind.key, id, null, detail); }

    public void add(Decision d, String kind, long id, Long to, String detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("step", step);
        e.put("decision", d.key);
        if (kind != null) e.put("kind", kind);
        if (id != Long.MIN_VALUE) e.put("id", id);
        if (to != null) e.put("to", to);
        if (detail != null && !detail.isEmpty()) e.put("detail", detail);
        entries.add(e);
        summary.merge(d.key, 1L, Long::sum);
        stepDecisions.computeIfAbsent(step, k -> new TreeMap<>()).merge(d.key, 1L, Long::sum);
        if (d == Decision.ERROR) errors++;
        if (d == Decision.WARNING) warnings++;
        if (echo && (d == Decision.ERROR || d == Decision.WARNING))
            System.out.println("  [" + step + "] " + d.key.toUpperCase() + (kind != null ? " " + kind : "") + (id != Long.MIN_VALUE ? " " + id : "") + ": " + detail);
    }

    public void relocated(Kind k, int from, int to, String why) { add(Decision.RELOCATED, k.key, from, (long) to, why); }
    public void note(String msg) { add(Decision.NOTE, (String) null, Long.MIN_VALUE, msg); }
    public void warn(String msg) { add(Decision.WARNING, (String) null, Long.MIN_VALUE, msg); }
    public void error(String msg) { add(Decision.ERROR, (String) null, Long.MIN_VALUE, msg); }
    public void warn(String kind, long id, String msg) { add(Decision.WARNING, kind, id, msg); }
    public void error(String kind, long id, String msg) { add(Decision.ERROR, kind, id, msg); }

    /** Adds n to a named counter of the current step (for bulk outcomes not worth one entry per id). */
    public void count(String key, long n) { counts.computeIfAbsent(step, k -> new TreeMap<>()).merge(key, n, Long::sum); }
    public void count(String key) { count(key, 1); }

    public int errors() { return errors; }
    public int warnings() { return warnings; }
    public List<Map<String, Object>> entries() { return entries; }

    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", 1);
        root.put("inputs", inputs);
        root.put("summary", summary);
        List<Object> st = new ArrayList<>();
        for (String s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s);
            m.put("counts", counts.getOrDefault(s, new TreeMap<>()));
            m.put("decisions", stepDecisions.getOrDefault(s, new TreeMap<>()));
            st.add(m);
        }
        root.put("steps", st);
        root.put("decisions", entries);
        return root;
    }

    public void save(Path file) throws IOException { Json.write(file, toJson()); }
}
