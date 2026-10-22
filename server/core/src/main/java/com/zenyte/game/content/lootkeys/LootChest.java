package com.zenyte.game.content.lootkeys;

import com.zenyte.game.GameInterface;
import com.zenyte.game.item.Item;
import com.zenyte.game.util.AccessMask;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.masks.UpdateFlag;
import com.zenyte.game.world.entity.player.Analytics;
import com.zenyte.game.world.entity.player.LogLevel;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.ContainerResult;
import com.zenyte.game.world.entity.player.container.impl.ContainerType;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import mgi.types.component.ComponentDefinitions;
import mgi.types.config.items.ItemDefinitions;

import java.util.List;

import static com.zenyte.game.content.lootkeys.LootkeyConstants.*;

/**
 * The Ferox Enclave loot chest (loc 43486, interface 742).
 * <p>
 * Keys are never consumed by opening the chest: the player takes, banks or destroys what they want and the key keeps
 * the rest; a key that becomes empty disappears (OSRS since 20 August 2025). Two client layouts of interface 742 are
 * supported because the cache is being moved from the 211 interface to the stock 241 one:
 * <ul>
 *     <li><b>211</b> (37 components): one key at a time in inventory 797 (loot_inv_access), key chosen in a dialogue.</li>
 *     <li><b>241</b> (47 components): one tab per key (wildy_loot_key0..4), each backed by inventory deadman_loot_inv{i}
 *     (558 + i); items are dynamic children of component 5 with slot = tab * 28 + slot.</li>
 * </ul>
 * The layout is detected from the cache (component 742:46 only exists in the 241 interface).
 */
public final class LootChest {
    /** Player temporary attribute: index of the key currently shown. */
    private static final String SELECTED_ATTR = "loot chest selected key";

    /* 211 layout components. */
    static final int C211_ITEMS = 3;
    static final int C211_VALUE = 6;
    static final int C211_DESTROY = 9;
    static final int C211_WITHDRAW_INV = 20;
    static final int C211_IRON_LAYER = 22;
    static final int C211_IRON_TEXT = 23;
    static final int C211_ITEM = 25;
    static final int C211_NOTE = 26;
    static final int C211_BANK_USED = 29;
    static final int C211_BANK_SIZE = 31;
    static final int C211_WITHDRAW_BANK = 34;

    /* 241 layout components (wildy_loot_chest.if3, osrs-dumps 6e5033a = openrs2 2746). */
    static final int C241_TABS = 4;
    static final int C241_ITEMS = 5;
    static final int C241_DESTROY = 12;
    static final int C241_ITEM = 16;
    static final int C241_NOTE = 21;
    static final int C241_WITHDRAW_INV = 27;
    static final int C241_BANK_USED = 30;
    static final int C241_BANK_SIZE = 32;
    static final int C241_WITHDRAW_BANK = 34;
    static final int C241_CONFIRM_DESTROY = 43;
    static final int C241_CANCEL_DESTROY = 44;

    private LootChest() {
    }

    public static boolean is241Layout() {
        return ComponentDefinitions.get(CHEST_INTERFACE, 46) != null;
    }

    /** "Loot" on the chest. */
    public static void open(final Player player) {
        LootKeys.normalizeIds(player);
        migrateLegacyChest(player);
        final List<Integer> held = LootKeys.heldIndices(player);
        if (held.isEmpty()) {
            player.getDialogueManager().start(new Dialogue(player, SKULLY_NPC_ID) {
                @Override
                public void buildDialogue() {
                    npc("You don't seem to have any loot keys on you there, mate.");
                }
            });
            return;
        }
        Analytics.flagInteraction(player, Analytics.InteractionType.LOOT_CHEST);
        player.setAnimation(new Animation(832));
        if (is241Layout() || held.size() == 1) {
            show(player, held.get(0));
            return;
        }
        player.getDialogueManager().start(new Dialogue(player) {
            @Override
            public void buildDialogue() {
                final String[] options = new String[held.size()];
                for (int i = 0; i < held.size(); i++) {
                    final Item key = LootKeys.keyAtIndex(player, held.get(i));
                    options[i] = "Loot key " + (held.get(i) + 1) + " (" + Utils.formatNumberWithCommas(LootKeys.storedValue(key)) + "gp)";
                }
                final var message = options("Select a loot key", options);
                if (held.size() > 0) message.onOptionOne(() -> show(player, held.get(0)));
                if (held.size() > 1) message.onOptionTwo(() -> show(player, held.get(1)));
                if (held.size() > 2) message.onOptionThree(() -> show(player, held.get(2)));
                if (held.size() > 3) message.onOptionFour(() -> show(player, held.get(3)));
                if (held.size() > 4) message.onOptionFive(() -> show(player, held.get(4)));
            }
        });
    }

