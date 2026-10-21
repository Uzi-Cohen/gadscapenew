package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.Setting;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:52:09
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class SetPrivateChatFilter implements GamePacketEncoder {

	private final Player player;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Private filter: " + player.getNumericAttribute(Setting.PRIVATE_FILTER.toString()).intValue());
	}

	public SetPrivateChatFilter(Player player) {
		this.player = player;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.misc.player.ChatFilterSettingsPrivateChat(player.getNumericAttribute(Setting.PRIVATE_FILTER.toString()).intValue());
	}

	@Override
	public LogLevel level() {
		return LogLevel.HIGH_PACKET;
	}

}
