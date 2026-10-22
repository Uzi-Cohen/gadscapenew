package com.zenyte.game.content.treasuretrails.plugins;

import com.zenyte.game.content.treasuretrails.ClueLevel;
import com.zenyte.game.content.treasuretrails.ClueStackLimit;
import com.zenyte.game.content.treasuretrails.ClueStackLimit.ScrollCase;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.util.Colour;

/**
 * Minor, major and Mimic scroll cases (OSRS, July 2025): opening one raises how many clue scrolls and scroll boxes of
 * its tier (the Mimic case: of every tier) the player can hold, see {@link ClueStackLimit}.
 */
public class ScrollCaseItem extends ItemPlugin {
    @Override
    public void handle() {
        bind("Open", (player, item, container, slotId) -> {
            final ScrollCase scrollCase = ScrollCase.get(item.getId());
            if (scrollCase == null) {
                return;
            }
            if (player.getInventory().getItem(slotId) != item) {
                return;
            }
            player.getInventory().deleteItem(slotId, item);
            if (!scrollCase.open(player)) {
                player.sendMessage("You've already obtained this scroll case!");
                return;
            }
            player.getCollectionLog().add(item);
            final ClueLevel tier = scrollCase.getTier();
            if (tier == null) {
                player.sendMessage(Colour.RED.wrap("You open the scroll case. You can now hold one more clue of every tier."));
                return;
            }
            final String name = tier.toString().toLowerCase();
            player.sendMessage(Colour.RED.wrap("You open the scroll case. You can now hold up to " + ClueStackLimit.getLimit(player, tier) + " " + name + " clues."));
        });
    }

    @Override
    public int[] getItems() {
        return ScrollCase.itemIds();
    }
}
