package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.SoundEffect;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 26. veebr 2018 : 1:58.03
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class AreaSound implements GamePacketEncoder {
    private final Player player;
    private final Location tile;
    private final SoundEffect sound;

    @Override
    public void log(@NotNull final Player player) {
        log(player, "Id: " + sound.getId() + ", radius: " + sound.getRadius() + ", delay: " + sound.getDelay());
    }

    public AreaSound(Player player, Location tile, SoundEffect sound) {
        this.player = player;
        this.tile = tile;
        this.sound = sound;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.zone.payload.SoundArea(sound.getId(), sound.getDelay(), sound.getRepetitions(), sound.getRadius(), 0, tile.getX() & 7, tile.getY() & 7);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
