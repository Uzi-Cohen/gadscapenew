package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 19:03:28
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateRunWeight implements GamePacketEncoder {

    private final Player player;

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Weight: " + ((int) (player.getInventory().getWeight() + player.getEquipment().getWeight())));
    }

    public UpdateRunWeight(Player player) {
        this.player = player;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.player.UpdateRunWeight((int) (player.getInventory().getWeight() + player.getEquipment().getWeight()));
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
