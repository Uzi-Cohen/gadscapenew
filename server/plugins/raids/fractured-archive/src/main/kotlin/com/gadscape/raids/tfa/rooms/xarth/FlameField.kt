package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * Phase 2 flame hazards (spec §4.7 "Flames"; loc 62713 `tfa_xarth_arenafire`, blockwalk=no). Pure bookkeeping: the
 * locs are placed and removed through [Sink].
 *
 * - [pick] reserves tiles for flames in flight (projectile 4378 launched); [land] places them; they stay until he dies.
 * - Count at P2: `base + perTwoScale × floor(n / 2)`, then `+add` every `addEvery` ticks, capped at `cap`.
 * - [tick] (end of the tick, §2.5 step 4): every living player on a landed flame takes `dmg` typeless and, at most once
 *   per `burnEvery` ticks, one burn stack. A flame hurts from the tick after it lands.
 */
class FlameField(private val cfg: XarthConfig.Flames, private val sink: Sink) {

    interface Sink {
        fun spawn(tile: StaticTile)
        fun remove(tile: StaticTile)
    }

    /** Landed flames: tile → landing tick. */
    private val landed = LinkedHashMap<StaticTile, Int>()
    private val pending = LinkedHashSet<StaticTile>()
    private val lastBurn = HashMap<Fighter, Int>()

    /** Landed + in flight. */
    val size: Int get() = landed.size + pending.size

    val landedTiles: Set<StaticTile> get() = landed.keys

    val pendingTiles: Set<StaticTile> get() = pending

    /** The P2 opening count for party scale [scale]. */
    fun initialCount(scale: Int): Int = minOf(cfg.cap, cfg.base + cfg.perTwoScale * (scale / 2))

    /** How many more fit under the cap (for a growth step of `add`). */
    fun growth(): Int = maxOf(0, minOf(cfg.add, cfg.cap - size))

    /** Reserves up to [count] random free tiles (see [XarthArena.flameCandidates]) for flames in flight. */
    fun pick(count: Int, random: Random): List<StaticTile> {
        if (count <= 0) return emptyList()
        val room = minOf(count, cfg.cap - size)
        if (room <= 0) return emptyList()
        val candidates = XarthArena.flameCandidates(cfg.minDistance, landed.keys + pending).toMutableList()
        val out = ArrayList<StaticTile>(room)
        while (out.size < room && candidates.isNotEmpty()) {
            out += candidates.removeAt(random.nextInt(candidates.size))
        }
        pending += out
        return out
    }

    /** A flame in flight lands on [tile] at [tick]. */
    fun land(tile: StaticTile, tick: Int) {
        if (!pending.remove(tile)) return
        if (landed.containsKey(tile)) return
        landed[tile] = tick
        sink.spawn(tile)
    }

    fun isBurning(tile: StaticTile, now: Int): Boolean = landed[tile]?.let { now > it } == true

    /** End-of-tick damage: [hit] gets the fighter, the rolled damage and whether a burn stack applies. */
    fun tick(now: Int, fighters: Collection<Fighter>, random: Random, hit: (Fighter, Int, Boolean) -> Unit) {
        if (landed.isEmpty()) return
        for (f in fighters) {
            if (!f.alive || !isBurning(f.tile, now)) continue
            val dmg = cfg.dmg.first + random.nextInt(cfg.dmg.last - cfg.dmg.first + 1)
            val last = lastBurn[f]
            val burn = last == null || now - last >= cfg.burnEvery
            if (burn) lastBurn[f] = now
            hit(f, dmg, burn)
        }
    }

    /** Removes every flame (his death, teardown) and forgets the flames in flight. */
    fun clear() {
        val tiles = landed.keys.toList()
        landed.clear()
        pending.clear()
        lastBurn.clear()
        tiles.forEach(sink::remove)
    }

    fun forget(fighter: Fighter) {
        lastBurn.remove(fighter)
    }
}
