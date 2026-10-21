package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.packet.GamePacketEncoderMode;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 13:48:56
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class RebuildNormal implements GamePacketEncoder {

    private final Player player;
    private final boolean init;

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Tile: x: " + player.getX() + ", y: " + player.getY() + ", z: " + player.getPlane());
    }

    public RebuildNormal(Player player, boolean init) {
        this.player = player;
        this.init = init;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        player.getForceReloadMap().set(false);
        // rev 241: no XTEA keys; the login rebuild (with the GPI init block) is REBUILD_LOGIN, sent by the login code.
        return com.gadscape.network.rsprot.RsprotOut.rebuildNormal(player);
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
