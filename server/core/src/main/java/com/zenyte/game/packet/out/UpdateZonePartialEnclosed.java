package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.packet.GamePacketEncoderMode;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Kris | 24/10/2018 00:17
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class UpdateZonePartialEnclosed implements GamePacketEncoder {

    private static final Logger logger = LoggerFactory.getLogger(UpdateZonePartialEnclosed.class);

    private final int x;
    private final int y;
    private final Player player;
    private final List<GamePacketEncoder> packets = new ArrayList<>();

    private boolean writeDirect = false;

    private static int getLocal(int abs, int chunk) {
        return abs - 8 * (chunk - 6);
    }

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "X: " + x + ", y: " + y);
    }

    @Override
    public @NotNull GamePacketEncoderMode encoderMode() {
        if(writeDirect)
            return GamePacketEncoderMode.WRITE;
        else return GamePacketEncoderMode.QUEUE;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        // the rev-211 encoder wrote the appended packets last-to-first
        final List<GamePacketEncoder> ordered = new ArrayList<>(packets);
        java.util.Collections.reverse(ordered);
        return com.gadscape.network.rsprot.RsprotOut.zonePartialEnclosed(player, x, y, ordered);
    }

    private static final Class<?>[] ZONE_FOLLOW_TYPES = new Class<?>[]{
            MapProjAnim.class,
            LocCombine.class,
            MapProjAnimSpecific.class,
            ObjDel.class,
            ObjUpdate.class,
            LocAnim.class,
            LocAdd.class,
            LocDel.class,
            MapAnim.class,
            ObjFlags.class,
            ObjAdd.class,
            AreaSound.class,
    };

    public void append(final GamePacketEncoder packet) {
        if(packet instanceof ObjAdd || packet instanceof ObjUpdate)
            writeDirect = true;
        packets.add(packet);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

    public UpdateZonePartialEnclosed(int x, int y, Player player) {
        this.x = x;
        this.y = y;
        this.player = player;
    }

}
