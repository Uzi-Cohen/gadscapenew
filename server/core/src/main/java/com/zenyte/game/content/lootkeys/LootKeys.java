package com.zenyte.game.content.lootkeys;

import com.google.common.reflect.TypeToken;
import com.gadscape.game.model.item.ItemValueExtKt;
import com.zenyte.game.item.Item;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.UpdateFlag;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.entity.player.container.ContainerPolicy;
import com.zenyte.game.world.entity.player.container.ContainerResult;
import com.zenyte.game.world.entity.player.container.impl.ContainerType;
import com.zenyte.game.world.entity.player.login.LoginManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.zenyte.game.content.lootkeys.LootkeyConstants.*;

/**
 * Loot key helpers shared by death handling, the chest, the item and the floor plugin.
 * <p>
 * A key's loot lives in the key item itself (attribute {@link LootkeyConstants#LOOT_KEY_ITEM_LOOT_ITEMS_ATTR},
 * a JSON slot -> item map, max 28 stacks). Keys use the five objs wildy_loot_key0..4 so that the 241 chest can show
 * one tab per key; the index of a key is its obj id - 26651.
 */
public final class LootKeys {
    private static final Logger log = LoggerFactory.getLogger(LootKeys.class);
    private static final Type CONTENTS_TYPE = new TypeToken<Int2ObjectLinkedOpenHashMap<Item>>() {}.getType();

    private LootKeys() {
    }

    /** Number of loot keys in the inventory (all five ids). */
    public static int countKeys(final Player player) {
        int count = 0;
        for (final int id : LOOT_KEY_IDS) {
            count += player.getInventory().getAmountOf(id);
        }
        return count;
    }

    /** Inventory slot of the key with this index, or -1. */
    public static int slotOfIndex(final Player player, final int index) {
        if (index < 0 || index >= LOOT_KEY_IDS.length) return -1;
        return player.getInventory().getContainer().getSlotOf(LOOT_KEY_IDS[index]);
    }

    public static Item keyAtIndex(final Player player, final int index) {
        final int slot = slotOfIndex(player, index);
        return slot == -1 ? null : player.getInventory().getItem(slot);
    }

    /** Lowest key index the player does not hold, or -1 when all five are held. */
    public static int freeIndex(final Player player) {
        for (int i = 0; i < LOOT_KEY_IDS.length; i++) {
            if (!player.getInventory().containsItem(LOOT_KEY_IDS[i])) {
                return i;
            }
        }
        return -1;
    }

    /** Indices of the keys held, in index order. */
    public static List<Integer> heldIndices(final Player player) {
        final List<Integer> list = new ArrayList<>(LOOT_KEY_IDS.length);
        for (int i = 0; i < LOOT_KEY_IDS.length; i++) {
            if (player.getInventory().containsItem(LOOT_KEY_IDS[i])) {
                list.add(i);
            }
        }
        return list;
    }

    /**
     * Makes every held key use a distinct index (keys made before the five-id scheme all used 26651, and two
     * players' keys can share an index after a transfer). Keys beyond five keep their id.
     */
    public static void normalizeIds(final Player player) {
        final Container inv = player.getInventory().getContainer();
        final boolean[] used = new boolean[LOOT_KEY_IDS.length];
        final List<Integer> duplicates = new ArrayList<>();
        for (final Int2ObjectMap.Entry<Item> entry : inv.getItems().int2ObjectEntrySet()) {
            final Item item = entry.getValue();
            if (item == null || !isLootKey(item.getId())) continue;
            final int index = keyIndex(item.getId());
            if (used[index]) {
                duplicates.add(entry.getIntKey());
            } else {
                used[index] = true;
            }
        }
        if (duplicates.isEmpty()) return;
        for (final int slot : duplicates) {
            int free = -1;
            for (int i = 0; i < used.length; i++) {
                if (!used[i]) {
                    free = i;
                    break;
                }
            }
            if (free == -1) break;
            used[free] = true;
            final Item old = inv.get(slot);
            inv.set(slot, new Item(LOOT_KEY_IDS[free], 1, old.getAttributesCopy()));
        }
        player.getInventory().refresh();
    }

    /** A fresh 28-slot container (not linked to a player, so it never triggers area container hooks). */
    public static Container newContainer() {
        return new Container(ContainerType.WILDERNESS_LOOT_KEY, ContainerPolicy.NORMAL, KEY_CAPACITY, Optional.empty());
    }

