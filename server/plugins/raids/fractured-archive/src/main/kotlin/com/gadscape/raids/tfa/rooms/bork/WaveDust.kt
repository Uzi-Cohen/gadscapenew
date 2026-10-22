package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * Which `tfa_wave_dust` spotanim (4394-4405) each tile of a Bork shockwave gets, and when (NOTES §6/§7.2 [INF strong]:
 * the model and seqs are ToA Zebak's floor roar, and "the 8 directional variants fit a square wave expanding from
 * Bork"). A tile beside one face of his 4x4 gets the straight variant turned toward that face's outward direction; a
 * tile off a corner gets the diagonal short. Long (2 t) variants for large beats and stomps, short (1.53 t) for small
 * beats and the gong. The graphic is delayed by `cyclesPerTile` per ring, so the wave visibly travels outward.
 *
 * Direction angles follow the client's orientation (south 0, west 90, north 180, east 270; diagonals south-west 45 ..
 * south-east 315), plus `wave.angleOffset`. Pure; unit tested.
 */
object WaveDust {

    /** A dust graphic for one tile. */
    data class Dust(val spot: Int, val tile: StaticTile, val delayCycles: Int)

    /** Outward angle (degrees, 0 = south) of [tile] from Bork's footprint, or -1 for a tile inside it. */
    @JvmStatic
    fun angle(borkSw: StaticTile, size: Int, tile: StaticTile): Int {
        val sideX = when {
            tile.x < borkSw.x -> -1
            tile.x > borkSw.x + size - 1 -> 1
            else -> 0
        }
        val sideY = when {
            tile.y < borkSw.y -> -1
            tile.y > borkSw.y + size - 1 -> 1
            else -> 0
        }
        return when {
            sideX == 0 && sideY == 0 -> -1
            sideX == 0 && sideY < 0 -> 0      // south
            sideX < 0 && sideY == 0 -> 90     // west
            sideX == 0 -> 180                 // north
            sideY == 0 -> 270                 // east
            sideX < 0 && sideY < 0 -> 45      // south-west
            sideX < 0 -> 135                  // north-west
            sideY > 0 -> 225                  // north-east
            else -> 315                       // south-east
        }
    }

    /**
     * The spotanim for an outward [angle] (+ [angleOffset], a multiple of 90): straight angles map to the long or
     * short 0/90/180/270 variants, diagonal ones to the 45/135/225/315 shorts.
     */
    @JvmStatic
    fun spotFor(angle: Int, long: Boolean, angleOffset: Int): Int {
        val a = Math.floorMod(angle + angleOffset, 360)
        return if (a % 90 == 0) {
            val quarter = a / 90
            if (long) BorkIds.spotDustLong(quarter) else BorkIds.spotDustShort(quarter)
        } else {
            BorkIds.spotDustDiagonal((a - 45) / 90)
        }
    }

    /**
     * The dust for every tile of [tiles] outside Bork's footprint: ring distance d → delay `baseDelay + d ×
     * cyclesPerTile` cycles.
     */
    @JvmStatic
    fun plan(
        borkSw: StaticTile, size: Int, tiles: Collection<StaticTile>, long: Boolean, wave: BorkSmorksConfig.Wave,
        baseDelay: Int = 0,
    ): List<Dust> = buildList {
        for (t in tiles) {
            val a = angle(borkSw, size, t)
            if (a < 0) continue
            val ring = BorkArena.distToFootprint(borkSw, size, t)
            add(Dust(spotFor(a, long, wave.angleOffset), t, baseDelay + ring * wave.cyclesPerTile))
        }
    }
}
