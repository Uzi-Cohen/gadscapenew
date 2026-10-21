package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.flooritem.FloorItem;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 11. march 2018 : 19:42.07
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server
 *      profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status
 *      profile</a>}
 */
public final class ObjUpdate implements GamePacketEncoder {
	private final FloorItem floorItem;
	private final int oldQuantity;

	@Override
	public void log(@NotNull final Player player) {
		final Location tile = floorItem.getLocation();
		this.log(player, "Item: " + floorItem.getId() + ", amount: " + floorItem.getAmount() + ", old quantity: " + oldQuantity + ", x: " + tile.getX() + ", y: " + tile.getY() + ", z: " + tile.getPlane());
	}

	public ObjUpdate(FloorItem floorItem, int oldQuantity) {
		this.floorItem = floorItem;
		this.oldQuantity = oldQuantity;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		final Location tile = floorItem.getLocation();
		return new net.rsprot.protocol.game.outgoing.zone.payload.ObjCount(floorItem.getId(), oldQuantity, floorItem.getAmount(), tile.getX() & 7, tile.getY() & 7);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
