package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.game.world.entity.npc.MultiHeadIconNpc
import com.gadscape.game.world.entity.npc.NpcHeadIcons
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player

/**
 * Ocellus in all three combat forms (spec §4.8): P1 16674 → P2 16675 (HUD form, not clickable) → P3/P4 16677,
 * one NPC that transforms (live had a "boss can disappear when transitioning from phase 1 to phase 2" bug, so it is
 * one NPC there too).
 *
 * The room's phase controllers drive it: the stock NPC combat AI, aggression, retaliation and random walking are
 * off. HP: P1 has its own pool (2500 base); P2 and P3 share the 5250 pool (spec: "16675 carries the same pool").
 * When HP reaches 0 the NPC does not die by itself: [sendDeath] hands over to the room (P1 → P2 transition, or the
 * real death in P3/P4).
 */
class OcellusNpc internal constructor(
    room: OcellusRoom,
    id: Int,
    tile: Location,
) : TfaBossNpc(room.context, id, tile, Direction.SOUTH), MultiHeadIconNpc {

    private val roomRef: OcellusRoom? = room

    /** The room; null only while the `NPC` super constructor runs. */
    internal val room: OcellusRoom get() = roomRef ?: error("OcellusNpc used during construction")

    override val headIcons: NpcHeadIcons = NpcHeadIcons()

    /** Protect from Melee (P1 while walking; spec §4.8): player melee deals 0. */
    var protectFromMelee: Boolean = false
        internal set

    /** Damage taken multiplier (P4 void chain: 0.5 [DOC]). */
    var damageTakenMultiplier: Double = 1.0
        internal set

    override val baseHp: Int
        get() {
            val cfg = roomRef?.cfg ?: return 1
            return if (id == TfaIds.Npc.OCELLUS_P1) cfg.hp.p1 else cfg.hp.p3
        }

    /** `scaling.json combatLevelOverride` keys: `ocellus_p1` (absent by default, cache 1121) and `ocellus_p3` (1601). */
    override val scalingKey: String?
        get() = when (id) {
            TfaIds.Npc.OCELLUS_P1 -> "ocellus_p1"
            TfaIds.Npc.OCELLUS_P3 -> "ocellus_p3"
            else -> null
        }

    override val traits: CombatTraits
        get() = roomRef?.traitsFor(id) ?: CombatTraits.BOSS_DEFAULT

    override fun spawn(): NPC {
        val npc = super.spawn()
        // NPC.spawn() rebuilds the combat definitions (and resets HP to the definition's); re-apply ours.
        applyProfile()
        applyScaling(baseHp, fill = true)
        Bridge.hudShown(this, room.viewers())
        return npc
    }

    /** Transforms into [newId], keeping the HP pool unless [newPool] is given (a new 2-player base HP). */
    internal fun transform(newId: Int, newPool: Int? = null) {
        setTransformation(newId)
        applyProfile()
        if (newPool != null) applyScaling(newPool, fill = true)
        applyTraits()
        Bridge.hudShown(this, room.viewers())
    }

    private fun applyProfile() {
        val hp = if (scaledMaxHp > 0) scaledMaxHp else baseHp
        CombatProfile.forNpc(id).apply(this, hp)
        applyTraits()
    }

    // ---- scripted: no stock AI -----------------------------------------------------------------------------------

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    override fun isCycleHealable(): Boolean = false

    override fun setRespawnTask() {}

    override fun isAttackable(e: Entity): Boolean = id != TfaIds.Npc.OCELLUS_P2 && super.isAttackable(e)

    override fun canAttack(source: Player): Boolean = id != TfaIds.Npc.OCELLUS_P2 && super.canAttack(source)

    // ---- hits -----------------------------------------------------------------------------------------------------

    override fun handleIngoingHit(hit: Hit) {
        super.handleIngoingHit(hit)
        if (protectFromMelee && hit.hitType == HitType.MELEE && hit.source is Player) hit.damage = 0
        if (damageTakenMultiplier != 1.0 && hit.damage > 0 && hit.hitType != HitType.HEALED) {
            hit.damage = (hit.damage * damageTakenMultiplier).toInt()
        }
    }

    override fun processHit(hit: Hit) {
        val before = hitpoints
        super.processHit(hit)
        val after = hitpoints
        roomRef?.onBossHp(this, before, after)
    }

    override fun onHitProcessed(hit: Hit) {
        super.onHitProcessed(hit)
        roomRef?.let { Bridge.hudHp(this, it.viewers()) }
    }

    override fun onDamageDealt(player: Player, amount: Int, hit: Hit) {
        super.onDamageDealt(player, amount, hit) // contribution (§3.10)
        roomRef?.onBossDamaged(this, player, amount)
    }

    /** HP reached 0: the room decides (P1 → P2, or the P3/P4 death). Never the stock death. */
    override fun sendDeath() {
        val r = roomRef
        if (r == null) {
            super.sendDeath()
            return
        }
        r.onBossZeroHp(this)
    }
}

