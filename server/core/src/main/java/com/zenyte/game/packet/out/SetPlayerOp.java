package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:51:10
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class SetPlayerOp implements GamePacketEncoder {

    private final Player player;
    private final int index;
    private final String option;
    private final boolean top;

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Index: " + index + ", top: " + top + ", option: " + option);
    }

    public SetPlayerOp(Player player, int index, String option, boolean top) {
        this.player = player;
        this.index = index;
        this.option = option;
        this.top = top;
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.player.SetPlayerOp(index, top, option == null ? "null" : option);
    }

}
