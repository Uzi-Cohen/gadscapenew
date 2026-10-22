package com.zenyte.game.content.sets;

import com.gadscape.game.rev241.Gad211;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;

/**
 * GadScape's own ornament kits (collection-log rewards, CollectionLogRewards.kt): each recolours the three pieces of
 * an armour set and is attached/dismantled like any other kit (DismantleableItem rows).
 * <p>
 * The kits are GadScape items of the user cache (31350-31352, inherit 21202, op "Apply"). The nine result items are
 * objs gadconv adds to the 241 cache (content-work/sets-prayers/CACHE_NEEDS.md, section "custom kits"): recoloured
 * copies of the base pieces, listed in the cache's idmap.json under {@code _new.obj} as {@code "sksp-15:<name>"}
 * (cacheV: 60000-60008). The constants resolve through {@link Gad211#newObj}, so they follow the cache; the
 * fallbacks are the planned ids. Their server stats are the base pieces' (gadscape/items/sets.json, whose entries name
 * the same {@code _new} keys). They are therefore not compile-time constants (no {@code case} labels).
 */
public final class GadScapeKits {

    private GadScapeKits() {
    }

    public static final int MASORI_PEGASIAN_ORNAMENT_KIT = 31350;
    public static final int ANCESTRAL_ETERNAL_ORNAMENT_KIT = 31351;
    public static final int TORVA_PRIMORDIAL_ORNAMENT_KIT = 31352;

    /** Masori (f) + Masori Pegasian kit. */
    public static final int PEGASIAN_MASORI_MASK_F = Gad211.newObj("sksp-15:Pegasian masori mask (f)", 60000);
    public static final int PEGASIAN_MASORI_BODY_F = Gad211.newObj("sksp-15:Pegasian masori body (f)", 60001);
    public static final int PEGASIAN_MASORI_CHAPS_F = Gad211.newObj("sksp-15:Pegasian masori chaps (f)", 60002);
    /** Ancestral + Ancestral Eternal kit. */
    public static final int ETERNAL_ANCESTRAL_HAT = Gad211.newObj("sksp-15:Eternal ancestral hat", 60003);
    public static final int ETERNAL_ANCESTRAL_ROBE_TOP = Gad211.newObj("sksp-15:Eternal ancestral robe top", 60004);
    public static final int ETERNAL_ANCESTRAL_ROBE_BOTTOM = Gad211.newObj("sksp-15:Eternal ancestral robe bottom", 60005);
    /** Torva + Torva Primordial kit. */
    public static final int PRIMORDIAL_TORVA_FULL_HELM = Gad211.newObj("sksp-15:Primordial torva full helm", 60006);
    public static final int PRIMORDIAL_TORVA_PLATEBODY = Gad211.newObj("sksp-15:Primordial torva platebody", 60007);
    public static final int PRIMORDIAL_TORVA_PLATELEGS = Gad211.newObj("sksp-15:Primordial torva platelegs", 60008);

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
