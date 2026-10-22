package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.player.Player

/**
 * Phase 2: portal barrages (spec §4.8 "Phase 2"; WU-27). Ocellus 16675 stands untargetable at the centre (HUD
 * form, `active=no` [CACHE]); The Blade hitbox 16676 is spawned on the same 3x3 and is attackable (contribution
 * counts). Three barrages run in SW, NW, NE (`p2.quadrants`, from the guides' "meet at the southwest slice"
 * [COMM]); after the third the party stands in SE (P3 quadrant 1). If the Blade reaches 0, the remaining barrages
 * are skipped [HOUSE `bladeSkipsBarrages`] and its discharge 4305 plays [INF].
 *
 * The barrage table (dbtable 141 rows 17051-17053 `phase, speed, attack_column, attack_row`, values stripped
 * [CACHE]) is `p2.barrages`; with `randomize` its rows are shuffled per raid.
 *
 * The hanging Blade loc swaps to its charged idle states [INF]: 62708 (idle1, anim 14874) for the barrages,
 * 62709 (idle2, anim 14875) for the last one; [P2ToP3] drops it.
 */
internal class OcellusP2(room: OcellusRoom) : PhaseController(room, OcellusPhase.P2) {

    private val cfg = room.cfg.p2

    val plan: List<Pair<Quadrant, OcellusConfig.Barrage>> = run {
        val rows = if (cfg.randomize) cfg.barrages.shuffled(room.random) else cfg.barrages
        cfg.quadrants.mapIndexed { i, q -> Quadrant.byName(q) to rows[i % rows.size] }
    }

    var index: Int = -1
        private set
    private var barrage: PortalBarrage? = null
    private var finishing = false

    override fun begin() {
        val npc = room.boss ?: return
        npc.setFaceLocation(room.fx.loc(room.geo.centre.offset(0, -1)))
        val hitbox = BladeHitboxNpc(room, room.fx.loc(room.centreTile))
        hitbox.spawn()
        room.blade = hitbox
        room.setHangingBlade(OcellusIds.BLADE_IDLE1)
        startBarrage(0)
    }

    override fun tick() {
        barrage?.tick()
    }

    /** Starts barrage [i]; past the last one, the P2 → P3 transition. */
    fun startBarrage(i: Int) {
        barrage?.abort()
        if (i >= plan.size) {
            finish()
            return
        }
        index = i
        if (i == plan.lastIndex) room.setHangingBlade(OcellusIds.BLADE_IDLE2)
        val (q, row) = plan[i]
        val b = PortalBarrage(room, q, row, i, "$tag.barrage")
        barrage = b
        b.start { after(cfg.barrageGap) { startBarrage(i + 1) } }
    }

    override fun onBladeDestroyed(blade: BladeHitboxNpc) {
        room.fx.spotOn(blade, TfaIds.Spot.BLADE_DISCHARGE)   // 4305 `p2_sword_discharge` (anim 14909, 2 t) [INF]
        room.timeline.after(2, "blade") { if (!blade.isFinished) blade.finish() }
        if (room.blade === blade) room.blade = null
        if (cfg.bladeSkipsBarrages) finish()
    }

    override fun onBossZeroHp(): Boolean = true // the HUD form cannot be damaged; ignore

    private fun finish() {
        if (finishing) return
        finishing = true
        barrage?.abort()
        barrage = null
        room.enter(P2ToP3(room))
    }

    override fun end() {
        super.end()
        barrage?.abort()
        barrage = null
    }

    override fun onArenaEntry(player: Player) {
        room.boss?.let { Bridge.hudShown(it, listOf(player)) }
    }

    override fun debugLines(): List<String> = buildList {
        add("P2 barrage ${index + 1}/${plan.size} plan=${plan.joinToString { "${it.first}:${it.second.speed}/${it.second.col}/${it.second.row}" }}")
        barrage?.let { add(it.debugLine()) }
    }
}
