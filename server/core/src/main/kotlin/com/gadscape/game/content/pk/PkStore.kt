package com.gadscape.game.content.pk

import com.gadscape.game.world.entity.player.pkPoints
import com.zenyte.game.item.ItemId.*
import com.zenyte.game.model.shop.JsonShop
import com.zenyte.game.model.shop.Shop
import com.zenyte.game.model.shop.ShopCurrency
import com.zenyte.game.model.shop.ShopDiscount
import com.zenyte.game.model.shop.ShopPolicy
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import mgi.types.config.items.ItemDefinitions

/**
 * The PK store: a shop paid in [PkPoints], opened with ::pkstore / ::pkshop anywhere outside the Wilderness.
 *
 * The shop is built in code (no plugin registration needed) the first time someone opens it.
 */
object PkStore {

    const val NAME = "PK Store"

    private const val GEAR_STOCK = 1_000
    private const val SUPPLY_STOCK = 100_000

    /**
     * One row of the store.
     *
     * @param id the item, from [com.zenyte.game.item.ItemId].
     * @param price PKP for one unit (one bundle when [bundle] > 1).
     * @param bundle how many of the item one unit hands out (runes, bolts, scales...).
     * @param noted hand out the noted form (food and potions), so a stack fits in one slot.
     */
    data class Entry(
        val id: Int,
        val price: Int,
        val bundle: Int = 1,
        val noted: Boolean = false,
        val stock: Int = if (bundle > 1 || noted) SUPPLY_STOCK else GEAR_STOCK,
    )

    /**
     * The price list. Edit the prices (PKP) here; the order is the order in the shop.
     */
    @JvmField
    val STOCK: List<Entry> = listOf(
        // Weapons
        Entry(DRAGON_CLAWS, 3_500),
        Entry(ARMADYL_GODSWORD, 2_500),
        Entry(KODAI_WAND, 4_000),
        Entry(STAFF_OF_THE_DEAD, 800),
        Entry(TOXIC_BLOWPIPE_EMPTY, 1_500),
        Entry(HEAVY_BALLISTA, 2_000),
        Entry(ARMADYL_CROSSBOW, 1_500),
        Entry(DRAGON_CROSSBOW, 600),
        Entry(ABYSSAL_WHIP, 150),
        Entry(DRAGON_DAGGERP_5698, 50),
        Entry(GRANITE_MAUL, 100),
        Entry(MASTER_WAND, 250),

        // Ammunition (bundles)
        Entry(ZULRAHS_SCALES, 5, bundle = 500),
        Entry(DRAGON_DART, 5, bundle = 100),
        Entry(DRAGON_JAVELIN, 5, bundle = 50),
        Entry(DRAGON_BOLTS, 3, bundle = 100),
        Entry(OPAL_DRAGON_BOLTS_E, 3, bundle = 100),
        Entry(RUBY_DRAGON_BOLTS_E, 4, bundle = 100),
        Entry(DIAMOND_DRAGON_BOLTS_E, 5, bundle = 100),
        Entry(DRAGONSTONE_DRAGON_BOLTS_E, 5, bundle = 100),

        // Ancestral
        Entry(ANCESTRAL_HAT, 1_500),
        Entry(ANCESTRAL_ROBE_TOP, 3_000),
        Entry(ANCESTRAL_ROBE_BOTTOM, 2_500),

        // Barrows
        Entry(AHRIMS_HOOD, 100),
        Entry(AHRIMS_ROBETOP, 150),
        Entry(AHRIMS_ROBESKIRT, 150),
        Entry(AHRIMS_STAFF, 100),
        Entry(DHAROKS_HELM, 150),
        Entry(DHAROKS_PLATEBODY, 150),
        Entry(DHAROKS_PLATELEGS, 150),
        Entry(DHAROKS_GREATAXE, 200),
        Entry(KARILS_COIF, 75),
        Entry(KARILS_LEATHERTOP, 150),
        Entry(KARILS_LEATHERSKIRT, 100),
        Entry(KARILS_CROSSBOW, 100),
        Entry(VERACS_HELM, 150),
        Entry(VERACS_BRASSARD, 100),
        Entry(VERACS_PLATESKIRT, 100),
        Entry(VERACS_FLAIL, 150),
        Entry(TORAGS_HELM, 100),
        Entry(TORAGS_PLATEBODY, 100),
        Entry(TORAGS_PLATELEGS, 100),
        Entry(TORAGS_HAMMERS, 75),
        Entry(GUTHANS_HELM, 100),
        Entry(GUTHANS_PLATEBODY, 100),
        Entry(GUTHANS_CHAINSKIRT, 100),
        Entry(GUTHANS_WARSPEAR, 100),

        // Mystic & Infinity
        Entry(MYSTIC_HAT, 15),
        Entry(MYSTIC_ROBE_TOP, 40),
        Entry(MYSTIC_ROBE_BOTTOM, 30),
        Entry(MYSTIC_GLOVES, 10),
        Entry(MYSTIC_BOOTS, 10),
        Entry(INFINITY_HAT, 150),
        Entry(INFINITY_TOP, 200),
        Entry(INFINITY_BOTTOMS, 175),
        Entry(INFINITY_GLOVES, 75),
        Entry(INFINITY_BOOTS, 150),

        // Shields & defenders
        Entry(DRAGON_DEFENDER, 250),
        Entry(AVERNIC_DEFENDER, 1_500),
        Entry(SPIRIT_SHIELD, 50),
        Entry(BLESSED_SPIRIT_SHIELD, 300),
        Entry(MAGES_BOOK, 150),

        // Capes
        Entry(FIRE_CAPE, 300),
        Entry(INFERNAL_CAPE, 2_000),
        Entry(IMBUED_SARADOMIN_CAPE, 750),
        Entry(IMBUED_GUTHIX_CAPE, 750),
        Entry(IMBUED_ZAMORAK_CAPE, 750),

        // Accessories
        Entry(HELM_OF_NEITIZNOT, 50),
        Entry(BARROWS_GLOVES, 150),
        Entry(AMULET_OF_FURY, 400),
        Entry(OCCULT_NECKLACE, 750),
        Entry(BERSERKER_RING_I, 600),
        Entry(SEERS_RING_I, 400),
        Entry(ARCHERS_RING_I, 500),
        Entry(DRAGON_BOOTS, 150),
        Entry(ETERNAL_BOOTS, 1_500),
        Entry(PRIMORDIAL_BOOTS, 1_500),
        Entry(PEGASIAN_BOOTS, 1_250),
        Entry(RUNE_POUCH, 300),

        // Food & potions (noted, priced each)
        Entry(ANGLERFISH, 1, noted = true),
        Entry(COOKED_KARAMBWAN, 1, noted = true),
        Entry(SARADOMIN_BREW4, 2, noted = true),
        Entry(SUPER_RESTORE4, 2, noted = true),
        Entry(SUPER_COMBAT_POTION4, 2, noted = true),
        Entry(RANGING_POTION4, 1, noted = true),
        Entry(DIVINE_SUPER_COMBAT_POTION4, 4, noted = true),
        Entry(DIVINE_RANGING_POTION4, 4, noted = true),
        Entry(DIVINE_MAGIC_POTION4, 3, noted = true),
        Entry(ANTIVENOM4_12913, 2, noted = true),

        // Runes for barrage & vengeance (bundles of 100)
        Entry(DEATH_RUNE, 3, bundle = 100),
        Entry(BLOOD_RUNE, 4, bundle = 100),
        Entry(WATER_RUNE, 1, bundle = 100),
        Entry(ASTRAL_RUNE, 2, bundle = 100),
        Entry(EARTH_RUNE, 1, bundle = 100),
    )

