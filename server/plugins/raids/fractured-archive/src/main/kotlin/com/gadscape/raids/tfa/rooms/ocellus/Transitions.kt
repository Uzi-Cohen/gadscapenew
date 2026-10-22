package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.player.Player

/**
 * P1 → P2 (spec §4.8 "Phase flow": "HP 0 → P1→P2: seq 14814 (7 t) + spot 4268, moves to the centre, transforms
 * to 16675 (seq 14837, 6 t)"): 13 t.
 * - 14814 `npc_ocellus_phase1_transformation` (210 cycles = 7 t, skeleton 2742 = 16674's) + spot 4268
 *   `vfx_ocellus_p1_to_p2_transformation` (anim 14838, 3 t) where he fell [CACHE];
 * - then he moves to the centre (4126,5672) and becomes 16675 with the P3 pool (5250 base, scaled); 14837
 *   `npc_ocellus_phase2_transformation` (180 cycles = 6 t) is on 16675's skeleton 2743 [CACHE]. The move shows the
 *   P1 teleport spots (4266 where he stood, 4267 on him) [INF];
 * - HUD switches to 16675 and the extra bar (meter) is gone (P1's end).
 * Protect from Melee is dropped at the transform (`p3InheritP1Pfm = false`, spec [C+]).
 */
internal class P1ToP2(room: OcellusRoom) : PhaseController(room, OcellusPhase.P1_TO_P2) {

    override fun begin() {
        val npc = room.boss ?: return
        val t = room.cfg.transitions
        npc.resetWalkSteps()
        npc.protectFromMelee = false
        Bridge.clearOverheads(npc)
        room.fx.anim(npc, TfaIds.Seq.OCELLUS_P1_TRANSFORM)
        room.fx.spotOn(npc, TfaIds.Spot.OCELLUS_P1_TO_P2)
        after(t.p1ToP2) {
            val n = room.boss ?: return@after
            room.fx.static(n.location)?.let { room.fx.spotAt(TfaIds.Spot.OCELLUS_P1_TELEPORT_OUT, it.offset(1, 1)) }
            room.fx.teleport(n, room.centreTile)
            n.transform(TfaIds.Npc.OCELLUS_P2, newPool = room.cfg.hp.p3)
            room.fx.anim(n, TfaIds.Seq.OCELLUS_P2_TRANSFORM)
            room.fx.spotOn(n, TfaIds.Spot.OCELLUS_P1_TELEPORT_IN)
            after(t.p2Form) { room.enter(OcellusP2(room)) }
        }
    }

    /** HP stays at 0 until the transform refills it: nothing to do. */
    override fun onBossZeroHp(): Boolean = true

    override fun onArenaEntry(player: Player) {
        room.boss?.let { Bridge.hudShown(it, listOf(player)) }
    }
}

/**
 * P2 → P3 (spec §4.8: "Blade drop (spot 4280 + 4388, 8 t; loc 62706 removed), Ocellus 14878 (8 t) + spots
 * 4282/4283, then 14881 (5 t): 21 t; transform to 16677; music 894; the cross collapses"):
 * - t 0: the Blade hitbox goes; repair particles 4304 (anim 14908) where it hung [INF]; the hanging Blade loc is
 *   removed and drops as spot 4280 (anim 14876, 240 cycles = 8 t) with its explosion 4388 (a timed variant of 4389
 *   `_instant`, so it is sent with the drop) [CACHE];
 * - t 8: 16675 plays 14878 `p2_to_p3_part1` (8 t, skeleton 2743 = 16675's); the arm spots 4282 (on him) and
 *   4283 (arm glow, as a map graphic on his centre: one spotanim slot per NPC) at +`armSpotDelay` (its sounds are at
 *   4.90 / 5.53 t) [INF];
 * - t 16: transform to 16677 (pool kept), 14881 `p2_to_p3_part2` (5 t, skeleton 2744 = 16677's), combat level
 *   1601 [DOC], music 894 "Ascension";
 * - t 21: P3.
 * The cross collapse is WU-28's P3 opening.
 */
