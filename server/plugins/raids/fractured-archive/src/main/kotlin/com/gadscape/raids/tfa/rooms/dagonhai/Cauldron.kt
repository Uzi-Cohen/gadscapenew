package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.TfaNpc
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player

/**
 * The Cauldron, npc 16649 `tfa_dagonhai_focus` (spec §4.6): size 3, op2 hidden (never attackable), HP 1000 = ritual
 * stability, and the HP HUD npc ("Ritual Stability", param 2289 shows the main bar in %; the extra bar is the ritual
 * progress). Stand 14656 (framemap 2709). It plays the un-intercepted tome attacks (14657 / 14659 / 14661), the ritual
 * completion (14678) and, after its model is swapped to 62612, the explosion 14679.
 */
internal class Cauldron(
    room: DagonhaiRoom,
    context: TfaEncounterContext,
    tile: Location,
) : TfaNpc(context, DagonhaiIds.CAULDRON, tile, Direction.SOUTH) {

    private val roomRef: DagonhaiRoom? = room

    override val traits: CombatTraits get() = roomRef?.cfg?.impTraits ?: DagonhaiConfig.DEFAULT_IMP_TRAITS

    override fun updateCombatDefinitions() {
        val room = roomRef ?: return super.updateCombatDefinitions()
        combatDefinitions = DagonhaiCombatDefs.cauldron(id, room.cfg)
    }

    override fun spawn(): NPC {
        val npc = super.spawn()
        applyTraits()
        return npc
    }

    /** Stability as the cauldron's HP (0 leaves it "dead" for the explosion; the room removes it). */
    fun setStability(value: Int) {
        if (value != hitpoints) setHitpoints(value.coerceAtLeast(0))
    }

    /** Swaps model 62603 for the exploding cauldron 62612 (the rig of seq 14679, framemap 2717). */
    fun explodeModel() {
        `override`().models(listOf(DagonhaiIds.MODEL_CAULDRON to DagonhaiIds.MODEL_CAULDRON_BROKEN))
    }

    // ------------------------------------------------------------------------- never attacked, never attacks

    override fun canAttack(source: Player): Boolean = false

    override fun isAttackableNPC(): Boolean = false

    override fun processHit(hit: Hit) {}

    override fun sendDeath() {}

    override fun processNPC() {}

    override fun checkAggressivity(): Boolean = false

    override fun autoRetaliate(source: Entity) {}

    override fun performDefenceAnimation(attacker: Entity) {}

    override fun isCycleHealable(): Boolean = false

    override fun drop(tile: Location) {}
}
