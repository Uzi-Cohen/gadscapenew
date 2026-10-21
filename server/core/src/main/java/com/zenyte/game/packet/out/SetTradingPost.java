package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;

/**
 * @author Kris | 03/03/2019 23:42
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class SetTradingPost implements GamePacketEncoder {
    @Override
    public OutgoingGameMessage toMessage() {
        return null; // trading post (GE history browser) is not supported on rev 241 yet
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
