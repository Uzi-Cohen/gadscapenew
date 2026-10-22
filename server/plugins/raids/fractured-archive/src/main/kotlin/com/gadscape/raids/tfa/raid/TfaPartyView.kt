package com.gadscape.raids.tfa.raid

import com.zenyte.game.world.entity.player.Player

/** What the raid needs from a Grouping Board party (party/TfaParty implements it). */
interface TfaPartyView {
    val id: Int
    val leader: Player
    /** Members in board order, leader first. */
    val members: List<Player>
    /** Called by the raid when it is created / ends, so the board can show "Step Inside Now!" or reset. */
    fun onRaidCreated(raid: Any)
    fun onRaidEnded()
}
