package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.NpcCombatTraits
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.npc.combatdefs.ImmunityType
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatDefinitions
import com.zenyte.game.world.entity.npc.combatdefs.StatType
import com.zenyte.game.world.entity.player.Player
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Builds combat definitions for a raid npc from a [GuardianConfig.Profile]: the 2746 npcs have no server combat
 * definition file, so levels and bonuses come from the boss JSON (wiki Monster Inspect / cache params).
 * [keepLevels] carries the current (possibly drained) levels over a form change.
 */
internal fun buildDefinitions(
    id: Int, hp: Int, attackSpeed: Int, profile: GuardianConfig.Profile, keepLevels: IntArray?, traits: CombatTraits,
): NPCCombatDefinitions {
    val defs = NPCCombatDefinitions.clone(id, null)
    // TfaNpc.applyTraits sets immunities before NPC.spawn rebuilds the definitions, so they are set here as well.
    val immunities = java.util.EnumSet.noneOf(ImmunityType::class.java)
    if (traits.immunePoison) immunities += ImmunityType.POISON
    if (traits.immuneVenom) immunities += ImmunityType.VENOM
    defs.immunityTypes = immunities
    defs.hitpoints = hp
    defs.attackSpeed = attackSpeed
    val stats = StatDefinitions()
    val levels = keepLevels ?: profile.levels.toIntArray()
    StatType.levelTypes.forEachIndexed { i, t -> stats.set(t, levels[i]) }
    listOf(StatType.ATTACK_STAB, StatType.ATTACK_SLASH, StatType.ATTACK_CRUSH, StatType.ATTACK_MAGIC, StatType.ATTACK_RANGED)
        .forEachIndexed { i, t -> stats.set(t, profile.attackBonuses[i]) }
    listOf(StatType.DEFENCE_STAB, StatType.DEFENCE_SLASH, StatType.DEFENCE_CRUSH, StatType.DEFENCE_MAGIC, StatType.DEFENCE_RANGED)
        .forEachIndexed { i, t -> stats.set(t, profile.defenceBonuses[i]) }
    listOf(StatType.MELEE_STRENGTH_BONUS, StatType.RANGED_STRENGTH_BONUS, StatType.MAGIC_STRENGTH_BONUS)
        .forEachIndexed { i, t -> stats.set(t, profile.otherBonuses[i]) }
    defs.statDefinitions = stats
    defs.setAttackStyle(AttackType.RANGED)
    return defs
}

/**
 * The Inert Guardian 16657 (§4.1 DORMANT/WAKING): op1 Touch, stand 14757 (its cache readyanim), spot 4207 replayed
 * every 6 t while dormant (the spot's seq 14767 plays once). On the wake it plays 14755 + 4206; after 5 t the room
 * replaces it with the boss npc on the same tiles.
 */
class InertGuardianNpc internal constructor(
    room: ArchiveGuardianRoom,
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, TfaIds.Npc.GUARDIAN_INERT, tile, Direction.SOUTH) {

    /** Null only while the Java npc constructor runs. */
    private val roomRef: ArchiveGuardianRoom? = room
    internal val room: ArchiveGuardianRoom get() = roomRef ?: error("room used during construction")

    private var idleClock = 0

    /** False once the Touch woke it (the idle spot stops). */
    internal var dormant = true

    override fun spawn(): NPC {
        val npc = super.spawn()
        setGraphics(GuardianAnims.INERT_IDLE_GFX)
        idleClock = 0
        return npc
    }

    override fun processNPC() {
        if (!dormant) return
        if (++idleClock >= GuardianAnims.INERT_SPOT_LOOP) {
            idleClock = 0
            setGraphics(GuardianAnims.INERT_IDLE_GFX)
        }
    }

    internal fun wake() {
        dormant = false
        setAnimation(GuardianAnims.WAKE)
        setGraphics(GuardianAnims.WAKE_GFX)
    }

    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
}

/**
 * The Archive Guardian boss npc: 16659 (flying, the HP is the shield), 16660 (grounded or enraged, the HP is the main
 * pool), 16658 (dead). Its HP pool, traits and HUD id follow [GuardianFight] through [ArchiveGuardianRoom].
 *
 * - Default npc combat, aggression and retaliation are off: every attack comes from the room's timeline.
 * - `sendDeath` does nothing: an empty shield or main pool is a state change ([GuardianFight.onPoolHpChanged]).
 * - Prayer multipliers are 1.0: the room computes prayer effects when each hit lands.
 */
