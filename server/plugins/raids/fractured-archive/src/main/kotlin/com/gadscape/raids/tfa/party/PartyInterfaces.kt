package com.gadscape.raids.tfa.party

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.stats.TfaStats
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.model.ui.UserInterface
import com.zenyte.game.util.AccessMask
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants

/**
 * Who is looking at which Grouping Board page, so a change can be pushed to exactly those players.
 * An entry is dropped as soon as its interface is found closed.
 */
internal object PartyViewers {

    sealed interface View

    /** 977 open; [rows] are the party ids of rows 0..44 as last sent (null = empty row). */
    class ListView(val rows: Array<Int?>) : View

    /** 976 open on [partyId], [tab] 0 Members / 1 Applicants. */
    class DetailsView(val partyId: Int, var tab: Int) : View

    private val views = HashMap<Player, View>()

    operator fun get(player: Player): View? = views[player]

    operator fun set(player: Player, view: View) {
        views[player] = view
    }

    fun forget(player: Player) {
        views.remove(player)
    }

    /** Re-sends 976 to the viewers of [party] and the rows to every open 977. */
    fun pushAll(party: TfaParty?) {
        val it = views.entries.iterator()
        val details = ArrayList<Pair<Player, DetailsView>>()
        val lists = ArrayList<Player>()
        while (it.hasNext()) {
            val (player, view) = it.next()
            when (view) {
                is ListView -> if (player.interfaceHandler.isVisible(TfaIds.Interface.PARTY_LIST)) lists += player else it.remove()
                is DetailsView -> if (!player.interfaceHandler.isVisible(TfaIds.Interface.PARTY_DETAILS)) it.remove()
                    else if (party != null && view.partyId == party.id) details += player to view
            }
        }
        lists.forEach(PartyListInterface::refresh)
        details.forEach { (player, view) -> PartyDetailsInterface.open(player, party!!, view.tab) }
    }

    /** [partyId] was removed: anyone looking at it goes back to the list. */
    fun partyGone(partyId: Int) {
        val viewers = views.filter { (p, v) ->
            v is DetailsView && v.partyId == partyId && p.interfaceHandler.isVisible(TfaIds.Interface.PARTY_DETAILS)
        }.keys.toList()
        viewers.forEach(PartyListInterface::open)
    }
}

/**
 * Interface 977 `tfa_partylist` (spec §3.3) [CACHE scripts 10087-10101]: opened as the main modal (onload 10087 shows
 * "Loading..."), then RUNCLIENTSCRIPT 10094(index, row) for **all 45** indexes, "" for empty ones (the client lays
 * the list out when index 44 arrives). Sorting and the friends/clan filter are client-side.
 */
object PartyListInterface {

    @JvmStatic
    fun open(player: Player) {
        val iface = TfaIds.Interface.PARTY_LIST
        player.interfaceHandler.sendInterface(InterfacePosition.CENTRAL, iface)
        // Dynamic pause buttons (cc_resume_pausebutton) need the "continue" event flag, like ToA's 774:1.
        player.packetDispatcher.sendComponentSettings(iface, TfaIds.Component.PARTY_LIST_BUTTONS, 0, ListButton.entries.size - 1, AccessMask.CONTINUE)
        player.packetDispatcher.sendComponentSettings(iface, TfaIds.Component.PARTY_LIST_ROWS, 0, TfaIds.Misc.MAX_PARTIES_LISTED - 1, AccessMask.CONTINUE)
        sendRows(player)
    }

    /** Reopens 977 (the onload resets the client's row counter) and resends every row. */
    @JvmStatic
    fun refresh(player: Player) = open(player)

    private fun sendRows(player: Player) {
        val parties = TfaPartyRegistry.all()
        val slots = TfaIds.Misc.MAX_PARTIES_LISTED
        val rows = arrayOfNulls<Int>(slots)
        parties.take(slots).forEachIndexed { i, p -> rows[i] = p.id }
        val now = TfaPartyRegistry.currentTick()
        PartyRowCodec.boardRows(parties, slots) { party ->
            PartyRowCodec.listRow(
                names = party.members.map { it.name },
                viewerInParty = party.isMember(player),
                preferredSize = party.preferredSize,
                preferredCompletions = party.preferredCompletions,
                mode = party.mode,
                ageTicks = now - party.creationTick,
            )
        }.forEachIndexed { index, row -> player.packetDispatcher.sendClientScript(TfaIds.Script.PARTY_LIST_ROW, index, row) }
        PartyViewers[player] = PartyViewers.ListView(rows)
    }

