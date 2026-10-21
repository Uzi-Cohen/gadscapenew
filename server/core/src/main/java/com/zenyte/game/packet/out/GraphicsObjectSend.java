package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.GraphicsObjectRS;
import com.zenyte.game.world.entity.player.Player;

public class GraphicsObjectSend implements GamePacketEncoder {
    private final GraphicsObjectRS gfx;
    private final Player player;

    public GraphicsObjectSend(Player player, GraphicsObjectRS object) {
        this.gfx = object;
        this.player = player;
    }
    private static int getLocal(int abs, int chunk) {
        return abs - 8 * (chunk - 6);
    }

    @Override
    public OutgoingGameMessage toMessage() {
        final Location lastTile = player.getLastLoadedMapRegionTile();
        final Location spawnLoc = gfx.getLocation();
        return new net.rsprot.protocol.game.outgoing.specific.MapAnimSpecific(gfx.getId(), gfx.getDelay(), 0, spawnLoc.getLocalX(lastTile), spawnLoc.getLocalY(lastTile));
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }
}
