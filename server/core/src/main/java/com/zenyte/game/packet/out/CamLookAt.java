package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 4. dets 2017 : 13:45.09
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class CamLookAt implements GamePacketEncoder {
	private final int viewLocalX;
	private final int viewLocalY;
	private final int cameraHeight;
	private final int speed;
	private final int acceleration;
	/** Used to turn the build-area coordinates into the absolute ones rev 241 expects; null = already absolute. */
	private final Player player;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "X: " + viewLocalX + ", y: " + viewLocalY + ", height: " + cameraHeight + ", speed: " + speed + ", acceleration: " + acceleration);
	}

	@Override
	public OutgoingGameMessage toMessage() {
		final int baseX = player == null ? 0 : (player.getLastLoadedMapRegionTile().getChunkX() - 6) << 3;
		final int baseY = player == null ? 0 : (player.getLastLoadedMapRegionTile().getChunkY() - 6) << 3;
		return new net.rsprot.protocol.game.outgoing.camera.CamLookAtV3(baseX + viewLocalX, baseY + viewLocalY, cameraHeight, speed, acceleration, false);
	}

	@Override
	public LogLevel level() {
		return LogLevel.HIGH_PACKET;
	}

	public CamLookAt(int viewLocalX, int viewLocalY, int cameraHeight, int speed, int acceleration) {
		this(null, viewLocalX, viewLocalY, cameraHeight, speed, acceleration);
	}

	public CamLookAt(Player player, int viewLocalX, int viewLocalY, int cameraHeight, int speed, int acceleration) {
		this.player = player;
		this.viewLocalX = viewLocalX;
		this.viewLocalY = viewLocalY;
		this.cameraHeight = cameraHeight;
		this.speed = speed;
		this.acceleration = acceleration;
	}
}
