package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 04/03/2019 00:05
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class Heatmap implements GamePacketEncoder {
    @Override
    public void log(@NotNull final Player player) {
        log(player, "Enabled: " + enabled);
    }

    private final boolean enabled;

    public Heatmap(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.client.SetHeatmapEnabled(enabled);
    }

    @Override
    public LogLevel level() {
        return LogLevel.HIGH_PACKET;
    }

}
