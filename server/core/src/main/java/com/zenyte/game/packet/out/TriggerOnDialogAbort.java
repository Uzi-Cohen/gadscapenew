package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 03/03/2019 23:23
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class TriggerOnDialogAbort implements GamePacketEncoder {

    @Override
    public void log(@NotNull final Player player) {
        log(player, "");
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return net.rsprot.protocol.game.outgoing.misc.player.TriggerOnDialogAbort.INSTANCE;
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
