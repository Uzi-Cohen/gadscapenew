package com.zenyte.game.packet;

import com.zenyte.game.world.entity.player.Player;
import com.zenyte.net.game.ServerEvent;
import com.zenyte.net.game.packet.GamePacketOut;
import net.rsprot.protocol.message.OutgoingGameMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A server packet. Since the rev-241 move the bytes are written by rsprot: each packet builds the
 * matching rsprot message in {@link #toMessage()} and the player's session queues it.
 *
 * @author Tommeh | 28 jul. 2018 | 13:46:49
 * @author Jire
 */
public interface GamePacketEncoder extends ServerEvent, LoggableEvent {

    @NotNull
    default GamePacketEncoderMode encoderMode() {
        return GamePacketEncoderMode.QUEUE;
    }

    /**
     * Builds the rev-241 (rsprot) message for this packet.
     *
     * @return the message, or {@code null} if rev 241 / rsprot has no equivalent; the session then
     * drops the packet and logs it once (see server/docs/rev241-net.md).
     */
    @Nullable
    default OutgoingGameMessage toMessage() {
        return null;
    }

    /**
     * Rev-211 byte encoding. No longer used by the network layer; only kept so the rev-211
     * PlayerInfo/NPCInfo still compile until the INFO track replaces them with rsprot's infos.
     */
    @Deprecated
    default GamePacketOut encode() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no rev-211 encoding any more");
    }

    default String name() {
        return "[Server prot: " + getClass().getSimpleName() + "] ";
    }

    default void log(@NotNull final Player player, final String text) {
        player.log(level(), name() + text);
    }

}
