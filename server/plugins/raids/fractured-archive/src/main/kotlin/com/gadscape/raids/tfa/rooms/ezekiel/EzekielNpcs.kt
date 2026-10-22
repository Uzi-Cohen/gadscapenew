package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.ImmunityType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatType
import com.zenyte.game.world.entity.player.Player

/**
 * Combat definitions for a room npc from an [EzekielConfig.Profile]: the 2746 npcs have no server combat definition
 * file, so levels and bonuses come from `ezekiel.json` (wiki Monster Inspect / cache params). Immunities are set here
 * too, because the stock rebuild on spawn would drop the ones `TfaNpc.applyTraits` set.
 */
internal fun ezekielDefinitions(id: Int, hp: Int, attackSpeed: Int, profile: EzekielConfig.Profile, traits: CombatTraits, style: AttackType): NPCCombatDefinitions {
    val defs = NPCCombatDefinitions.clone(id, null)
    val immunities = java.util.EnumSet.noneOf(ImmunityType::class.java)
    if (traits.immunePoison) immunities += ImmunityType.POISON
    if (traits.immuneVenom) immunities += ImmunityType.VENOM
    defs.immunityTypes = immunities
    defs.hitpoints = hp
    defs.attackSpeed = attackSpeed
    val stats = StatDefinitions()
    StatType.levelTypes.forEachIndexed { i, t -> stats.set(t, profile.levels[i]) }
    listOf(StatType.ATTACK_STAB, StatType.ATTACK_SLASH, StatType.ATTACK_CRUSH, StatType.ATTACK_MAGIC, StatType.ATTACK_RANGED)
        .forEachIndexed { i, t -> stats.set(t, profile.attackBonuses[i]) }
    listOf(StatType.DEFENCE_STAB, StatType.DEFENCE_SLASH, StatType.DEFENCE_CRUSH, StatType.DEFENCE_MAGIC, StatType.DEFENCE_RANGED)
        .forEachIndexed { i, t -> stats.set(t, profile.defenceBonuses[i]) }
    listOf(StatType.MELEE_STRENGTH_BONUS, StatType.RANGED_STRENGTH_BONUS, StatType.MAGIC_STRENGTH_BONUS)
        .forEachIndexed { i, t -> stats.set(t, profile.otherBonuses[i]) }
    defs.statDefinitions = stats
    defs.setAttackStyle(style)
    return defs
}

/** Melee style of the cleave from `cleaveStyle`. */
internal fun cleaveAttackType(style: String): AttackType = when (style) {
    "stab" -> AttackType.STAB
    "crush" -> AttackType.CRUSH
    else -> AttackType.SLASH
}

/**
 * Ezekiel 16652 (spec §4.2): size 3, scaled HP from `baseHp`, earth 25% / ranged 445/370/320 / 60% defence floor through
 * the engine's live view of [traits] (WU-04), stats and bonuses from `ezekiel.json`.
 *
 * - Stock npc combat, aggression and retaliation are off: every attack and step comes from the room's timeline.
 * - `sendDeath` hands the death to the room (seq 14697 + 4179, then the death sword).
 * - Prayer multipliers are 1.0: the room computes prayer effects when each hit lands.
 */