class ArchiveGuardianNpc internal constructor(
    room: ArchiveGuardianRoom,
    context: TfaEncounterContext,
    tile: Location,
) : TfaBossNpc(context, TfaIds.Npc.GUARDIAN_FLYING, tile, Direction.SOUTH) {

    /** Null only while the Java npc constructor runs (it calls overridable methods). */
    private val roomRef: ArchiveGuardianRoom? = room
    internal val room: ArchiveGuardianRoom get() = roomRef ?: error("room used during construction")

    /** The form the room put the npc in (selects traits and the combat profile). */
    internal var form: Form = Form.FLYING
        private set

    enum class Form { FLYING, GROUNDED, ENRAGED, DEAD }

    /** A queued battery discharge: the form it was meant for and the HP floor it may not pass (enrage kill range). */
    private class Discharge(val form: Form, val floor: Int)

    /** Discharge hits waiting to land. */
    private val discharges: MutableMap<Hit, Discharge> = Collections.synchronizedMap(IdentityHashMap())

    override val baseHp: Int get() = room.cfg.shieldHp

    override val traits: CombatTraits
        get() {
            val r = roomOrNull() ?: return super.traits
            return when (form) {
                Form.FLYING, Form.DEAD -> r.cfg.traits.flying
                Form.GROUNDED -> r.cfg.traits.grounded
                Form.ENRAGED -> r.cfg.traits.enraged
            }
        }

    private fun roomOrNull(): ArchiveGuardianRoom? = roomRef

    override fun updateCombatDefinitions() {
        val r = roomOrNull()
        if (r == null) {
            super.updateCombatDefinitions()
            return
        }
        val profile = if (form == Form.GROUNDED) r.cfg.stats.grounded else r.cfg.stats.flying
        val current = combatDefinitions?.statDefinitions?.combatStats?.clone()
        val keep = if (current != null && current.any { it > 0 }) current else null
        val hp = if (form == Form.GROUNDED || form == Form.ENRAGED) r.cfg.mainHp else r.cfg.shieldHp
        setCombatDefinitions(buildDefinitions(id, hp, r.cfg.attackSpeed, profile, keep, traits))
    }

    /** Puts the npc in [next] form as npc [npcId]; reloads definitions and traits. */
    internal fun becomeForm(next: Form, npcId: Int) {
        form = next
        if (id != npcId) setTransformation(npcId) else updateCombatDefinitions()
        applyTraits()
    }

    /** Sets the pool HP without notifying the fight (form changes, debug). */
    internal fun setPool(hp: Int, base: Int) {
        applyScaling(base, fill = false)
        setHitpoints(hp.coerceAtLeast(0))
    }

    internal fun queueDischarge(amount: Int, floor: Int) {
        if (amount <= 0) return
        val hit = Hit(amount, com.zenyte.game.world.entity.masks.HitType.TYPELESS)
        discharges[hit] = Discharge(form, floor)
        applyHit(hit)
    }

    override fun processHit(hit: Hit) {
        val r = room
        val discharge = discharges.remove(hit)
        if (discharge != null) {
            // A shield discharge that lands after the shield broke is discarded (overflow rule, §4.1 [HOUSE]).
            hit.damage = if (discharge.form != form) 0 else GuardianRules.clampToFloor(hitpoints, hit.damage, discharge.floor)
        } else if (r.fight.isInvulnerable) {
            hit.damage = 0
        }
        val before = hitpoints
        super.processHit(hit)
        val after = hitpoints
        if (before != after) r.onBossHpChanged(this, before, after)
    }

    override fun onDamageDealt(player: Player, amount: Int, hit: Hit) {
        super.onDamageDealt(player, amount, hit)
        room.recordDamage(player, amount)
    }

    override fun onHitProcessed(hit: Hit) {
        super.onHitProcessed(hit)
        room.onBossHudChanged(this)
    }

    override fun canAttack(source: Player): Boolean {
        if (!room.fight.isTargetable) {
            source.sendMessage("The Archive Guardian can't be attacked right now.")
            return false
        }
        return super.canAttack(source)
    }

    /** An empty pool is a state change of the fight, never the engine's death. */
    override fun sendDeath() {}

    /** Ticks the death pose 16658 still stands after teardown (until the nexus re-copy shows corpse loc 62800). */
    private var lingerTicks = -1

    /** Keeps the death pose for [ticks] more ticks, then removes the npc (it counts on its own processing). */
    internal fun lingerThenFinish(ticks: Int) {
        lingerTicks = ticks
    }

    override fun processNPC() {
        if (lingerTicks >= 0 && --lingerTicks < 0 && !isFinished) finish()
    }
    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
    override fun isFreezeable(): Boolean = false
    override fun getMeleePrayerMultiplier(): Double = 1.0
    override fun getRangedPrayerMultiplier(): Double = 1.0
    override fun getMagicPrayerMultiplier(): Double = 1.0
}

