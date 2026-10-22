package com.zenyte.game.content.treasuretrails.plugins;

import com.zenyte.game.content.treasuretrails.ClueItem;
import com.zenyte.game.content.treasuretrails.TreasureTrail;
import com.zenyte.game.content.treasuretrails.clues.Clue;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.flooritem.FloorItem;
import com.zenyte.plugins.flooritem.FloorItemPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Kris | 06/04/2019 17:22
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class ClueScrollItem extends ItemPlugin implements FloorItemPlugin {
    @Override
    public void handle() {
        bind("Read", (player, item, container, slotId) -> {
            List<String> list = TreasureTrail.getCluesList(item);
            final Clue clue = list == null || list.isEmpty() ? null : TreasureTrail.getClues().get(list.get(0));
            if (clue == null) {
                //No step yet, or a step that no longer exists on this server: draw a new one for the same tier.
                TreasureTrail.setRandomClue(item).view(player, item);
            } else {
                clue.view(player, item);
            }
        });
        bind("Check steps", (player, item, container, slotId) -> {
            final List<String> list = TreasureTrail.getCluesList(item);
            if (list == null) {
                TreasureTrail.setRandomClue(item);
            }
            final int steps = item.getNumericAttribute("Clue scroll current steps").intValue();
            player.sendMessage("You have completed " + steps + " step" + (steps == 1 ? "" : "s") + " on this " + item.getName().substring(13, item.getName().length() - 1) + " clue scroll.");
        });
    }

    @Override
    public boolean overrideTake() {
        return true;
    }

    @Override
    public void handle(Player player, FloorItem item, int optionId, String option) {
        if (option.equalsIgnoreCase("Take")) {
            if (player.containsItem(item.getId())) {
                player.sendMessage("You can only carry one of those at a time.");
                return;
            }
            World.takeFloorItem(player, item);
        }
    }

    @Override
    public boolean canTelegrab(@NotNull final Player player, @NotNull final FloorItem item) {
        if (player.containsItem(item.getId())) {
            player.sendMessage("You can only carry one of those at a time.");
            return false;
        }
        return true;
    }

    @Override
    public int[] getItems() {
        return ClueItem.getCluesArray();
    }
}
