package com.zenyte.game.content.sets;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.model.item.enums.DismantleableItem;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.plugins.itemonitem.ItemAttaching;

/**
 * GadScape's own boot ornament kits, one per completed collection log (CollectionLogRewards.kt: Tombs of Amascut
 * "masori themed pegasian boot kit", Chambers of Xeric "ancestral themed eternal boot kit", Nex "torva themed primordial
 * boot kit"). A kit is used on (or "Apply"-ed to) a pair of the matching boss boots and turns them into a themed copy
 * with the same stats; "Dismantle" gives the kit and the boots back, and a PvP death splits them like a Jagex kit
 * (DismantleableItem rows).
 * <p>
 * The kits are GadScape items of the user cache (31350-31352, inherit 21202, op "Apply"). The three results are new objs
 * the GadScape 241 cache adds (content-work/sets-prayers/CACHE_NEEDS.md section 3): copies of the boots, claimed at the
 * top of the converter's GadScape obj range and listed under {@code _new} in idmap.json, so they never meet the ids the
 * converter gives relocated objs (allocated from 60000 upwards). Every use also checks the cache name of the result
 * ({@link DismantleableItem#isAvailable()}), so a result id the cache holds another item at does nothing.
 */
public final class GadScapeKits {

    private GadScapeKits() {
    }

    public static final int MASORI_PEGASIAN_ORNAMENT_KIT = 31350;
    public static final int ANCESTRAL_ETERNAL_ORNAMENT_KIT = 31351;
    public static final int TORVA_PRIMORDIAL_ORNAMENT_KIT = 31352;

    /** Pegasian boots + Masori Pegasian Ornament Kit. */
    public static final int MASORI_PEGASIAN_BOOTS = 64997;
    public static final String MASORI_PEGASIAN_BOOTS_NAME = "Masori pegasian boots";
    /** Eternal boots + Ancestral Eternal Ornament Kit. */
    public static final int ANCESTRAL_ETERNAL_BOOTS = 64998;
    public static final String ANCESTRAL_ETERNAL_BOOTS_NAME = "Ancestral eternal boots";
    /** Primordial boots + Torva Primordial Ornament Kit. */
    public static final int TORVA_PRIMORDIAL_BOOTS = 64999;
    public static final String TORVA_PRIMORDIAL_BOOTS_NAME = "Torva primordial boots";

    /** The boots a kit is applied to. */
    public static int baseOf(final int kit) {
        return kit == MASORI_PEGASIAN_ORNAMENT_KIT ? ItemId.PEGASIAN_BOOTS
                : kit == ANCESTRAL_ETERNAL_ORNAMENT_KIT ? ItemId.ETERNAL_BOOTS
                : kit == TORVA_PRIMORDIAL_ORNAMENT_KIT ? ItemId.PRIMORDIAL_BOOTS : -1;
    }

    /** "Apply" on a kit: attaches it to the matching boots in the inventory, or says which boots it is for. */
    public static final class KitItem extends ItemPlugin {
        @Override
        public void handle() {
            bind("Apply", (player, item, slotId) -> {
                final int base = baseOf(item.getId());
                final int baseSlot = base == -1 ? -1 : player.getInventory().getContainer().getSlotOf(base);
                if (baseSlot == -1) {
                    final String boots = base == ItemId.PEGASIAN_BOOTS ? "a pair of Pegasian boots"
                            : base == ItemId.ETERNAL_BOOTS ? "a pair of Eternal boots" : "a pair of Primordial boots";
                    player.sendMessage("Use the kit on " + boots + " to apply it. It can be dismantled again at any time.");
                    return;
                }
                final Item boots = player.getInventory().getItem(baseSlot);
                new ItemAttaching().handleItemOnItemAction(player, item, boots, slotId, baseSlot);
            });
        }

        @Override
        public int[] getItems() {
            return new int[] {MASORI_PEGASIAN_ORNAMENT_KIT, ANCESTRAL_ETERNAL_ORNAMENT_KIT, TORVA_PRIMORDIAL_ORNAMENT_KIT};
        }
    }
}