    private static void show(final Player player, final int index) {
        if (LootKeys.keyAtIndex(player, index) == null) {
            return;
        }
        player.getTemporaryAttributes().put(SELECTED_ATTR, index);
        GameInterface.WILDERNESS_LOOT_KEY.open(player);
    }

    /** Index of the key being shown, falling back to the first key held; -1 if the player holds none. */
    public static int selected(final Player player) {
        final Object value = player.getTemporaryAttributes().get(SELECTED_ATTR);
        if (value instanceof Integer && LootKeys.keyAtIndex(player, (Integer) value) != null) {
            return (Integer) value;
        }
        final List<Integer> held = LootKeys.heldIndices(player);
        if (held.isEmpty()) {
            return -1;
        }
        player.getTemporaryAttributes().put(SELECTED_ATTR, held.get(0));
        return held.get(0);
    }

    /** Called by the interface's open(): sends everything the current layout needs. Returns false if nothing to show. */
    static boolean sendOpen(final Player player) {
        LootKeys.normalizeIds(player);
        final int index = selected(player);
        if (index == -1) {
            player.sendMessage("You don't have any loot keys.");
            return false;
        }
        final LootkeySettings settings = LootKeys.settings(player);
        final boolean iron = player.isIronman();
        if (is241Layout()) {
            player.getVarManager().sendBit(VARBIT_LOOT_TAB, index);
            player.getVarManager().sendBit(VARBIT_WITHDRAW_NOTES, settings.isWithdrawAsNote() ? 1 : 0);
            for (int i = 0; i < LOOT_KEY_IDS.length; i++) {
                sendInventory241(player, i);
            }
            player.getInterfaceHandler().sendInterface(GameInterface.WILDERNESS_LOOT_KEY);
            player.getPacketDispatcher().sendComponentSettings(CHEST_INTERFACE, C241_TABS, 0, LOOT_KEY_IDS.length - 1, AccessMask.CLICK_OP1);
            if (iron) {
                player.getPacketDispatcher().sendComponentSettings(CHEST_INTERFACE, C241_ITEMS, 0, LOOT_KEY_IDS.length * KEY_CAPACITY - 1,
                        AccessMask.CLICK_OP8, AccessMask.CLICK_OP9, AccessMask.CLICK_OP10);
            } else {
                player.getPacketDispatcher().sendComponentSettings(CHEST_INTERFACE, C241_ITEMS, 0, LOOT_KEY_IDS.length * KEY_CAPACITY - 1,
                        AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3, AccessMask.CLICK_OP4, AccessMask.CLICK_OP5,
                        AccessMask.CLICK_OP6, AccessMask.CLICK_OP7, AccessMask.CLICK_OP8, AccessMask.CLICK_OP9, AccessMask.CLICK_OP10);
            }
            for (int i = 0; i < LOOT_KEY_IDS.length; i++) {
                sendValue241(player, i);
            }
            sendBankSpace(player, C241_BANK_USED, C241_BANK_SIZE);
        } else {
            final Item key = LootKeys.keyAtIndex(player, index);
            final Container contents = LootKeys.readContents(key);
            player.getInterfaceHandler().sendInterface(GameInterface.WILDERNESS_LOOT_KEY);
            player.getPacketDispatcher().sendUpdateItemContainer(contents, ContainerType.WILDERNESS_LOOT_KEY);
            if (iron) {
                setHidden(player, C211_IRON_LAYER, true);
                setHidden(player, C211_IRON_TEXT, false);
                player.getPacketDispatcher().sendComponentSettings(CHEST_INTERFACE, C211_ITEMS, 0, KEY_CAPACITY - 1,
                        AccessMask.CLICK_OP8, AccessMask.CLICK_OP9, AccessMask.CLICK_OP10);
                player.getPacketDispatcher().sendClientScript(150, CHEST_INTERFACE << 16 | C211_ITEMS, INV_LOOT_ACCESS, 7, 4, 0, -1,
                        "", "", "", "", "", "", "", "Destroy-X", "Destroy-All");
            } else {
                player.getPacketDispatcher().sendComponentSettings(CHEST_INTERFACE, C211_ITEMS, 0, KEY_CAPACITY - 1,
                        AccessMask.CLICK_OP1, AccessMask.CLICK_OP2, AccessMask.CLICK_OP3, AccessMask.CLICK_OP4, AccessMask.CLICK_OP5,
                        AccessMask.CLICK_OP6, AccessMask.CLICK_OP7, AccessMask.CLICK_OP8, AccessMask.CLICK_OP9, AccessMask.CLICK_OP10);
                player.getVarManager().sendBit(VARBIT_WITHDRAW_NOTES, settings.isWithdrawAsNote() ? 1 : 0);
                player.getPacketDispatcher().sendClientScript(150, CHEST_INTERFACE << 16 | C211_ITEMS, INV_LOOT_ACCESS, 7, 4, 0, -1,
                        "Take", "Take-5", "Take-10", "Take-X", "Take-All", "Bank-X", "Bank-All", "Destroy-X", "Destroy-All");
                sendBankSpace(player, C211_BANK_USED, C211_BANK_SIZE);
            }
            setText(player, C211_VALUE, "Value in key: " + Utils.formatNumberWithCommas(LootKeys.value(contents)) + "gp");
        }
        return true;
    }

