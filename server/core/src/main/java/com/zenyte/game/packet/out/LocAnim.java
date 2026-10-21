package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.WorldObject;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:28:56
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class LocAnim implements GamePacketEncoder {
	private final int id;
	private final int type;
	private final int rotation;
	private final int x;
	private final int y;
	private final int plane;
	private final Animation animation;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Id: " + id + ", type: " + type + ", rotation: " + rotation + ", x: " + x + ", y: " + y + ", z: " + plane + ", animation id: " + animation.getId() + ", delay: " + animation.getDelay());
	}

	public LocAnim(WorldObject object, Animation animation) {
		type = object.getType();
		rotation = object.getRotation();
		x = object.getX();
		y = object.getY();
		id = object.getId();
		plane = object.getPlane();
		this.animation = animation;
	}

	public LocAnim(int id, int type, int rotation, Location location, Animation animation) {
		this.id = id;
		this.type = type;
		this.rotation = rotation;
		x = location.getX();
		y = location.getY();
		plane = location.getPlane();
		this.animation = animation;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.zone.payload.LocAnim(animation.getId(), x & 7, y & 7, type, rotation);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
