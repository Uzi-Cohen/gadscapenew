package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.SoundEffect;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 26. veebr 2018 : 2:06.19
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class SynthSound implements GamePacketEncoder {
    private final SoundEffect sound;

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Sound: " + sound.getId() + ", delay: " + sound.getDelay());
    }

    public SynthSound(SoundEffect sound) {
        this.sound = sound;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.sound.SynthSound(sound.getId(), sound.getRepetitions(), sound.getDelay());
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
