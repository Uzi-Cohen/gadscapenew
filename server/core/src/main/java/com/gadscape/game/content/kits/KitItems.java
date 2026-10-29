package com.gadscape.game.content.kits;

import com.zenyte.game.item.Item;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.ContainerWrapper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Items spawned by a premade kit (the Premade Kits tab, PremadeKits). They are ordinary item ids carrying the
 * item attribute {@link #TAG} = 1, which is saved with the item (bank, inventory, equipment) like charges are.
 * <p>
 * An item with any attribute already counts as untradeable ({@link Item#isTradable()}), so trades, shops, the Grand
 * Exchange and stakes refuse kit items without help; the checks here cover the paths that don't go through that
 * (admin trades, dropping, death, the party chest, risk value).
 * <p>
 * Kit items can't be traded, sold, dropped for other players or put in the party chest, and they vanish on death
 * (not kept, not dropped to the killer).
 */
public final class KitItems {

    /** The item attribute that marks a kit item. */
    public static final String TAG = "gadscape_kit";

    private KitItems() {
    }

    /** True when [item] was spawned by a premade kit. */
    public static boolean isKitItem(final Item item) {
        if (item == null) {
            return false;
        }
        final Object value = item.getAttribute(TAG);
        if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        return Boolean.TRUE.equals(value);
    }

    /** Marks [item] as a kit item and returns it. */
    public static Item tag(final Item item) {
        if (item != null) {
            item.setAttribute(TAG, 1);
        }
        return item;
    }

    /**
     * The item that replaces [from] (a potion's next dose, a degraded piece) keeps the kit mark; returns [to].
     */
    public static Item carry(final Item from, final Item to) {
        if (to != null && isKitItem(from)) {
            return tag(new Item(to.getId(), to.getAmount(), to.getAttributesCopy()));
        }
        return to;
    }

    /** True when the player wears or carries any kit item. */
    public static boolean hasKitItems(final Player player) {
        return count(player.getInventory()) + count(player.getEquipment()) > 0;
    }

    /** Removes every kit item from the inventory and equipment (and the looting bag); returns how many stacks went. */
    public static int removeAll(final Player player) {
        int removed = remove(player.getInventory()) + remove(player.getEquipment());
        final Container bag = player.getLootingBag().getContainer();
        if (removeFrom(bag) > 0) {
            removed++;
            player.getLootingBag().refresh();
        }
        if (removed > 0) {
            player.getAppearance().resetRenderAnimation();
            player.getCombatDefinitions().refresh();
        }
        return removed;
    }

    /** On death: kit items vanish before anything is kept, dropped or sent to a gravestone. */
    public static void onDeath(final Player player) {
        if (removeAll(player) > 0) {
            player.sendMessage("Your kit gear vanishes.");
        }
    }

    private static int count(final ContainerWrapper wrapper) {
        if (wrapper == null) {
            return 0;
        }
        int n = 0;
        for (final Item item : wrapper.getContainer().getItems().values()) {
            if (isKitItem(item)) {
                n++;
            }
        }
        return n;
    }

    private static int remove(final ContainerWrapper wrapper) {
        if (wrapper == null) {
            return 0;
        }
        final int removed = removeFrom(wrapper.getContainer());
        if (removed > 0) {
            wrapper.refreshAll();
        }
        return removed;
    }

    private static int removeFrom(final Container container) {
        if (container == null) {
            return 0;
        }
        final IntArrayList slots = new IntArrayList();
        for (final Int2ObjectMap.Entry<Item> entry : container.getItems().int2ObjectEntrySet()) {
            if (isKitItem(entry.getValue())) {
                slots.add(entry.getIntKey());
            }
        }
        if (slots.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < slots.size(); i++) {
            container.set(slots.getInt(i), null);
        }
        return slots.size();
    }
}
