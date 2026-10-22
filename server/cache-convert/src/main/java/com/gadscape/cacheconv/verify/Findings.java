package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Results of a verification: per check (e.g. {@code "refs/obj->model"}, {@code "scripts/gosub-leftover"}) how many
 * items were fine and how many were bad, with the first examples (deterministic: callers add in ascending id order)
 * and, optionally, the full list of bad keys so two runs (OUT and BASE) can be compared. A check has a severity:
 * {@code error} (the cache is broken), {@code warning} (suspicious) or {@code info} (counts only).
 */
public final class Findings {
    public enum Severity { ERROR, WARNING, INFO }

    public static final class Check {
        public final String name;
        public Severity severity;
        public long ok, bad;
        /** Bad items that are also bad in the baseline (stock problems), when a baseline was compared. */
        public long stock = -1;
        /** Keys of the bad items that are bad in the baseline too. */
        public final TreeSet<String> stockKeys = new TreeSet<>();
        /** Bad items (not stock) that match a documented exception ({@link KnownExceptions}), and why, by key. */
        public final TreeMap<String, String> known = new TreeMap<>();
        public final List<String> examples = new ArrayList<>();
        /** Keys of the bad items (subject ids), for baseline comparison. */
        public final TreeSet<String> keys = new TreeSet<>();
        /** How many bad items carry each key (several items can share one, e.g. three hook setters of one script). */
        public final TreeMap<String, Integer> keyCounts = new TreeMap<>();
        /** Bad items (not stock) that are documented exceptions (sum over {@link #known}'s keys). */
        public long knownCount;
        public String note;

        Check(String name, Severity s) { this.name = name; this.severity = s; }

        /** Bad items not in the baseline (all bad items when no baseline was compared) and not documented exceptions. */
        public long fresh() { return (stock < 0 ? bad : bad - stock) - knownCount; }
    }

    private final TreeMap<String, Check> checks = new TreeMap<>();
    private final Map<String, Object> info = new LinkedHashMap<>();
    public int maxExamples = 15;

    public Check check(String name, Severity s) {
        Check c = checks.computeIfAbsent(name, k -> new Check(k, s));
        if (s.ordinal() < c.severity.ordinal()) c.severity = s;
        return c;
    }

    public Check get(String name) { return checks.get(name); }
    public Map<String, Check> checks() { return checks; }

    public void ok(String name, Severity s) { check(name, s).ok++; }
    public void ok(String name, Severity s, long n) { check(name, s).ok += n; }

    /** One bad item: {@code key} identifies it across caches (e.g. "obj 4151 -> model 9999"), {@code example} describes it. */
    public void bad(String name, Severity s, String key, String example) {
        Check c = check(name, s);
        c.bad++;
        if (key != null) { c.keys.add(key); c.keyCounts.merge(key, 1, Integer::sum); }
        if (c.examples.size() < maxExamples) c.examples.add(example != null ? example : key);
    }

    public void check(String name, Severity s, boolean good, String key, String example) {
        if (good) ok(name, s); else bad(name, s, key, example);
    }

    public void info(String key, Object value) { info.put(key, value); }
    public Map<String, Object> info() { return info; }

    /**
     * Marks which bad items are stock: present in the baseline's findings for the same check (e.g. BASE's own dangling
     * references). Their count goes to {@link Check#stock}; {@link #errors} counts only the rest.
     */
    public void subtractBaseline(Findings base) {
        for (Check c : checks.values()) {
            Check b = base.checks.get(c.name);
            long s = 0;
            if (b != null) for (String k : c.keys) if (b.keys.contains(k)) { s += c.keyCounts.getOrDefault(k, 1); c.stockKeys.add(k); }
            c.stock = s;
            if (s > 0) {
                List<String> fresh = new ArrayList<>();
                for (String k : c.keys) if (b == null || !b.keys.contains(k)) { if (fresh.size() < maxExamples) fresh.add(k); }
                if (!fresh.isEmpty() && c.fresh() > 0) { c.examples.clear(); c.examples.addAll(fresh); c.note = "examples are the items that are not bad in the baseline"; }
            }
        }
    }

