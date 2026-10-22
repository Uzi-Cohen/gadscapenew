package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * Where and how many jellies spawn (spec §4.5 "Jellies"). Pure: every choice goes through the given [Random], so a
 * seeded raid reproduces the same spawns.
 */
object JellyPlanner {

    /** Jellies per threshold sequence: `min(cap, perThresholdBase + perScale × n)` [HOUSE]. */
    fun count(cfg: DreadnothConfig.Jelly, scale: Int): Int =
        minOf(cfg.cap, cfg.perThresholdBase + cfg.perScale * scale).coerceAtLeast(0)

    /** [total] split over [parts] cycles as evenly as possible, the earlier cycles taking the remainder. */
    fun split(total: Int, parts: Int): List<Int> {
        if (parts <= 0) return emptyList()
        val base = total / parts
        val extra = total % parts
        return List(parts) { i -> base + if (i < extra) 1 else 0 }
    }

    /**
     * [count] distinct free arena tiles at least [minDistance] from the boss footprint at [bossSw] (spec: "random free
     * tiles at least 2 tiles from the boss"), avoiding [occupied]. If the arena is too crowded the distance is relaxed
     * to 1, then to any free tile outside the footprint; returns fewer tiles only when no free tile is left.
     */
    fun spawnTiles(count: Int, bossSw: StaticTile?, occupied: Set<StaticTile>, minDistance: Int, random: Random): List<StaticTile> {
        if (count <= 0) return emptyList()
        val free = DreadnothArena.WALKABLE.filter { it !in occupied }
        val picked = ArrayList<StaticTile>(count)
        // Never inside the footprint; relax the distance only when the arena is crowded.
        val distances = listOf(maxOf(1, minDistance), 1).distinct()
        for (min in distances) {
            if (picked.size >= count) break
            val pool = free.filter { t ->
                t !in picked && (bossSw == null || DreadnothArena.distanceToFootprint(bossSw, t) >= min)
            }.toMutableList()
            shuffle(pool, random)
            picked += pool.take(count - picked.size)
        }
        return picked
    }

    /** One death-wave jelly leaving the boss: from footprint tile [origin] to [dest], [distance] tiles (Chebyshev). */
    data class Hop(val origin: StaticTile, val dest: StaticTile, val distance: Int) {
        /** 14774 `npc_jelly_jump_1` (1 t) for a one-tile hop, 14773 `npc_jelly_jump_3` (3.07 t) beyond [INF, U74]. */
        val longHop: Boolean get() = distance > 1
    }

    /** The death wave: [hops] out of the footprint, plus [risers] for jellies that found no tile within reach. */
    data class DeathWave(val hops: List<Hop>, val risers: List<StaticTile>)

    /**
     * Plans the death wave of [count] jellies (spec: "hop out of the boss footprint"): destinations are free arena tiles
     * 1..[maxTiles] from the footprint at [bossSw]; each starts on the footprint tile nearest to its destination.
     * Jellies that do not fit rise on other free tiles instead.
     */
    fun deathWave(count: Int, bossSw: StaticTile, occupied: Set<StaticTile>, maxTiles: Int, random: Random): DeathWave {
        if (count <= 0) return DeathWave(emptyList(), emptyList())
        val pool = DreadnothArena.WALKABLE.filter {
            it !in occupied && DreadnothArena.distanceToFootprint(bossSw, it) in 1..maxTiles
        }.toMutableList()
        shuffle(pool, random)
        val hops = pool.take(count).map { dest ->
            val origin = DreadnothArena.nearestFootprintTile(bossSw, dest)
            Hop(origin, dest, DreadnothArena.chebyshev(origin, dest))
        }
        val missing = count - hops.size
        val risers = if (missing > 0) {
            spawnTiles(missing, bossSw, occupied + hops.map { it.dest }, 1, random)
        } else emptyList()
        return DeathWave(hops, risers)
    }

    /** Fisher-Yates with the raid's random (same order as `Collections.shuffle(list, random)`). */
    private fun <T> shuffle(list: MutableList<T>, random: Random) {
        java.util.Collections.shuffle(list, random)
    }
}