    /* ------------------------------------------------------------------------------------------------------------ */
    /* Item operations. index = key index (0..4), slot = slot inside that key (0..27).                               */
    /* ------------------------------------------------------------------------------------------------------------ */

    public static void take(final Player player, final int index, final int slot, final int clickedItem, final int amount) {
        if (player.isIronman()) {
            player.sendMessage("As an Iron Man, you may only destroy items collected via loot keys.");
            return;
        }
        final Item key = LootKeys.keyAtIndex(player, index);
        if (key == null) return;
        final Container contents = LootKeys.readContents(key);
        final Item item = contents.get(slot);
        if (item == null || clickedItem != -1 && clickedItem != item.getId() && clickedItem != notedOrSelf(item)) return;
        final int moved = moveToInventory(player, contents, slot, amount);
        if (moved == -1) {
            player.sendMessage("You don't have enough inventory space.");
            return;
        }
        afterChange(player, index, key, contents);
    }

    public static void bank(final Player player, final int index, final int slot, final int clickedItem, final int amount) {
        if (player.isIronman()) {
            player.sendMessage("As an Iron Man, you may only destroy items collected via loot keys.");
            return;
        }
        final Item key = LootKeys.keyAtIndex(player, index);
        if (key == null) return;
        final Container contents = LootKeys.readContents(key);
        final Item item = contents.get(slot);
        if (item == null || clickedItem != -1 && clickedItem != item.getId() && clickedItem != notedOrSelf(item)) return;
        if (moveToBank(player, contents, slot, amount) <= 0) {
            player.sendMessage("You don't have enough space in your bank.");
            return;
        }
        afterChange(player, index, key, contents);
    }

    public static void destroy(final Player player, final int index, final int slot, final int clickedItem, final int amount) {
        final Item key = LootKeys.keyAtIndex(player, index);
        if (key == null) return;
        final Container contents = LootKeys.readContents(key);
        final Item item = contents.get(slot);
        if (item == null || clickedItem != -1 && clickedItem != item.getId() && clickedItem != notedOrSelf(item)) return;
        final int count = Math.min(amount, totalOf(contents, item));
        final Item removed = removeFromKey(contents, slot, item, count);
        if (removed == null) return;
        LootKeys.settings(player).incrementDestroyedValue(LootKeys.itemValue(removed));
        player.log(LogLevel.INFO, "Destroyed from loot key: " + removed);
        afterChange(player, index, key, contents);
    }

    public static void takeAll(final Player player, final int index) {
        if (player.isIronman()) {
            player.sendMessage("As an Iron Man, you may only destroy items collected via loot keys.");
            return;
        }
        final Item key = LootKeys.keyAtIndex(player, index);
        if (key == null) return;
        final Container contents = LootKeys.readContents(key);
        boolean full = false;
        for (final int slot : contents.getItems().keySet().toIntArray()) {
            if (contents.get(slot) == null) continue;
            if (moveToInventory(player, contents, slot, Integer.MAX_VALUE) == -1) {
                full = true;
            }
        }
        if (full) {
            player.sendMessage("You don't have enough inventory space to take everything.");
        }
        afterChange(player, index, key, contents);
    }

    public static void bankAll(final Player player, final int index) {
        if (player.isIronman()) {
            player.sendMessage("As an Iron Man, you may only destroy items collected via loot keys.");
            return;
        }
        final Item key = LootKeys.keyAtIndex(player, index);
        if (key == null) return;
        final Container contents = LootKeys.readContents(key);
        boolean full = false;
        for (final int slot : contents.getItems().keySet().toIntArray()) {
            if (contents.get(slot) == null) continue;
            if (moveToBank(player, contents, slot, Integer.MAX_VALUE) <= 0) {
                full = true;
            }
        }
        if (full) {
            player.sendMessage("You don't have enough space in your bank to bank everything.");
        }
        afterChange(player, index, key, contents);
    }

