package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * PLACEHOLDER for P3/P4 until WU-28 (spec §4.8 "Phase 3" / "Phase 4"): keeps the room playable end to end with the
 * P3 basics only. Ocellus 16677 chases the tank (the first player to hit him, then the top P3 damage dealer) and
 * attacks every 6 t [CACHE param 14]: melee 14842 (2 t, walkmerge) max 30 slash, Protect from Melee blocks; when
 * the tank is out of reach, the void shot at everyone (14851 `basic_projectile_all` [spec C+], cast 4295,
 * projectile 4297, impact 4293, 20-28 magic [DOC "20+"], Protect from Magic halves). All seqs on 16677's skeleton
 * 2744 [CACHE]. At 0 HP → [OcellusDeath].
 *
 * Not here (WU-28): arena split and stepping stones, the M M W cycle and spins, specials (vines, void adds,
 * Insolence, Untether), Obliteration at 75/50/25%, censers, P4 and its curses.
 */
internal class OcellusP3Placeholder(room: OcellusRoom) : PhaseController(room, OcellusPhase.P3) {

    private val cfg = room.cfg.p3
    private var tank: Player? = null
    private val damage = HashMap<Player, Int>()

    override fun begin() {
        val npc = room.boss ?: return
        Bridge.hudShown(npc, room.viewers())
        timeline.every(cfg.speed, 2, "$tag.attack") { attack() }
    }

    override fun tick() {
        val npc = room.boss ?: return
        val t = currentTank() ?: return
        if (npc.isFrozen) return
        if (CombatUtilities.isWithinMeleeDistance(npc, t)) {
            npc.resetWalkSteps()
            return
        }
        if (!npc.hasWalkSteps()) npc.addWalkSteps(t.x, t.y, -1, true)
    }

    override fun onBossDamaged(player: Player, amount: Int) {
        damage.merge(player, amount, Int::plus)
        if (tank == null) tank = player
    }

    override fun onPlayerDeath(player: Player) {
        if (tank === player) tank = null
        damage.remove(player)
    }

    override fun onBossZeroHp(): Boolean {
        room.enter(OcellusDeath(room))
        return true
    }

    override fun onArenaEntry(player: Player) {
        room.boss?.let { Bridge.hudShown(it, listOf(player)) }
    }

    private fun currentTank(): Player? {
        val t = tank
        if (t != null && room.isTarget(t)) return t
        val next = damage.entries.filter { room.isTarget(it.key) }.maxByOrNull { it.value }?.key
            ?: room.targets().minByOrNull { it.location.getTileDistance(room.boss!!.location) }
        tank = next
        return next
    }

    private fun attack() {
        val npc = room.boss ?: return
        val t = currentTank() ?: return
        npc.faceEntity(t)
        if (CombatUtilities.isWithinMeleeDistance(npc, t)) {
            room.fx.anim(npc, TfaIds.Seq.OCELLUS_P3_MELEE)
            val dmg = if (t.prayerManager.isActive(Prayer.PROTECT_FROM_MELEE)) 0 else {
                val accuracy = CombatUtilities.getHitAccuracy(npc, t, AttackType.SLASH)
                if (room.random.nextDouble() < accuracy) room.random.nextInt(cfg.meleeMax + 1) else 0
            }
            room.fx.hurt(t, dmg, HitType.MELEE, npc)
            return
        }
        room.fx.anim(npc, TfaIds.Seq.OCELLUS_P3_VOID_SHOT_ALL)
        room.fx.spotOn(npc, TfaIds.Spot.OCELLUS_VOID_SHOT_CAST_IN)
        for (p in room.targets()) {
            val ticks = room.fx.projectile(TfaIds.Spot.OCELLUS_VOID_SHOT_PROJ_1, npc, p, room.cfg.projectiles.p1Magic)
            val roll = cfg.voidShot.first + room.random.nextInt(cfg.voidShot.last - cfg.voidShot.first + 1)
            timeline.after(ticks, "hit") {
                if (!room.isTarget(p)) return@after
                room.fx.spotOn(p, TfaIds.Spot.OCELLUS_VOID_SHOT_IMPACT)
                val dealt = if (p.prayerManager.isActive(Prayer.PROTECT_FROM_MAGIC)) roll / 2 else roll
                room.fx.hurt(p, dealt, HitType.MAGIC, room.boss)
            }
        }
    }

    override fun debugLines(): List<String> =
        listOf("P3 (placeholder until WU-28) tank=${tank?.username} damage=${damage.entries.joinToString { "${it.key.username}=${it.value}" }}")
}
