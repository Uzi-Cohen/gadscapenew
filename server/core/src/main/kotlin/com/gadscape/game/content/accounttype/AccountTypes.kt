package com.gadscape.game.content.accounttype

import com.gadscape.game.world.entity.player.selectedGameMode
import com.zenyte.game.GameInterface
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.model.item.degradableitems.DegradableItem
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.Skills
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options
import com.zenyte.game.world.entity.player.privilege.GameMode
import com.zenyte.plugins.renewednpc.ZenyteGuide

/**
 * The account types picked when a new player starts. Ironman and Hardcore keep their own [GameMode];
 * Eco, PvP and Ultraman are regular accounts with their own rates.
 */
enum class AccountType(
    val title: String,
    val gameMode: GameMode,
    val combatRate: Int,
    val skillingRate: Int,
    /** Drop rate boost from the mode, 0.02 = 2%. Null keeps the old exp-mode table. */
    val dropRate: Double?,
    val description: String,
) {
    ECO("Eco", GameMode.REGULAR, 100, 100, 0.02,
        "Start from combat level 3. 100x exp and a 2% drop rate boost."),
    PVP("PvP", GameMode.REGULAR, 100, 100, 0.0,
        "Pick a PK build, get its stats and kit, and go PKing. 100x exp, no drop rate boost."),
    IRONMAN("Ironman", GameMode.STANDARD_IRON_MAN, 80, 80, null,
        "Stand alone: no trading, no help. 80x exp."),
    HARDCORE("Hardcore Ironman", GameMode.HARDCORE_IRON_MAN, 80, 80, null,
        "An Ironman with one life. 80x exp."),
    ULTRAMAN("Ultraman", GameMode.REGULAR, 10, 10, 0.05,
        "10x exp and a 5% drop rate boost. Pick one sigil and get the league tools."),
    ;

    companion object {
        @JvmStatic
        fun of(player: Player): AccountType? = entries.firstOrNull { it.name == player.accountTypeName }
    }
}

var Player.accountTypeName: String by persistentAttribute("account_type", "")
var Player.sigilName: String by persistentAttribute("ultraman_sigil", "")

val Player.accountType: AccountType? get() = AccountType.of(this)

/** Sigils for Ultraman accounts, after the Deadman ones. Combat sigils only work against monsters. */
enum class Sigil(val title: String, val description: String) {
    FORMIDABLE_FIGHTER("Sigil of the Formidable Fighter", "+20% melee accuracy and +10% melee damage against monsters."),
    RUTHLESS_RANGER("Sigil of the Ruthless Ranger", "+20% ranged accuracy and +10% ranged damage against monsters."),
    METICULOUS_MAGE("Sigil of the Meticulous Mage", "+20% magic accuracy and +10% magic damage against monsters."),
    FORTUNE("Sigil of Fortune", "+5% drop rate, on top of the Ultraman 5%."),
    DEVOTION("Sigil of Devotion", "Prayer drains half as fast."),
    PRODIGY("Sigil of the Prodigy", "Double experience in non-combat skills."),
    ;

    companion object {
        @JvmStatic
        fun of(player: Player): Sigil? =
            if (player.accountType != AccountType.ULTRAMAN) null else entries.firstOrNull { it.name == player.sigilName }
    }
}

/** Hooks called from the combat, prayer, exp and drop code. */
object Sigils {
    const val MELEE = 0
    const val RANGED = 1
    const val MAGIC = 2

    private fun combatSigil(style: Int) = when (style) {
        MELEE -> Sigil.FORMIDABLE_FIGHTER
        RANGED -> Sigil.RUTHLESS_RANGER
        else -> Sigil.METICULOUS_MAGE
    }

    @JvmStatic
    fun accuracy(player: Player, target: Entity?, style: Int): Double =
        if (target is NPC && Sigil.of(player) == combatSigil(style)) 1.20 else 1.0

    @JvmStatic
    fun maxHit(player: Player, target: Entity?, style: Int, maxHit: Int): Int =
        if (target is NPC && Sigil.of(player) == combatSigil(style)) (maxHit * 1.10).toInt() else maxHit

    @JvmStatic
    fun prayerResistance(player: Player, resistance: Int): Int =
        if (Sigil.of(player) == Sigil.DEVOTION) resistance * 2 else resistance

