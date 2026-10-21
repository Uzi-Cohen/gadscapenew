package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.packet.GamePacketEncoderMode;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:41:34
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateZoneFullFollows implements GamePacketEncoder {

	private final int chunkX;
	private final int chunkY;
	private final int plane;

	@Override
	public void log(@NotNull final Player player) {
		this.log(player, "X: " + chunkX + ", y: " + chunkY);
	}

	public UpdateZoneFullFollows(int chunkX, int chunkY) {
		this(chunkX, chunkY, 0);
	}

	/** chunkX/chunkY: zone south-west tile relative to the build area (e.g. 48); plane: the zone's level (rev 241). */
	public UpdateZoneFullFollows(int chunkX, int chunkY, int plane) {
		this.plane = plane;
		this.chunkX = chunkX;
		this.chunkY = chunkY;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.zone.header.UpdateZoneFullFollows(chunkX, chunkY, plane);
	}

	@Override
	public @NotNull GamePacketEncoderMode encoderMode() {
		return GamePacketEncoderMode.WRITE_FLUSH;
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}
