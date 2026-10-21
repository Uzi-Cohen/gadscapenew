package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:20:18
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class IfSetAnim implements GamePacketEncoder {
	private final int interfaceId;
	private final int componentId;
	private final int animationId;

	@Override
	public void log(@NotNull final Player player) {
		log(player, "Interface: " + interfaceId + ", component: " + componentId + ", animation: " + animationId);
	}

	public IfSetAnim(int interfaceId, int componentId, int animationId) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.animationId = animationId;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.interfaces.IfSetAnim(interfaceId, componentId, animationId);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
