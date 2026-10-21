package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 03/03/2019 23:46
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class IfModelRotate implements GamePacketEncoder {

    private final int interfaceId;
    private final int componentId;
    private final int roll;
    private final int pitch;

    public IfModelRotate(int interfaceId, int componentId, int roll, int pitch) {
        this.interfaceId = interfaceId;
        this.componentId = componentId;
        this.roll = roll;
        this.pitch = pitch;
    }

    @Override
    public void log(@NotNull final Player player) {
        log(player,
                "Interface: " + interfaceId + ", component: " + componentId + ", roll: " + roll + ", pitch: " + pitch);
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.interfaces.IfSetRotateSpeed(interfaceId, componentId, roll, pitch);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