    @JvmStatic
    fun experience(player: Player, skill: Int): Double =
        if (Sigil.of(player) == Sigil.PRODIGY && !Skills.isCombatSkill(skill)) 2.0 else 1.0

    /** The mode's drop rate boost (0.05 = 5%), or null when the old exp-mode table decides. */
    @JvmStatic
    fun modeDropRate(player: Player): Double? {
        val base = player.accountType?.dropRate ?: return null
        return base + if (Sigil.of(player) == Sigil.FORTUNE) 0.05 else 0.0
    }
}

/** PK builds a PvP account can start as: levels, worn gear and inventory. */
enum class PkBuild(
    val title: String,
    val levels: Map<Int, Int>,
    val worn: List<Int>,
    val inventory: List<Item>,
) {
    MAIN("Max main", mapOf(
        SkillConstants.ATTACK to 99, SkillConstants.STRENGTH to 99, SkillConstants.DEFENCE to 99,
        SkillConstants.HITPOINTS to 99, SkillConstants.RANGED to 99, SkillConstants.MAGIC to 99, SkillConstants.PRAYER to 99,
    ), listOf(ItemId.HELM_OF_NEITIZNOT, ItemId.FIRE_CAPE, ItemId.AMULET_OF_FURY, ItemId.MYSTIC_ROBE_TOP,
        ItemId.MYSTIC_ROBE_BOTTOM, ItemId.ANCIENT_STAFF, ItemId.DRAGON_DEFENDER, ItemId.BARROWS_GLOVES,
        ItemId.DRAGON_BOOTS, ItemId.BERSERKER_RING),
        listOf(Item(ItemId.ABYSSAL_WHIP), Item(ItemId.DRAGON_DAGGERP), Item(ItemId.RUNE_CROSSBOW), Item(ItemId.DIAMOND_BOLTS_E, 100),
            Item(ItemId.BLACK_DHIDE_BODY), Item(ItemId.RUNE_PLATELEGS))),
    ZERKER("Zerker (45 def)", mapOf(
        SkillConstants.ATTACK to 75, SkillConstants.STRENGTH to 99, SkillConstants.DEFENCE to 45,
        SkillConstants.HITPOINTS to 99, SkillConstants.RANGED to 99, SkillConstants.MAGIC to 99, SkillConstants.PRAYER to 52,
    ), listOf(ItemId.HELM_OF_NEITIZNOT, ItemId.FIRE_CAPE, ItemId.AMULET_OF_FURY, ItemId.FIGHTER_TORSO,
        ItemId.MYSTIC_ROBE_BOTTOM, ItemId.ANCIENT_STAFF, ItemId.DRAGON_DEFENDER, ItemId.BARROWS_GLOVES,
        ItemId.CLIMBING_BOOTS, ItemId.BERSERKER_RING),
        listOf(Item(ItemId.ABYSSAL_WHIP), Item(ItemId.DRAGON_DAGGERP), Item(ItemId.GRANITE_MAUL), Item(ItemId.RUNE_PLATELEGS))),
    PURE("Pure (1 def)", mapOf(
        SkillConstants.ATTACK to 75, SkillConstants.STRENGTH to 99, SkillConstants.DEFENCE to 1,
        SkillConstants.HITPOINTS to 99, SkillConstants.RANGED to 99, SkillConstants.MAGIC to 94, SkillConstants.PRAYER to 52,
    ), listOf(ItemId.COIF, ItemId.ZAMORAK_CAPE, ItemId.AMULET_OF_GLORY4, ItemId.LEATHER_BODY_G,
        ItemId.BLACK_DHIDE_CHAPS, ItemId.MAGIC_SHORTBOW, ItemId.MITHRIL_GLOVES, ItemId.CLIMBING_BOOTS),
        listOf(Item(ItemId.RUNE_ARROW, 250), Item(ItemId.DRAGON_SCIMITAR), Item(ItemId.DRAGON_DAGGERP), Item(ItemId.GRANITE_MAUL),
            Item(ItemId.ANCIENT_STAFF), Item(ItemId.MONKS_ROBE_TOP))),
    TANK("Med level (70 def)", mapOf(
        SkillConstants.ATTACK to 80, SkillConstants.STRENGTH to 90, SkillConstants.DEFENCE to 70,
        SkillConstants.HITPOINTS to 90, SkillConstants.RANGED to 90, SkillConstants.MAGIC to 94, SkillConstants.PRAYER to 70,
    ), listOf(ItemId.HELM_OF_NEITIZNOT, ItemId.OBSIDIAN_CAPE, ItemId.AMULET_OF_GLORY4, ItemId.BLACK_DHIDE_BODY,
        ItemId.BLACK_DHIDE_CHAPS, ItemId.ANCIENT_STAFF, ItemId.DRAGON_DEFENDER, ItemId.BARROWS_GLOVES,
        ItemId.CLIMBING_BOOTS, ItemId.BERSERKER_RING),
        listOf(Item(ItemId.ABYSSAL_WHIP), Item(ItemId.DRAGON_DAGGERP), Item(ItemId.RUNE_CROSSBOW), Item(ItemId.DIAMOND_BOLTS_E, 100))),
    ;

    companion object {
        /** Food, potions and barrage/vengeance runes every build gets. */
        val SUPPLIES = listOf(
            Item(ItemId.SUPER_COMBAT_POTION4), Item(ItemId.RANGING_POTION4), Item(ItemId.SARADOMIN_BREW4, 2),
            Item(ItemId.SUPER_RESTORE4, 2), Item(ItemId.COOKED_KARAMBWAN, 4), Item(ItemId.SHARK, 6),
            Item(ItemId.DEATH_RUNE, 500), Item(ItemId.BLOOD_RUNE, 500), Item(ItemId.WATER_RUNE, 1500),
            Item(ItemId.ASTRAL_RUNE, 200), Item(ItemId.EARTH_RUNE, 1000),
        )
    }
}