    public static void destroyAll(final Player player, final int index) {
        final Item key = LootKeys.keyAtIndex(player, index);
        if (key == null) return;
        final Container contents = LootKeys.readContents(key);
        final long value = LootKeys.value(contents);
        LootKeys.settings(player).incrementDestroyedValue(value);
        player.log(LogLevel.INFO, "Destroyed all loot key contents worth " + value + ": " + contents.getItems().values());
        for (final int slot : contents.getItems().keySet().toIntArray()) {
            contents.set(slot, null);
        }
        afterChange(player, index, key, contents);
    }

    public static void setWithdrawAsNote(final Player player, final boolean note) {
        LootKeys.settings(player).setWithdrawAsNote(note);
        player.getVarManager().sendBit(VARBIT_WITHDRAW_NOTES, note ? 1 : 0);
    }

    /** 241 layout: the player clicked a key tab. */
    public static void selectTab(final Player player, final int index) {
        if (LootKeys.keyAtIndex(player, index) == null) {
            return;
        }
        player.getTemporaryAttributes().put(SELECTED_ATTR, index);
        player.getVarManager().sendBit(VARBIT_LOOT_TAB, index);
    }

    /* ------------------------------------------------------------------------------------------------------------ */

    private static int notedOrSelf(final Item item) {
        final ItemDefinitions defs = item.getDefinitions();
        return defs == null ? item.getId() : defs.getNotedOrDefault();
    }

    private static int totalOf(final Container contents, final Item item) {
        return item.hasAttributes() ? item.getAmount() : contents.getAmountOf(item.getId());
    }

    /** Removes up to {@code count} of the item (all slots for plain items, the one slot for items with attributes). */
    private static Item removeFromKey(final Container contents, final int slot, final Item item, final int count) {
        if (count <= 0) return null;
        final ContainerResult result = item.hasAttributes()
                ? contents.remove(slot, count)
                : contents.remove(new Item(item.getId(), count));
        final int removed = result.getSucceededAmount();
        return removed <= 0 ? null : new Item(item.getId(), removed, item.getAttributesCopy());
    }

    /** Returns the amount moved, or -1 if nothing fit. */
    private static int moveToInventory(final Player player, final Container contents, final int slot, final int amount) {
        final Item item = contents.get(slot);
        if (item == null) return 0;
        int count = Math.min(amount, totalOf(contents, item));
        final boolean note = LootKeys.settings(player).isWithdrawAsNote() && !item.hasAttributes();
        final int outId = note ? notedOrSelf(item) : item.getId();
        count = Math.min(count, player.getInventory().getContainer().getMaximumTransferrableAmount(new Item(outId, count, item.getAttributesCopy())));
        if (count <= 0) return -1;
        final Item removed = removeFromKey(contents, slot, item, count);
        if (removed == null) return 0;
        final ContainerResult added = player.getInventory().addItem(new Item(outId, removed.getAmount(), removed.getAttributesCopy()));
        final int notAdded = removed.getAmount() - added.getSucceededAmount();
        if (notAdded > 0) {
            // Should not happen (space was checked); put the rest back rather than losing it.
            contents.add(new Item(removed.getId(), notAdded, removed.getAttributesCopy()));
        }
        final int moved = added.getSucceededAmount();
        LootKeys.settings(player).incrementTotalValueClaimed(LootKeys.itemValue(new Item(item.getId(), moved)));
        return moved;
    }

    /** Returns the amount moved to the bank. */
    private static int moveToBank(final Player player, final Container contents, final int slot, final int amount) {
        final Item item = contents.get(slot);
        if (item == null) return 0;
        final int count = Math.min(amount, totalOf(contents, item));
        if (count <= 0) return 0;
        final ContainerResult added = player.getBank().add(new Item(item.getId(), count, item.getAttributesCopy()));
        final int moved = added.getSucceededAmount();
        if (moved <= 0) return 0;
        removeFromKey(contents, slot, item, moved);
        LootKeys.settings(player).incrementTotalValueClaimed(LootKeys.itemValue(new Item(item.getId(), moved)));
        return moved;
    }

