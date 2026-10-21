package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * @author Kris | 03/03/2019 23:25
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class UpdateUID192 implements GamePacketEncoder {

    private final byte[] uid;

    public UpdateUID192(byte[] uid) {
        this.uid = uid;
    }

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "UID: " + Arrays.toString(uid));
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.client.UpdateUid192(java.util.Arrays.copyOf(uid, 24));
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