object AccountSetup {

    /** League tools Ultramen start with. */
    private val ULTRAMAN_TOOLS = intArrayOf(ItemId.INFERNAL_AXE, ItemId.INFERNAL_PICKAXE, ItemId.INFERNAL_HARPOON)

    /** First login of a new character: skip Tutorial Island and pick the account type. */
    @JvmStatic
    fun onUnregisteredLogin(player: Player) {
        player.setLocation(ZenyteGuide.SPAWN_LOCATION)
        player.appearance.isInvisible = false
        WorldTasksManager.schedule({ if (!player.isFinished) openPicker(player) }, 2)
    }

    @JvmStatic
    fun openPicker(player: Player) {
        player.dialogue(ZenyteGuide.NPC_ID) {
            npc("Welcome! Pick how you want to play. You can't change it later, so read each one before you pick.")
            options("Pick your account type") {
                for (type in AccountType.entries) "${type.title}." { WorldTasksManager.schedule { confirm(player, type) } }
            }
        }
    }

    private fun confirm(player: Player, type: AccountType) {
        player.dialogue(ZenyteGuide.NPC_ID) {
            npc("${Colour.DARK_BLUE.wrap(type.title)}: ${type.description}")
            options("Play as ${type.title}?") {
                "Yes, play ${type.title}." { WorldTasksManager.schedule { choose(player, type) } }
                "No, show me the others." { WorldTasksManager.schedule { openPicker(player) } }
            }
        }
    }

    private fun choose(player: Player, type: AccountType) {
        if (player.getBooleanAttribute("registered")) return
        player.accountTypeName = type.name
        player.selectedGameMode = type.gameMode
        player.setExperienceMultiplier(type.combatRate, type.skillingRate)
        player.temporaryAttributes["ironman_setup"] = "register"
        // Design the character next; closing the design screen continues through ZenyteGuide.finishAppearance.
        GameInterface.TUTORIAL_MAKE_OVER.open(player)
    }

    /** Called once the character is registered and has its starter items. */
    @JvmStatic
    fun afterRegistration(player: Player) {
        when (player.accountType) {
            AccountType.PVP -> WorldTasksManager.schedule({ pickBuild(player) }, 2)
            AccountType.ULTRAMAN -> {
                for (id in ULTRAMAN_TOOLS) player.inventory.addOrDrop(Item(id, 1, DegradableItem.getDefaultCharges(id, 0)))
                WorldTasksManager.schedule({ pickSigil(player, 0) }, 2)
            }
            else -> {}
        }
    }

