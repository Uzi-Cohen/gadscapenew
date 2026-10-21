package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 1. apr 2018 : 1:02.59
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class ResetAnims implements GamePacketEncoder {

	@Override
	public void log(@NotNull final Player player) {
		log(player, "");
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return net.rsprot.protocol.game.outgoing.misc.client.ResetAnims.INSTANCE;
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}