internal class P2ToP3(room: OcellusRoom) : PhaseController(room, OcellusPhase.P2_TO_P3) {

    override fun begin() {
        val t = room.cfg.transitions
        room.blade?.let { if (!it.isFinished) it.finish() }
        room.blade = null
        val bladeTile = room.bladeTile
        room.fx.spotAt(TfaIds.Spot.BLADE_REPAIR, bladeTile)
        room.setHangingBlade(null)
        room.fx.spotAt(TfaIds.Spot.BLADE_DROP, bladeTile)
        room.fx.spotAt(TfaIds.Spot.BLADE_DROP_EXPLOSION, bladeTile)
        after(t.bladeDrop) {
            val npc = room.boss ?: return@after
            room.fx.anim(npc, TfaIds.Seq.OCELLUS_P2_TO_P3_PART1)
            after(t.armSpotDelay) {
                room.boss?.let { n ->
                    room.fx.spotOn(n, TfaIds.Spot.OCELLUS_P2_TO_P3_ARM)
                    room.fx.static(n.location)?.let { room.fx.spotAt(TfaIds.Spot.OCELLUS_P2_TO_P3_ARM_GLOW, it.offset(1, 1)) }
                }
            }
            after(t.p2ToP3Part1) {
                val n = room.boss ?: return@after
                n.transform(TfaIds.Npc.OCELLUS_P3)
                Bridge.setCombatLevel(n, room.cfg.combatLevel.p3 ?: room.context.scaling.combatLevel("ocellus_p3"))
                room.fx.anim(n, TfaIds.Seq.OCELLUS_P2_TO_P3_PART2)
                Bridge.playMusic(room.viewers(), Bridge.TRACK_P3, TfaIds.Midi.OCELLUS_P3, TfaIds.Dbrow.MUSIC_OCELLUS_P3)
                after(t.p2ToP3Part2) { room.enter(room.createP3()) }
            }
        }
    }

    override fun onBossZeroHp(): Boolean = true

    override fun onArenaEntry(player: Player) {
        room.boss?.let { Bridge.hudShown(it, listOf(player)) }
    }
}

/**
 * Death (spec §4.8 P4 → "HP 0 → death 14872 (15 t) + spots 4274/4275/4276 → The Blade 16683 on his last 3x3
 * (centre if blocked)"): 14872 `npc_ocellus_phase3_death` (440 cycles = 14.67 t, skeleton 2744 = 16677's) with
 * 4274 on him and the skirt 4275 / crack 4276 as map graphics on his centre (all 14.67 t) [CACHE]. Then he is
 * removed and The Blade 16683 (stand 14946) stands on his SW tile; the room is complete.
 */
internal class OcellusDeath(room: OcellusRoom) : PhaseController(room, OcellusPhase.DYING) {

    override fun begin() {
        val npc = room.boss ?: return
        npc.resetWalkSteps()
        Bridge.clearOverheads(npc)
        npc.protectFromMelee = false
        npc.damageTakenMultiplier = 1.0
        room.fx.anim(npc, TfaIds.Seq.OCELLUS_DEATH)
        room.fx.spotOn(npc, TfaIds.Spot.OCELLUS_DEATH)
        room.fx.static(npc.location)?.let { sw ->
            room.fx.spotAt(TfaIds.Spot.OCELLUS_DEATH_SKIRT, sw.offset(1, 1))
            room.fx.spotAt(TfaIds.Spot.OCELLUS_DEATH_CRACK, sw.offset(1, 1))
        }
        after(room.cfg.death.ticks) {
            val n = room.boss ?: return@after
            val sw = room.fx.static(n.location) ?: room.centreTile
            Bridge.hudHidden(n, room.viewers())
            n.finish()
            room.boss = null
            val blade = TheBladeNpc(room, room.fx.loc(sw))
            blade.spawn()
            room.theBlade = blade
            room.markDead()
        }
    }

    override fun onBossZeroHp(): Boolean = true
}
