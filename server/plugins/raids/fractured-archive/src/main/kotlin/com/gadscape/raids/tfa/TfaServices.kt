package com.gadscape.raids.tfa

import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player

/**
 * The seams between the units of the Fractured Archive. Each property has a no-op default so every unit can be
 * built and tested before the unit that implements the service exists; the owning unit installs its
 * implementation once at server launch (`TfaServices.contribution = ContributionService`).
 *
 * | Service        | Implemented by                    | Called from                                  |
 * |----------------|-----------------------------------|----------------------------------------------|
 * | [raids]        | WU-12 raid registry               | `::tfa`, spectating, anything given a Player |
 * | [contribution] | WU-16 ContributionService         | [com.gadscape.raids.tfa.encounter.TfaNpc]    |
 * | [bossHud]      | WU-06/WU-14 BossHpHud (iface 303) | [TfaBossNpc]                                 |
 * | [combatTraits] | WU-04 engine hooks (E5-E9)        | [TfaBossNpc] / TfaNpc on spawn               |
 */
object TfaServices {

    @JvmStatic
    @Volatile
    var raids: TfaRaidDirectory = TfaRaidDirectory.NONE

    @JvmStatic
    @Volatile
    var contribution: ContributionSink = ContributionSink.NONE

    @JvmStatic
    @Volatile
    var bossHud: BossHudSink = BossHudSink.NONE

    @JvmStatic
    @Volatile
    var combatTraits: CombatTraitsBinder = CombatTraitsBinder.NONE

    /** One line per service: which implementation is installed (for `::tfa dump`). */
    @JvmStatic
    fun describe(): List<String> = listOf(
        "raids: ${name(raids, TfaRaidDirectory.NONE)}",
        "contribution: ${name(contribution, ContributionSink.NONE)}",
        "bossHud: ${name(bossHud, BossHudSink.NONE)}",
        "combatTraits: ${name(combatTraits, CombatTraitsBinder.NONE)}",
    )

    private fun name(service: Any, none: Any) =
        if (service === none) "not installed (no-op)" else service::class.qualifiedName ?: service.toString()
}

/** Finds a player's raid (WU-12). */
interface TfaRaidDirectory {

    /** The context of the encounter currently active in [player]'s raid, or null. */
    fun contextOf(player: Player): TfaEncounterContext?

    /** The encounter currently active in [player]'s raid, or null. */
    fun encounterOf(player: Player): TfaEncounter?

    /** Number of live raid instances. */
    fun activeRaidCount(): Int

    /** Lines describing [player]'s raid for `::tfa dump` (state, members, room). */
    fun describe(player: Player): List<String> = emptyList()

    companion object {
        @JvmField
        val NONE: TfaRaidDirectory = object : TfaRaidDirectory {
            override fun contextOf(player: Player): TfaEncounterContext? = null
            override fun encounterOf(player: Player): TfaEncounter? = null
            override fun activeRaidCount(): Int = 0
            override fun toString() = "TfaRaidDirectory.NONE"
        }
    }
}

/**
 * Receives every point of damage a player deals to any NPC of a room (§3.10, §4 shared rules):
 * `amount` is already capped at the target's remaining HP.
 */
fun interface ContributionSink {
    fun recordDamage(context: TfaEncounterContext, player: Player, target: NPC, amount: Int)

    companion object {
        @JvmField
        val NONE = ContributionSink { _, _, _, _ -> }
    }
}

/** Boss HP HUD (interface 303) updates; implemented with `BossHpHud` (E15). */
interface BossHudSink {
    /** The boss's HP (or its displayed pool) changed. */
    fun onHpChanged(boss: TfaBossNpc) {}

    /** The boss appeared, transformed (HUD npc id switch) or should open the HUD. */
    fun onShown(boss: TfaBossNpc) {}

    /** The boss died or left; close or zero its bar. */
    fun onHidden(boss: TfaBossNpc) {}

    companion object {
        @JvmField
        val NONE: BossHudSink = object : BossHudSink {
            override fun toString() = "BossHudSink.NONE"
        }
    }
}

/** Applies [CombatTraits] to an NPC through the engine's combat pipeline (WU-04, E5-E9). */
fun interface CombatTraitsBinder {
    fun bind(npc: NPC, traits: CombatTraits)

    companion object {
        @JvmField
        val NONE = CombatTraitsBinder { _, _ -> }
    }
}
