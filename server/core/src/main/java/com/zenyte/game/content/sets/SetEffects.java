package com.zenyte.game.content.sets;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.impl.equipment.Equipment;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Facade for armour-set checks that other code asks about by set rather than by item id (sets-prayers area).
 * The combat set effects live with their combat code: Void ({@code CombatUtilities.hasFull*Void}), Barrows, Inquisitor,
 * Obsidian, Moon sets ({@code combat.moon.MoonSets}), Crystal armour ({@code CrystalArmour}); the skilling outfits'
 * experience boosts are {@code Skills.ExperienceBoost}. The other skilling outfit effects are here (values from the OSRS
 * wiki): Zealot's robes, Guild hunter outfit, Prescription goggles.
 */
public final class SetEffects {

    private SetEffects() {
    }

    private static final IntSet TORVA_HELMS = new IntOpenHashSet(new int[] {ItemId.TORVA_FULLHELM, ItemId.SANGUINE_TORVA_FULL_HELM, GadScapeKits.PRIMORDIAL_TORVA_FULL_HELM});
    private static final IntSet TORVA_BODIES = new IntOpenHashSet(new int[] {ItemId.TORVA_PLATEBODY, ItemId.SANGUINE_TORVA_PLATEBODY, GadScapeKits.PRIMORDIAL_TORVA_PLATEBODY});
    private static final IntSet TORVA_LEGS = new IntOpenHashSet(new int[] {ItemId.TORVA_PLATELEGS, ItemId.SANGUINE_TORVA_PLATELEGS, GadScapeKits.PRIMORDIAL_TORVA_PLATELEGS});

    /** Torva full helm, platebody and platelegs, in any of their variants (Sanguine, GadScape Primordial). */
    public static boolean wearingFullTorva(final Equipment equipment) {
        return TORVA_HELMS.contains(equipment.getId(EquipmentSlot.HELMET))
                && TORVA_BODIES.contains(equipment.getId(EquipmentSlot.PLATE))
                && TORVA_LEGS.contains(equipment.getId(EquipmentSlot.LEGS));
    }

    /* ---- Zealot's robes (Shades of Mort'ton) ---- */

    private static final int[] ZEALOTS_ROBES = {ItemId.ZEALOTS_HELM, ItemId.ZEALOTS_ROBE_TOP, ItemId.ZEALOTS_ROBE_BOTTOM, ItemId.ZEALOTS_BOOTS};
    private static final double ZEALOT_CHANCE_PER_PIECE = 0.0125;

    /**
     * Zealot's robes: every piece worn gives a 1.25% chance (5% for the set) that the remains are not used up when
     * burying bones or offering them on an altar (chaos altar, player-owned house). Scattering ashes is not affected.
     */
    public static boolean zealotSavesRemains(final Player player) {
        int pieces = 0;
        for (final int id : ZEALOTS_ROBES) {
            if (player.getEquipment().containsItem(id)) {
                pieces++;
            }
        }
        return pieces > 0 && Utils.randomDouble() < pieces * ZEALOT_CHANCE_PER_PIECE;
    }

    /* ---- Guild hunter outfit (Hunter Guild rumours) ---- */

    /** Headwear +0.2%, top +0.8%, legs +0.6%, boots +0.4%, and +0.5% for the whole set (2.5% in all). */
    private static final Int2IntOpenHashMap GUILD_HUNTER_PIECES = new Int2IntOpenHashMap();

    static {
        GUILD_HUNTER_PIECES.put(ItemId.GUILD_HUNTER_HEADWEAR, 2);
        GUILD_HUNTER_PIECES.put(ItemId.GUILD_HUNTER_TOP, 8);
        GUILD_HUNTER_PIECES.put(ItemId.GUILD_HUNTER_LEGS, 6);
        GUILD_HUNTER_PIECES.put(ItemId.GUILD_HUNTER_BOOTS, 4);
    }

