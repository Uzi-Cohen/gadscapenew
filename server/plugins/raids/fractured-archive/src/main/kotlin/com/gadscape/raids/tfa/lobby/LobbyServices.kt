package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.player.Player

/**
 * Seams between the lobby (WU-10) and the units that own what a lobby loc opens. Each has a working default, so the
 * lobby is usable before those units land; the owner installs its implementation once at server launch.
 *
 * | Property | Owner | Default |
 * |---|---|---|
 * | [loot] | WU-29 vault and loot (wheelbarrow storage) | varbit 16416 only; Search says the wheelbarrow is empty |
 */
object LobbyServices {

    @JvmStatic
    @Volatile
    var loot: LootWheelbarrowSink = LootWheelbarrowSink.DEFAULT
}

/** Loot Wheelbarrow 63299 (multiloc on varbit 16416: 63300 empty / 63301 "Search") [CACHE], spec §3.15. */
interface LootWheelbarrowSink {

    /** True while [player] has loot waiting (the Archive Door refuses a raid start then, §3.4). */
    fun hasUnclaimedLoot(player: Player): Boolean = player.varManager.getBitValue(TfaIds.Varbit.HAS_CLAIMABLE_LOOT) == 1

    /** "Search": reopen interface 973 on the stored container. */
    fun search(player: Player) {
        player.sendMessage("The wheelbarrow is empty.")
    }

    /** Lobby entry / login: re-send varbit 16416 from the stored loot (it lives in a session varp). */
    fun refresh(player: Player) {}

    companion object {
        @JvmField
        val DEFAULT: LootWheelbarrowSink = object : LootWheelbarrowSink {
            override fun toString() = "LootWheelbarrowSink.DEFAULT"
        }
    }
}
