package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.flooritem.FloorItem;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:40:23
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class ObjAdd implements GamePacketEncoder {
    private final FloorItem floorItem;

    @Override
    public void log(@NotNull final Player player) {
        final Location tile = floorItem.getLocation();
        this.log(player, "Item: " + floorItem.getId() + ", amount: " + floorItem.getAmount() + ", x: " + tile.getX() + ", y: " + tile.getY() + ", z: " + tile.getPlane());
    }

    public ObjAdd(FloorItem floorItem) {
        this.floorItem = floorItem;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        final Location tile = floorItem.getLocation();
        return new net.rsprot.protocol.game.outgoing.zone.payload.ObjAdd(floorItem.getId(), floorItem.getAmount(), tile.getX() & 7, tile.getY() & 7, (byte) 31);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
