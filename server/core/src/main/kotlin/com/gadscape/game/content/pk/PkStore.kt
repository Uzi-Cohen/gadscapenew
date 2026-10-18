package com.gadscape.game.content.pk

import com.gadscape.game.content.kits.KitsTabCache
import com.gadscape.game.world.entity.player.pkPoints
import com.zenyte.game.GameInterface
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId.*
import com.zenyte.game.model.ui.NewInterfaceHandler
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import mgi.types.config.items.ItemDefinitions
import java.text.NumberFormat
import java.util.Locale

/**
 * The PK store, paid in [PkPoints]: its own window (categories, items with prices) opened with ::pkstore or
 * from the side panel. The price list is [CATEGORIES]; edit prices there.
 */
object PkStore {

    /**
     * @param price PKP for one unit (one bundle when [bundle] > 1).
     * @param bundle how many of the item one unit hands out (ammo, runes).
     * @param noted hand out the noted form (food and potions).
     */
    data class Entry(val id: Int, val price: Int, val bundle: Int = 1, val noted: Boolean = false)

    class Category(val name: String, val items: List<Entry>)

    @JvmField
    val CATEGORIES: List<Category> = listOf(
        Category("Melee", listOf(
            Entry(VESTAS_LONGSWORD, 15_000),
            Entry(STATIUSS_WARHAMMER, 12_000),
            Entry(VESTAS_SPEAR, 6_000),
            Entry(VIGGORAS_CHAINMACE, 6_000),
            Entry(ANCIENT_GODSWORD, 8_000),
            Entry(GHRAZI_RAPIER, 7_000),
            Entry(INQUISITORS_MACE, 7_000),
            Entry(OSMUMTENS_FANG, 6_000),
            Entry(ELDER_MAUL, 6_000),
            Entry(DRAGON_CLAWS, 3_500),
            Entry(ARMADYL_GODSWORD, 2_500),
            Entry(BANDOS_GODSWORD, 1_500),
            Entry(SARADOMIN_GODSWORD, 1_200),
            Entry(ZAMORAK_GODSWORD, 1_000),
            Entry(DRAGON_WARHAMMER, 2_000),
            Entry(GRANITE_MAUL_24225, 300),
            Entry(ABYSSAL_TENTACLE, 400),
            Entry(ABYSSAL_WHIP, 150),
            Entry(DRAGON_DAGGERP_5698, 50),
        )),
        Category("Ranged", listOf(
            Entry(MORRIGANS_JAVELIN, 1_500, bundle = 50),
            Entry(MORRIGANS_THROWING_AXE, 1_200, bundle = 50),
            Entry(CRAWS_BOW, 5_000),
            Entry(ZARYTE_CROSSBOW, 9_000),
            Entry(HEAVY_BALLISTA, 2_000),
            Entry(ARMADYL_CROSSBOW, 1_500),
            Entry(DRAGON_HUNTER_CROSSBOW, 2_500),
            Entry(TOXIC_BLOWPIPE_EMPTY, 1_500),
            Entry(DRAGON_CROSSBOW, 600),
            Entry(KARILS_CROSSBOW, 100),
            Entry(DRAGON_JAVELIN, 5, bundle = 50),
            Entry(DRAGON_DART, 5, bundle = 100),
            Entry(ZULRAHS_SCALES, 5, bundle = 500),
            Entry(DRAGON_BOLTS, 3, bundle = 100),
            Entry(OPAL_DRAGON_BOLTS_E, 3, bundle = 100),
            Entry(RUBY_DRAGON_BOLTS_E, 4, bundle = 100),
            Entry(DIAMOND_DRAGON_BOLTS_E, 5, bundle = 100),
            Entry(DRAGONSTONE_DRAGON_BOLTS_E, 5, bundle = 100),
        )),
        Category("Magic", listOf(
            Entry(ZURIELS_STAFF, 8_000),
            Entry(THAMMARONS_SCEPTRE, 5_000),
            Entry(VOLATILE_NIGHTMARE_STAFF, 8_000),
            Entry(ELDRITCH_NIGHTMARE_STAFF, 7_000),
            Entry(HARMONISED_NIGHTMARE_STAFF, 6_000),
            Entry(KODAI_WAND, 4_000),
            Entry(TOXIC_STAFF_OF_THE_DEAD, 1_500),
            Entry(STAFF_OF_THE_DEAD, 800),
            Entry(MASTER_WAND, 250),
            Entry(ANCIENT_STAFF, 50),
            Entry(DEATH_RUNE, 3, bundle = 100),
            Entry(BLOOD_RUNE, 4, bundle = 100),
            Entry(WATER_RUNE, 1, bundle = 100),
            Entry(ASTRAL_RUNE, 2, bundle = 100),
            Entry(EARTH_RUNE, 1, bundle = 100),
            Entry(RUNE_POUCH, 300),
        )),
        Category("Armour", listOf(
            Entry(STATIUSS_FULL_HELM, 3_000),
            Entry(STATIUSS_PLATEBODY, 5_000),
            Entry(STATIUSS_PLATELEGS, 4_500),
            Entry(VESTAS_CHAINBODY, 5_000),
            Entry(VESTAS_PLATESKIRT, 4_500),
            Entry(MORRIGANS_COIF, 2_000),
            Entry(MORRIGANS_LEATHER_BODY, 3_000),
            Entry(MORRIGANS_LEATHER_CHAPS, 2_500),
            Entry(ZURIELS_HOOD, 2_000),
            Entry(ZURIELS_ROBE_TOP, 3_500),
            Entry(ZURIELS_ROBE_BOTTOM, 3_000),
            Entry(TORVA_FULLHELM, 6_000),
            Entry(TORVA_PLATEBODY, 8_000),
            Entry(TORVA_PLATELEGS, 7_000),
            Entry(ANCESTRAL_HAT, 1_500),
            Entry(ANCESTRAL_ROBE_TOP, 3_000),
            Entry(ANCESTRAL_ROBE_BOTTOM, 2_500),
            Entry(JUSTICIAR_FACEGUARD, 3_000),
            Entry(JUSTICIAR_CHESTGUARD, 4_000),
            Entry(JUSTICIAR_LEGGUARDS, 3_500),
            Entry(INQUISITORS_GREAT_HELM, 2_500),
            Entry(INQUISITORS_HAUBERK, 3_500),
            Entry(INQUISITORS_PLATESKIRT, 3_000),
            Entry(MASORI_MASK, 2_500),
            Entry(MASORI_BODY, 3_500),
            Entry(MASORI_CHAPS, 3_000),
            Entry(ARMADYL_HELMET, 800),
            Entry(ARMADYL_CHESTPLATE, 1_500),
            Entry(ARMADYL_CHAINSKIRT, 1_400),
            Entry(BANDOS_CHESTPLATE, 1_500),
            Entry(BANDOS_TASSETS, 1_400),
            Entry(NEITIZNOT_FACEGUARD, 1_200),
            Entry(HELM_OF_NEITIZNOT, 50),
            Entry(DHAROKS_HELM, 150),
            Entry(DHAROKS_PLATEBODY, 150),
            Entry(DHAROKS_PLATELEGS, 150),
            Entry(DHAROKS_GREATAXE, 200),
            Entry(VERACS_HELM, 150),
            Entry(VERACS_FLAIL, 150),
            Entry(KARILS_LEATHERTOP, 150),
            Entry(KARILS_LEATHERSKIRT, 100),
            Entry(AHRIMS_HOOD, 100),
            Entry(AHRIMS_ROBETOP, 150),
            Entry(AHRIMS_ROBESKIRT, 150),
            Entry(MYSTIC_ROBE_TOP, 40),
            Entry(MYSTIC_ROBE_BOTTOM, 30),
            Entry(INFINITY_BOOTS, 150),
        )),
        Category("Jewellery", listOf(
            Entry(AMULET_OF_TORTURE, 2_500),
            Entry(NECKLACE_OF_ANGUISH, 2_500),
            Entry(OCCULT_NECKLACE, 750),
            Entry(AMULET_OF_FURY, 400),
            Entry(TORMENTED_BRACELET, 2_000),
            Entry(RING_OF_SUFFERING_I, 2_000),
            Entry(BRIMSTONE_RING, 1_200),
            Entry(BERSERKER_RING_I, 600),
            Entry(SEERS_RING_I, 400),
            Entry(ARCHERS_RING_I, 500),
            Entry(FEROCIOUS_GLOVES, 1_200),
            Entry(ZARYTE_VAMBRACES, 2_000),
            Entry(BARROWS_GLOVES, 150),
            Entry(PRIMORDIAL_BOOTS, 1_500),
            Entry(PEGASIAN_BOOTS, 1_250),
            Entry(ETERNAL_BOOTS, 1_500),
            Entry(DRAGON_BOOTS, 150),
        )),
        Category("Capes & shields", listOf(
            Entry(INFERNAL_CAPE, 2_000),
            Entry(FIRE_CAPE, 300),
            Entry(IMBUED_SARADOMIN_CAPE, 750),
            Entry(IMBUED_GUTHIX_CAPE, 750),
            Entry(IMBUED_ZAMORAK_CAPE, 750),
            Entry(AVERNIC_DEFENDER, 1_500),
            Entry(DRAGON_DEFENDER, 250),
            Entry(ELYSIAN_SPIRIT_SHIELD, 10_000),
            Entry(SPECTRAL_SPIRIT_SHIELD, 3_000),
            Entry(ARCANE_SPIRIT_SHIELD, 3_000),
            Entry(BLESSED_SPIRIT_SHIELD, 300),
            Entry(DRAGONFIRE_SHIELD, 1_000),
            Entry(MAGES_BOOK, 150),
            Entry(TOME_OF_FIRE, 800),
        )),
        Category("Supplies", listOf(
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
        )),
    )

