package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * sprites: archive 8 is BASE's; the user's own and edited sprites are layered on top (spec policies 1-3). Per USER
 * sprite group, ascending:
 * <ul>
 *   <li><b>vanilla</b> (USER == VAN211) or removed by the user: BASE's group stays.</li>
 *   <li><b>user-added, BASE lacks the id</b>: the user's group at its id.</li>
 *   <li><b>user-added, BASE has the id</b> (CRIT-13, B11): the same picture (a re-encode), the same thing (Jagex art
 *       the user imported from a later revision and Jagex re-coloured since, {@link SpriteSheet.FrameDiff#sameThing})
 *       or a blank user placeholder: BASE's sprite, nothing moves. Anything else is GadScape's own art at an id BASE's
 *       UI uses: it moves to the reserved sprite range (20000-29999), recorded in the id map so ui-convert and configs
 *       rewrite every reference ({@link SpriteRefs}); the moved group is named {@code gadscape_sprite_<211 id>} (the
 *       user's copy carries BASE's name for the id, which would make name lookups ambiguous).</li>
 *   <li><b>user-changed vanilla sprite</b>: a frame-by-frame three-way merge ({@link SpriteMerge}): the user's edits
 *       on top of BASE's version, conflicts go to the user (reported). The GadScape logo, the re-lettered spellbook
 *       teleport icons, the extra chat/head icons all stay; frames the user only had in an older Jagex version are
 *       BASE's.</li>
 *   <li>The mapscene sheet (group "mapscene", 317) is left to {@link MapsceneStep}; the glyph sheets of user fonts to
 *       {@link FontsStep}.</li>
 * </ul>
 * Run it early (with the other steps that move ids, before configs and ui-convert, which rewrite sprite references).
 * Writes {@code WORK/sprites-db/sprites.tsv} (one line per non-vanilla USER sprite).
 * Options: {@code --sprites.sameThing=false} counts only identical pictures as the same (every re-coloured Jagex
 * import moves too).
 */
public final class SpritesStep implements Step {
    public static final String NAME = "sprites";

    @Override public String name() { return NAME; }

    /** Name hash given to a USER sprite group placed at a new id, or added without a usable name. */
    public static int gadscapeName(int userId) { return Bytes.nameHash("gadscape_sprite_" + userId); }

    @Override public void run(Ctx ctx) throws IOException {
        var user = ctx.user();
        var van = ctx.van211();
        boolean sameThing = Boolean.parseBoolean(ctx.opt.get("sprites.sameThing", "true"));
        int mapscene = MapsceneStep.sheetId(ctx.base);
        Set<Integer> fontIds = FontsStep.userFontIds(ctx);
        Set<Integer> baseNames = new HashSet<>();
        for (int g : ctx.base.groupIds(Arch.SPRITES)) baseNames.add(ctx.base.groupName(Arch.SPRITES, g));
        StringBuilder tsv = new StringBuilder("# sprite\torigin\tdecision\tto\tdetail\n");
        long vanilla = 0, vanillaGone = 0, removed = 0, same = 0;
        for (int id : user.groupIds(Arch.SPRITES)) {
            Origin o = Origin.ofGroup(Arch.SPRITES, id, user, van);
            if (o == Origin.VANILLA) { vanilla++; if (!ctx.base.hasGroup(Arch.SPRITES, id)) vanillaGone++; continue; }
            if (!o.isUser()) continue;
            if (id == mapscene) { line(tsv, id, o, "mapscene", id, "left to the mapscene step"); continue; }
            if (fontIds.contains(id)) { line(tsv, id, o, "font", id, "left to the fonts step"); continue; }
            GroupData gu = user.group(Arch.SPRITES, id);
            if (gu == null) { ctx.report.error("sprite", id, "USER sprite group does not decode"); continue; }
            GroupData gb = ctx.base.group(Arch.SPRITES, id);
            if (gb == null) {
                int name = gu.name != -1 && !baseNames.contains(gu.name) ? gu.name : gadscapeName(id);
                ctx.out.putGroup(Arch.SPRITES, rename(gu, id, name));
                String why = o == Origin.USER_ADDED ? "GadScape sprite (BASE has no sprite " + id + ")" : "user-changed vanilla sprite that BASE no longer has";
                ctx.report.add(o == Origin.USER_ADDED ? Decision.USER_ADDED : Decision.USER_CHANGED, Kind.SPRITE, id, why);
                line(tsv, id, o, o == Origin.USER_ADDED ? "user-added" : "user-changed", id, why);
                continue;
            }
            if (gu.sameContent(gb)) { same++; line(tsv, id, o, "same", id, "identical bytes"); continue; }
            SpriteSheet su, sb;
            try { su = SpriteSheet.parse(single(gu)); sb = SpriteSheet.parse(single(gb)); }
            catch (RuntimeException e) {
                // cannot compare the pictures: keep both (BASE at the id, the user's copy moved)
                relocate(ctx, tsv, id, o, gu, "clash with BASE sprite " + id + " (pictures not comparable: " + e.getMessage() + ")");
                continue;
            }
            if (o == Origin.USER_ADDED) added(ctx, tsv, id, gu, su, sb, sameThing);
            else changed(ctx, tsv, id, gu, su, sb, van.group(Arch.SPRITES, id));
        }
        for (int id : van.groupIds(Arch.SPRITES)) if (!user.hasGroup(Arch.SPRITES, id)) removed++;
        ctx.report.count("sprites.vanilla (BASE kept)", vanilla);
        ctx.report.count("sprites.same-bytes-as-BASE", same);
        // vanilla 211 sprites Jagex deleted: not ported here (ui-convert copies the ones GadScape's own UI names)
        if (vanillaGone > 0) ctx.report.count("sprites.vanilla-deleted-by-BASE (not ported)", vanillaGone);
        if (removed > 0) ctx.report.count("sprites.removed-by-user (BASE kept)", removed);
        ctx.out.release(Arch.SPRITES);
        user.release(Arch.SPRITES);
        Files.writeString(ctx.work("sprites-db").resolve("sprites.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        ctx.log("sprites: %d relocated to the reserved range, %s", ctx.ids.all(Kind.SPRITE).size(), ctx.work("sprites-db").resolve("sprites.tsv"));
    }

    /** A user-added sprite at an id BASE also has. */
    private void added(Ctx ctx, StringBuilder tsv, int id, GroupData gu, SpriteSheet su, SpriteSheet sb, boolean sameThing) {
        if (SpriteSheet.samePicture(su, sb)) {
            ctx.report.add(Decision.SAME, Kind.SPRITE, id, "same picture as BASE (re-encoded)");
            line(tsv, id, Origin.USER_ADDED, "same", id, "same picture (re-encoded)");
            return;
        }
        if (sameThing && SpriteSheet.sameThing(su, sb)) {
            ctx.report.add(Decision.SAME, Kind.SPRITE, id, "Jagex art the user imported from an older revision, re-coloured by Jagex since: BASE's version");
            line(tsv, id, Origin.USER_ADDED, "same", id, "Jagex re-colour (" + su.describe() + ")");
            return;
        }
        if (su.blank() && !sb.blank()) {
            ctx.report.add(Decision.TAKEN_FROM_BASE, Kind.SPRITE, id, "the user's sprite is an empty placeholder: BASE's");
            line(tsv, id, Origin.USER_ADDED, "taken-from-base", id, "user placeholder (blank)");
            return;
        }
        relocate(ctx, tsv, id, Origin.USER_ADDED, gu, "GadScape sprite at an id BASE uses (user " + su.describe() + ", BASE " + sb.describe() + ")");
    }

    private void relocate(Ctx ctx, StringBuilder tsv, int id, Origin o, GroupData gu, String why) {
        int to = ctx.ids.relocate(Kind.SPRITE, id, why);
        ctx.out.putGroup(Arch.SPRITES, rename(gu, to, gadscapeName(id)));
        ctx.report.relocated(Kind.SPRITE, id, to, why);
        line(tsv, id, o, "relocated", to, why);
    }

    /** A vanilla sprite the user edited, which BASE also has (and differs from the user's). */
    private void changed(Ctx ctx, StringBuilder tsv, int id, GroupData gu, SpriteSheet su, SpriteSheet sb, GroupData gv) {
        SpriteSheet sv;
        try { sv = gv == null ? null : SpriteSheet.parse(single(gv)); }
        catch (RuntimeException e) { sv = null; }
        if (SpriteSheet.samePicture(su, sb)) {
            ctx.report.add(Decision.SAME, Kind.SPRITE, id, "the user's edit is BASE's picture");
            line(tsv, id, Origin.USER_CHANGED, "same", id, "same picture as BASE");
            return;
        }
        SpriteMerge.Result r = SpriteMerge.merge(sv, su, sb);
        if (r.allBase(sb.frameCount())) {
            ctx.report.add(Decision.TAKEN_FROM_BASE, Kind.SPRITE, id, "every frame the user changed is a Jagex version of BASE's: BASE's (" + r.describe() + ")");
            line(tsv, id, Origin.USER_CHANGED, "taken-from-base", id, r.describe());
            return;
        }
        if (r.allUser(su.frameCount())) {
            ctx.out.putGroup(Arch.SPRITES, rename(gu, id, gu.name));
            String why = (r.conflicts > 0 ? "user's edit wins over BASE's change" : "BASE did not change it") + " (" + r.describe() + ")";
            ctx.report.add(r.conflicts > 0 ? Decision.CLASH_KEPT : Decision.USER_CHANGED, Kind.SPRITE, id, why);
            line(tsv, id, Origin.USER_CHANGED, r.conflicts > 0 ? "clash-kept" : "user-changed", id, why);
            return;
        }
        SpriteSheet.Composed c = SpriteMerge.build(r, su, sb);
        GroupData.Builder b = new GroupData.Builder(id).name(gu.name);
        b.put(gu.fileIdAt(0), gu.fileNameAt(0), c.sheet().encode());
        ctx.out.putGroup(Arch.SPRITES, b.build());
        String why = "three-way frame merge: " + r.describe() + (c.approximated() > 0 ? "; " + c.approximated() + " of " + c.coloursWanted() + " colours mapped to the nearest" : "");
        ctx.report.add(Decision.MERGED, Kind.SPRITE, id, why);
        if (r.conflicts > 0) ctx.report.count("sprites.merged-frame-conflicts-user-wins", r.conflicts);
        if (c.approximated() > 0) ctx.report.warn("sprite", id, c.approximated() + " colours of the merged sheet were mapped to the nearest palette colour");
        line(tsv, id, Origin.USER_CHANGED, "merged", id, why);
    }

    /** The bytes of file 0 (sprite groups hold one file). */
    static byte[] single(GroupData g) {
        if (g.fileCount() != 1) throw new IllegalStateException("sprite group " + g.id + " has " + g.fileCount() + " files");
        return g.dataAt(0);
    }

    /** The user's group at a new id with the given name hash (files keep their ids and names). */
    static GroupData rename(GroupData g, int id, int name) {
        GroupData.Builder b = new GroupData.Builder(id).name(name);
        for (int i = 0; i < g.fileCount(); i++) b.put(g.fileIdAt(i), g.fileNameAt(i), g.dataAt(i).clone());
        return b.build();
    }

    private static void line(StringBuilder tsv, int id, Origin o, String decision, int to, String detail) {
        tsv.append(id).append('\t').append(o.name().toLowerCase().replace('_', '-')).append('\t').append(decision).append('\t').append(to).append('\t')
                .append(detail.replace('\t', ' ').replace('\n', ' ')).append('\n');
    }

    /** Sprite ids this step moved, ascending (for checks). */
    public static TreeSet<Integer> moved(Ctx ctx) { return new TreeSet<>(ctx.ids.all(Kind.SPRITE).keySet()); }
}
