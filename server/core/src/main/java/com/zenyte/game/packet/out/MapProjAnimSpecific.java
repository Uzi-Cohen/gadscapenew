package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.Position;
import com.zenyte.game.world.Projectile;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public final class MapProjAnimSpecific implements GamePacketEncoder {

	@Override
	public void log(@NotNull final Player player) {
	}

	public MapProjAnimSpecific(final Player player, final Position senderTile, final Position receiverObject, final Projectile projectile, final int duration, final int offset) {
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return null; // rev-211 encoder wrote an empty payload
	}

	@Override
	public LogLevel level() {
		return LogLevel.SPAM;
	}

}
