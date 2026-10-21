package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * @author Tommeh | 28 jul. 2018 | 18:47:03
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class RunClientScript implements GamePacketEncoder {

	private final int scriptId;
	private final Object[] arguments;
	private String type;
	private final boolean blank;

	@Override
	public void log(@NotNull final Player player) {
		this.log(player, "Script: " + scriptId + ", params: " + Arrays.toString(arguments));
	}

	public RunClientScript(int scriptId, boolean blank, Object... arguments) {
		this.scriptId = scriptId;
		this.blank = blank;
		this.arguments = arguments;
		char[] chars = new char[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			chars[i] = arguments[i] instanceof String ? 's' : 'i';
			type += arguments[i] instanceof String ? arguments[i].toString().length() + 1 : 4;
		}
		type = new String(chars);
	}

	public RunClientScript(int scriptId, boolean blank) {
		this.scriptId = scriptId;
		this.blank = blank;
		this.arguments = null;
		this.type = "";
	}

	@Override
	public OutgoingGameMessage toMessage() {
		return com.gadscape.network.rsprot.RsprotOut.runClientScript(scriptId, blank ? null : arguments);
	}

	@Override
	public LogLevel level() {
		return LogLevel.LOW_PACKET;
	}
}
