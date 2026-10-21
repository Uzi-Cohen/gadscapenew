package com.gadscape.network.rsprot

import com.zenyte.game.world.entity.player.Player
import net.rsprot.protocol.game.outgoing.map.RebuildLoginV2
import net.rsprot.protocol.message.OutgoingGameMessage

/**
 * STUB. This file is owned by the INFO track (player info / NPC info on rsprot, branch
 * claude/rev241-info); the NET track only needs it to compile. INFO's version replaces it at merge.
 *
 * Besides the contract (login / logout / tick) this stub declares the two hooks the INFO branch
 * exposes for NET ([rebuildLogin] and [sink]), so NET's login can hand the info packets over:
 *  - NET calls [login] on the world thread right after LoginResponse.Ok was written and the player
 *    got its index, then queues [rebuildLogin] as the very first game packet (REBUILD_LOGIN carries
 *    the GPI init block that only INFO's PlayerInfo can build);
 *  - NET installs [sink] once at startup (Rsprot.start) so INFO's per-tick info packets are queued
 *    on the player's rsprot session;
 *  - NET calls [logout] once when the player's session closes (Player.finish -> RsprotSession.close).
 */
object RsprotInfo {

    fun interface Sink {
        fun send(player: Player, message: OutgoingGameMessage): Boolean
    }

    @JvmStatic
    @Volatile
    var sink: Sink? = null

    fun login(player: Player) {
    }

    fun logout(player: Player) {
    }

    fun tick() {
    }

    /** INFO: the REBUILD_LOGIN message for a player that has just been passed to [login]. */
    fun rebuildLogin(player: Player): RebuildLoginV2? = null
}
