package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.packet.GamePacketEncoderMode;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 19:10:26
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class RebuildRegion implements GamePacketEncoder {

    private static final int BITBUFFER_LENGTH = (int) Math.ceil(13.0F * 13.0F * 4.0F * 27.0F / 8.0F);

    private final Player player;

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Tile: x: " + player.getX() + ", y: " + player.getY() + ", z: " + player.getPlane());
    }

    public RebuildRegion(Player player) {
        this.player = player;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        final boolean forceReloadMap = player.getForceReloadMap().getAndSet(false);
        return com.gadscape.network.rsprot.RsprotOut.rebuildRegion(player, forceReloadMap);
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