    private const val CATEGORY_KEY = "pk_store_category"
    private const val PAGE_KEY = "pk_store_page"
    private fun n(value: Int) = NumberFormat.getIntegerInstance(Locale.US).format(value)

    @Volatile
    private var registered = false

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
        if (!registered) {
            NewInterfaceHandler.add(PkStoreInterface::class.java)
            registered = true
        }
        player.interfaceHandler.sendInterface(GameInterface.PK_STORE)
        refresh(player)
    }

    fun category(player: Player): Int = player.temporaryAttributes[CATEGORY_KEY] as? Int ?: 0
    fun page(player: Player): Int = player.temporaryAttributes[PAGE_KEY] as? Int ?: 0

    fun select(player: Player, category: Int) {
        if (category !in CATEGORIES.indices) return
        player.temporaryAttributes[CATEGORY_KEY] = category
        player.temporaryAttributes[PAGE_KEY] = 0
        refresh(player)
    }

    fun turnPage(player: Player, delta: Int) {
        player.temporaryAttributes[PAGE_KEY] = (page(player) + delta).coerceAtLeast(0)
        refresh(player)
    }

    fun refresh(player: Player) {
        val d = player.packetDispatcher
        val ui = GameInterface.PK_STORE
        val category = category(player)
        val items = CATEGORIES[category].items
        val pages = maxOf(1, (items.size + KitsTabCache.STORE_SLOTS - 1) / KitsTabCache.STORE_SLOTS)
        val page = page(player).coerceAtMost(pages - 1)
        player.temporaryAttributes[PAGE_KEY] = page
        d.sendComponentText(ui, KitsTabCache.STORE_POINTS, "PK points: <col=3ed32d>${n(player.pkPoints)}</col>")
        for (i in 0 until KitsTabCache.STORE_CATEGORIES) {
            val label = KitsTabCache.STORE_FIRST_CATEGORY + i * 2 + 1
            val cat = CATEGORIES.getOrNull(i)
            d.sendComponentText(ui, label, cat?.name ?: "")
            d.sendComponentVisibility(ui, label - 1, cat == null)
            d.sendComponentVisibility(ui, label, cat == null)
            d.sendComponentSpriteColour(ui.id, label, com.zenyte.game.util.RSColour(if (i == category) KitsTabCache.WHITE else KitsTabCache.ORANGE))
        }
        for (slot in 0 until KitsTabCache.STORE_SLOTS) {
            val entry = items.getOrNull(page * KitsTabCache.STORE_SLOTS + slot)
            val itemComponent = KitsTabCache.STORE_FIRST_ITEM + slot
            val priceComponent = KitsTabCache.STORE_FIRST_PRICE + slot
            if (entry == null) {
                d.sendComponentVisibility(ui, itemComponent, true)
                d.sendComponentText(ui, priceComponent, "")
                continue
            }
            d.sendComponentItem(ui, itemComponent, entry.id, entry.bundle)
            d.sendComponentVisibility(ui, itemComponent, false)
            d.sendComponentText(ui, priceComponent, n(entry.price))
        }
        d.sendComponentText(ui, KitsTabCache.STORE_PAGE, "Page ${page + 1}/$pages")
    }

    fun entryAt(player: Player, slot: Int): Entry? =
        CATEGORIES[category(player)].items.getOrNull(page(player) * KitsTabCache.STORE_SLOTS + slot)

    fun value(player: Player, entry: Entry) {
        val name = ItemDefinitions.getOrThrow(entry.id).name
        val what = if (entry.bundle > 1) "$name (x${entry.bundle})" else name
        player.sendMessage("$what costs <col=ff0000>${n(entry.price)}</col> PK points. You have ${n(player.pkPoints)}.")
    }

    fun buy(player: Player, entry: Entry, amount: Int) {
        if (amount <= 0) return
        if (WildernessArea.isWithinWilderness(player) || player.isUnderCombat) {
            player.sendMessage("You can't buy from the PK store right now.")
            return
        }
        val defs = ItemDefinitions.getOrThrow(entry.id)
        val handOut = if (entry.noted) defs.notedOrDefault else entry.id
        val stacks = entry.noted || entry.bundle > 1 || ItemDefinitions.getOrThrow(handOut).isStackable()
        val affordable = player.pkPoints / entry.price
        var count = minOf(amount, affordable)
        if (count <= 0) {
            player.sendMessage("You need ${n(entry.price)} PK points for that. You have ${n(player.pkPoints)}.")
            return
        }
        val inventory = player.inventory
        if (stacks) {
            if (!inventory.containsItem(handOut, 1) && inventory.freeSlots == 0) {
                player.sendMessage("You don't have enough inventory space.")
                return
            }
        } else {
            count = minOf(count, inventory.freeSlots)
            if (count <= 0) {
                player.sendMessage("You don't have enough inventory space.")
                return
            }
        }
        player.pkPoints -= entry.price * count
        if (stacks) inventory.addItem(Item(handOut, entry.bundle * count))
        else repeat(count) { inventory.addItem(Item(handOut, 1)) }
        player.sendMessage("You bought ${if (entry.bundle * count > 1) "${n(entry.bundle * count)} x " else ""}${defs.name} " +
                "for ${n(entry.price * count)} PK points.")
        refresh(player)
    }
}
