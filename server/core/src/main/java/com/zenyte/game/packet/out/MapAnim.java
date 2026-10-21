package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Graphics;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:53:08
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class MapAnim implements GamePacketEncoder {
    private final Location location;
    private final Graphics graphic;

    public MapAnim(Location location, Graphics graphic) {
        this.location = location;
        this.graphic = graphic;
    }

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Graphics: " + graphic.getId() + ", delay: " + graphic.getDelay() + ", height: " + graphic.getHeight() + ", tile: x: " + location.getX() + ", y: " + location.getY() + ", z: " + location.getPlane());
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.zone.payload.MapAnim(graphic.getId(), graphic.getDelay(), graphic.getHeight(), location.getX() & 7, location.getY() & 7);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
