package com.gadscape.raids.tfa.rooms.ezekiel

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What one Animated Sword does when it launches (spec §4.2 "Flight", "Dodge", "Hit on Ezekiel"), free of world state.
 *
 * - The sword exact-moves along its lane to the far edge over `flightTicks` (2 t for the full 6-tile run) and
 *   despawns.
 * - **Hit on Ezekiel** [COMM wiki Strategies, rev 15378033 / 15378206]: his SW tile lies on the sword's SW line (same y
 *   for a horizontal sword, same x for a vertical one) inside the swept range. The sword then stops with its leading
 *   edge on his middle row / column [HOUSE] and he takes the hit.
 * - **Players** in the 3-wide swept band between the sword's start and its stop are hit.
 * - **Dodge** [COMM "click away right as they begin flying (similar to Drakan's lunge)"; HOUSE rule]: a player in the
 *   path with a movement step queued on the launch tick rolls (14705; 14706 strafe when moving sideways to their
 *   facing) to the first tile outside the lane band, on the side they were stepping toward, and takes no damage.
 */
object SwordFlight {

    /** One player as the flight sees them on the launch tick. */
    data class Mover<P>(
        val who: P,
        val tile: Tile,
        /** A walk step is queued this tick. */
        val moving: Boolean,
        /** Direction of the first queued step (each -1..1). */
        val stepDx: Int = 0,
        val stepDy: Int = 0,
        /** Client facing angle (0 S, 512 W, 1024 N, 1536 E). */
        val facing: Int = 0,
    )

    data class Dodge<P>(val who: P, val from: Tile, val to: Tile, val strafe: Boolean)

    data class Result<P>(
        /** The sword's SW where the flight ends. */
        val stop: Tile,
        /** Tiles travelled along the lane. */
        val travelTiles: Int,
        /** Client cycles of the exact move (proportional to [travelTiles]; at least 1). */
        val travelCycles: Int,
        val hitsBoss: Boolean,
        /** Ticks after the launch when the sword reaches him (only meaningful with [hitsBoss]). */
        val impactTicks: Int,
        /** Ticks after the launch when the sword despawns. */
        val despawnTicks: Int,
        /** The tiles the sword sweeps. */
        val swept: Rect,
        val hit: List<P>,
        val dodges: List<Dodge<P>>,
    )

    /** Tiles a full run covers (9-tile floor minus a size-3 sword). */
    val FULL_RUN: Int get() = EzekielArena.FLOOR.width - EzekielArena.SIZE

    @JvmStatic
    fun <P> resolve(
        spec: SwordSpec,
        flightTicks: Int,
        bossSw: Tile?,
        players: List<Mover<P>>,
        dodgeEnabled: Boolean,
        /** True when a player may be moved onto the tile (floor, not under Ezekiel, not blocked). */
        free: (Tile) -> Boolean,
    ): Result<P> {
        val horizontal = spec.axis == SwordAxis.HORIZONTAL
        val step = spec.step
        val aligned = bossSw != null && if (horizontal) bossSw.y == spec.lane else bossSw.x == spec.lane
        val inRange = bossSw != null && bossSw in EzekielArena.FLOOR
        val hitsBoss = aligned && inRange
        val stop: Tile = if (!hitsBoss) spec.end else {
            val boss = bossSw!!
            if (horizontal) {
                val x = if (step > 0) (boss.x - 1).coerceIn(spec.start.x, spec.end.x) else (boss.x + 1).coerceIn(spec.end.x, spec.start.x)
                Tile(x, spec.start.y)
            } else {
                val y = if (step > 0) (boss.y - 1).coerceIn(spec.start.y, spec.end.y) else (boss.y + 1).coerceIn(spec.end.y, spec.start.y)
                Tile(spec.start.x, y)
            }
        }
        val travel = if (horizontal) abs(stop.x - spec.start.x) else abs(stop.y - spec.start.y)
        val fullCycles = flightTicks * EzekielIds.CYCLES_PER_TICK
        val cycles = if (travel == 0) 1 else (travel.toDouble() * fullCycles / FULL_RUN).roundToInt().coerceAtLeast(1)
        val impactTicks = ceil(cycles / EzekielIds.CYCLES_PER_TICK.toDouble()).toInt().coerceAtLeast(1)
        val despawnTicks = if (hitsBoss) impactTicks else flightTicks.coerceAtLeast(1)
        val swept = Rect(
            minOf(spec.start.x, stop.x), minOf(spec.start.y, stop.y),
            maxOf(spec.start.x, stop.x) + EzekielArena.SIZE - 1, maxOf(spec.start.y, stop.y) + EzekielArena.SIZE - 1,
        )
        val hit = ArrayList<P>()
        val dodges = ArrayList<Dodge<P>>()
        val claimed = HashSet<Tile>()
        for (p in players) {
            if (p.tile !in swept) continue
            if (dodgeEnabled && p.moving) {
                val to = dodgeTile(spec, p, free, claimed)
                if (to != null) {
                    claimed += to
                    dodges += Dodge(p.who, p.tile, to, isSideways(p.facing, to.x - p.tile.x, to.y - p.tile.y))
                    continue
                }
            }
            hit += p.who
        }
        return Result(stop, travel, cycles, hitsBoss, impactTicks, despawnTicks, swept, hit, dodges)
    }

    /**
     * The first tile outside the lane band, perpendicular to the flight: on the side of the player's step if it has a
     * perpendicular component, else the nearer side (south / west first on a tie); the other side if that one is
     * off the floor or not [free]. Null when neither side works.
     */
    @JvmStatic
    fun <P> dodgeTile(spec: SwordSpec, p: Mover<P>, free: (Tile) -> Boolean, claimed: Set<Tile> = emptySet()): Tile? {
        val horizontal = spec.axis == SwordAxis.HORIZONTAL
        val pos = if (horizontal) p.tile.y else p.tile.x
        val below = spec.lane - 1
        val above = spec.lane + EzekielArena.SIZE
        val stepSign = if (horizontal) p.stepDy else p.stepDx
        val order = when {
            stepSign > 0 -> listOf(above, below)
            stepSign < 0 -> listOf(below, above)
            abs(pos - below) <= abs(above - pos) -> listOf(below, above)
            else -> listOf(above, below)
        }
        for (v in order) {
            val t = if (horizontal) Tile(p.tile.x, v) else Tile(v, p.tile.y)
            if (t in EzekielArena.FLOOR && t !in claimed && free(t)) return t
        }
        return null
    }

    /** True when a move ([dx], [dy]) is sideways to [facing] (within 45 degrees of perpendicular): strafe, not roll. */
    @JvmStatic
    fun isSideways(facing: Int, dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return false
        val move = EzekielArena.angleOf(dx, dy)
        val delta = abs(((move - facing) % 2048 + 2048 + 1024) % 2048 - 1024) // 0..1024
        return delta in 384..640
    }

    /** Damage a sword deals to a player: base + round(perEmpower × e) [HOUSE `15 + 25 × e`]. */
    @JvmStatic
    fun playerDamage(base: Int, perEmpower: Int, empower: Double): Int = base + (perEmpower * empower.coerceIn(0.0, 1.0)).roundToInt()

    /** Damage a sword deals to Ezekiel: round(pct% × max HP × (1 + e)) [HOUSE `0.05 × maxHp × (1 + e)`]. */
    @JvmStatic
    fun bossDamage(maxHp: Int, pct: Int, empower: Double): Int =
        (maxHp * pct / 100.0 * (1.0 + empower.coerceIn(0.0, 1.0))).roundToInt()
}
