package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType

/**
 * Undead binder, npc 16670 (spec §4.4 "Binders"; wiki Undead_binder): 95 HP (not scaled), Def 225, magic defence −20,
 * stab +15 strength. Stand 14789 / walk 14790 (framemap 1288); attack 14791 (walkmerge, so it can swing while the
 * formation shuffles); death 14792.
 *
 * It never chases or retaliates: [MoiaRoom] moves it with the formation, makes it swing at a player within 2 tiles
 * every 5 ticks, and owns its death (seq 14792, removal after `binders.despawn` ticks). Damage players deal to it
 * counts toward contribution through [TfaNpc].
 */
internal class UndeadBinder(
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaNpc(context, MoiaIds.BINDER, tile, facing) {

    var listener: MoiaDeathListener? = null

    /** Ticks until it may swing again. */
    var attackCooldown: Int = 0

    /** Part of the enrage diamond (plays the empower loop). */
    var empowered: Boolean = false

    /** Room tick of the next 4223 empower-loop spot. */
    var empowerNextTick: Int = 0

    var dying: Boolean = false
        private set

    private var cachedConfig: MoiaConfig? = null

    private val cfg: MoiaConfig?
        get() = cachedConfig ?: contextOrNull?.config?.get(MoiaConfig)?.also { cachedConfig = it }

    override val traits: CombatTraits get() = cfg?.binderTraits ?: MoiaConfig.DEFAULT_BINDER_TRAITS

    override fun updateCombatDefinitions() {
        val c = cfg ?: return super.updateCombatDefinitions()
        val first = combatDefinitionsMap.isEmpty()
        val def = combatDefinitionsMap.get(id) ?: MoiaCombatDefs.build(
            id, c.binders.hp, c.binders.attackSpeed, c.binderStats, AttackType.STAB, c.binders.max,
            MoiaIds.SEQ_BINDER_DEATH, traits,
        )
        setCombatDefinitions(def)
        if (first) setHitpoints(def.hitpoints)
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits() // NPC.spawn replaced the combat definitions after TfaNpc applied the traits (ENGINE-combat §2)
        return npc
    }

    // ---- scripted by the room --------------------------------------------------------------------------------

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun checkAggressivity(): Boolean = false

    override fun autoRetaliate(source: Entity) {}

    /** The binder seq set (14789-14792) has no defend seq. */
    override fun performDefenceAnimation(attacker: Entity) {}

    override fun isCycleHealable(): Boolean = false

    override fun sendDeath() {
        if (dying) return
        dying = true
        resetWalkSteps()
        combat.removeTarget()
        optionMask = 0
        listener?.onBinderDeath(this)
    }

    override fun drop(tile: Location) {}
}
