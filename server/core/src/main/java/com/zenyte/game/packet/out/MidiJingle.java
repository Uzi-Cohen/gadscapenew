package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 14. apr 2018 : 15:03.45
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class MidiJingle implements GamePacketEncoder {
	private final int trackId;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Song: " + trackId);
	}

	public MidiJingle(int trackId) {
		this.trackId = trackId;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.sound.MidiJingle(trackId);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