/**
 * The Blade's P2 hitbox 16676 (spec §4.8 P2): invisible model, overhead bar at height 900, 1000 base HP scaled,
 * defence drains reach 0 [DOC hotfix]. Damage counts for contribution (TfaNpc default). At 0 HP it discharges
 * (spot 4305 [INF]) and the room skips the remaining barrages (`p2.bladeSkipsBarrages`).
 */
class BladeHitboxNpc internal constructor(
    room: OcellusRoom,
    tile: Location,
) : TfaNpc(room.context, TfaIds.Npc.OCELLUS_BLADE_HITBOX, tile, Direction.SOUTH) {
    // A TfaNpc, not a TfaBossNpc: the HUD shows Ocellus 16675 in P2, never the Blade (param 509 = 0 [CACHE]).

    private val roomRef: OcellusRoom? = room

    /** Scaled max HP (`Scaling.hp(1000, n)`, spec §2.6 table: 1000 / 1427 / ... / 3564); 0 before spawn. */
    var scaledMaxHp: Int = 0
        private set

    override val traits: CombatTraits get() = roomRef?.cfg?.traits?.blade ?: CombatTraits.ADD_DEFAULT

    override fun getMaxHitpoints(): Int = if (scaledMaxHp > 0) scaledMaxHp else super.getMaxHitpoints()

    override fun spawn(): NPC {
        val npc = super.spawn()
        val r = roomRef
        if (r != null) {
            scaledMaxHp = r.context.scaling.hp(r.cfg.hp.blade, r.context.scale)
            CombatProfile.BLADE.apply(this, scaledMaxHp)
            setHitpoints(scaledMaxHp)
            applyTraits()
        }
        return npc
    }

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    override fun isCycleHealable(): Boolean = false

    override fun setRespawnTask() {}

    override fun sendDeath() {
        val r = roomRef
        if (r == null) {
            super.sendDeath()
            return
        }
        r.onBladeDestroyed(this)
    }
}

/**
 * The intro NPC 16671 (multi on varbit 16384: 16672 "Hooded Figure" Talk-to / 16673 "Ocellus" Talk-to + Proceed)
 * at SW (4126,5668) facing south [HOUSE]. Stand 14804 / walk 14805 from its config [CACHE]. Talk-to / Proceed are
 * handled by [OcellusIntroPlugin].
 */
class OcellusIntroNpc internal constructor(
    room: OcellusRoom,
    tile: Location,
) : TfaNpc(room.context, TfaIds.Npc.OCELLUS_INTRO, tile, Direction.SOUTH) {

    private val roomRef: OcellusRoom? = room

    internal val room: OcellusRoom? get() = roomRef

    override fun setRespawnTask() {}

    override fun checkAggressivity(): Boolean = false
}

/**
 * The Blade 16683 "Commune" / "Travel" (spec §4.8: spawned on his last 3x3 after the 15-t death; stand 14946
 * [CACHE]). Its options lead to the vault and belong to WU-29.
 */
class TheBladeNpc internal constructor(
    room: OcellusRoom,
    tile: Location,
) : TfaNpc(room.context, TfaIds.Npc.THE_BLADE, tile, Direction.SOUTH) {

    override fun setRespawnTask() {}

    override fun checkAggressivity(): Boolean = false
}
