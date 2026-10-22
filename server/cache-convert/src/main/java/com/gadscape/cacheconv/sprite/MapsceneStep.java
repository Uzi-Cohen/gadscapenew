package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * mapscene: the minimap icon sheet (sprite group "mapscene", 317), whose frames locs address by index (loc opcode
 * 68). BASE's sheet stays as it is, so every BASE loc keeps its icon; the old MapsceneFix tool (which appended 241's
 * frames to the user's sheet and shifted BASE locs) is replaced by the opposite direction:
 * <ol>
 *   <li>a USER frame is <i>custom</i> when it has a picture and BASE has none at that index, or BASE's frame there is
 *       a different picture ({@link SpriteSheet.FrameDiff#sameThing}: Jagex re-colours do not count);</li>
 *   <li>only custom frames that user-added or user-changed locs actually use are merged: they are appended after
 *       BASE's last frame (palette merged with BASE's colours first; colours beyond 255 map to the nearest), and the
 *       opcode 68 of those locs in the output is rewritten to the new index.</li>
 * </ol>
 * On the current inputs USER's sheet is a Jagex sheet (frames 0-190 are BASE's pictures re-coloured; 191-199 are
 * empty), so no frame is custom and BASE's sheet is kept. User locs that point at an empty USER frame now show BASE's
 * icon there; they are reported.
 * <p>
 * Run it after {@code configs} (it patches the locs that step wrote). Options: {@code --mapscene.merge=auto|never}.
 */
public final class MapsceneStep implements Step {
    public static final String NAME = "mapscene";
    public static final int DEFAULT_SHEET = 317;

    @Override public String name() { return NAME; }

    /** The mapscene sheet's group id in a cache (by name, else 317). */
    public static int sheetId(CacheView c) {
        int id = c.groupIdByName(Arch.SPRITES, "mapscene");
        return id >= 0 ? id : DEFAULT_SHEET;
    }

    /** Frames of the USER sheet that are GadScape's own pictures (see the class comment), ascending. */
    public static TreeSet<Integer> customFrames(SpriteSheet user, SpriteSheet base) {
        TreeSet<Integer> r = new TreeSet<>();
        for (int f = 0; f < user.frameCount(); f++) {
            if (user.blankFrame(f)) continue;
            if (f >= base.frameCount() || base.blankFrame(f) || !SpriteSheet.diff(user, f, base, f).sameThing()) r.add(f);
        }
        return r;
    }

    @Override public void run(Ctx ctx) {
        var user = ctx.user();
        int sheet = sheetId(ctx.base);
        GroupData gu = user.group(Arch.SPRITES, sheet), gb = ctx.base.group(Arch.SPRITES, sheet);
        if (gu == null || gb == null) { ctx.report.note("mapscene: sheet " + sheet + " missing in " + (gu == null ? "USER" : "BASE") + "; nothing to do"); return; }
        if (gu.sameContent(gb)) { ctx.report.note("mapscene: USER's sheet is BASE's"); return; }
        SpriteSheet su = SpriteSheet.parse(SpritesStep.single(gu)), sb = SpriteSheet.parse(SpritesStep.single(gb));
        TreeSet<Integer> custom = customFrames(su, sb);
        ctx.report.count("mapscene.user-frames", su.frameCount());
        ctx.report.count("mapscene.base-frames", sb.frameCount());
        ctx.report.count("mapscene.custom-user-frames", custom.size());

        // which mapscene frames the user's own and edited locs use
        TreeMap<Integer, List<Integer>> usedBy = new TreeMap<>();   // frame -> USER loc ids
        TreeMap<Integer, List<Integer>> blankUse = new TreeMap<>(); // user frame empty, BASE has an icon
        int scanned = 0, unreadable = 0;
        for (int id : user.configIds(Arch.CFG_LOC)) {
            if (!Origin.ofConfig(Arch.CFG_LOC, id, user, ctx.van211()).isUser()) continue;
            int v;
            try { v = mapscene(ConfigConv.loc(id, user.configRef(Arch.CFG_LOC, id))); }
            catch (RuntimeException e) { unreadable++; continue; }
            scanned++;
            if (v < 0) continue;
            if (custom.contains(v)) usedBy.computeIfAbsent(v, k -> new ArrayList<>()).add(id);
            else if (v < su.frameCount() && su.blankFrame(v) && v < sb.frameCount() && !sb.blankFrame(v)) blankUse.computeIfAbsent(v, k -> new ArrayList<>()).add(id);
        }
        user.release(Arch.CONFIGS);
        ctx.report.count("mapscene.user-locs-scanned", scanned);
        if (unreadable > 0) ctx.report.warn("loc", Long.MIN_VALUE, unreadable + " user locs could not be read for their mapscene");
        if (!blankUse.isEmpty())
            ctx.report.note("mapscene: " + count(blankUse) + " user locs use mapscene frames that are empty in USER but have an icon in BASE (they now show BASE's icon): " + describe(blankUse));
        if (usedBy.isEmpty()) {
            ctx.report.add(Decision.TAKEN_FROM_BASE, Kind.SPRITE, sheet, "mapscene sheet: no user loc uses a GadScape frame (" + custom.size() + " custom USER frames); BASE's "
                    + sb.frameCount() + "-frame sheet kept");
            return;
        }
        if (ctx.opt.get("mapscene.merge", "auto").equals("never")) {
            ctx.report.warn("sprite", sheet, "mapscene merge disabled: " + count(usedBy) + " user locs use GadScape frames " + usedBy.keySet() + " and show BASE's icons instead");
            return;
        }
        merge(ctx, sheet, gb, su, sb, usedBy);
    }

