package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:50:19
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class SetMapFlag implements GamePacketEncoder {

	private final int x;
	private final int y;
	/** Used to turn the scene coordinates into the absolute ones rev 241 expects; null = already absolute. */
	private final Player player;

	@Override
	public void log(@NotNull final Player player) {
		this.log(player, "X: " + x + ", y: " + y);
	}

	public SetMapFlag(int x, int y) {
		this(null, x, y);
	}

	public SetMapFlag(Player player, int x, int y) {
		this.player = player;
		this.x = x;
		this.y = y;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		if (x == 255 && y == 255) {
		    return new net.rsprot.protocol.game.outgoing.misc.player.SetMapFlagV2();
		}
		// rev 241 takes absolute coordinates; the callers pass coordinates in the scene (build area)
		final int baseX = player == null ? 0 : (player.getSceneBaseChunkId() & 2047) << 3;
		final int baseY = player == null ? 0 : (player.getSceneBaseChunkId() >> 11 & 2047) << 3;
		return new net.rsprot.protocol.game.outgoing.misc.player.SetMapFlagV2(baseX + x, baseY + y);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}
