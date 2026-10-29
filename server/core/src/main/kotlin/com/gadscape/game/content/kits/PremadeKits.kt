package com.gadscape.game.content.kits

import com.zenyte.game.GameInterface
import com.zenyte.game.content.pvpworld.PvpWorld
import com.zenyte.game.content.skills.magic.Spellbook
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.model.item.degradableitems.DegradableItem
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.entity.player.Skills
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot
import com.zenyte.game.world.entity.player.dialogue.options
import com.zenyte.game.world.region.DynamicArea
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import mgi.types.component.ComponentDefinitions

/** One stack of a kit: item id and amount. */
class KitItem(val id: Int, val amount: Int = 1)

/**
 * The premade kits (free gear from the Premade Kits tab): what each wears, what fills the inventory, the combat stats
 * it sets and its spellbook. [icons] are the helm, body and weapon the tab shows; [level] is the combat level the tab
 * prints (what [stats] works out to).
 */
enum class PremadeKit(
    val title: String,
    val level: Int,
    val icons: IntArray,
    val equipment: Map<EquipmentSlot, KitItem>,
    val inventory: List<KitItem>,
    val stats: Map<Int, Int>,
    val spellbook: Spellbook? = null,
) {
    MAIN(
        "Main", 126, intArrayOf(ItemId.HELM_OF_NEITIZNOT, ItemId.FIGHTER_TORSO, ItemId.ABYSSAL_WHIP),
        gear(
            EquipmentSlot.HELMET to ItemId.HELM_OF_NEITIZNOT, EquipmentSlot.CAPE to ItemId.FIRE_CAPE,
            EquipmentSlot.AMULET to ItemId.AMULET_OF_GLORY4, EquipmentSlot.PLATE to ItemId.FIGHTER_TORSO,
            EquipmentSlot.LEGS to ItemId.RUNE_PLATELEGS, EquipmentSlot.WEAPON to ItemId.ABYSSAL_WHIP,
            EquipmentSlot.SHIELD to ItemId.RUNE_DEFENDER, EquipmentSlot.HANDS to ItemId.BARROWS_GLOVES,
            EquipmentSlot.BOOTS to ItemId.DRAGON_BOOTS, EquipmentSlot.RING to ItemId.BERSERKER_RING,
        ),
        listOf(KitItem(ItemId.DRAGON_DAGGERP_5698)) + meleeSupplies() + food(11, 11),
        maxed(),
    ),
    ZERKER(
        "Zerker", 99, intArrayOf(ItemId.BERSERKER_HELM, ItemId.FIGHTER_TORSO, ItemId.DRAGON_SCIMITAR),
        gear(
            EquipmentSlot.HELMET to ItemId.BERSERKER_HELM, EquipmentSlot.CAPE to ItemId.FIRE_CAPE,
            EquipmentSlot.AMULET to ItemId.AMULET_OF_GLORY4, EquipmentSlot.PLATE to ItemId.FIGHTER_TORSO,
            EquipmentSlot.LEGS to ItemId.RUNE_PLATELEGS, EquipmentSlot.WEAPON to ItemId.DRAGON_SCIMITAR,
            EquipmentSlot.SHIELD to ItemId.DRAGON_DEFENDER, EquipmentSlot.HANDS to ItemId.BARROWS_GLOVES,
            EquipmentSlot.BOOTS to ItemId.CLIMBING_BOOTS, EquipmentSlot.RING to ItemId.BERSERKER_RING,
        ),
        listOf(KitItem(ItemId.DRAGON_DAGGERP_5698)) + meleeSupplies() + food(11, 11),
        combatStats(attack = 75, strength = 99, defence = 45, prayer = 52),
    ),
    PURE(
        "Pure", 83, intArrayOf(ItemId.COIF, ItemId.MONKS_ROBE_TOP, ItemId.GRANITE_MAUL),
        gear(
            EquipmentSlot.HELMET to ItemId.COIF, EquipmentSlot.CAPE to ItemId.AVAS_ACCUMULATOR,
            EquipmentSlot.AMULET to ItemId.AMULET_OF_GLORY4, EquipmentSlot.PLATE to ItemId.MONKS_ROBE_TOP,
            EquipmentSlot.LEGS to ItemId.BLACK_DHIDE_CHAPS, EquipmentSlot.WEAPON to ItemId.DRAGON_SCIMITAR,
            EquipmentSlot.HANDS to ItemId.MITHRIL_GLOVES, EquipmentSlot.BOOTS to ItemId.CLIMBING_BOOTS,
            EquipmentSlot.RING to ItemId.RING_OF_RECOIL,
        ) + (EquipmentSlot.AMMUNITION to KitItem(ItemId.RUNE_ARROW, 500)),
        listOf(KitItem(ItemId.GRANITE_MAUL), KitItem(ItemId.MAGIC_SHORTBOW), KitItem(ItemId.SUPER_COMBAT_POTION4),
            KitItem(ItemId.RANGING_POTION4)) + brewsAndRestores() + food(10, 10),
        combatStats(attack = 60, strength = 99, defence = 1, prayer = 52),
    ),
    HYBRID(
        "Hybrid", 126, intArrayOf(ItemId.HELM_OF_NEITIZNOT, ItemId.MYSTIC_ROBE_TOP, ItemId.ANCIENT_STAFF),
        gear(
            EquipmentSlot.HELMET to ItemId.HELM_OF_NEITIZNOT, EquipmentSlot.CAPE to ItemId.SARADOMIN_CAPE,
            EquipmentSlot.AMULET to ItemId.AMULET_OF_GLORY4, EquipmentSlot.PLATE to ItemId.MYSTIC_ROBE_TOP,
            EquipmentSlot.LEGS to ItemId.MYSTIC_ROBE_BOTTOM, EquipmentSlot.WEAPON to ItemId.ANCIENT_STAFF,
            EquipmentSlot.SHIELD to ItemId.DRAGON_DEFENDER, EquipmentSlot.HANDS to ItemId.BARROWS_GLOVES,
            EquipmentSlot.BOOTS to ItemId.DRAGON_BOOTS, EquipmentSlot.RING to ItemId.BERSERKER_RING,
        ),
        listOf(KitItem(ItemId.BLACK_DHIDE_BODY), KitItem(ItemId.ABYSSAL_WHIP), KitItem(ItemId.DRAGON_DAGGERP_5698)) +
            barrageRunes() + meleeSupplies() + food(9, 8),
        maxed(), Spellbook.ANCIENT,
    ),
    TRIBRID(
        "Tribrid", 126, intArrayOf(ItemId.HELM_OF_NEITIZNOT, ItemId.AHRIMS_ROBETOP, ItemId.AHRIMS_STAFF),
        gear(
            EquipmentSlot.HELMET to ItemId.HELM_OF_NEITIZNOT, EquipmentSlot.CAPE to ItemId.ZAMORAK_CAPE,
            EquipmentSlot.AMULET to ItemId.AMULET_OF_GLORY4, EquipmentSlot.PLATE to ItemId.AHRIMS_ROBETOP,
            EquipmentSlot.LEGS to ItemId.AHRIMS_ROBESKIRT, EquipmentSlot.WEAPON to ItemId.AHRIMS_STAFF,
            EquipmentSlot.HANDS to ItemId.BARROWS_GLOVES,
            EquipmentSlot.BOOTS to ItemId.DRAGON_BOOTS, EquipmentSlot.RING to ItemId.BERSERKER_RING,
        ) + (EquipmentSlot.AMMUNITION to KitItem(ItemId.DIAMOND_BOLTS_E, 200)),
        // Ahrim's staff is two-handed: the defender goes with the whip switch in the inventory
        listOf(KitItem(ItemId.KARILS_LEATHERTOP), KitItem(ItemId.RUNE_CROSSBOW), KitItem(ItemId.ABYSSAL_WHIP),
            KitItem(ItemId.DRAGON_DEFENDER), KitItem(ItemId.DRAGON_DAGGERP_5698)) + barrageRunes() +
            listOf(KitItem(ItemId.SUPER_COMBAT_POTION4), KitItem(ItemId.RANGING_POTION4)) + brewsAndRestores() + food(8, 6),
        maxed(), Spellbook.ANCIENT,
    );

    companion object {
        val VALUES = values()
    }
}

