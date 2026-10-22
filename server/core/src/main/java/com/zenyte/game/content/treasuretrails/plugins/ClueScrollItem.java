package com.zenyte.game.content.treasuretrails.plugins;

import com.zenyte.game.content.treasuretrails.ClueItem;
import com.zenyte.game.content.treasuretrails.ClueStackLimit;
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
            if (atStackLimit(player, item)) {
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
        return !atStackLimit(player, item);
    }

    /**
     * OSRS stackable clues: a clue scroll cannot be picked up once the player holds as many clue scrolls and scroll
     * boxes of its tier as their limit allows (e.g. a scroll dropped, a scroll box of the tier picked up, then the
     * scroll picked up again).
     */
    private static boolean atStackLimit(@NotNull final Player player, @NotNull final FloorItem item) {
        final ClueItem type = ClueItem.getMap().get(item.getId());
        if (type != null && !ClueStackLimit.canReceive(player, type)) {
            player.sendMessage("You can't take any more clues of that tier.");
            return true;
        }
        return false;
    }

    @Override
    public int[] getItems() {
        return ClueItem.getCluesArray();
    }
}
