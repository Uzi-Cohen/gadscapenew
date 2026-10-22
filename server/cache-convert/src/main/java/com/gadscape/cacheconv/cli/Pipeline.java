package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The build-cacheu steps, in run order. The output starts as a byte copy of BASE before the first step; the
 * pipeline commits it after every step. Area implementers provide Step classes in their own packages; the integrator
 * lists them here.
 */
public final class Pipeline {
    private Pipeline() {}

    public static List<Step> steps(Options o) {
        List<Step> s = new ArrayList<>();
        s.add(new CheckRanges());
        // area steps are added here by the integrator, e.g.
        //   s.add(new com.gadscape.cacheconv.anim.FramesStep());
        return s;
    }

    /**
     * Records how many ids of each reserved GadScape range BASE, USER and the output already use (the allocator skips
     * used ids; a warning names every range that is not completely free) and the final ranges, so report.json and
     * idmap.json show what was chosen.
     */
    static final class CheckRanges implements Step {
        @Override public String name() { return "check-ranges"; }

        @Override public void run(Ctx ctx) {
            Map<String, Object> occ = ctx.ids.rangeOccupancy();
            ctx.report.input("reservedRanges", occ);
            for (var e : occ.entrySet()) {
                @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) e.getValue();
                for (var c : row.entrySet()) {
                    if (c.getKey().equals("range") || Integer.valueOf(0).equals(c.getValue())) continue;
                    ctx.report.warn(e.getKey(), Long.MIN_VALUE, "reserved range " + java.util.Arrays.toString((int[]) row.get("range")) + " already has ids in " + c.getKey() + ": " + c.getValue() + " (the allocator skips them)");
                }
            }
        }
    }
}