    /** Decodes the key's loot. Unknown/empty keys give an empty container. */
    public static Container readContents(final Item key) {
        final Container container = newContainer();
        if (key == null) return container;
        final Object raw = key.getAttribute(LOOT_KEY_ITEM_LOOT_ITEMS_ATTR);
        if (raw == null) return container;
        try {
            final Int2ObjectLinkedOpenHashMap<Item> map = LoginManager.gson.get().fromJson(raw.toString(), CONTENTS_TYPE);
            if (map != null) {
                for (final Int2ObjectMap.Entry<Item> entry : map.int2ObjectEntrySet()) {
                    final Item item = entry.getValue();
                    if (item == null || item.getAmount() <= 0) continue;
                    final int slot = entry.getIntKey();
                    if (slot >= 0 && slot < KEY_CAPACITY && container.get(slot) == null) {
                        container.set(slot, new Item(item.getId(), item.getAmount(), item.getAttributesCopy()));
                    } else {
                        container.add(item);
                    }
                }
            }
        } catch (final Exception e) {
            log.error("Unreadable loot key contents: {}", raw, e);
        }
        return container;
    }

    /** Stores the loot (and its value) on the key item. */
    public static void writeContents(final Item key, final Container contents) {
        final Int2ObjectLinkedOpenHashMap<Item> map = new Int2ObjectLinkedOpenHashMap<>();
        for (final Int2ObjectMap.Entry<Item> entry : contents.getItems().int2ObjectEntrySet()) {
            if (entry.getValue() != null) {
                map.put(entry.getIntKey(), entry.getValue());
            }
        }
        key.setAttribute(LOOT_KEY_ITEM_LOOT_ITEMS_ATTR, LoginManager.gson.get().toJson(map));
        key.setAttribute(LOOT_KEY_ITEM_LOOT_VALUE_ATTR, value(contents));
    }

    public static long itemValue(final Item item) {
        if (item == null) return 0L;
        return (long) ItemValueExtKt.getItemValue(item.getId()) * item.getAmount();
    }

    public static long value(final Container contents) {
        long total = 0L;
        for (final Item item : contents.getItems().values()) {
            total += itemValue(item);
        }
        return total;
    }

    public static long storedValue(final Item key) {
        return key == null ? 0L : key.getNumericAttribute(LOOT_KEY_ITEM_LOOT_VALUE_ATTR).longValue();
    }

    /**
     * Gives a key to the player at their lowest free index: into the inventory, or (inventory full) onto the floor at
     * {@code tile}, visible only to them for 2 minutes. Returns false if the player already holds five keys.
     */
    public static boolean give(final Player player, final Item key, final Location tile) {
        final int index = freeIndex(player);
        if (index == -1) {
            return false;
        }
        final Item item = new Item(LOOT_KEY_IDS[index], 1, key.getAttributesCopy());
        final ContainerResult result = player.getInventory().addItem(item);
        if (result.isFailure()) {
            World.spawnFloorItem(item, tile == null ? player.getLocation() : tile, -1, player, player, FLOOR_KEY_TICKS, 0);
            player.sendMessage("<col=ef1020>Your inventory is full, so your loot key has been dropped on the floor.</col>");
        }
        player.getUpdateFlags().flag(UpdateFlag.APPEARANCE);
        return true;
    }

    /** Short value text used on the 241 chest tabs (e.g. 950K, 12.5M). */
    public static String shortValue(final long value) {
        if (value >= 10_000_000_000L) return (value / 1_000_000_000L) + "B";
        if (value >= 1_000_000_000L) return String.format("%.1fB", value / 1_000_000_000D);
        if (value >= 10_000_000L) return (value / 1_000_000L) + "M";
        if (value >= 1_000_000L) return String.format("%.1fM", value / 1_000_000D);
        if (value >= 100_000L) return (value / 1_000L) + "K";
        return Long.toString(value);
    }

    /** Settings are created lazily (disabled) so that players without the enchantment can still use keys from others. */
    public static LootkeySettings settings(final Player player) {
        LootkeySettings settings = player.getLootkeySettings();
        if (settings == null) {
            settings = new LootkeySettings(false, false, false, DEFAULT_THRESHOLD, 0, 0);
            settings.setUnlocked(false);
            player.setLootkeySettings(settings);
        }
        return settings;
    }

    /** Whether the player paid Skully for the enchantment. */
    public static boolean isUnlocked(final Player player) {
        final LootkeySettings settings = player.getLootkeySettings();
        return settings != null && settings.isUnlocked();
    }
}
