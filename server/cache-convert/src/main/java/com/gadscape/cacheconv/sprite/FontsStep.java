package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.util.TreeSet;

/**
 * fonts: archive 13 (font metrics) is BASE's, plus the user's own fonts. A font is two groups with the same id: the
 * metrics in archive 13 and the glyph sheet in archive 8 (sprites); both move together.
 * <ul>
 *   <li>vanilla fonts (USER == VAN211, metrics and glyphs): BASE's.</li>
 *   <li>a user-added font at an id BASE has neither in archive 13 nor in archive 8: both groups at that id.</li>
 *   <li>a user-changed vanilla font (or a user-added one at an id BASE uses) that differs from BASE: the user's font
 *       is kept at the id when BASE's version is still the vanilla one (USER's edit, nothing of BASE's lost); otherwise
 *       it is an error: fonts cannot be relocated yet (the id map has no font kind; interface font fields and script
 *       fontmetrics constants would need rewriting), BASE's font stays.</li>
 * </ul>
 * On the current inputs every USER font is vanilla (16 groups; BASE has 21), so the output's fonts are BASE's.
 * Run it before {@link SpritesStep}, which leaves the glyph sheets of the fonts handled here alone.
 */
public final class FontsStep implements Step {
    public static final String NAME = "fonts";

    @Override public String name() { return NAME; }

    /** Font ids the user added or changed (metrics or glyph sheet differ from VAN211). */
    public static TreeSet<Integer> userFontIds(Ctx ctx) {
        TreeSet<Integer> ids = new TreeSet<>();
        var user = ctx.user();
        for (int id : user.groupIds(Arch.FONTS)) {
            Origin m = Origin.ofGroup(Arch.FONTS, id, user, ctx.van211());
            Origin g = Origin.ofGroup(Arch.SPRITES, id, user, ctx.van211());
            if (m.isUser() || g.isUser()) ids.add(id);
        }
        return ids;
    }

    @Override public void run(Ctx ctx) {
        var user = ctx.user();
        var van = ctx.van211();
        long vanilla = 0;
        TreeSet<Integer> mine = userFontIds(ctx);
        for (int id : user.groupIds(Arch.FONTS)) {
            if (!mine.contains(id)) { vanilla++; continue; }
            Origin om = Origin.ofGroup(Arch.FONTS, id, user, van);
            GroupData um = user.group(Arch.FONTS, id), ug = user.group(Arch.SPRITES, id);
            if (um == null || ug == null) { ctx.report.error("font", id, "USER font " + id + " lacks its " + (um == null ? "metrics (archive 13)" : "glyph sheet (archive 8)")); continue; }
            boolean baseM = ctx.base.hasGroup(Arch.FONTS, id), baseG = ctx.base.hasGroup(Arch.SPRITES, id);
            if (!baseM && !baseG) {
                put(ctx, id, um, ug);
                ctx.report.add(Decision.USER_ADDED, "font", id, "GadScape font (metrics + glyph sheet)");
                continue;
            }
            GroupData bm = ctx.base.group(Arch.FONTS, id), bg = ctx.base.group(Arch.SPRITES, id);
            if (bm != null && bg != null && bm.sameContent(um) && bg.sameContent(ug)) { ctx.report.add(Decision.SAME, "font", id, "identical to BASE"); continue; }
            GroupData vm = van.group(Arch.FONTS, id), vg = van.group(Arch.SPRITES, id);
            boolean baseIsVanilla = bm != null && bg != null && vm != null && vg != null && bm.sameContent(vm) && bg.sameContent(vg);
            if (om != Origin.USER_ADDED && baseIsVanilla) {
                put(ctx, id, um, ug);
                ctx.report.add(Decision.USER_CHANGED, "font", id, "user-edited vanilla font; BASE's is still the vanilla one");
                continue;
            }
            ctx.report.error("font", id, "user font clashes with BASE font " + id + " and fonts cannot be relocated (no font kind in the id map): BASE's font kept");
        }
        ctx.report.count("fonts.vanilla (BASE kept)", vanilla);
        ctx.report.count("fonts.base-only", countBaseOnly(ctx));
    }

    private static long countBaseOnly(Ctx ctx) {
        long n = 0;
        for (int id : ctx.base.groupIds(Arch.FONTS)) if (!ctx.user().hasGroup(Arch.FONTS, id)) n++;
        return n;
    }

    private static void put(Ctx ctx, int id, GroupData metrics, GroupData glyphs) {
        int name = metrics.name != -1 ? metrics.name : Bytes.nameHash("gadscape_font_" + id);
        ctx.out.putGroup(Arch.FONTS, SpritesStep.rename(metrics, id, name));
        ctx.out.putGroup(Arch.SPRITES, SpritesStep.rename(glyphs, id, glyphs.name != -1 ? glyphs.name : name));
    }
}
