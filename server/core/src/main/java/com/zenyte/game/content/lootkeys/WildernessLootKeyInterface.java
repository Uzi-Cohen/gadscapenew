package com.zenyte.game.content.lootkeys;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.util.ItemUtil;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;

import static com.zenyte.game.content.lootkeys.LootChest.*;
import static com.zenyte.game.content.lootkeys.LootkeyConstants.KEY_CAPACITY;

/**
 * Interface 742 (wildy_loot_chest). Both cache layouts are registered: only the components that exist in the loaded
 * cache can be clicked, and {@link LootChest} only writes to components of the detected layout.
 * <p>
 * Item ops (both layouts): 1 Take, 2 Take-5, 3 Take-10, 4 Take-X, 5 Take-All, 6 Bank-X, 7 Bank-All, 8 Destroy-X,
 * 9 Destroy-All, 10 Examine.
 */
public class WildernessLootKeyInterface extends Interface {

    @Override
    protected void attach() {
        // 211 layout (user/vanilla 211 cache, 37 components)
        put(2, 1, "Wilderness Loot Key");
        put(3, "Take");
        put(9, "Destroy");
        put(20, "Withdraw all to inventory");
        put(25, "Item");
        put(26, "Note");
        put(34, "Withdraw all to bank");
        // 241 layout (openrs2 2746, 47 components); 34 (withdrawbank) is shared with the 211 layout
        put(4, "Key tab");
        put(5, "Take 241");
        put(12, "Destroy 241");
        put(16, "Item 241");
        put(21, "Note 241");
        put(27, "Withdraw all to inventory 241");
        put(43, "Confirm destroy 241");
        put(44, "Cancel destroy 241");
    }

    @Override
    protected void build() {
        /* 211 */
        bind("Take", (player, slotId, itemId, option) -> itemOp(player, selected(player), slotId, itemId, option));
        bind("Destroy", player -> confirmDestroyAll(player));
        bind("Withdraw all to inventory", player -> takeAll(player, selected(player)));
        bind("Item", player -> setWithdrawAsNote(player, false));
        bind("Note", player -> setWithdrawAsNote(player, true));
        bind("Withdraw all to bank", player -> bankAll(player, selected(player)));

        /* 241 */
        bind("Key tab", (player, slotId, itemId, option) -> selectTab(player, slotId));
        bind("Take 241", (player, slotId, itemId, option) -> {
            final int index = slotId / KEY_CAPACITY;
            final int slot = slotId % KEY_CAPACITY;
            if (index != selected(player)) {
                selectTab(player, index);
            }
            itemOp(player, index, slot, itemId, option);
        });
        // The client shows its own confirmation layer (script 8009); the destroy happens on 43.
        bind("Destroy 241", player -> { });
        bind("Confirm destroy 241", player -> destroyAll(player, selected(player)));
        bind("Cancel destroy 241", player -> { });
        bind("Item 241", player -> setWithdrawAsNote(player, false));
        bind("Note 241", player -> setWithdrawAsNote(player, true));
        bind("Withdraw all to inventory 241", player -> takeAll(player, selected(player)));
    }

    private void itemOp(final Player player, final int index, final int slot, final int itemId, final int option) {
        if (index < 0 || slot < 0 || slot >= KEY_CAPACITY) {
            return;
        }
        switch (option) {
            case 1 -> take(player, index, slot, itemId, 1);
            case 2 -> take(player, index, slot, itemId, 5);
            case 3 -> take(player, index, slot, itemId, 10);
            case 4 -> player.sendInputInt("Enter amount:", amount -> take(player, index, slot, itemId, Math.max(0, amount)));
            case 5 -> take(player, index, slot, itemId, Integer.MAX_VALUE);
            case 6 -> player.sendInputInt("Enter amount to bank:", amount -> bank(player, index, slot, itemId, Math.max(0, amount)));
            case 7 -> bank(player, index, slot, itemId, Integer.MAX_VALUE);
            case 8 -> player.sendInputInt("Enter amount to destroy:", amount -> destroy(player, index, slot, itemId, Math.max(0, amount)));
            case 9 -> destroy(player, index, slot, itemId, Integer.MAX_VALUE);
            case 10 -> {
                if (itemId > 0) {
                    ItemUtil.sendItemExamine(player, itemId);
                }
            }
            default -> { }
        }
    }

    private void confirmDestroyAll(final Player player) {
        final int index = selected(player);
        if (index == -1) {
            return;
        }
        player.getDialogueManager().start(new Dialogue(player) {
            @Override
            public void buildDialogue() {
                options("Destroy everything in this loot key?", "<col=ff0000>Yes, destroy it all.", "No.")
                        .onOptionOne(() -> destroyAll(player, index))
                        .onOptionTwo(() -> GameInterface.WILDERNESS_LOOT_KEY.open(player));
            }
        });
    }

    @Override
    public void open(final Player player) {
        // Never half-open: without a key there is nothing to show (QA sweep NPE when settings were null).
        LootChest.sendOpen(player);
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.WILDERNESS_LOOT_KEY;
    }
}
