package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.player.Player

/**
 * P1 tendrils in the world (spec §4.8 Phase 1): a tendril is a line of floor cracks from Ocellus's footprint to the
 * arena edge along one of 8 directions; it lives `p1.tendrilLife` ticks, then resolves through [TendrilLedger]
 * (blocked: 5 to each blocker; unblocked: +8 meter).
 *
 * Graphics [CACHE spot_ocellus.txt; INF placement]: the straight family 4236-4247 (N/E/S/W) and the diagonal family
 * 4248-4259 (NE/SE/SW/NW), each laid out as start 0/90/180/270, then body+end per rotation; all model 62692 with
 * framemap-2760 anims: start 14825/14826 (120 cycles = 4 t = the tendril's life), body 14818/14820 (4 t),
 * end 14819/14821 (38 cycles). One crack graphic per `p1.crackSegment` tiles of the line (the model spans ~5.5 tiles
 * [model 62692 bounds z -353..351]), on the segment's middle tile:
 * - spawn: the **start** crack (it lives exactly the tendril's 4 t);
 * - unblocked at resolve: the **body** crack (energy flowing into him, 4 t);
 * - blocked at resolve: the **end** crack (closes, 1.27 t).
 * The rotation index per direction is `p1.crackRotations` (tunable in game without a build).
 */
internal class TendrilController(private val room: OcellusRoom) {

    private val cfg = room.cfg.p1

    /** Energy meter 0..`p1.meterMax` (HUD extra bar). */
    var meter: Int = 0
        private set

    private var ledger = TendrilLedger<Player>(cfg.blockDmg, cfg.meterPerUnblocked, cfg.meterMax)

    class Tendril(val dir: Compass, val tiles: List<StaticTile>, val anchors: List<StaticTile>)

    private val active = ArrayList<Tendril>()

    val activeCount: Int get() = active.size
    val blockedInSet: Int get() = ledger.blocked
    val resolvedInSet: Int get() = ledger.resolved

    /** Spawns a tendril along [tiles]; resolves after the tendril life under [tag], then runs [then]. */
    fun spawn(dir: Compass, tiles: List<StaticTile>, tag: String, then: (() -> Unit)? = null) {
        if (tiles.isEmpty()) {
            room.timeline.after(cfg.tendrilLife, tag) { then?.invoke() }
            return
        }
        val t = Tendril(dir, tiles, segmentAnchors(tiles))
        active += t
        crack(t, Stage.START)
        room.timeline.after(cfg.tendrilLife, tag) {
            resolve(t)
            then?.invoke()
        }
    }

    private fun resolve(t: Tendril) {
        if (!active.remove(t)) return
        val boss = room.boss
        val onLine = room.targets().filter { p -> room.tileOf(p)?.let { it in t.tiles } == true }
        when (val outcome = ledger.resolve(onLine, meter)) {
            is TendrilLedger.Outcome.Blocked -> {
                for (p in onLine) room.fx.hurt(p, outcome.damageEach, HitType.DEFAULT, boss)
                crack(t, Stage.END)
            }
            is TendrilLedger.Outcome.Unblocked -> {
                setMeter(outcome.meterAfter)
                crack(t, Stage.BODY)
            }
        }
    }

    /** Ends the set: heals every blocker for the tendril damage they took (wiki: "healed after ... the last tendril"). */
    fun settle() {
        active.clear()
        for ((p, amount) in ledger.settle()) {
            if (room.isTarget(p)) room.fx.heal(p, amount)
        }
    }

    fun forget(p: Player) = ledger.forget(p)

    fun setMeter(value: Int) {
        meter = value.coerceIn(0, cfg.meterMax)
        Bridge.meterUpdate(room.viewers(), meter, cfg.meterMax)
    }

    // ---- graphics ---------------------------------------------------------------------------------------------

    private enum class Stage { START, BODY, END }

    private fun segmentAnchors(tiles: List<StaticTile>): List<StaticTile> =
        tiles.chunked(cfg.crackSegment).map { it[it.size / 2] }

    private fun crack(t: Tendril, stage: Stage) {
        val diagonal = t.dir.dx != 0 && t.dir.dy != 0
        val family = if (diagonal) TfaIds.Spot.OCELLUS_CRACK_DIAGONAL else TfaIds.Spot.OCELLUS_CRACK_STRAIGHT
        val r = (cfg.crackRotations[ArenaGeometry.key(t.dir)] ?: 0).coerceIn(0, 3)
        val spot = when (stage) {
            Stage.START -> family[r]
            Stage.BODY -> family[4 + 2 * r]
            Stage.END -> family[5 + 2 * r]
        }
        for (a in t.anchors) room.fx.spotAt(spot, a)
    }
}
