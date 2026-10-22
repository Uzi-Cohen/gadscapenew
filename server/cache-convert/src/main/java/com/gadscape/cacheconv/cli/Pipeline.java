package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The build-cacheu steps, in run order. The output starts as a byte copy of BASE before the first step; the
 * pipeline commits it after every step. All steps share one {@link com.gadscape.cacheconv.core.IdMap}: a step that
 * moves ids runs before every step that writes references to them, and later steps map those references through
 * {@code ctx.ids}.
 * <ol>
 *   <li><b>check-ranges</b>: occupancy of the reserved ranges (report).</li>
 *   <li><b>Planners</b> (record relocations, write little or nothing):
 *     <ul>
 *       <li>{@code ui-relocate}: custom scripts/interfaces at ids BASE uses move (47000+, 5200+); reserves undefined
 *           var ids custom UI uses inside the reserved ranges, so it runs before any var allocation;</li>
 *       <li>{@code fonts}, {@code sprites}: user fonts/sprites (sprites at ids BASE's UI uses move to 20000+);</li>
 *       <li>{@code db-plan}: user dbrows/tables at BASE ids move (60000+), before configs-plan;</li>
 *       <li>{@code models}: user models (moved ones to the top of [BASE max + 1, 65535]);</li>
 *       <li>{@code configs-plan}: UI configs, protected Raids 4 ids, GadScape vars;</li>
 *       <li>{@code maps-plan}: user locs that BASE squares place (64500+);</li>
 *       <li>{@code anim}: seqs, frames, framemaps, keyframes (written here; hand objs follow configs-plan).</li>
 *     </ul></li>
 *   <li><b>Writers</b>, which map every reference through the id map: {@code configs} (archive 2 except seqs),
 *       {@code config-sprites} (deleted vanilla sprites those configs name), {@code mapscene}, {@code ui-merge} (the three-way-merged vanilla UI), {@code ui-convert} (GadScape's own UI),
 *       {@code maps} (user squares), {@code db-index} (archive 21 from the rows written), {@code rebrand} (texts).</li>
 *   <li><b>Checks</b> (read-only, one report error per failure): {@code configs-oracle}, {@code configs-refcheck},
 *       {@code configs-expect}, {@code models-refcheck}, {@code anim-refcheck}, {@code ui-check}, and {@code verify}
 *       (the whole-cache verifier with BASE as baseline) last.</li>
 * </ol>
 * {@code --only}/{@code --skip} select steps by name; a planner skipped makes the later steps see fewer relocations.
 */
public final class Pipeline {
    private Pipeline() {}

    public static List<Step> steps(Options o) {
        List<Step> s = new ArrayList<>();
        s.add(new CheckRanges());
        // planners: record relocations before anything writes references
        s.add(new com.gadscape.cacheconv.ui.UiRelocateStep());          // ui-relocate
        s.add(new com.gadscape.cacheconv.sprite.FontsStep());           // fonts
        s.add(new com.gadscape.cacheconv.sprite.SpritesStep());         // sprites
        s.add(new com.gadscape.cacheconv.db.DbPlanStep());              // db-plan
        s.add(new com.gadscape.cacheconv.model.ModelsStep());           // models
        s.add(new com.gadscape.cacheconv.config.ConfigPlanStep());      // configs-plan
        s.add(new com.gadscape.cacheconv.map.MapLocPlanStep());         // maps-plan
        s.add(new com.gadscape.cacheconv.anim.AnimStep());              // anim
        // writers: every reference goes through ctx.ids
        s.add(new com.gadscape.cacheconv.config.ConfigStep());          // configs
        s.add(new com.gadscape.cacheconv.sprite.ConfigSpritesStep());   // config-sprites
        s.add(new com.gadscape.cacheconv.sprite.MapsceneStep());        // mapscene
        s.add(new com.gadscape.cacheconv.uimerge.UiMergeStep());        // ui-merge
        s.add(new com.gadscape.cacheconv.ui.UiConvertStep());           // ui-convert
        s.add(new com.gadscape.cacheconv.map.MapsStep());               // maps
        s.add(new com.gadscape.cacheconv.db.DbIndexStep());             // db-index
        s.add(new com.gadscape.cacheconv.rebrand.RebrandStep());        // rebrand
        // checks (read-only)
        s.add(new com.gadscape.cacheconv.config.ConfigOracle());        // configs-oracle
        s.add(new com.gadscape.cacheconv.config.ConfigRefCheck());      // configs-refcheck
        s.add(new com.gadscape.cacheconv.config.ConfigExpect());        // configs-expect
        s.add(new com.gadscape.cacheconv.model.ModelRefCheckStep());    // models-refcheck
        s.add(new com.gadscape.cacheconv.anim.AnimRefCheck());          // anim-refcheck
        s.add(new com.gadscape.cacheconv.ui.UiCheckStep());             // ui-check
        s.add(new com.gadscape.cacheconv.verify.VerifyStep());          // verify
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
