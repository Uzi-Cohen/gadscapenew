package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:22:23
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class IfSetModel implements GamePacketEncoder {
    private final int interfaceId;
    private final int componentId;
    private final int modelId;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Interface: " + interfaceId + ", component: " + componentId + ", model: " + modelId);
    }

    public IfSetModel(int interfaceId, int componentId, int modelId) {
        this.interfaceId = interfaceId;
        this.componentId = componentId;
        this.modelId = modelId;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.interfaces.IfSetModelV2(interfaceId, componentId, modelId);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }
}