    /** Appends the used custom frames to BASE's sheet and points the user locs at them. */
    static void merge(Ctx ctx, int sheet, GroupData gb, SpriteSheet su, SpriteSheet sb, TreeMap<Integer, List<Integer>> usedBy) {
        List<SpriteSheet.Pick> picks = new ArrayList<>();
        for (int f = 0; f < sb.frameCount(); f++) picks.add(new SpriteSheet.Pick(sb, f));
        TreeMap<Integer, Integer> remap = new TreeMap<>();
        for (int f : usedBy.keySet()) { remap.put(f, picks.size()); picks.add(new SpriteSheet.Pick(su, f)); }
        if (picks.size() > 65535) throw new IllegalStateException("mapscene sheet would have " + picks.size() + " frames");
        SpriteSheet.Composed c = SpriteSheet.compose(sb, picks);
        GroupData.Builder b = new GroupData.Builder(sheet).name(gb.name);
        b.put(gb.fileIdAt(0), gb.fileNameAt(0), c.sheet().encode());
        ctx.out.putGroup(Arch.SPRITES, b.build());
        ctx.report.add(Decision.MERGED, Kind.SPRITE, sheet, "mapscene: BASE's " + sb.frameCount() + " frames + " + remap.size() + " GadScape frames appended " + remap
                + (c.approximated() > 0 ? "; " + c.approximated() + " of " + c.coloursWanted() + " colours mapped to the nearest" : ""));
        int patched = 0;
        for (var e : usedBy.entrySet()) {
            for (int locId : e.getValue()) {
                int to = ctx.ids.map(Kind.LOC, locId);
                byte[] cur = ctx.out.config(Arch.CFG_LOC, to);
                if (cur == null) { ctx.report.warn("loc", locId, "uses mapscene frame " + e.getKey() + " but is not in the output (loc " + to + ")"); continue; }
                int at;
                try { at = mapsceneOffset(cur); }
                catch (RuntimeException ex) { ctx.report.warn("loc", to, "cannot patch its mapscene: " + ex.getMessage()); continue; }
                if (at < 0 || Bytes.u16(cur, at) != e.getKey()) { ctx.report.warn("loc", to, "mapscene in the output is not frame " + e.getKey() + "; left as is"); continue; }
                Bytes.w16(cur, at, remap.get(e.getKey()));
                ctx.out.putConfig(Arch.CFG_LOC, to, cur);
                patched++;
            }
        }
        ctx.report.count("mapscene.locs-patched", patched);
    }

    /** The mapscene (opcode 68) of a build-241 loc definition, or -1. */
    public static int mapscene(byte[] b) { int at = mapsceneOffset(b); return at < 0 ? -1 : Bytes.u16(b, at); }

    /**
     * Offset of the u16 operand of opcode 68 in a build-241 loc definition (the layout {@link ConfigConv#loc} writes:
     * int model lists 6/7, 78/79 with the retain byte; BASE's 1/5 model lists hold big smarts), or -1 when the loc has
     * none. Unknown opcodes throw.
     */
    public static int mapsceneOffset(byte[] b) {
        Buf in = new Buf(b);
        while (true) {
            int start = in.p, op = in.u8();
            switch (op) {
                case 0: return -1;
                case 68: return in.p;
                case 1: { int n = in.u8(); for (int i = 0; i < n; i++) { in.skipBigSmart(); in.skip(1); } break; } // 241: model ids are big smarts
                case 5: { int n = in.u8(); for (int i = 0; i < n; i++) in.skipBigSmart(); break; }
                case 6: { int n = in.u8(); in.skip(n * 5); break; }
                case 7: { int n = in.u8(); in.skip(n * 4); break; }
                case 2: case 30: case 31: case 32: case 33: case 34: in.skipStr(); break;
                case 14: case 15: case 19: case 28: case 29: case 39: case 69: case 75: case 81: case 91: case 95: case 96: in.skip(1); break;
                case 17: case 18: case 21: case 22: case 23: case 27: case 62: case 64: case 73: case 74: case 89: case 90: case 94: break;
                case 24: case 42: case 61: case 65: case 66: case 67: case 70: case 71: case 72: case 82: in.skip(2); break;
                case 40: case 41: { int n = in.u8(); in.skip(n * 4); break; }
                case 77: case 92: { in.skip(4); if (op == 92) in.skip(2); int n = in.u8(); in.skip((n + 1) * 2); break; }
                case 78: in.skip(4); break;
                case 79: { in.skip(6); int n = in.u8(); in.skip(n * 2); break; }
                case 93: in.skip(6); break;
                case 100: in.skip(2); in.skipStr(); break;
                case 101: in.skip(13); in.skipStr(); break;
                case 102: in.skip(15); in.skipStr(); break;
                case 249: in.skipParams(); break;
                default: throw new IllegalStateException("loc opcode " + op + " at " + start);
            }
        }
    }

    private static int count(Map<Integer, List<Integer>> m) { int n = 0; for (var l : m.values()) n += l.size(); return n; }

    private static String describe(Map<Integer, List<Integer>> m) {
        StringBuilder s = new StringBuilder();
        for (var e : m.entrySet()) {
            if (s.length() > 0) s.append("; ");
            List<Integer> l = e.getValue();
            s.append("frame ").append(e.getKey()).append(": locs ").append(l.size() <= 8 ? l.toString() : l.subList(0, 8) + " ... (" + l.size() + ")");
        }
        return s.toString();
    }
}