private fun gear(vararg slots: Pair<EquipmentSlot, Int>): Map<EquipmentSlot, KitItem> =
    linkedMapOf(*slots.map { it.first to KitItem(it.second) }.toTypedArray())

private fun brewsAndRestores() = listOf(KitItem(ItemId.SARADOMIN_BREW4), KitItem(ItemId.SARADOMIN_BREW4),
    KitItem(ItemId.SUPER_RESTORE4), KitItem(ItemId.SUPER_RESTORE4))

private fun meleeSupplies() = listOf(KitItem(ItemId.SUPER_COMBAT_POTION4)) + brewsAndRestores()

/** Ice Barrage: 6 water, 4 blood, 2 death a cast; 100 casts, loose in the inventory (so they carry the kit mark). */
private fun barrageRunes() = listOf(KitItem(ItemId.WATER_RUNE, 600), KitItem(ItemId.BLOOD_RUNE, 400), KitItem(ItemId.DEATH_RUNE, 200))

private fun food(sharks: Int, karambwans: Int) = List(sharks) { KitItem(ItemId.SHARK) } + List(karambwans) { KitItem(ItemId.COOKED_KARAMBWAN) }

private fun combatStats(attack: Int, strength: Int, defence: Int, prayer: Int) = linkedMapOf(
    SkillConstants.ATTACK to attack, SkillConstants.STRENGTH to strength, SkillConstants.DEFENCE to defence,
    SkillConstants.HITPOINTS to 99, SkillConstants.RANGED to 99, SkillConstants.MAGIC to 99, SkillConstants.PRAYER to prayer,
)