    /**
     * Marks the bad items (not stock) that match a documented exception. Their count goes to {@link Check#known};
     * {@link #errors} counts only the rest. Examples are replaced by the remaining items when any remain.
     */
    public void applyKnown(KnownExceptions known) {
        if (known == null) return;
        for (Check c : checks.values()) {
            if (c.bad == 0) continue;
            List<String> rest = new ArrayList<>();
            for (String k : c.keys) {
                if (c.stockKeys.contains(k)) continue;
                String why = known.reason(c.name, k);
                if (why != null) { c.known.put(k, why); c.knownCount += c.keyCounts.getOrDefault(k, 1); } else rest.add(k);
            }
            if (!c.known.isEmpty() && !rest.isEmpty()) {
                c.examples.clear();
                c.examples.addAll(rest.subList(0, Math.min(maxExamples, rest.size())));
                c.note = "examples are the items that are neither bad in the baseline nor documented exceptions";
            }
        }
    }

    /** Number of error-severity checks with bad items (not counting stock ones). */
    public int errors() {
        int n = 0;
        for (Check c : checks.values()) if (c.severity == Severity.ERROR && c.fresh() > 0) n++;
        return n;
    }

    public long badCount(String name) { Check c = checks.get(name); return c == null ? 0 : c.bad; }

    public Map<String, Object> toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> sum = new LinkedHashMap<>();
        int errors = 0, warnings = 0;
        for (Check c : checks.values()) {
            if (c.fresh() > 0 && c.severity == Severity.ERROR) errors++;
            if (c.fresh() > 0 && c.severity == Severity.WARNING) warnings++;
        }
        sum.put("errorChecks", errors);
        sum.put("warningChecks", warnings);
        root.put("summary", sum);
        root.put("info", info);
        Map<String, Object> cs = new LinkedHashMap<>();
        for (Check c : checks.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", c.severity.name().toLowerCase());
            m.put("ok", c.ok);
            m.put("bad", c.bad);
            if (c.stock >= 0) m.put("badInBaselineToo", c.stock);
            if (!c.known.isEmpty()) {
                m.put("knownExceptions", c.knownCount);
                Map<String, Object> why = new LinkedHashMap<>();
                for (var e : c.known.entrySet()) why.put(e.getKey(), e.getValue());
                m.put("knownExceptionItems", why);
            }
            if (c.note != null) m.put("note", c.note);
            if (!c.examples.isEmpty()) m.put("examples", c.examples);
            cs.put(c.name, m);
        }
        root.put("checks", cs);
        return root;
    }

    /** Adds counts to the report's current step, plus one error/warning per failing check. */
    public void toReport(Report rep) {
        for (Check c : checks.values()) {
            rep.count(c.name + " ok", c.ok);
            if (c.bad == 0) continue;
            rep.count(c.name + " bad", c.bad);
            String msg = c.name + ": " + c.bad + " bad" + (c.stock > 0 ? " (" + c.stock + " also in the baseline)" : "")
                    + (c.known.isEmpty() ? "" : " (" + c.knownCount + " documented exceptions: " + new TreeSet<>(c.known.values()) + ")") + ", e.g. " + c.examples;
            if (c.fresh() == 0) rep.note(msg);
            else if (c.severity == Severity.ERROR) rep.error(msg);
            else if (c.severity == Severity.WARNING) rep.warn(msg);
            else rep.note(msg);
        }
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Check c : checks.values()) {
            String mark = c.bad == 0 ? "  ok " : c.fresh() == 0 ? (c.known.isEmpty() ? " base" : "known") : c.severity == Severity.ERROR ? "ERROR" : c.severity == Severity.WARNING ? " WARN" : " info";
            out.add(String.format("%s %-44s ok %8d  bad %6d%s%s", mark, c.name, c.ok, c.bad, c.stock > 0 ? "  (in baseline " + c.stock + ")" : "",
                    c.known.isEmpty() ? "" : "  (documented exceptions " + c.knownCount + ")"));
            if (c.bad > 0 && c.severity != Severity.INFO) for (String e : c.examples.subList(0, Math.min(6, c.examples.size()))) out.add("        " + e);
        }
        return out;
    }
}
