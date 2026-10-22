package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/**
 * Dreadnoth's blood tiles (spec §4.5 "Blood tiles"): loc 62609 on a tile for `blood.lifetime` ticks; a player standing
 * on one at the end of a tick takes `blood.dmg` (6-8 [DOC]) typeless damage. Sources: jelly deaths, the enrage
 * splatter and every n-th boss melee. Blood never heals the boss. Template: ToB `BloodTrail` (same loc art).
 *
 * Timing: the room ticks before the players move, so the positions it sees are the ones players ended the previous
 * tick on. A tile placed on tick T therefore hurts from tick T+1 on (a player who steps off on tick T takes nothing),
 * and is removed on tick T + lifetime. Placing blood on a tile that already has some restarts its lifetime.
 *
 * The loc itself is spawned and removed through [Sink] (the room's [Bridge]), so the logic stays testable.
 */
class BloodField(private val cfg: DreadnothConfig.Blood, private val sink: Sink) {

    interface Sink {
        fun spawn(tile: StaticTile)
        fun remove(tile: StaticTile)
    }

    private class Entry(var expiresAt: Int, val activeFrom: Int)

    private val tiles = LinkedHashMap<StaticTile, Entry>()

    val size: Int get() = tiles.size

    operator fun contains(tile: StaticTile): Boolean = tile in tiles

    fun tiles(): Set<StaticTile> = tiles.keys.toSet()

    /** Places (or refreshes) blood on [tile] at tick [now]. */
    fun add(tile: StaticTile, now: Int) {
        val entry = tiles[tile]
        if (entry != null) {
            entry.expiresAt = now + cfg.lifetime
            return
        }
        tiles[tile] = Entry(now + cfg.lifetime, now + 1)
        sink.spawn(tile)
    }

    /**
     * Expires old tiles, then hurts each living [fighters] member standing on an active tile: [hit] gets the fighter
     * and the rolled damage (6-8).
     */
    fun tick(now: Int, fighters: List<Fighter>, random: Random, hit: (Fighter, Int) -> Unit) {
        val it = tiles.entries.iterator()
        while (it.hasNext()) {
            val (tile, entry) = it.next()
            if (entry.expiresAt <= now) {
                it.remove()
                sink.remove(tile)
            }
        }
        if (tiles.isEmpty()) return
        for (f in fighters) {
            if (!f.alive) continue
            val entry = tiles[f.tile] ?: continue
            if (now < entry.activeFrom) continue
            val dmg = cfg.dmg.first + if (cfg.dmg.last > cfg.dmg.first) random.nextInt(cfg.dmg.last - cfg.dmg.first + 1) else 0
            hit(f, dmg)
        }
    }

    /** Removes every tile (completion, wipe, teardown). */
    fun clear() {
        val all = tiles.keys.toList()
        tiles.clear()
        all.forEach(sink::remove)
    }
}
