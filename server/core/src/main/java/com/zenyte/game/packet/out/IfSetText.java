package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:26:10
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class IfSetText implements GamePacketEncoder {
	private final int interfaceId;
	private final int componentId;
	private final String text;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Interface: " + interfaceId + ", component: " + componentId + ", text: " + text);
	}

	public IfSetText(int interfaceId, int componentId, String text) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.text = text;
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.interfaces.IfSetText(interfaceId, componentId, text == null ? "" : text);
	}
}
