package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.entry.EntranceFlow
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.party.PartyListInterface
import com.gadscape.raids.tfa.party.RaidEntry
import com.gadscape.raids.tfa.spectate.SpectatorService
import com.gadscape.raids.tfa.stats.StatsInterface979
import com.zenyte.game.GameInterface
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.WorldObject

/**
 * Every lobby loc op of spec §3.2 / §5.9 [CACHE r16470 placements, loc configs]. Plugin-scanned (`ObjectAction`).
 *
 * | Loc | Op | Does |
 * |---|---|---|
 * | Grouping Board 62767 (4138,5547) | Inspect | party list 977 |
 * | Strategy board 63083 (4116,5547) | Spectate | recent / specific party ([SpectatorService.strategyBoard], WU-18) |
 * | Alliance records 62773 (4135,5544) | Read | Alliance Log 979, lobby mode (10102(0)) |
 * | Bank Chest 63275 (4121,5553) | Use / Collect | bank / collection box |
 * | Archive Door 62768 (4123,5562) | Enter | raid start or join ([RaidEntry]) |
 * | Opening 63009 (4126,5505) | Enter | back to the temple (4051,4564) [HOUSE] |
 * | Loot Wheelbarrow 63299 → 63301 | Search | stored loot (WU-29 through [LobbyServices.loot]) |
 *
 * Ascension rocks are in [AscensionRocks].
 */
class TfaLobbyLocs : ObjectAction {

    override fun handleObjectAction(player: Player, `object`: WorldObject, name: String, optionId: Int, option: String) {
        val id = `object`.id
        when {
            id == TfaIds.Loc.GROUPING_BOARD -> PartyListInterface.open(player)
            id == TfaIds.Loc.STRATEGY_BOARD -> SpectatorService.strategyBoard(player)
            id == TfaIds.Loc.ALLIANCE_RECORDS -> StatsInterface979.open(player, inActiveRaid = false)
            id == TfaIds.Loc.LOBBY_BANK_CHEST -> when {
                option.equals("Collect", ignoreCase = true) -> GameInterface.GRAND_EXCHANGE_COLLECTION_BOX.open(player)
                else -> GameInterface.BANK.open(player)
            }
            id == TfaIds.Loc.ARCHIVE_DOOR -> RaidEntry.enter(player)
            id == TfaIds.Loc.LOBBY_EXIT -> EntranceFlow.toTemple(player)
            id == TfaIds.Loc.LOOT_WHEELBARROW || id == TfaIds.Loc.LOOT_WHEELBARROW_FULL -> {
                if (option.equals("Search", ignoreCase = true)) LobbyServices.loot.search(player)
            }
        }
    }

    override fun getObjects(): Array<Any> = arrayOf(
        TfaIds.Loc.GROUPING_BOARD, TfaIds.Loc.STRATEGY_BOARD, TfaIds.Loc.ALLIANCE_RECORDS, TfaIds.Loc.LOBBY_BANK_CHEST,
        TfaIds.Loc.ARCHIVE_DOOR, TfaIds.Loc.LOBBY_EXIT, TfaIds.Loc.LOOT_WHEELBARROW, TfaIds.Loc.LOOT_WHEELBARROW_FULL,
    )
}
