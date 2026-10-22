package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the stack analysis over every script of a cache with a table and prints the problems (dev tool, used to check
 * the shipped table on the stock cache). Usage: {@code dev/run.sh verify.CsCheckBase CACHE [--table=FILE] [--max=N]}.
 */
public final class CsCheckBase {
    private CsCheckBase() {}

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        CsTable t = o.get("table", null) == null ? CsTable.load() : CsTable.parse(Files.readAllLines(Path.of(o.get("table", null))));
        int max = Integer.parseInt(o.get("max", "400"));
        try (JagCache c = JagCache.open("C", Path.of(o.args.get(0)))) {
            CsWorld w = new CsWorld(c);
            TreeMap<Integer, CsStack.Result> res = w.analyseAll(t::counts);
            Map<String, Integer> kinds = new TreeMap<>();
            List<String> lines = new ArrayList<>();
            int clean = 0, incomplete = 0;
            for (CsStack.Result r : res.values()) {
                if (r.violations().isEmpty() && r.complete()) clean++;
                if (!r.complete()) incomplete++;
                for (CsStack.Problem p : r.problems) {
                    kinds.merge(p.kind(), 1, Integer::sum);
                    if (!CsStack.isIncomplete(p.kind()) && lines.size() < max) lines.add("script " + r.id + " " + p);
                }
            }
            System.out.println(res.size() + " scripts, " + clean + " clean, " + incomplete + " incomplete, parse errors " + w.parseErrors.keySet() + ", problems " + kinds);
            lines.forEach(System.out::println);
        }
    }
}
