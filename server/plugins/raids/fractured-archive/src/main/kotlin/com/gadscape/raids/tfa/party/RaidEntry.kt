package com.gadscape.raids.tfa.party

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.entry.Miniquest
import com.gadscape.raids.tfa.entry.TfaEntryGate
import com.gadscape.raids.tfa.lobby.LobbyServices
import com.gadscape.raids.tfa.raid.TfaRaids
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory

/**
 * Archive Door 62768 "Enter" (spec §3.4), up to the hand-off to the raid ([TfaRaids], fw-core):
 * - **leader, no raid yet**: every member is in the lobby, nobody holds unclaimed loot (varbit 16416) [HOUSE: refuse
 *   and say who], every member passed the entry gate, at most 8 members, Practice only while it is enabled; then
 *   [TfaRaids.create]. The raid calls back [TfaParty.onRaidCreated], which flashes "Step Inside Now!" (16386 = 2)
 *   for the members still in the lobby.
 * - **member, raid exists**: [TfaRaids.join] (late joins until the Guardian is touched, `tfa.json lateJoin`);
 *   refused → "Your party has already started." (ToB lockout) [INF].
 * - **member, no raid yet**: "Your party leader must enter the Archive first." [HOUSE].
 * - **no party**: "You are not in a party." and the Grouping Board opens.
 */
object RaidEntry {

    private val log = LoggerFactory.getLogger(RaidEntry::class.java)

    /** Why a leader's start was refused (pure, for tests and messages). */
    sealed interface StartCheck {
        data object Ok : StartCheck
        data class NotInLobby(val names: List<String>) : StartCheck
        data class UnclaimedLoot(val names: List<String>) : StartCheck
        data class GateNotPassed(val names: List<String>) : StartCheck
        data object TooMany : StartCheck
        data object PracticeDisabled : StartCheck

        val message: String?
            get() = when (this) {
                Ok -> null
                is NotInLobby -> "Every member of your party must be in the lobby to start: ${names.joinToString()}."
                is UnclaimedLoot -> "${names.joinToString()} must collect their loot from the Loot Wheelbarrow before the raid can start."
                is GateNotPassed -> "${names.joinToString()} has not yet been allowed into the Archive."
                TooMany -> "A raid party can have at most 8 members."
                PracticeDisabled -> "Practice Mode is not available yet."
            }
    }

    /** The start rules, given what is known about each member (pure). */
    fun <M> check(
        members: List<M>,
        nameOf: (M) -> String,
        inLobby: (M) -> Boolean,
        hasUnclaimedLoot: (M) -> Boolean,
        gatePassed: (M) -> Boolean,
        mode: Int,
        practiceEnabled: Boolean,
        maxMembers: Int = 8,
    ): StartCheck {
        if (members.size > maxMembers) return StartCheck.TooMany
        if (mode == 1 && !practiceEnabled) return StartCheck.PracticeDisabled
        members.filterNot(inLobby).takeIf { it.isNotEmpty() }?.let { return StartCheck.NotInLobby(it.map(nameOf)) }
        members.filter(hasUnclaimedLoot).takeIf { it.isNotEmpty() }?.let { return StartCheck.UnclaimedLoot(it.map(nameOf)) }
        members.filterNot(gatePassed).takeIf { it.isNotEmpty() }?.let { return StartCheck.GateNotPassed(it.map(nameOf)) }
        return StartCheck.Ok
    }

    /** The Archive Door "Enter". */
    @JvmStatic
    fun enter(player: Player) {
        val party = TfaPartyRegistry.partyOf(player)
        if (party == null) {
            player.sendMessage("You are not in a party.")
            PartyListInterface.open(player)
            return
        }
        if (party.raid != null) {
            join(player, party)
            return
        }
        if (party.leader != player) {
            player.sendMessage("Your party leader must enter the Archive first.")
            return
        }
        val result = check(
            members = party.members,
            nameOf = { it.name },
            inLobby = { LobbyOverlay.isInLobby(it) && TfaPartyRegistry.isOnline(it) },
            hasUnclaimedLoot = { LobbyServices.loot.hasUnclaimedLoot(it) },
            gatePassed = TfaEntryGate::isUnlocked,
            mode = party.mode,
            practiceEnabled = TfaConfig.current.raid.practice.enabled,
        )
        result.message?.let {
            player.sendMessage(it)
            return
        }
        player.interfaceHandler.closeInterfaces()
        try {
            TfaRaids.create(party)
        } catch (e: Exception) {
            log.error("Fractured Archive: creating a raid for {} failed", party, e)
            player.sendMessage("The Archive Door will not open right now. Please try again.")
            return
        }
        if (party.raid != null) Miniquest.onRaidEntered(player)
    }

    private fun join(player: Player, party: TfaParty) {
        if (!TfaRaids.join(player, party)) {
            player.sendMessage("Your party has already started.")
            return
        }
        Miniquest.onRaidEntered(player)
        LobbyOverlay.refresh(player)
    }
}
