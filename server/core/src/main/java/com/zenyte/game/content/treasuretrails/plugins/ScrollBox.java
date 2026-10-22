package com.zenyte.game.content.treasuretrails.plugins;

import com.zenyte.game.content.treasuretrails.ClueItem;
import com.zenyte.game.content.treasuretrails.ClueStackLimit;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.flooritem.FloorItem;
import com.zenyte.plugins.flooritem.FloorItemPlugin;
import mgi.types.config.items.ItemDefinitions;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

/**
 * @author Kris | 23/11/2019
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class ScrollBox extends ItemPlugin implements FloorItemPlugin {
    @Override
    public void handle() {
        bind("Open", (player, item, container, slotId) -> {
            final int index = ArrayUtils.indexOf(ClueItem.getBoxesArray(), item.getId());
            if (index == -1) {
                return;
            }
            final int clue = ClueItem.getCluesArray()[index];
            if (player.containsItem(clue)) {
                player.sendMessage("The clue box refuses to open - you already have a clue. (" + ItemDefinitions.getOrThrow(clue).getName() + ")");
                return;
            }
            if (!(item.getAmount() == 1 || player.getInventory().hasFreeSlots())) {
                player.sendMessage("You need some free inventory space to open the box.");
                return;
            }
            player.getInventory().ifDeleteItem(new Item(item.getId(), 1), () -> player.getInventory().addOrDrop(new Item(clue)));
        });
    }

    @Override
    public int[] getItems() {
        return ClueItem.getBoxesArray();
    }

    /*
     * Ground scroll boxes: the OSRS stack limit per tier (ClueStackLimit) applies when picking them up.
     */

    @Override
    public boolean overrideTake() {
        return true;
    }

    @Override
    public void handle(final Player player, final FloorItem item, final int optionId, final String option) {
        if (!option.equalsIgnoreCase("Take")) {
            return;
        }
        final ClueItem type = ClueStackLimit.getBoxType(item.getId());
        if (type == null) {
            World.takeFloorItem(player, item);
            return;
        }
        if (!ClueStackLimit.canReceive(player, type)) {
            player.sendMessage("You can't take any more of that tier of scroll box.");
            return;
        }
        World.takeFloorItem(player, item);
        ClueStackLimit.destroyExcess(player, type);
    }

    @Override
    public boolean canTelegrab(@NotNull final Player player, @NotNull final FloorItem item) {
        final ClueItem type = ClueStackLimit.getBoxType(item.getId());
        if (type != null && ClueStackLimit.getRemaining(player, type) < item.getAmount()) {
            player.sendMessage("You can't take any more of that tier of scroll box.");
            return false;
        }
        return true;
    }
}
