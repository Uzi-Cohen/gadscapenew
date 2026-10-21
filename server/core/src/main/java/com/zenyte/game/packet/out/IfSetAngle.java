package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 14. apr 2018 : 14:48.38
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class IfSetAngle implements GamePacketEncoder {
	@Override
	public void log(@NotNull final Player player) {
		log(player, "Interface: " + interfaceId + ", component: " + componentId + ", rotationX: " + rotationX + ", rotationY: " + rotationY + ", zoom: " + modelZoom);
	}

	private final int interfaceId;
	private final int componentId;
	private final int rotationX;
	private final int rotationY;
	private final int modelZoom;

	public IfSetAngle(int interfaceId, int componentId, int rotationX, int rotationY, int modelZoom) {
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.rotationX = rotationX;
		this.rotationY = rotationY;
		this.modelZoom = modelZoom;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return new net.rsprot.protocol.game.outgoing.interfaces.IfSetAngle(interfaceId, componentId, rotationX, rotationY, modelZoom);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}
