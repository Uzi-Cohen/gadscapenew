package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 19:01:36
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateRebootTimer implements GamePacketEncoder {

    private final int timer;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Timer: " + timer);
    }

    public UpdateRebootTimer(int timer) {
        this.timer = timer;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.client.UpdateRebootTimerV2(timer, new net.rsprot.protocol.game.outgoing.misc.client.UpdateRebootTimerV2.SetUpdateMessage(""));
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
