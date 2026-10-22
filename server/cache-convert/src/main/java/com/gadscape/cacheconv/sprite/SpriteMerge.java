package com.gadscape.cacheconv.sprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Three-way, frame-by-frame merge of a vanilla sprite sheet the user edited (VAN211 -&gt; USER) with BASE's version of
 * it (VAN211 -&gt; BASE). For frame i, drawn on the canvas:
 * <ul>
 *   <li>USER == VAN211: the user did not touch it, BASE's frame (Jagex's latest);</li>
 *   <li>BASE == VAN211: only the user changed it, the user's frame;</li>
 *   <li>USER and BASE are the same thing ({@link SpriteSheet.FrameDiff#sameThing}: both are Jagex versions of one
 *       picture, e.g. the user had imported a newer Jagex sheet): BASE's frame;</li>
 *   <li>the user's frame is empty where BASE has a picture (a placeholder): BASE's frame;</li>
 *   <li>otherwise a conflict, and the user's edit wins (spec rule 10: USER wins conflicts and they are reported).</li>
 * </ul>
 * Frames beyond the vanilla count: the user's added frames are kept at their index; where BASE added frames at the same
 * index the same rules apply (same thing or user placeholder: BASE; else conflict, USER). Frames only one side has
 * are taken from that side. Frame indices never move: the client, BASE content and the GadScape server address frames
 * by index (head icons, chat icons, hitmarks), so a conflict cannot be solved by appending (the mapscene sheet, whose
 * frames BASE locs address, is handled separately by {@link MapsceneStep}).
 */
public final class SpriteMerge {
    private SpriteMerge() {}

    public enum Source { BASE, USER }

    /** Per-frame choice and counts. */
    public static final class Result {
        public final List<Source> picks = new ArrayList<>();
        public int fromBase, fromUser, conflicts, userOnly, baseOnly, placeholders, jagexVariants;
        public final List<Integer> conflictFrames = new ArrayList<>();

        public boolean allBase(int baseFrames) { return fromUser == 0 && picks.size() == baseFrames; }
        public boolean allUser(int userFrames) { return fromBase == 0 && picks.size() == userFrames; }

        public String describe() {
            return picks.size() + " frames: " + fromBase + " BASE, " + fromUser + " USER (" + userOnly + " only in USER, " + conflicts + " conflicts"
                    + (conflictFrames.isEmpty() ? "" : " " + ranges(conflictFrames)) + "), " + jagexVariants + " Jagex variants, " + placeholders + " user placeholders";
        }
    }

    public static Result merge(SpriteSheet van, SpriteSheet user, SpriteSheet base) {
        Result r = new Result();
        int nv = van == null ? 0 : van.frameCount(), nu = user.frameCount(), nb = base.frameCount();
        int n = Math.max(nu, nb);
        for (int i = 0; i < n; i++) {
            boolean hv = i < nv, hu = i < nu, hb = i < nb;
            Source s;
            if (hu && hb) {
                if (hv && SpriteSheet.sameFrame(user, i, van, i)) s = Source.BASE;
                else if (hv && SpriteSheet.sameFrame(base, i, van, i)) s = Source.USER;
                else if (SpriteSheet.diff(user, i, base, i).sameThing()) { s = Source.BASE; r.jagexVariants++; }
                else if (user.blankFrame(i) && !base.blankFrame(i)) { s = Source.BASE; r.placeholders++; }
                else { s = Source.USER; r.conflicts++; r.conflictFrames.add(i); }
            } else if (hu) { s = Source.USER; r.userOnly++; }
            else { s = Source.BASE; r.baseOnly++; }
            r.picks.add(s);
            if (s == Source.BASE) r.fromBase++; else r.fromUser++;
        }
        return r;
    }

    /** The merged sheet: frames picked per {@link Result#picks}; the palette is based on the sheet contributing most frames. */
    public static SpriteSheet.Composed build(Result r, SpriteSheet user, SpriteSheet base) {
        List<SpriteSheet.Pick> picks = new ArrayList<>();
        for (int i = 0; i < r.picks.size(); i++) picks.add(new SpriteSheet.Pick(r.picks.get(i) == Source.BASE ? base : user, i));
        return SpriteSheet.compose(r.fromUser > r.fromBase ? user : base, picks);
    }

    static String ranges(List<Integer> l) {
        StringBuilder s = new StringBuilder();
        int i = 0;
        while (i < l.size()) {
            int j = i;
            while (j + 1 < l.size() && l.get(j + 1) == l.get(j) + 1) j++;
            if (s.length() > 0) s.append(',');
            s.append(l.get(i));
            if (j > i) s.append('-').append(l.get(j));
            i = j + 1;
        }
        return s.toString();
    }
}