    @JvmStatic
    fun pickBuild(player: Player) {
        if (player.accountType != AccountType.PVP || player.getBooleanAttribute("pvp_build_picked")) return
        player.options("Pick your PK build") {
            for (build in PkBuild.entries) build.title { WorldTasksManager.schedule { applyBuild(player, build) } }
        }
    }

    private fun applyBuild(player: Player, build: PkBuild) {
        if (player.getBooleanAttribute("pvp_build_picked")) return
        player.putBooleanAttribute("pvp_build_picked", true)
        val skills = player.skills
        for ((skill, level) in build.levels) skills.setSkill(skill, level, Skills.getXPForLevel(level).toDouble())
        // Bank whatever the starter gave, so the kit fits.
        for (slot in 0 until 28) player.inventory.getItem(slot)?.let { player.bank.add(it); player.inventory.set(slot, null) }
        val equipment = player.equipment
        for (id in build.worn) {
            val item = Item(id, 1, DegradableItem.getDefaultCharges(id, 0))
            val slot = item.definitions?.slot ?: -1
            if (slot >= 0 && equipment.getItem(slot) == null) equipment.set(slot, item) else player.inventory.addOrDrop(item)
        }
        for (item in build.inventory + PkBuild.SUPPLIES) player.inventory.addOrDrop(Item(item.id, item.amount))
        equipment.refreshAll()
        player.inventory.refreshAll()
        player.bonuses.update()
        player.combatDefinitions.refresh()
        player.appearance.resetRenderAnimation()
        player.dialogue(ZenyteGuide.NPC_ID) {
            npc("You're a ${build.title} now. Your kit is on you; anything you had before is in your bank. " +
                    "Earn PK points in the Wilderness and spend them in the PK store (::pkstore).")
        }
    }

    @JvmStatic
    fun pickSigil(player: Player, page: Int) {
        if (player.accountType != AccountType.ULTRAMAN) return
        val sigils = Sigil.entries
        val shown = if (page == 0) sigils.subList(0, 3) else sigils.subList(3, sigils.size)
        player.options("Pick your sigil (you get one)") {
            for (sigil in shown) sigil.title { WorldTasksManager.schedule { confirmSigil(player, sigil) } }
            (if (page == 0) "More sigils..." else "Back...") { WorldTasksManager.schedule { pickSigil(player, 1 - page) } }
        }
    }

    private fun confirmSigil(player: Player, sigil: Sigil) {
        player.dialogue {
            plain("${Colour.DARK_BLUE.wrap(sigil.title)}<br>${sigil.description}")
            options("Take the ${sigil.title}?") {
                "Yes." { WorldTasksManager.schedule { setSigil(player, sigil) } }
                "No, show me the others." { WorldTasksManager.schedule { pickSigil(player, 0) } }
            }
        }
    }

    private fun setSigil(player: Player, sigil: Sigil) {
        if (player.accountType != AccountType.ULTRAMAN) return
        val changing = player.sigilName.isNotEmpty()
        if (changing) {
            val cost = Item(ItemId.COINS_995, SIGIL_CHANGE_COST)
            if (!player.inventory.containsItem(cost)) {
                player.dialogue { plain("Changing your sigil costs 10,000,000 coins, carried in your inventory.") }
                return
            }
            player.inventory.deleteItem(cost)
        }
        player.sigilName = sigil.name
        player.dialogue { plain("You now carry the ${Colour.DARK_BLUE.wrap(sigil.title)}.<br>${sigil.description}") }
    }

    private const val SIGIL_CHANGE_COST = 10_000_000

    /** ::sigil */
    @JvmStatic
    fun sigilCommand(player: Player) {
        if (player.accountType != AccountType.ULTRAMAN) {
            player.sendMessage("Only Ultraman accounts carry a sigil.")
            return
        }
        val current = Sigil.of(player)
        if (current == null) {
            pickSigil(player, 0)
            return
        }
        player.dialogue {
            plain("Your sigil: ${Colour.DARK_BLUE.wrap(current.title)}<br>${current.description}")
            options("Change it for 10,000,000 coins?") {
                "Yes, show me the sigils." { WorldTasksManager.schedule { pickSigil(player, 0) } }
                "No."()
            }
        }
    }
}
