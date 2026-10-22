package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;

/**
 * The loc writer the maps area calls when it moves a USER loc that BASE map squares place (MAP-6) to the reserved loc
 * range. Preferred order: the maps planner records its moves in the id map ({@code ctx.ids.record(Kind.LOC, ...)}) before
 * {@link ConfigStep} runs; {@link ConfigStep} then writes every moved loc at its new id, leaves BASE's loc at the old
 * id and maps every loc reference (multiloc transforms, loc-typed params/enums) through the id map. A step that moves
 * locs after {@link ConfigStep} calls {@link #move} instead, which does the same for one loc (references to it inside
 * other configs that were already written are then not rewritten; {@link ConfigRefCheck} reports them).
 * <p>
 * Loc ids in multiloc transform lists (ops 77/92) are 2 bytes: a moved loc that a transform references must stay
 * below 65535, otherwise writing that transform fails and is reported as an error.
 */
public final class LocWriter {
    private LocWriter() {}

    /**
     * The preferred call for the maps planner: moves USER loc {@code id} to the next free id of the loc range (spec
     * 70000-79999), records it in the id map and reports it. Must run before {@link ConfigStep}, which then writes
     * the loc at its new id with every reference mapped and keeps BASE's loc at {@code id}. Returns the new id (the
     * existing one if the loc was already moved). Note: multiloc transform lists hold 2-byte loc ids, so a loc that
     * a transform references cannot use a range above 65534 ({@link ConfigStep} reports such a transform as an error).
     */
    public static int plan(Ctx ctx, int id, String why) {
        int to = ctx.ids.lookup(Kind.LOC, id);
        if (to != -1) return to;
        to = ctx.ids.relocate(Kind.LOC, id, why);
        ctx.report.relocated(Kind.LOC, id, to, why);
        return to;
    }

    /** The USER loc in the 241 layout with every reference mapped through the id map (null if USER has no such loc). */
    public static byte[] convert(Ctx ctx, int id) {
        CfgModel m = new CfgModel(ctx);
        CfgCodec.Entry u = m.userEntry(Arch.CFG_LOC, id);
        return u == null ? null : CfgCodec.map(u, m.mapper()).encode();
    }

    /**
     * Writes USER loc {@code id} at {@code to} (converted, references mapped) and puts BASE's loc back at {@code id}
     * (or removes the id when BASE has none). Records the move in the id map if it is not there yet and reports it.
     * Returns the bytes written.
     */
    public static byte[] move(Ctx ctx, int id, int to, String why) {
        if (ctx.ids.lookup(Kind.LOC, id) != to) {
            ctx.ids.record(Kind.LOC, id, to, why);
            ctx.report.relocated(Kind.LOC, id, to, why);
        }
        byte[] b = convert(ctx, id);
        if (b == null) throw new IllegalArgumentException("USER has no loc " + id);
        ctx.out.putConfig(Arch.CFG_LOC, to, b);
        byte[] base = ctx.base.config(Arch.CFG_LOC, id);
        if (base != null) ctx.out.putConfig(Arch.CFG_LOC, id, base);
        else ctx.out.removeConfig(Arch.CFG_LOC, id);
        Origin o = Origin.ofConfig(Arch.CFG_LOC, id, ctx.user(), ctx.van211());
        ctx.report.add(Decision.NOTE, Kind.LOC, id, "LocWriter: " + o + " loc written at " + to + (base != null ? "; BASE's loc restored at " + id : ""));
        return b;
    }
}