private fun maxed() = combatStats(attack = 99, strength = 99, defence = 99, prayer = 99)

/**
 * Spawning kits: free, only somewhere safe (not the wilderness or a PvP world tile, not an instance, not in combat),
 * with no gear of the player's own worn or carried (a kit fills both). Kit items from an earlier kit are replaced.
 * The kit's combat stats are set (the player is asked first when that lowers any of them). Iron men can't use kits.
 */
object PremadeKits {
    /** Player attribute: the name of the last kit spawned ("Last kit" tile). */
    const val LAST_KIT = "premade_kit_last"

    /** True when the cache has the kits tab (KitsPatch); otherwise the music tab stays. */
    val available: Boolean by lazy {
        try {
            ComponentDefinitions.get(KitsUi.ID, KitsUi.TITLE)?.text == "Premade Kits"
        } catch (e: Exception) {
            false
        }
    }

    fun last(player: Player): PremadeKit? {
        val name = player.attributes[LAST_KIT] as? String ?: return null
        return PremadeKit.VALUES.firstOrNull { it.name == name }
    }

    /** Why [player] can't spawn a kit here and now, or null. */
    fun refusal(player: Player): String? = when {
        player.isIronman -> "Iron men bring their own gear: kits aren't for you."
        player.isDead || player.isFinished || player.isLocked -> "You can't do that right now."
        WildernessArea.isWithinWilderness(player) || PvpWorld.isPvpTile(player) -> "You can't spawn a kit in a dangerous area."
        player.area is DynamicArea -> "You can't spawn a kit in here."
        player.isUnderCombat() -> "You can't spawn a kit while in combat."
        ownItems(player) -> "Bank your own items first: a kit fills your inventory and equipment."
        else -> null
    }

    private fun ownItems(player: Player): Boolean =
        player.inventory.container.items.values.any { it != null && !KitItems.isKitItem(it) } ||
            player.equipment.container.items.values.any { it != null && !KitItems.isKitItem(it) }

