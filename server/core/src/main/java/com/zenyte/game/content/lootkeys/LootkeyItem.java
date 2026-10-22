package com.zenyte.game.content.lootkeys;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.masks.UpdateFlag;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.region.area.wilderness.WildernessArea;

/**
 * Loot key item ops (Check, Destroy) for wildy_loot_key0..4.
 * OSRS: keys can be destroyed anywhere outside the Wilderness; inside it only when the loot is worth less than
 * 1,000,000 coins. Destroying a key this way does not count towards Skully's totals.
 */
public class LootkeyItem extends ItemPlugin {

    @Override
    public void handle() {
        bind("Check", (player, item, container, slotId) -> {
            final long value = LootKeys.storedValue(item);
            final int stacks = LootKeys.readContents(item).getSize();
            player.sendMessage("Your loot key contains " + stacks + (stacks == 1 ? " item" : " items")
                    + " worth approximately " + Utils.formatNumberWithCommas(value) + " coins.");
        });
        bind("Destroy", (player, item, slotId) -> {
            final long value = LootKeys.storedValue(item);
            if (WildernessArea.isWithinWilderness(player) && value >= LootkeyConstants.WILDERNESS_DESTROY_LIMIT) {
                player.sendMessage("You can't destroy a loot key worth " + Utils.formatNumberWithCommas(LootkeyConstants.WILDERNESS_DESTROY_LIMIT)
                        + " coins or more in the Wilderness.");
                return;
            }
            player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    destroyItem(item, "The loot in this key (worth about " + Utils.formatNumberWithCommas(value)
                            + " coins) will be lost forever.").onYes(() -> {
                        final Item inSlot = player.getInventory().getItem(slotId);
                        if (inSlot != item) {
                            return;
                        }
                        player.getInventory().deleteItem(slotId, item);
                        player.log(LogLevel.INFO, "Destroyed loot key worth " + value + ": " + item);
                        player.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
                    });
                }
            });
        });
    }

    @Override
    public int[] getItems() {
        return LootkeyConstants.LOOT_KEY_IDS;
    }
}