    @Volatile
    private var registered = false

    private fun shopItemId(entry: Entry): Int =
        if (entry.noted) ItemDefinitions.get(entry.id)?.notedOrDefault ?: entry.id else entry.id

    @Synchronized
    private fun ensureRegistered() {
        if (registered) return
        val items = STOCK.map { entry ->
            val id = shopItemId(entry)
            if (entry.bundle > 1)
                Shop.setBundleSize(NAME, id, entry.bundle)
            JsonShop.Item(id, entry.stock, -1, entry.price, Shop.DEFAULT_RESTOCK_TIMER, false)
        }
        val jsonShop = JsonShop(NAME, ShopCurrency.PK_POINTS, ShopPolicy.NO_SELLING, Shop.DEFAULT_SELL_MULTIPLIER.toFloat(), ShopDiscount.NONE, items)
        Shop.shops[NAME] = Shop(jsonShop, false)
        Shop.ironmanShops[NAME] = Shop(jsonShop, true)
        registered = true
    }

    @JvmStatic
    fun open(player: Player) {
        if (WildernessArea.isWithinWilderness(player)) {
            player.sendMessage("You can't open the PK store in the Wilderness.")
            return
        }
        if (player.isLocked || player.isUnderCombat) {
            player.sendMessage("You can't open the PK store right now.")
            return
        }
        ensureRegistered()
        player.openShop(NAME)
        player.sendMessage("You have <col=ff0000>${player.pkPoints}</col> PK points. Runes, bolts and ammunition are sold in bundles: check an item's value to see the bundle size.")
    }
}
