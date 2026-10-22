package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.player.Player

/**
 * The miniquest "The Fractured Archive" (spec §3.1) [CACHE dbrow 17027: quest id 217, endstate 8]: varbit 16426
 * `tfa_miniquest` (varp 5920 bits 0-7; the quest list reads it). 16427 `tfa_wizard_apprentice_met` (bit 8) and
 * 16428 `tfa_juna_dialogue` (bit 9) share the varp. At value 6 the ending NPCs 16701-16703 appear (multis).
 *
 * Values [HOUSE, spec §3.1]: 1 message received / first entry attempt, 2 entered the lobby, 3 first raid entered,
 * 6 first Ocellus kill, 8 "Finishing up" done (endstate). Every transition only moves forward.
 *
 * Other units call [onOcellusKilled] (WU-28 / fw-core, at Ocellus's death, for every member present) and
 * [complete] (whoever writes the ending dialogue with 16701-16703).
 */
object Miniquest {

    const val NOT_STARTED = 0
    const val STARTED = 1
    const val ENTERED_LOBBY = 2
    const val FIRST_RAID = 3
    const val OCELLUS_KILLED = 6
    const val COMPLETE = TfaIds.Misc.MINIQUEST_ENDSTATE

    /** Player attribute: the Alliance's message 34657 was read (the intro dialogue branches on it). */
    const val MESSAGE_READ_ATTRIBUTE = "tfa_alliance_message_read"

    @JvmStatic
    fun stage(player: Player): Int = player.varManager.getBitValue(TfaIds.Varbit.MINIQUEST)

    /** Raises the stage to [value] if it is lower (never moves backwards). */
    @JvmStatic
    fun advance(player: Player, value: Int) {
        if (stage(player) < value) player.varManager.sendBit(TfaIds.Varbit.MINIQUEST, value)
    }

    @JvmStatic
    fun onStarted(player: Player) = advance(player, STARTED)

    @JvmStatic
    fun onLobbyEntered(player: Player) = advance(player, ENTERED_LOBBY)

    @JvmStatic
    fun onRaidEntered(player: Player) = advance(player, FIRST_RAID)

    @JvmStatic
    fun onOcellusKilled(player: Player) = advance(player, OCELLUS_KILLED)

    @JvmStatic
    fun complete(player: Player) = advance(player, COMPLETE)

    @JvmStatic
    fun hasReadMessage(player: Player): Boolean = player.getBooleanAttribute(MESSAGE_READ_ATTRIBUTE)

    /** Varbit 16427, set by the wizard apprentice's first dialogue. */
    @JvmStatic
    fun setWizardApprenticeMet(player: Player) {
        player.varManager.sendBit(TfaIds.Varbit.WIZARD_APPRENTICE_MET, 1)
    }

    @JvmStatic
    fun hasMetWizardApprentice(player: Player): Boolean = player.varManager.getBitValue(TfaIds.Varbit.WIZARD_APPRENTICE_MET) == 1
}
