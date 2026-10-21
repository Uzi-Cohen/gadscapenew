package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 19:02:06
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateRunEnergy implements GamePacketEncoder {

    private final int energy;

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Energy: " + energy);
    }

    public UpdateRunEnergy(int energy) {
        this.energy = energy;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.player.UpdateRunEnergy(Math.min(65535, energy * 100));
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