class EzekielNpc internal constructor(
    room: EzekielRoom,
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaBossNpc(context, EzekielIds.BOSS, tile, facing) {

    /** Null only while the Java npc constructor runs (it calls overridable methods). */
    private val roomRef: EzekielRoom? = room
    internal val room: EzekielRoom get() = roomRef ?: error("room used during construction")

    override val baseHp: Int get() = roomRef?.cfg?.baseHp ?: 3000

    override val traits: CombatTraits get() = roomRef?.cfg?.traits ?: super.traits

    override fun updateCombatDefinitions() {
        val cfg = roomRef?.cfg
        if (cfg == null) {
            super.updateCombatDefinitions()
            return
        }
        setCombatDefinitions(ezekielDefinitions(id, cfg.baseHp, cfg.attackSpeed, cfg.stats.ezekiel, cfg.traits, cleaveAttackType(cfg.cleaveStyle)))
    }

    override fun processHit(hit: Hit) {
        val before = hitpoints
        super.processHit(hit)
        val after = hitpoints
        if (before != after) roomRef?.onBossHpChanged(this, before, after)
    }

    override fun canAttack(source: Player): Boolean {
        if (roomRef?.bossAttackable() == false) return false
        return super.canAttack(source)
    }

    /** HP 0: the room plays the death (spec §4.2 DYING); never the engine's death task. */
    override fun sendDeath() {
        roomRef?.onBossDeath()
    }

    override fun processNPC() {}
    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
    override fun isCycleHealable(): Boolean = false
    override fun getMeleePrayerMultiplier(): Double = 1.0
    override fun getRangedPrayerMultiplier(): Double = 1.0
    override fun getMagicPrayerMultiplier(): Double = 1.0
}

/**
 * An Animated Sword 16653 (spec §4.2 Animated Swords): its HP (80) is the empower capacity. Damage players deal fills
 * empower (and counts toward contribution through [TfaNpc]); the sword never dies: at 0 HP it is fully empowered and
 * stops taking hits. No HP bar: the room shows headbar 85 (empower, yellow) and 88 (launch, orange) instead. Turnspeed
 * 0 in the cache, so the spawn facing is its flight direction.
 */
class AnimatedSword internal constructor(
    room: EzekielRoom,
    context: TfaEncounterContext,
    val volley: Int,
    val spec: SwordSpec,
    tile: Location,
) : TfaNpc(context, EzekielIds.SWORD, tile, spec.direction) {

    private val roomRef: EzekielRoom? = room

    /** Set at the launch: no more hits. */
    var launched: Boolean = false
        internal set

    /** Tick the launch is due (for the empower bar duration). */
    var launchAt: Int = 0
        internal set

    override val traits: CombatTraits get() = roomRef?.cfg?.swordTraits ?: CombatTraits.ADD_DEFAULT

    private val capacity: Int get() = roomRef?.cfg?.swordHp ?: 80

    override fun updateCombatDefinitions() {
        val cfg = roomRef?.cfg
        if (cfg == null) {
            super.updateCombatDefinitions()
            return
        }
        setCombatDefinitions(ezekielDefinitions(id, cfg.swordHp, 4, cfg.stats.sword, cfg.swordTraits, AttackType.SLASH))
    }

    override fun spawn(): NPC {
        updateCombatDefinitions()
        setHitpoints(capacity)
        return super.spawn()
    }

    override fun getMaxHitpoints(): Int = capacity

    /** Empower fraction 0..1 (damage taken / capacity). */
    val empower: Double get() = ((capacity - hitpoints).coerceIn(0, capacity)).toDouble() / capacity

    override fun canAttack(source: Player): Boolean = !launched && super.canAttack(source)

    /** The normal HP bar is suppressed: the room shows the empower bar after each hit. */
    override fun addHitbar() {}

    override fun onHitProcessed(hit: Hit) {
        roomRef?.onSwordEmpowered(this)
    }

    /** Fully empowered: it stays (the room removes it after its flight). */
    override fun sendDeath() {}

    override fun isEntityClipped(): Boolean = false
    override fun processNPC() {}
    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
    override fun isCycleHealable(): Boolean = false
}

/**
 * A non-interactive room npc (sword fence 16654, finale sword 16655, death sword 16656; all `active=no` or op-less in
 * the cache). One HP in its own definitions so the stock rebuild on spawn does not run the engine death; it never
 * moves, fights or blocks.
 */
open class EzekielProp internal constructor(
    context: TfaEncounterContext,
    id: Int,
    tile: Location,
    facing: Direction,
) : TfaNpc(context, id, tile, facing) {

    override fun updateCombatDefinitions() {
        setCombatDefinitions(NPCCombatDefinitions.clone(id, null).also { it.hitpoints = 1 })
    }

    override fun spawn(): NPC {
        setHitpoints(1)
        val npc = super.spawn()
        setHitpoints(1)
        return npc
    }

    override fun sendDeath() {}
    override fun canAttack(source: Player): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun isEntityClipped(): Boolean = false
    override fun triggersAutoRetaliate(): Boolean = false
    override fun processNPC() {}
    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
}

/** A sword fence 16654 of the finale (variant [variant] 0..6: drop 14731 + 2i, idle 14732 + 2i). */
class SwordFence internal constructor(context: TfaEncounterContext, val variant: Int, tile: Location, facing: Direction) :
    EzekielProp(context, EzekielIds.FENCE, tile, facing)

/** A finale sword 16655 ([which]: left, right or front), with its seq/spot set. */
class FinaleSwordNpc internal constructor(context: TfaEncounterContext, val which: FinaleSword, internal val set: EzekielIds.FinaleSet, tile: Location, facing: Direction) :
    EzekielProp(context, EzekielIds.FINALE_SWORD, tile, facing) {
    /** The telegraph plays until this tick (the idle hold waits). */
    var busyUntil: Int = 0
}

/** The sword left in the floor after his death (16656, readyanim 14690 on his skeleton). */
class DeathSwordNpc internal constructor(context: TfaEncounterContext, tile: Location, facing: Direction) :
    EzekielProp(context, EzekielIds.DEATH_SWORD, tile, facing)