    /** Saves the key, consumes it when empty and refreshes the open interface. */
    private static void afterChange(final Player player, final int index, final Item key, final Container contents) {
        final boolean empty = contents.getSize() == 0;
        if (empty) {
            final int invSlot = LootKeys.slotOfIndex(player, index);
            if (invSlot != -1 && player.getInventory().getItem(invSlot) == key) {
                player.getInventory().deleteItem(invSlot, key);
            }
            LootKeys.settings(player).incrementKeysClaimed();
            player.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
            player.sendMessage("Your loot key crumbles to dust as the last of its loot is removed.");
        } else {
            LootKeys.writeContents(key, contents);
            player.getInventory().refresh();
        }
        if (!player.getInterfaceHandler().isVisible(CHEST_INTERFACE)) {
            return;
        }
        if (is241Layout()) {
            sendInventory241(player, index);
            sendValue241(player, index);
            sendBankSpace(player, C241_BANK_USED, C241_BANK_SIZE);
            if (empty) {
                final List<Integer> held = LootKeys.heldIndices(player);
                if (held.isEmpty()) {
                    player.getInterfaceHandler().closeInterface(GameInterface.WILDERNESS_LOOT_KEY);
                    return;
                }
                selectTab(player, held.get(0));
            }
        } else {
            if (empty) {
                player.getInterfaceHandler().closeInterface(GameInterface.WILDERNESS_LOOT_KEY);
                return;
            }
            player.getPacketDispatcher().sendUpdateItemContainer(contents, ContainerType.WILDERNESS_LOOT_KEY);
            setText(player, C211_VALUE, "Value in key: " + Utils.formatNumberWithCommas(LootKeys.value(contents)) + "gp");
            sendBankSpace(player, C211_BANK_USED, C211_BANK_SIZE);
        }
    }

    private static void sendInventory241(final Player player, final int index) {
        final Item key = LootKeys.keyAtIndex(player, index);
        final Container contents = key == null ? LootKeys.newContainer() : LootKeys.readContents(key);
        player.getPacketDispatcher().sendUpdateItemContainer(INV_DEADMAN_LOOT_BASE + index, -1, 0, contents);
    }

    private static void sendValue241(final Player player, final int index) {
        final Item key = LootKeys.keyAtIndex(player, index);
        player.getPacketDispatcher().sendClientScript(SCRIPT_VALUE_UPDATE, index, key == null ? "" : LootKeys.shortValue(LootKeys.storedValue(key)));
    }

    private static void sendBankSpace(final Player player, final int usedComponent, final int sizeComponent) {
        final int size = player.getBank().getContainer().getContainerSize();
        setText(player, usedComponent, size - player.getBank().getContainer().getFreeSlotsSize());
        setText(player, sizeComponent, size);
    }

    /** Only writes to components that exist in the cache, so a layout mismatch can never kick the client. */
    private static void setText(final Player player, final int component, final Object text) {
        if (ComponentDefinitions.get(CHEST_INTERFACE, component) != null) {
            player.getPacketDispatcher().sendComponentText(CHEST_INTERFACE, component, text);
        }
    }

    private static void setHidden(final Player player, final int component, final boolean hidden) {
        if (ComponentDefinitions.get(CHEST_INTERFACE, component) != null) {
            player.getPacketDispatcher().sendComponentVisibility(CHEST_INTERFACE, component, hidden);
        }
    }

    /**
     * Loot left in the chest by the old flow (key consumed on opening, loot kept in LootkeySettings) is put back into a
     * key so it can be taken with the new chest.
     */
    private static void migrateLegacyChest(final Player player) {
        final LootkeySettings settings = player.getLootkeySettings();
        if (settings == null || !settings.hasLegacyChestLoot()) {
            return;
        }
        final int index = LootKeys.freeIndex(player);
        if (index == -1 || !player.getInventory().hasFreeSlots()) {
            player.sendMessage("<col=ef1020>You have loot waiting in the chest. Free an inventory slot (and hold fewer than "
                    + MAX_KEYS + " keys) to collect it.</col>");
            return;
        }
        final Container contents = LootKeys.newContainer();
        final Int2ObjectLinkedOpenHashMap<Item> legacy = settings.getCurrentItemsInChest();
        for (final Int2ObjectMap.Entry<Item> entry : legacy.int2ObjectEntrySet()) {
            final Item item = entry.getValue();
            if (item != null && item.getAmount() > 0) {
                contents.add(item);
            }
        }
        final Item key = new Item(LOOT_KEY_IDS[index], 1);
        LootKeys.writeContents(key, contents);
        player.getInventory().addItem(key);
        LootkeySettings.clear(player);
        player.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
        player.sendMessage("The loot you left in the chest has been put back into a loot key.");
    }
}
