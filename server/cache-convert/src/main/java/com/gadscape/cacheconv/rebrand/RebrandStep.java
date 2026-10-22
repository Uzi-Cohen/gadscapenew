package com.gadscape.cacheconv.rebrand;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Step;

/**
 * "rebrand" (spec rule 12): replaces old branding (Near-Reality, Roat Pkz, OSNR, RuneSpawn, ...) in the output's
 * config strings, interface texts and script strings with the rules of the {@link RebrandTable}, after every step
 * that writes those (so GadScape's own and merged content are both covered) and before the checks. With no table
 * (the default until the branding task provides one) it changes nothing and says so in the report; the verify step's
 * brand check lists the remaining hits either way.
 * Options: {@code --rebrand.table=FILE|none}.
 */
public final class RebrandStep implements Step {
    public static final String NAME = "rebrand";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) throws Exception {
        RebrandTable t = RebrandTable.load(ctx.opt);
        ctx.report.input("rebrandTable", t.source + " (" + t.rules.size() + " rules)");
        if (t.isEmpty()) {
            ctx.report.note("rebrand: no replacement table (--rebrand.table=FILE or resource " + RebrandTable.RESOURCE + "); nothing replaced, the verify brand check lists the hits");
            ctx.log("no replacement table: nothing to do");
            return;
        }
        Rebrander r = new Rebrander(t, ctx.out, ctx.report);
        r.run();
        ctx.log("%d rules from %s: %s", t.rules.size(), t.source, r.counts);
    }
}
