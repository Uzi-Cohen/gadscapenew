package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:25:23
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public class IfSetPosition implements GamePacketEncoder {
	private final int interfaceId;
	private final int componentId;
	private final int x;
	private final int y;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Interface: " + interfaceId + ", component: " + componentId + ", x: " + x + ", y: " + y);
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.interfaces.IfSetPosition(interfaceId, componentId, x, y);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

	public IfSetPosition(int interfaceId, int componentId, int x, int y) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.x = x;
		this.y = y;
	}
}