    internal fun click(player: Player, componentId: Int, sub: Int) {
        when (componentId) {
            TfaIds.Component.PARTY_LIST_BUTTONS -> when (ListButton.of(sub)) {
                ListButton.REFRESH -> if (TfaPartyRegistry.refreshLimiter.tryAcquire(player, TfaPartyRegistry.currentTick())) refresh(player)
                ListButton.MY_PARTY -> {
                    val own = TfaPartyRegistry.partyOf(player) ?: TfaPartyRegistry.create(player)
                    if (own != null) PartyDetailsInterface.open(player, own, 0)
                }
                ListButton.FILTER -> {
                    // varbit 16380 (varp 5907 bit 0): the client filters rows as they arrive, so flip it first, then resend.
                    val value = player.varManager.getBitValue(TfaIds.Varbit.FRIENDS_FILTER)
                    player.varManager.sendBitInstant(TfaIds.Varbit.FRIENDS_FILTER, if (value == 0) 1 else 0)
                    refresh(player)
                }
                null -> Unit
            }
            TfaIds.Component.PARTY_LIST_ROWS -> {
                val view = PartyViewers[player] as? PartyViewers.ListView ?: return
                val id = view.rows.getOrNull(sub) ?: return
                val party = TfaPartyRegistry.byId(id)
                if (party == null) {
                    player.sendMessage("That party is no longer recruiting.")
                    refresh(player)
                    return
                }
                PartyDetailsInterface.open(player, party, 0)
            }
        }
    }
}

/**
 * Interface 976 `tfa_partydetails` (spec §3.3) [CACHE scripts 10049-10086]: after the onload, 8 × 10053(role, row)
 * (one per slot, "" when empty), N × 10058(row) for the applicants, then 10060(role, completions, mode, size,
 * spectatingDisabled, tab). Every refresh reopens the interface, because 10053 appends rows to a counter only the
 * onload resets.
 */
object PartyDetailsInterface {

    @JvmStatic
    fun open(player: Player, party: TfaParty, tab: Int) {
        val iface = TfaIds.Interface.PARTY_DETAILS
        player.interfaceHandler.sendInterface(InterfacePosition.CENTRAL, iface)
        player.packetDispatcher.sendComponentSettings(iface, TfaIds.Component.PARTY_DETAILS_BUTTONS, 0, DetailsButton.LAST_SUB, AccessMask.CONTINUE)
        val role = ViewerRole.of(TfaPartyRegistry.book, party.state, player)
        val members = party.members
        for (slot in 0 until 8) {
            val row = members.getOrNull(slot)?.let { PartyRowCodec.memberRow(memberInfo(it)) } ?: ""
            player.packetDispatcher.sendClientScript(TfaIds.Script.PARTY_MEMBER_ROW, role, row)
        }
        for (applicant in party.applicants) {
            player.packetDispatcher.sendClientScript(TfaIds.Script.PARTY_APPLICANT_ROW, PartyRowCodec.memberRow(memberInfo(applicant)))
        }
        player.packetDispatcher.sendClientScript(
            TfaIds.Script.PARTY_DETAILS_SETTINGS,
            role, party.preferredCompletions, party.mode, party.preferredSize, party.spectatingDisabled, tab.coerceIn(0, 1),
        )
        PartyViewers[player] = PartyViewers.DetailsView(party.id, tab.coerceIn(0, 1))
    }

    /** What 976 shows for [player]: base levels, combat, KC (varp 5894) and practice completions. */
    fun memberInfo(player: Player): PartyRowCodec.MemberInfo {
        val skills = player.skills
        return PartyRowCodec.MemberInfo(
            name = player.name,
            combat = skills.combatLevel,
            attack = skills.getLevelForXp(SkillConstants.ATTACK),
            strength = skills.getLevelForXp(SkillConstants.STRENGTH),
            ranged = skills.getLevelForXp(SkillConstants.RANGED),
            magic = skills.getLevelForXp(SkillConstants.MAGIC),
            defence = skills.getLevelForXp(SkillConstants.DEFENCE),
            hitpoints = skills.getLevelForXp(SkillConstants.HITPOINTS),
            prayer = skills.getLevelForXp(SkillConstants.PRAYER),
            completions = TfaStats.killCount(player),
            practiceCompletions = TfaStats.practiceCompletions(player),
        )
    }

