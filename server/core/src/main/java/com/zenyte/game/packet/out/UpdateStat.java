package com.zenyte.game.packet.out;

import net.rsprot.protocol.message.OutgoingGameMessage;

import com.zenyte.game.packet.GamePacketEncoder;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tommeh | 28 jul. 2018 | 19:04:32
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class UpdateStat implements GamePacketEncoder {

    private final int stat;
    private final int experience;
    private final int currentStat;

    public UpdateStat(final int stat, final double experience, final int currentStat) {
        this.stat = stat;
        this.experience = (int) experience;
        this.currentStat = Math.min(255, currentStat);
    }

    @Override
    public void log(@NotNull final Player player) {
        this.log(player, "Stat: " + stat + ", experience: " + player.getSkills().getExperience(stat) + ", current " +
                "level: " + player.getSkills().getLevel(stat));
    }

    public UpdateStat(int stat, int experience, int currentStat) {
        this.stat = stat;
        this.experience = experience;
        this.currentStat = currentStat;
    }

    @Override
    public OutgoingGameMessage toMessage() {
        return new net.rsprot.protocol.game.outgoing.misc.player.UpdateStatV2(stat, currentStat, currentStat, experience);
    }

    @Override
    public LogLevel level() {
        return LogLevel.LOW_PACKET;
    }

}
