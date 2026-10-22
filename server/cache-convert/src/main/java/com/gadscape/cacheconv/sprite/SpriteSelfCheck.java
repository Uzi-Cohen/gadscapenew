package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.core.Origin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Checks of the sprite steps on the real caches (a dry run: nothing is written):
 * <ol>
 *   <li>the sheet codec gives every BASE and USER sprite group's bytes back;</li>
 *   <li>after fonts + sprites + mapscene: every relocated sprite holds the USER bytes at its new id and BASE's at the
 *       old one; every sprite the steps did not touch is BASE's; user-edited sprites kept at their id are the USER
 *       bytes; merged sheets draw exactly the frame each side contributed; the new groups have unique names;</li>
 *   <li>the audit's cases: B11 (user 5259-5265 moved, BASE's trophies 5259-5265 restored), the GadScape logo (498)
 *       and the re-lettered spellbook icons kept, the mapscene sheet (317) BASE's, fonts BASE's.</li>
 * </ol>
 * <pre>  dev/run.sh sprite.SpriteSelfCheck</pre>  Exit code 1 on any failure; {@link #check} is also run by the unit tests
 * when the cache layout is available.
 */
public final class SpriteSelfCheck {
    private final List<String> failures = new ArrayList<>();
    private int checks;

    private void expect(boolean ok, String what) { checks++; if (!ok && failures.size() < 1000) failures.add(what); }

    public static void main(String[] a) throws Exception {
        Options o = Options.parse(a, 0);
        List<String> f = check(o);
        System.out.println(f.isEmpty() ? "sprite self-check: OK" : "sprite self-check: " + f.size() + " FAILURES");
        f.stream().limit(40).forEach(x -> System.out.println("  " + x));
        System.exit(f.isEmpty() ? 0 : 1);
    }

    /** Runs every check; returns the failures (empty = OK). */
    public static List<String> check(Options o) throws Exception {
        SpriteSelfCheck c = new SpriteSelfCheck();
        c.codec(o);
        c.steps(o);
        System.out.println("sprite self-check: " + c.checks + " checks");
        return c.failures;
    }

    void codec(Options o) {
        for (Path p : new Path[]{o.base, o.user}) {
            JagCache jc = JagCache.open(p.equals(o.base) ? "BASE" : "USER", p);
            int n = 0, bad = 0;
            for (int g : jc.groupIds(Arch.SPRITES)) {
                GroupData gd = jc.group(Arch.SPRITES, g);
                if (gd == null || gd.fileCount() != 1) continue;
                byte[] b = gd.dataAt(0);
                boolean ok;
                try { ok = Arrays.equals(SpriteSheet.parse(b).encode(), b); }
                catch (RuntimeException e) { ok = false; }
                n++;
                if (!ok) bad++;
                expect(ok, jc.label() + " sprite " + g + " does not round-trip");
            }
            jc.release(Arch.SPRITES);
            System.out.printf("codec: %d %s sprites, %d do not round-trip%n", n, jc.label(), bad);
            jc.close();
        }
    }

    void steps(Options o) throws Exception {
        o.dryRun = true;
        o.out = (o.work != null ? o.work : Path.of("gadconv-work")).resolve("sprites-db/selfcheck-out"); // never written (dry run)
        boolean echo;
        try (Ctx ctx = Ctx.open(o, true)) {
            echo = ctx.report.echo;
            ctx.report.echo = false;
            for (var s : List.of(new FontsStep(), new SpritesStep(), new MapsceneStep())) { ctx.report.begin(s.name()); s.run(ctx); ctx.report.end(); }
            ctx.report.echo = echo;
            var out = ctx.out;
            var user = ctx.user();
            Map<Integer, Integer> moved = ctx.ids.all(Kind.SPRITE);
            java.util.HashSet<Integer> names = new java.util.HashSet<>();
            int baseKept = 0, userKept = 0, merged = 0;
            for (int g : out.groupIds(Arch.SPRITES)) {
                int name = out.groupName(Arch.SPRITES, g);
                expect(names.add(name), "sprite group name " + name + " used twice (group " + g + ")");
                GroupData og = out.group(Arch.SPRITES, g), bg = ctx.base.group(Arch.SPRITES, g);
                int from = ctx.ids.origin(Kind.SPRITE, g);
                if (from >= 0) { expect(og.sameContent(user.group(Arch.SPRITES, from)), "relocated sprite " + from + " -> " + g + " is not the USER group"); continue; }
                if (!out.isPending(Arch.SPRITES, g)) { expect(bg != null && og.sameContent(bg), "untouched sprite " + g + " is not BASE's"); baseKept++; continue; }
                GroupData ug = user.group(Arch.SPRITES, g);
                if (bg == null) { expect(ug != null && og.sameContent(ug), "added sprite " + g + " is not the USER group"); userKept++; continue; }
                if (ug != null && og.sameContent(ug)) { userKept++; continue; }
                // merged: every frame is the BASE or the USER frame
                SpriteSheet so = SpriteSheet.parse(og.dataAt(0)), su = SpriteSheet.parse(ug.dataAt(0)), sb = SpriteSheet.parse(bg.dataAt(0));
                for (int f = 0; f < so.frameCount(); f++) {
                    boolean b = f < sb.frameCount() && SpriteSheet.sameFrame(so, f, sb, f), u = f < su.frameCount() && SpriteSheet.sameFrame(so, f, su, f);
                    expect(b || u, "merged sprite " + g + " frame " + f + " is neither BASE's nor USER's");
                }
                expect(so.frameCount() == Math.max(su.frameCount(), sb.frameCount()), "merged sprite " + g + " has " + so.frameCount() + " frames");
                merged++;
            }
            for (var e : moved.entrySet()) expect(out.group(Arch.SPRITES, e.getKey()).sameContent(ctx.base.group(Arch.SPRITES, e.getKey())), "BASE sprite " + e.getKey() + " not restored");
            // audit expectations
            for (int id = 5259; id <= 5265; id++) expect(ctx.ids.moved(Kind.SPRITE, id), "B11: user sprite " + id + " not moved");
            expect(out.group(Arch.SPRITES, 498).sameContent(user.group(Arch.SPRITES, 498)), "the GadScape logo 498 is not the user's");
            for (int id : new int[]{27, 30, 33, 37, 54, 55}) expect(out.group(Arch.SPRITES, id).sameContent(user.group(Arch.SPRITES, id)), "spellbook icon " + id + " is not the user's");
            int ms = MapsceneStep.sheetId(ctx.base);
            expect(out.group(Arch.SPRITES, ms).sameContent(ctx.base.group(Arch.SPRITES, ms)), "mapscene sheet is not BASE's");
            for (int g : ctx.base.groupIds(Arch.FONTS)) expect(out.group(Arch.FONTS, g).sameContent(ctx.base.group(Arch.FONTS, g)), "font " + g + " is not BASE's");
            for (int id : user.groupIds(Arch.SPRITES)) {
                Origin or = Origin.ofGroup(Arch.SPRITES, id, user, ctx.van211());
                if (or != Origin.VANILLA) continue;
                GroupData bg = ctx.base.group(Arch.SPRITES, id), og = out.group(Arch.SPRITES, id);
                if (bg == null) expect(og == null, "vanilla sprite " + id + " that BASE deleted is in the output");
                else expect(og != null && og.sameContent(bg), "vanilla sprite " + id + " is not BASE's");
            }
            expect(moved.keySet().stream().allMatch(id -> moved.get(id) >= 20000 && moved.get(id) <= 29999), "a sprite moved outside 20000-29999");
            System.out.printf("steps: %d relocated, %d BASE groups untouched, %d USER groups at their id, %d merged%n", moved.size(), baseKept, userKept, merged);
        }
    }
}
