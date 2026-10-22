package com.zenyte.game.content.minigame.duelarena.interfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.content.achievementdiary.Diary;
import com.zenyte.game.content.minigame.duelarena.Duel;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Duel Arena scoreboard.
 * <p>
 * Interface 108 is no longer the duel scoreboard in the 211 or 241 caches (it is cr_tutorial, 7 components since the
 * Duel Arena became Emir's Arena), so writing the 72 history lines to 108:0..71 kicked the player with a packet error
 * (QA iface steps 127/132, CRIT-16). The scoreboard is now shown in the standard scroll/journal interface (119).
 *
 * @author Tommeh | 30-11-2018 | 18:06 (original)
 */
public final class DuelArenaScoreboardInterface extends Interface {

    @Override
    protected void attach() {
    }

    @Override
    public void open(Player player) {
        final List<String> lines = new ArrayList<>();
        lines.add("<col=000080>My wins: " + player.getNumericAttribute("DuelsWon").intValue()
                + "    My losses: " + player.getNumericAttribute("DuelsLost").intValue());
        lines.add("");
        final List<String> history = new ArrayList<>(Duel.getDuelHistory());
        if (history.isEmpty()) {
            lines.add("No duels have been fought yet.");
        } else {
            lines.add("<col=800000>Latest duels");
            for (int i = history.size() - 1; i >= 0; i--) {
                lines.add(history.get(i));
            }
        }
        Diary.sendJournal(player, "Duel Arena Scoreboard", lines);
    }

    @Override
    protected void build() {
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.DUEL_SCOREBOARD;
    }
}
