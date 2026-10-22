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

    /** The player this packet is sent to (ownership is relative to the viewer); null = unknown, sent as public. */
    private final Player viewer;

    public ObjAdd(FloorItem floorItem) {
        this(floorItem, null);
    }

    public ObjAdd(FloorItem floorItem, Player viewer) {
        this.floorItem = floorItem;
        this.viewer = viewer;
    }

    /* OBJ_ADD ownership types (RuneLite TileItem.OWNERSHIP_*). */
    private static final int OWNERSHIP_NONE = 0;
    private static final int OWNERSHIP_SELF = 1;
    private static final int OWNERSHIP_OTHER = 2;

    /**
     * Sends the ownership and the timers so RuneLite's ground items plugin can filter PK/PvM loot by owner and show
     * despawn/public timers (both were always 0, as in the 211 encoder). Timers are in game ticks.
     */
    @Override
    public OutgoingGameMessage toMessage() {
        final Location tile = floorItem.getLocation();
        final int invisible = Math.max(0, floorItem.getInvisibleTicks());
        final int visible = Math.max(0, floorItem.getVisibleTicks());
        final boolean isPrivate = invisible > 0;
        final boolean becomesPublic = isPrivate && visible > 0 && floorItem.isTradable();
        final int timeUntilPublic = becomesPublic ? invisible : 0;
        final int timeUntilDespawn = isPrivate ? (becomesPublic ? invisible + visible : invisible) : visible;
        final int ownership;
        if (viewer == null || !floorItem.hasOwner() && !isPrivate) {
            ownership = OWNERSHIP_NONE;
        } else if (floorItem.isReceiver(viewer) || floorItem.isOwner(viewer)) {
            ownership = OWNERSHIP_SELF;
        } else {
            ownership = floorItem.hasOwner() ? OWNERSHIP_OTHER : OWNERSHIP_NONE;
        }
        return new net.rsprot.protocol.game.outgoing.zone.payload.ObjAdd(floorItem.getId(), floorItem.getAmount(),
                tile.getX() & 7, tile.getY() & 7, (byte) 31,
                Math.min(timeUntilPublic, 65535), Math.min(timeUntilDespawn, 65535), ownership,
                isPrivate && !becomesPublic);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
