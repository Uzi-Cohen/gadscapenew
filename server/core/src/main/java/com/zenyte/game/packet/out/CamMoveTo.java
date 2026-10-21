package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public class CamMoveTo implements GamePacketEncoder {
	private final int localX;
	private final int localY;
	private final int cameraHeight;
	private final int speed;
	private final int acceleration;
	/** Used to turn the build-area coordinates into the absolute ones rev 241 expects; null = already absolute. */
	private final Player player;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "X: " + localX + ", y: " + localY + ", height: " + cameraHeight + ", speed: " + speed + ", acceleration: " + acceleration);
	}

	public CamMoveTo(int localX, int localY, int cameraHeight, int speed, int acceleration) {
		this(null, localX, localY, cameraHeight, speed, acceleration);
	}

	public CamMoveTo(Player player, int localX, int localY, int cameraHeight, int speed, int acceleration) {
		this.player = player;
		this.localX = localX;
		this.localY = localY;
		this.cameraHeight = cameraHeight;
		this.speed = speed;
		this.acceleration = acceleration;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		final int baseX = player == null ? 0 : (player.getLastLoadedMapRegionTile().getChunkX() - 6) << 3;
		final int baseY = player == null ? 0 : (player.getLastLoadedMapRegionTile().getChunkY() - 6) << 3;
		return new net.rsprot.protocol.game.outgoing.camera.CamMoveToV3(baseX + localX, baseY + localY, cameraHeight, speed, acceleration, false);
	}

	@Override
	public LogLevel level() {
		return LogLevel.HIGH_PACKET;
	}
}
