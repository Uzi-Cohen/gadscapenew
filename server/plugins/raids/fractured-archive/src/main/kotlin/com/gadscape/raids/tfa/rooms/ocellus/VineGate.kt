package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject

/**
 * The vines entrance between the bridge and the arena (spec §3.6 / §4.8 "Vine gate" [CACHE locs; INF flow]).
 *
 * Map: 62951 `entrance02_open` (type 22, rot 0) at (4127,5660) on top of the static 3x11 floor piece 62950
 * (type 10 at (4126,5660)) [CACHE r16472_locs]. At fight start 62951 → 62953 `closing` (default anim 15028,
 * 60 cycles = 2 t) → 62952 `closed` (anim 15029); after the kill the reverse (62951, default anim 15027).
 *
 * Blocking: the spec names 62949 `entrance_gate` (3 wide, blockwalk) as the blocker, but its only anchor on the
 * entrance row is (4126,5660), the tile of the 3x11 floor piece 62950 (same type-10 slot): spawning it there
 * would delete the arm's floor. The three entrance tiles x 4126-4128, y 5660 are closed with plain collision
 * instead [HOUSE], under the vine visuals.
 *
 * Members still in the corridor when it closes are moved to the spill tile (`tfa.json tiles.ocellusGateSpill`,
 * (4127,5661) [HOUSE]).
 */
internal class VineGate(private val room: OcellusRoom) {

    var closed: Boolean = false
        private set

    private var visual: WorldObject? = null

    private val decoTile: StaticTile get() = room.onPlane(StaticTile(4127, 5660))
    private val blockTiles: List<StaticTile> get() = (4126..4128).map { room.onPlane(StaticTile(it, 5660)) }

    /** Corridor and bridge (x 4124-4130, y 5632-5659) plus the entrance row, on the arena plane [CACHE arena_map]. */
    fun inCorridor(t: StaticTile): Boolean = t.plane == room.geo.plane && t.x in 4124..4130 && t.y in 5632..5660

    fun close() {
        if (closed) return
        closed = true
        for (p in room.context.members) spill(p)
        val fx = room.fx
        val closing = fx.spawnLoc(TfaIds.Loc.OCELLUS_VINE_ENTRANCE_CLOSING, 22, 0, decoTile) // replaces 62951
        fx.locAnim(closing, TfaIds.Seq.OCELLUS_VINE_GATE_CLOSING)                             // 15028, 2 t
        visual = closing
        blockTiles.forEach(fx::block)
        // 62953's anim is 60 cycles = 2 t [CACHE seq 15028]; not phase-bound (tag "gate").
        room.timeline.after(2, "gate") {
            val v = visual ?: return@after
            if (!closed) return@after
            val shut = fx.swapLoc(v, TfaIds.Loc.OCELLUS_VINE_ENTRANCE_CLOSED)
            fx.locAnim(shut, TfaIds.Seq.OCELLUS_VINE_GATE_CLOSED)                                // 15029
            visual = shut
        }
    }

    /** Moves [p] into the arena if the raid has them in the corridor (late arrival or straggler). */
    fun spill(p: Player) {
        if (!closed || p.isDead || p.isFinished) return
        val t = room.fx.static(p.location) ?: return
        if (!inCorridor(t)) return
        val spill = room.context.config.raid.tiles.ocellusGateSpill
        room.fx.teleport(p, room.onPlane(spill))
    }

    /** Reverse after the kill (and on teardown): the open entrance 62951 with its anim 15027, collision removed. */
    fun open() {
        if (!closed) return
        closed = false
        val fx = room.fx
        blockTiles.forEach(fx::unblock)
        val v = visual
        visual = null
        if (v != null) {
            val open = fx.swapLoc(v, TfaIds.Loc.OCELLUS_VINE_ENTRANCE_OPEN)
            fx.locAnim(open, TfaIds.Seq.OCELLUS_VINE_GATE_OPEN)
            fx.release(open) // the map's own loc again; teardown leaves it
        }
    }
}
