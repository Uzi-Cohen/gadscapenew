package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.model.HintArrow;
import com.zenyte.game.model.HintArrowPosition;
import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 18:49:15
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class SetHintArrow implements GamePacketEncoder {

	private final HintArrow icon;

	@Override
	public void log(@NotNull final Player player) {
		if (icon == null) {
			log(player, "Hint arrow reset");
		} else {
			log(player, "Position: " + icon.getPosition().name() + ", " + (icon.getPosition() == HintArrowPosition.ENTITY ? ("Entity: " + icon.getTarget().getEntityType().name() + ", index: " + icon.getTarget().getIndex()) : ("x: " + icon.getX() + ", y: " + icon.getY() + ", height: " + icon.getHeight())));
		}
	}

	public SetHintArrow(HintArrow icon) {
		this.icon = icon;
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return com.gadscape.network.rsprot.RsprotOut.hintArrow(icon);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}

}
