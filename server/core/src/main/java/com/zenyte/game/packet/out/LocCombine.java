package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.AttachedObject;
import com.zenyte.game.world.object.WorldObject;
import org.jetbrains.annotations.NotNull;

/**
 * Sends an object's model to the requested coordinates. The point of the packet
 * is to properly render the character and not have it glitch through the object.
 * Attaches the specified object to the requested player.
 *
 * @author Kris | 1. apr 2018 : 3:54.16
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server
 *      profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status
 *      profile</a>}
 */
public final class LocCombine implements GamePacketEncoder {
	private final int index;
	private final AttachedObject object;

	@Override
	public OutgoingGameMessage toMessage() {
		final WorldObject obj = object.getObject();
		return new net.rsprot.protocol.game.outgoing.zone.payload.LocMerge(index, obj.getId(), obj.getX() & 7, obj.getY() & 7, obj.getType(), obj.getRotation(),
		        object.getStartTime(), object.getEndTime(), object.getMinX(), object.getMinY(), object.getMaxX(), object.getMaxY());
	}

	public LocCombine(int index, AttachedObject object) {
		this.index = index;
		this.object = object;
	}

	@Override
	public void log(@NotNull final Player player) {
		final Player targetPlayer = World.getPlayers().get(index);
		final WorldObject obj = object.getObject();
		log(player, "Index: " + index + ", name: " + targetPlayer.getUsername() + ", id: " + obj.getId() + ", type: " + obj.getType() + ", rotation: " + obj.getRotation() + ", timeframe: " + object.getStartTime() + " - " + object.getEndTime() + ", width: " + object.getMinX() + " - " + object.getMaxX() + ", height: " + object.getMinY() + " - " + object.getMaxY());
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
