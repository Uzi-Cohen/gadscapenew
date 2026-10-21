package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.model.CameraShakeType;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 4. dets 2017 : 14:09.18
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class CamShake implements GamePacketEncoder {
	private final CameraShakeType type;
	private final int shakeIntensity;
	private final int movementIntensity;
	private final int speed;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Type: " + type.name() + ", shake intensity: " + shakeIntensity + ", movement intensity: " + movementIntensity + ", speed: " + speed);
	}

	public CamShake(CameraShakeType type, int shakeIntensity, int movementIntensity, int speed) {
		this.type = type;
		this.shakeIntensity = shakeIntensity;
		this.movementIntensity = movementIntensity;
		this.speed = speed;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.camera.CamShake(type.getType(), shakeIntensity, movementIntensity, speed);
	}

	@Override
	public LogLevel level() {
		return LogLevel.HIGH_PACKET;
	}

}
