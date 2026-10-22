package com.zenyte.game.content.sets;

import com.zenyte.game.model.item.pluginextensions.ItemPlugin;

/**
 * GadScape's own ornament kits (collection-log rewards, CollectionLogRewards.kt): each recolours the three pieces of
 * an armour set and is attached/dismantled like any other kit (DismantleableItem rows).
 * <p>
 * The kits are GadScape items of the user cache (31350-31352, inherit 21202, op "Apply"). The nine result items are new
 * objs the GadScape 241 cache has to add (content-work/sets-prayers/CACHE_NEEDS.md, section "custom kits"): recoloured
 * copies of the base pieces at 60000-60008. Their server stats are the base pieces' (gadscape/items/sets.json). If the
 * cache converter places them at other ids, change these constants (and sets.json) only.
 */
public final class GadScapeKits {

    private GadScapeKits() {
    }

    public static final int MASORI_PEGASIAN_ORNAMENT_KIT = 31350;
    public static final int ANCESTRAL_ETERNAL_ORNAMENT_KIT = 31351;
    public static final int TORVA_PRIMORDIAL_ORNAMENT_KIT = 31352;

    /** Masori (f) + Masori Pegasian kit. */
    public static final int PEGASIAN_MASORI_MASK_F = 60000;
    public static final int PEGASIAN_MASORI_BODY_F = 60001;
    public static final int PEGASIAN_MASORI_CHAPS_F = 60002;
    /** Ancestral + Ancestral Eternal kit. */
    public static final int ETERNAL_ANCESTRAL_HAT = 60003;
    public static final int ETERNAL_ANCESTRAL_ROBE_TOP = 60004;
    public static final int ETERNAL_ANCESTRAL_ROBE_BOTTOM = 60005;
    /** Torva + Torva Primordial kit. */
    public static final int PRIMORDIAL_TORVA_FULL_HELM = 60006;
    public static final int PRIMORDIAL_TORVA_PLATEBODY = 60007;
    public static final int PRIMORDIAL_TORVA_PLATELEGS = 60008;

    /** "Apply" on a kit: OSRS-style hint (the kit is used on the armour). */
    public static final class KitItem extends ItemPlugin {
        @Override
        public void handle() {
            bind("Apply", (player, item, slotId) -> {
                final String target = item.getId() == MASORI_PEGASIAN_ORNAMENT_KIT ? "a piece of Masori armour (f)"
                        : item.getId() == ANCESTRAL_ETERNAL_ORNAMENT_KIT ? "a piece of Ancestral robes" : "a piece of Torva armour";
                player.sendMessage("Use the kit on " + target + " to apply it. It can be dismantled again at any time.");
            });
        }

        @Override
        public int[] getItems() {
            return new int[] {MASORI_PEGASIAN_ORNAMENT_KIT, ANCESTRAL_ETERNAL_ORNAMENT_KIT, TORVA_PRIMORDIAL_ORNAMENT_KIT};
        }
    }
}
