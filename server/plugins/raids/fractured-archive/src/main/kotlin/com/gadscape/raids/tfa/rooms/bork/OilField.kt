package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * The incendiary cannonball's burning oil (spec §4.3 "Oil"): tiles that each live [lifetime] ticks.
 *
 * - [add]: a new tile gets its splat (spot 4047-4050 by rotation) and the puddle loc 62544 through the [Sink]; a tile
 *   that is already oiled has its lifetime refreshed.
 * - [tick] at the end of each tick (§2.5 step 4): expired tiles fade (despawn spot 4051-4054 with the same rotation +
 *   loc anim 14576) and their loc is removed one tick later; every fighter standing on a live tile that was placed
 *   before this tick takes rnd(6, 7) typeless [DOC] and gains burn stacks (E10) through [hurt].
 * - [clear] removes every loc at once (completion, wipe, teardown).
 *
 * Pure (the world side is the [Sink]); unit tested.
 */
class OilField(
    private val lifetime: Int,
    private val damage: IntRange,
    private val sink: Sink,
) {

    interface Sink {
        /** A new oil tile: splat graphic + puddle loc, both with [rotation] (0-3). */
        fun spawn(tile: StaticTile, rotation: Int)

        /** The tile's lifetime ended: despawn graphic + the loc's fade anim. */
        fun fade(tile: StaticTile, rotation: Int)

        /** Remove the loc (after its fade, or at once on [clear]). */
        fun remove(tile: StaticTile)
    }

    private class Oil(val rotation: Int, var placed: Int, var expires: Int)

    private val tiles = LinkedHashMap<StaticTile, Oil>()
    private val fading = LinkedHashMap<StaticTile, Int>()

    /** Live oil tiles. */
    val size: Int get() = tiles.size

    fun isOiled(tile: StaticTile): Boolean = key(tile) in tiles

    /** The live tiles (static, plane 0). */
    fun liveTiles(): Set<StaticTile> = tiles.keys.toSet()

    /** Oils [tile] at tick [now] with [rotation] (0-3), or refreshes it. */
    fun add(tile: StaticTile, now: Int, rotation: Int) {
        val k = key(tile)
        val existing = tiles[k]
        if (existing != null) {
            existing.expires = now + lifetime
            return
        }
        if (fading.remove(k) != null) sink.remove(k) // a fading puddle is replaced by the new one
        tiles[k] = Oil(rotation and 3, now, now + lifetime)
        sink.spawn(k, rotation and 3)
    }

    /**
     * End of tick [now]: removes faded locs, fades expired tiles, then hurts every alive fighter on a live tile placed
     * before [now]. [hurt] receives the fighter and the rolled damage.
     */
    fun <F : Fighter> tick(now: Int, fighters: Collection<F>, random: Random, hurt: (F, Int) -> Unit) {
        if (fading.isNotEmpty()) {
            val done = fading.filterValues { it <= now }.keys.toList()
            for (t in done) {
                fading.remove(t)
                sink.remove(t)
            }
        }
        if (tiles.isNotEmpty()) {
            val expired = tiles.filterValues { it.expires <= now }
            for ((t, oil) in expired) {
                tiles.remove(t)
                sink.fade(t, oil.rotation)
                fading[t] = now + FADE_TICKS
            }
        }
        if (tiles.isEmpty()) return
        for (f in fighters) {
            if (!f.alive) continue
            val oil = tiles[key(f.tile)] ?: continue
            if (oil.placed >= now) continue
            val span = damage.last - damage.first + 1
            hurt(f, damage.first + if (span > 1) random.nextInt(span) else 0)
        }
    }

    /** Removes every loc now (no fade). */
    fun clear() {
        for (t in tiles.keys) sink.remove(t)
        for (t in fading.keys) sink.remove(t)
        tiles.clear()
        fading.clear()
    }

    private fun key(tile: StaticTile) = if (tile.plane == 0) tile else StaticTile(tile.x, tile.y, 0)

    companion object {
        /** CACHE: the loc fade 14576 is 30 cycles (1 t); the despawn spots 4051-4054 are 1.43 t. */
        const val FADE_TICKS = 1
    }
}
