package com.gadscape.raids.tfa.hud

import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory

/**
 * Seq prefetch on arena entry (spec §3.7 "Prefetch"; WU-14 test "prefetch scripts fire once per room"): the room's
 * no-argument RUNCLIENTSCRIPT that `seq_prefetch`es its animations [CACHE scripts 10010-10016, 10030]:
 * 10013 Guardian, 10012 Ezekiel, 10010 Bork, 10015 Moia, 10014 Dreadnoth, 10011 Dagon'hai, 10030 Xarth,
 * 10016 Ocellus (corridor entry). Ids come from `TfaRoomId.prefetchScript` (remap-safe).
 */
object Prefetch {

    private val log = LoggerFactory.getLogger(Prefetch::class.java)

    /** Runs [room]'s prefetch for [member] once per raid. */
    @JvmStatic
    fun send(member: TfaRaidMember, room: TfaRoomId) {
        if (!member.prefetched.add(room)) return
        send(member.player, room)
    }

    /** Runs [room]'s prefetch for [player] now (spectators, debug). */
    @JvmStatic
    fun send(player: Player, room: TfaRoomId) {
        try {
            player.packetDispatcher.sendClientScript(room.prefetchScript)
        } catch (e: Exception) {
            log.warn("Fractured Archive: prefetch {} failed for {}", room, player.username, e)
        }
    }
}