    internal fun click(player: Player, sub: Int) {
        val view = PartyViewers[player] as? PartyViewers.DetailsView ?: return
        val party = TfaPartyRegistry.byId(view.partyId)
        val button = DetailsButton.of(sub) ?: return
        if (party == null) {
            player.sendMessage("That party is no longer recruiting.")
            PartyListInterface.open(player)
            return
        }
        val leader = party.leader == player
        when (button) {
            DetailsButton.Back -> PartyListInterface.open(player)
            DetailsButton.Refresh -> if (TfaPartyRegistry.refreshLimiter.tryAcquire(player, TfaPartyRegistry.currentTick())) open(player, party, view.tab)
            DetailsButton.Unblock -> TfaPartyRegistry.unblock(player, party)
            DetailsButton.PreferredSize -> if (leader) {
                player.sendInputInt("Set your preferred party size (1-8):") { amount ->
                    if (amount in 1..8) TfaPartyRegistry.setPreferredSize(player, party, amount)
                    else player.sendMessage("The preferred party size must be between 1 and 8.")
                    reopen(player, view)
                }
            }
            DetailsButton.PreferredCompletions -> if (leader) {
                player.sendInputInt("Set your preferred number of completions (0-99999):") { amount ->
                    TfaPartyRegistry.setPreferredCompletions(player, party, amount)
                    reopen(player, view)
                }
            }
            DetailsButton.PreferredMode -> Unit // reserved for Practice Mode; no client op sends it [CACHE]
            DetailsButton.Action -> action(player, party)
            DetailsButton.MembersTab -> open(player, party, 0)
            DetailsButton.ApplicantsTab -> open(player, party, 1)
            DetailsButton.SpectateToggle -> TfaPartyRegistry.toggleSpectating(player, party)
            is DetailsButton.Kick -> TfaPartyRegistry.kick(player, party, button.slot)
            is DetailsButton.MoveUp -> TfaPartyRegistry.move(player, party, button.slot, up = true)
            is DetailsButton.MoveDown -> TfaPartyRegistry.move(player, party, button.slot, up = false)
            is DetailsButton.Accept -> TfaPartyRegistry.accept(player, party, button.index)
            is DetailsButton.Reject -> TfaPartyRegistry.reject(player, party, button.index)
        }
    }

    /** The role-dependent action button: Apply / Withdraw / Leave / Disband. */
    private fun action(player: Player, party: TfaParty) {
        when (ViewerRole.of(TfaPartyRegistry.book, party.state, player)) {
            ViewerRole.LEADER -> TfaPartyRegistry.disband(player, party)
            ViewerRole.MEMBER -> TfaPartyRegistry.leave(player)
            ViewerRole.APPLICANT -> TfaPartyRegistry.withdraw(player)
            ViewerRole.APPLY -> TfaPartyRegistry.apply(player, party)
            else -> {
                val own = TfaPartyRegistry.partyOf(player)
                player.sendMessage(when {
                    own != null -> "You are already in a party."
                    party.raid != null -> "That party is already inside the Archive."
                    party.members.size >= 8 -> "That party is full."
                    else -> "That party is not accepting applications from you."
                })
            }
        }
        if (TfaPartyRegistry.byId(party.id) == null && !player.interfaceHandler.isVisible(TfaIds.Interface.PARTY_LIST)) {
            PartyListInterface.open(player)
        }
    }

    /** The count dialog may have closed 976: show the party again. */
    private fun reopen(player: Player, view: PartyViewers.DetailsView) {
        val party = TfaPartyRegistry.byId(view.partyId) ?: return PartyListInterface.open(player)
        open(player, party, view.tab)
    }
}

/**
 * RESUME_PAUSEBUTTON on 976:1 (subs 0-47) and 977:1 / 977:14 [CACHE]: the Zenyte pause-button path dispatches to
 * `ButtonAction.interfaces` for interfaces without an `Interface` plugin. Plugin-scanned (`UserInterface`).
 */
class GroupingBoardInterfaces : UserInterface {

    override fun handleComponentClick(player: Player, interfaceId: Int, componentId: Int, slotId: Int, itemId: Int, optionId: Int, option: String) {
        when (interfaceId) {
            TfaIds.Interface.PARTY_LIST -> PartyListInterface.click(player, componentId, slotId)
            TfaIds.Interface.PARTY_DETAILS -> if (componentId == TfaIds.Component.PARTY_DETAILS_BUTTONS) PartyDetailsInterface.click(player, slotId)
        }
    }

    override fun getInterfaceIds(): IntArray = intArrayOf(TfaIds.Interface.PARTY_LIST, TfaIds.Interface.PARTY_DETAILS)
}
