package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.WorldObject;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:28:11
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class LocAdd implements GamePacketEncoder {
	private final WorldObject object;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Id: " + object.getId() + ", type: " + object.getType() + ", rotation: " + object.getRotation() + ", x: " + object.getX() + ", y: " + object.getY() + ", z: " + object.getPlane());
	}

	public LocAdd(WorldObject object) {
		this.object = object;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.zone.payload.LocAddChangeV2(object.getId(), object.getX() & 7, object.getY() & 7, object.getType(), object.getRotation(), (byte) 31);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