/**
 * An Inert Guardian battery (§4.1): 16662 active (Attack), 16661 inactive (only the enraged laser can break it),
 * 16663 destroyed. 180 HP, not scaled. Killing it (or the laser) plays 15025 and turns it into 16663 one tick later;
 * the fight then applies its discharge.
 */
class GuardianBattery internal constructor(
    room: ArchiveGuardianRoom,
    context: TfaEncounterContext,
    val alcove: Alcove,
    tile: Location,
    active: Boolean,
) : TfaNpc(context, if (active) TfaIds.Npc.GUARDIAN_BATTERY_ACTIVE else TfaIds.Npc.GUARDIAN_BATTERY_INACTIVE, tile, Direction.SOUTH) {

    /** Null only while the Java npc constructor runs. */
    private val roomRef: ArchiveGuardianRoom? = room
    internal val room: ArchiveGuardianRoom get() = roomRef ?: error("room used during construction")

    var destroyed = false
        private set

    private var adapter: NpcCombatTraits? = null

    override val traits: CombatTraits get() = roomRef?.cfg?.traits?.battery ?: CombatTraits.ADD_DEFAULT

    override fun getCombatTraits(): NpcCombatTraits? {
        super.getCombatTraits()?.let { return it }
        if (roomRef == null) return null
        return adapter ?: Bridge.traitsAdapter({ traits }).also { adapter = it }
    }

    override fun updateCombatDefinitions() {
        val cfg = roomRef?.cfg
        if (cfg == null) {
            super.updateCombatDefinitions()
            return
        }
        setCombatDefinitions(buildDefinitions(id, cfg.battery.hp, 4, cfg.stats.battery, null, traits))
    }

    override fun spawn(): NPC {
        updateCombatDefinitions()
        setHitpoints(room.cfg.battery.hp)
        return super.spawn()
    }

    override fun getMaxHitpoints(): Int = roomRef?.cfg?.battery?.hp ?: super.getMaxHitpoints()

    val isActive: Boolean get() = !destroyed && id == TfaIds.Npc.GUARDIAN_BATTERY_ACTIVE

    override fun canAttack(source: Player): Boolean {
        if (!isActive) return false
        if (!room.batteriesAttackable()) {
            source.sendMessage("The Inert Guardian is not active yet.")
            return false
        }
        return super.canAttack(source)
    }

    /** Killed by players. */
    override fun sendDeath() {
        room.onBatteryDestroyed(this, byLaser = false)
    }

    /** Marks it destroyed and plays the despawn; the room schedules the 16663 transform. */
    internal fun markDestroyed() {
        destroyed = true
        setAnimation(GuardianAnims.BATTERY_DESPAWN)
    }

    internal fun showDestroyed() {
        setTransformation(TfaIds.Npc.GUARDIAN_BATTERY_DESTROYED)
    }

    override fun processNPC() {}
    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
}

/**
 * A Vine 16664 (tendril): not attackable, does not block movement, swipes its 3x3 three times on the tendril clock.
 * It needs 1 HP so the engine keeps its hits (hits from a 0-HP npc are dropped).
 */
class GuardianVine internal constructor(
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, TfaIds.Npc.GUARDIAN_VINE, tile, Direction.SOUTH) {

    var emerged = false

    /** One HP, set in its own definitions: the stock rebuild on spawn would set 0 and run the engine death. */
    override fun updateCombatDefinitions() {
        setCombatDefinitions(NPCCombatDefinitions.clone(id, null).also { it.hitpoints = 1 })
    }

    override fun spawn(): NPC {
        setHitpoints(1)
        val npc = super.spawn()
        setHitpoints(1)
        return npc
    }

    /** Never dies (it is not attackable); the room removes it. */
    override fun sendDeath() {}

    override fun isEntityClipped(): Boolean = false
    override fun triggersAutoRetaliate(): Boolean = false
    override fun processNPC() {}
    override fun afterMovement() {}
    override fun autoRetaliate(source: Entity) {}
    override fun performDefenceAnimation(attacker: Entity) {}
    override fun checkAggressivity(): Boolean = false
    override fun canAttack(source: Player): Boolean = false
    override fun getMeleePrayerMultiplier(): Double = 1.0
}