    /** "Spawn" on a tile: asks first when the kit lowers a combat stat, then spawns it. */
    fun request(player: Player, kit: PremadeKit) {
        val why = refusal(player)
        if (why != null) {
            player.sendMessage(why)
            return
        }
        val lowered = kit.stats.filter { (skill, level) -> player.skills.getLevelForXp(skill) > level }.keys
        if (lowered.isEmpty()) {
            spawn(player, kit)
            return
        }
        val names = lowered.joinToString(", ") { Skills.getSkillName(it) }
        player.options("Set $names lower for the ${kit.title} kit?") {
            "Yes, set my stats and spawn the kit." { spawn(player, kit) }
            "No."()
        }
    }

    fun spawn(player: Player, kit: PremadeKit) {
        val why = refusal(player)
        if (why != null) {
            player.sendMessage(why)
            return
        }
        KitItems.removeAll(player)
        val equipment = player.equipment
        for ((slot, stack) in kit.equipment) equipment.set(slot, item(stack))
        for (stack in kit.inventory) player.inventory.addItem(item(stack))
        for ((skill, level) in kit.stats) player.skills.setSkill(skill, level, Skills.getXPForLevel(level).toDouble())
        val spellbook = kit.spellbook
        if (spellbook != null && player.combatDefinitions.spellbook != spellbook) player.combatDefinitions.setSpellbook(spellbook, true)
        equipment.refreshAll()
        player.inventory.refreshAll()
        player.bonuses.update()
        player.appearance.resetRenderAnimation()
        player.combatDefinitions.refresh()
        player.varManager.sendBit(COMBAT_LEVEL_VARBIT, player.skills.combatLevel)
        player.attributes[LAST_KIT] = kit.name
        player.sendMessage("You spawn the ${kit.title} kit. It's free gear: it can't be traded, sold or dropped, and it vanishes on death.")
        if (available) KitsTab.render(player)
    }

    /** "Re-spawn" on the Last kit tile. */
    fun respawnLast(player: Player) {
        val kit = last(player)
        if (kit == null) {
            player.sendMessage("You haven't spawned a kit yet.")
            return
        }
        request(player, kit)
    }

    private fun item(stack: KitItem): Item = KitItems.tag(Item(stack.id, stack.amount, DegradableItem.getFullCharges(stack.id)))

    /** Varbit the client shows the combat level from (Skills sends it on a level-up). */
    private const val COMBAT_LEVEL_VARBIT = 13027
}

/** The Premade Kits side tab: it takes the music tab's slot when the cache has it (MusicPlayerInterface.open). */
object KitsTab {
    private const val ITEM_ZOOM = 100

    fun open(player: Player) {
        GameInterface.PREMADE_KITS_TAB.open(player)
    }

    fun render(player: Player) {
        val d = player.packetDispatcher
        val last = PremadeKits.last(player)
        for (k in 0 until KitsUi.TILES) {
            val kit = PremadeKit.VALUES.getOrNull(k)
            val selected = kit != null && kit == last
            d.sendComponentVisibility(KitsUi.ID, KitsUi.tile(k, KitsUi.FRAME_SELECTED), !selected)
            if (kit != null) {
                d.sendComponentText(KitsUi.ID, KitsUi.tile(k, KitsUi.NAME), if (selected) "<col=ffffff>${kit.title}</col>" else kit.title)
                for (j in 0 until 3) d.sendComponentItem(KitsUi.ID, KitsUi.tile(k, KitsUi.ITEM + j), kit.icons[j], ITEM_ZOOM)
            } else {
                d.sendComponentText(KitsUi.ID, KitsUi.tile(k, KitsUi.LEVEL), last?.level?.toString() ?: "")
            }
        }
    }

    fun click(player: Player, component: Int) {
        val k = KitsUi.tileOf(component)
        if (k < 0) return
        val kit = PremadeKit.VALUES.getOrNull(k)
        if (kit == null) PremadeKits.respawnLast(player) else PremadeKits.request(player, kit)
    }
}
