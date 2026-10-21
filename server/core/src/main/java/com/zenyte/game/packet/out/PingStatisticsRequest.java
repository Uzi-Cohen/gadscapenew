package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.packet.GamePacketEncoderMode;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 1. apr 2018 : 22:02.56
 * @author Jire
 */
public final class PingStatisticsRequest implements GamePacketEncoder {

    @Override
    public void log(@NotNull final Player player) {
        log(player, "");
    }

    @Override
    public OutgoingGameMessage toMessage() {
        final long time = System.nanoTime();
        return new net.rsprot.protocol.game.outgoing.misc.client.SendPing((int) (time >>> 32), (int) time);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

    @NotNull
    @Override
    public GamePacketEncoderMode encoderMode() {
        return GamePacketEncoderMode.WRITE_FLUSH;
    }

}
