package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player

/**
 * The Smorks, npc 16641 (spec §4.3): goblins on a cannon cart, static in the middle of the arena (their walk anim is
 * the idle, turnspeed 12). A [TfaBossNpc] with scaled HP (`smorks.baseHp` 600 → §2.6 table) and the §4.3 traits
 * (earth 85%, ranged 80/150/80, flat armour 2, **no** defence floor, freeze/poison/venom/cannon immune).
 *
 * The engine's combat loop is off: [BorkSmorksBrain] fires every volley and cannonball. Their hits keep landing after
 * they die (volleys and cannonballs already in flight), so [markApplyDamageFromHitsAfterDeath] is set. Protection
 * prayers block their volleys fully (the stock NPC prayer multipliers are 0).
 */
internal class Smorks(
    room: BorkSmorksRoom,
    context: TfaEncounterContext,
    tile: Location,
    facing: Direction,
) : TfaBossNpc(context, BorkIds.SMORKS, tile, facing) {

    private val roomRef: BorkSmorksRoom? = room

    var targetable: Boolean = true
        private set

    init {
        maxDistance = 64
        radius = 0
        markApplyDamageFromHitsAfterDeath()
    }

    override val baseHp: Int get() = roomRef?.config?.smorks?.baseHp ?: DEFAULT_BASE_HP

    override val traits: CombatTraits get() = roomRef?.config?.smorks?.traits ?: BorkSmorksConfig.SMORKS_TRAITS

    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        val defs = BorkCombatDefs.smorks(room.config)
        if (scaledMaxHp > 0) defs.hitpoints = scaledMaxHp
        combatDefinitions = defs
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits()
        return npc
    }

    fun setTargetableState(value: Boolean) {
        targetable = value
        isCantInteract = !value
    }

    // ------------------------------------------------------------------------------ engine combat switched off

    override fun processNPC() {}

    override fun afterMovement() {}

    override fun autoRetaliate(source: Entity) {}

    override fun checkAggressivity(): Boolean = false

    /** Static on the cart: never takes a walk step (knock-backs, follow). */
    override fun addWalkStep(nextX: Int, nextY: Int, lastX: Int, lastY: Int, check: Boolean): Boolean = false

    override fun canAttack(source: Player): Boolean = targetable && !isDead && super.canAttack(source)

    override fun isAttackableNPC(): Boolean = targetable && super.isAttackableNPC()

    override fun isCycleHealable(): Boolean = false

    /** HOUSE: +60% like Bork (the wiki has no value for the Smorks). */
    override fun getXpModifier(hit: Hit): Float = (roomRef?.config?.smorks?.xpModifier ?: 1.0).toFloat()

    override fun onHitProcessed(hit: Hit) {
        super.onHitProcessed(hit)
        roomRef?.onNpcHit()
    }

    override fun sendDeath() {
        onDeath(mostDamagePlayer)
        setTargetableState(false)
        roomRef?.onSmorksDeath()
    }

    private companion object {
        const val DEFAULT_BASE_HP = 600
    }
}