    /**
     * Guild hunter outfit: the catch-rate bonus added to a trap's success probability (0.0 - 0.025). OSRS grants the set
     * part to anyone who owns all four pieces and wears one; here the whole set has to be worn.
     */
    public static double guildHunterCatchBonus(final Player player) {
        int tenthsOfPercent = 0;
        int worn = 0;
        for (final Int2IntOpenHashMap.Entry piece : GUILD_HUNTER_PIECES.int2IntEntrySet()) {
            if (player.getEquipment().containsItem(piece.getIntKey())) {
                tenthsOfPercent += piece.getIntValue();
                worn++;
            }
        }
        if (worn == GUILD_HUNTER_PIECES.size()) {
            tenthsOfPercent += 5;
        }
        return tenthsOfPercent / 1000.0;
    }

    /* ---- Alchemist's outfit (Mastering Mixology) ---- */

    private static final double GOGGLES_SAVE_CHANCE = 0.10;

    private static boolean wearsPrescriptionGoggles(final Player player) {
        final int helm = player.getEquipment().getId(EquipmentSlot.HELMET);
        return helm == ItemId.PRESCRIPTION_GOGGLES || helm == ItemId.PRESCRIPTION_GOGGLES_29976;
    }

    /**
     * Prescription goggles: a 10% chance to keep the secondary ingredient when mixing a potion. Returns the material
     * (one of {@code materials}) that is not used up this time, or null. Unfinished potions have no secondary.
     */
    public static Item secondarySavedByGoggles(final Player player, final Item product, final Item[] materials) {
        if (product == null || materials == null || !wearsPrescriptionGoggles(player)) {
            return null;
        }
        final String productName = product.getName();
        if (productName == null || productName.toLowerCase().contains("(unf)")) {
            return null;
        }
        Item secondary = null;
        boolean potionBase = false;
        for (final Item material : materials) {
            final String name = material.getName() == null ? "" : material.getName().toLowerCase();
            // the potion base (unfinished potion or a potion being upgraded) is never the secondary; nor are tools
            if (name.contains("(unf)") || name.matches(".*\\(\\d\\)$")) {
                potionBase = true;
                continue;
            }
            if (name.startsWith("vial") || name.contains("pestle") || name.equals("knife")) {
                continue;
            }
            secondary = material;
        }
        // only when mixing a potion (not when grinding or cutting an ingredient)
        if (!potionBase || secondary == null || Utils.randomDouble() >= GOGGLES_SAVE_CHANCE) {
            return null;
        }
        return secondary;
    }

    /** "Change" on the Mastering Mixology outfit: switches the goggles/labcoat/pants between their two looks. */
    public static final class AlchemistOutfitChange extends ItemPlugin {
        private static final Int2IntOpenHashMap OTHER_LOOK = new Int2IntOpenHashMap();

        static {
            pair(ItemId.PRESCRIPTION_GOGGLES, ItemId.PRESCRIPTION_GOGGLES_29976);
            pair(ItemId.ALCHEMIST_LABCOAT, ItemId.ALCHEMIST_LABCOAT_29980);
            pair(ItemId.ALCHEMIST_PANTS, ItemId.ALCHEMIST_PANTS_29984);
        }

        private static void pair(final int a, final int b) {
            OTHER_LOOK.put(a, b);
            OTHER_LOOK.put(b, a);
        }

        @Override
        public void handle() {
            bind("Change", (player, item, slotId) -> {
                final int other = OTHER_LOOK.getOrDefault(item.getId(), -1);
                if (other == -1 || player.getInventory().getItem(slotId) != item) {
                    return;
                }
                final String name = item.getName().toLowerCase();
                item.setId(other);
                player.getInventory().refresh(slotId);
                player.sendFilteredMessage("You change the look of your " + name + ".");
            });
        }

        @Override
        public int[] getItems() {
            return OTHER_LOOK.keySet().toIntArray();
        }
    }
}
