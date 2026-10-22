package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Graphics

/**
 * Telegraph helpers (spec §2.1 `encounter/Telegraphs.kt`): map graphics on static tiles of an encounter's instance,
 * optionally delayed in client cycles (Bork wave dust 4394-4405 uses the per-tile delay, §5.7).
 *
 * The spotanim id always comes from the room's `TfaIds.Spot` constant (with its evidence there); these helpers only
 * translate the coordinates. The client plays each spotanim once for its own length.
 */
object Telegraphs {

    /** Spotanim [spot] on static [tile] after [delayCycles] client cycles (30 per tick) at [height]. */
    @JvmStatic
    @JvmOverloads
    fun tile(context: TfaEncounterContext, spot: Int, tile: StaticTile, delayCycles: Int = 0, height: Int = 0) {
        if (spot < 0) return
        World.sendGraphics(Graphics(spot, delayCycles, height), context.instance.translate(tile))
    }

    /** [spot] on every tile of [tiles] (same delay). */
    @JvmStatic
    @JvmOverloads
    fun tiles(context: TfaEncounterContext, spot: Int, tiles: Collection<StaticTile>, delayCycles: Int = 0, height: Int = 0) {
        for (t in tiles) tile(context, spot, t, delayCycles, height)
    }

    /**
     * A wave of [spot] over [tiles], each tile [stepCycles] client cycles after the previous one (a sweeping
     * telegraph without server ticks in between).
     */
    @JvmStatic
    @JvmOverloads
    fun wave(context: TfaEncounterContext, spot: Int, tiles: List<StaticTile>, stepCycles: Int, startCycles: Int = 0, height: Int = 0) {
        for ((i, t) in tiles.withIndex()) tile(context, spot, t, startCycles + i * stepCycles, height)
    }

    /** [spot] on a live tile (already translated). */
    @JvmStatic
    @JvmOverloads
    fun live(spot: Int, tile: Location, delayCycles: Int = 0, height: Int = 0) {
        if (spot < 0) return
        World.sendGraphics(Graphics(spot, delayCycles, height), tile)
    }

    /** The 5x5 (or [size]) footprint of tiles around a centre tile, e.g. a landing shadow. */
    @JvmStatic
    fun square(centre: StaticTile, size: Int): List<StaticTile> {
        val half = size / 2
        val out = ArrayList<StaticTile>(size * size)
        for (dx in -half until size - half) for (dy in -half until size - half) out += centre.offset(dx, dy)
        return out
    }
}
