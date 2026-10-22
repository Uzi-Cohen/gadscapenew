package com.gadscape.raids.tfa.debug

import com.gadscape.game.content.omnishop.OmniShop
import com.gadscape.game.content.omnishop.OmniShopDefinition
import com.gadscape.game.content.omnishop.OmniShopStock
import com.gadscape.game.content.omnishop.VarbitCurrency
import com.gadscape.game.world.PrivateScene
import com.gadscape.game.world.entity.npc.MultiHeadIconNpc
import com.gadscape.game.world.entity.npc.NpcHeadIcons
import com.gadscape.network.rsprot.CamTarget
import com.gadscape.network.rsprot.IfScriptTriggerRouter
import com.gadscape.raids.tfa.ids.TfaIds
import com.google.common.eventbus.Subscribe
import com.zenyte.game.item.ItemId
import com.zenyte.game.world.World
import com.zenyte.game.world.`object`.WorldObject
import com.zenyte.game.world.entity.InterpolatingHitBar
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * `::tfa proto ...`: in-game checks for the engine and protocol prerequisites (unit engine-proto, WU-02/03/06):
 * camera targets, interpolating head bars, two head icon slots, combat-level override, the 2746 HP HUD with its
 * extra bar, per-player graphics and locs, the omnishop, and the IF_SCRIPT_TRIGGER routes.
 */
object EngineProtoCommands {

    /** Giant krill and Halibut are newer than GadScape's ItemId table [CACHE 2746 obj giant_krill 32312, halibut 32336]. */
    private const val GIANT_KRILL = 32312
    private const val HALIBUT = 32336

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) {
        TfaCommands.register(
            "proto",
            "proto <cam|headbar|icons|level|hud|gfx|loc|shop|triggers> ...",
            "engine checks: cam <name|npc|tile x y z|reset>, headbar <85-89> <ticks>, icons <i0> [i1], level <n>, " +
                "hud <npc> <max> [cur base|off], gfx <spot>, loc <id|clear>, shop [normal|failure|classic], triggers",
        ) { player, args -> execute(player, args) }
    }

    private fun execute(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "cam" -> cam(player, args.drop(1))
            "headbar" -> headbar(player, args.drop(1))
            "icons" -> icons(player, args.drop(1))
            "level" -> level(player, args.drop(1))
            "hud" -> hud(player, args.drop(1))
            "gfx" -> gfx(player, args.drop(1))
            "loc" -> loc(player, args.drop(1))
            "shop" -> shop(player, args.drop(1))
            "triggers" -> IfScriptTriggerRouter.describe().ifEmpty { listOf("no IF_SCRIPT_TRIGGER routes") }
                .forEach { player.sendMessage(it) }
            else -> player.sendMessage("::tfa proto <cam|headbar|icons|level|hud|gfx|loc|shop|triggers> ...")
        }
    }

    private fun nearestNpc(player: Player): NPC? =
        World.getNPCs().filterNotNull()
            .filter { !it.isFinished && it.plane == player.plane && it.location.withinDistance(player.x, player.y, 15) }
            .minByOrNull { (it.x - player.x) * (it.x - player.x) + (it.y - player.y) * (it.y - player.y) }

    private fun cam(player: Player, args: List<String>) {
        val ok = when (args.firstOrNull()?.lowercase()) {
            null, "reset" -> CamTarget.reset(player)
            "npc" -> nearestNpc(player)?.let { CamTarget.npc(player, it) } ?: false
            "tile" -> {
                val x = args.getOrNull(1)?.toIntOrNull() ?: player.x
                val y = args.getOrNull(2)?.toIntOrNull() ?: player.y
                val z = args.getOrNull(3)?.toIntOrNull() ?: player.plane
                CamTarget.tile(player, x, y, z)
            }
            else -> World.getPlayer(args.joinToString(" ")).map { CamTarget.player(player, it) }.orElse(false)
        }
        player.sendMessage(if (ok) "Camera: ${CamTarget.describe(player) ?: "you"}" else "No such target.")
    }

    private fun headbar(player: Player, args: List<String>) {
        val type = args.getOrNull(0)?.toIntOrNull() ?: TfaIds.Headbar.CHARGE_70
        val ticks = args.getOrNull(1)?.toIntOrNull() ?: 12
        val bar = when (type) {
            TfaIds.Headbar.CHARGE_70 -> InterpolatingHitBar.Charge70()
            TfaIds.Headbar.SHIELD_60 -> InterpolatingHitBar.Shield60()
            TfaIds.Headbar.BLOOD_45 -> InterpolatingHitBar.Blood45()
            TfaIds.Headbar.HEAT_60 -> InterpolatingHitBar.Heat60()
            TfaIds.Headbar.SHIELD_30 -> InterpolatingHitBar.Shield30()
            else -> return player.sendMessage("Headbars 85-89 only.")
        }
        val target = nearestNpc(player) ?: player
        bar.interpolate(0.0, 1.0, ticks).show(target)
        player.sendMessage("Headbar $type filling over $ticks ticks on $target.")
    }

    /** A test NPC with eight head icon slots (spawned on your tile, removed by `::tfa proto icons clear`). */
    private class IconTestNpc(id: Int, tile: Location) : NPC(id, tile, true), MultiHeadIconNpc {
        override val headIcons = NpcHeadIcons()
    }

    private const val ICON_NPC = "tfa_proto_icon_npc"

    private fun icons(player: Player, args: List<String>) {
        (player.temporaryAttributes.remove(ICON_NPC) as? NPC)?.finish()
        if (args.firstOrNull() == "clear") return
        val indexes = args.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(NpcHeadIcons.DEFLECT_MELEE, NpcHeadIcons.DEFLECT_MAGIC) }
        val npc = IconTestNpc(TfaIds.Npc.OCELLUS_P3, Location(player.x + 2, player.y, player.plane))
        npc.headIcons.setPrayers(*indexes.toIntArray())
        npc.spawn()
        player.temporaryAttributes[ICON_NPC] = npc
        player.sendMessage("Spawned ${npc.id} with head icons ${npc.headIcons}.")
    }

    private fun level(player: Player, args: List<String>) {
        val npc = nearestNpc(player) ?: return player.sendMessage("No NPC nearby.")
        val level = args.firstOrNull()?.toIntOrNull() ?: 1601
        npc.setCombatLevelChange(level)
        player.sendMessage("${npc.id} now shows combat level ${if (level < 0) "(cache)" else level}.")
    }

    private fun hud(player: Player, args: List<String>) {
        val hud = player.hpHud
        if (args.firstOrNull() == "close") return hud.close()
        if (args.firstOrNull() == "off") return hud.hideExtraBar()
        val npcId = args.getOrNull(0)?.toIntOrNull() ?: TfaIds.Npc.BORK_SMORKS_HUD
        val max = args.getOrNull(1)?.toIntOrNull() ?: 1500
        val extraCurrent = args.getOrNull(2)?.toIntOrNull()
        val extraBase = args.getOrNull(3)?.toIntOrNull()
        if (extraCurrent != null && extraBase != null) {
            if (hud.isOpen) hud.showExtraBar(extraCurrent, extraBase)
            else hud.openWithExtraBar(npcId, max, max, extraCurrent, extraBase)
        } else if (hud.isOpen) {
            hud.switchNpc(npcId, max, max)
        } else {
            hud.open(npcId, max)
        }
        player.sendMessage("HP HUD npc $npcId $max" + (if (extraCurrent != null) ", extra $extraCurrent/$extraBase" else ""))
    }

    private fun gfx(player: Player, args: List<String>) {
        val id = args.firstOrNull()?.toIntOrNull() ?: return player.sendMessage("::tfa proto gfx <spotanim>")
        val tile = Location(player.x + 1, player.y, player.plane)
        player.sendMessage(if (PrivateScene.graphic(player, Graphics(id), tile)) "Graphic $id at $tile (only you see it)." else "Tile not in scope.")
    }

    private fun loc(player: Player, args: List<String>) {
        if (args.firstOrNull() == "clear") {
            PrivateScene.clear(player)
            return player.sendMessage("Private locs cleared.")
        }
        val id = args.firstOrNull()?.toIntOrNull() ?: return player.sendMessage("::tfa proto loc <id|clear>")
        val obj = WorldObject(id, 10, 0, player.x, player.y + 1, player.plane)
        PrivateScene.spawnLoc(player, obj)
        player.sendMessage("Loc $id at ${obj.x},${obj.y} (only you see it).")
    }

    /**
     * The Alliance Supplies shops (§3.12) as test definitions: rows in `omnishop_shop_stock` order, costs from
     * `omnishop_stock_cost`, stock from the wiki [CACHE dbrows 17150-17162; DOC]. WU-17 owns the real ones.
     */
    private fun shopDefinition(failure: Boolean): OmniShopDefinition {
        val d = TfaIds.Dbrow
        val rows = buildList {
            add(OmniShopStock(d.STOCK_NOTE, TfaIds.Obj.ALLIANCE_LOG, 0, 1, buyable = false, viewOnly = true))
            if (failure) add(OmniShopStock(d.STOCK_LEFTOVERS, TfaIds.Obj.LEFTOVERS, 0, 1))
            add(OmniShopStock(d.STOCK_SARADOMIN_BREW_4, ItemId.SARADOMIN_BREW4, 3, 6))
            add(OmniShopStock(d.STOCK_SUPER_RESTORE_4, ItemId.SUPER_RESTORE4, 3, 4))
            add(OmniShopStock(d.STOCK_PRAYER_POTION_4, ItemId.PRAYER_POTION4, 2, 4))
            add(OmniShopStock(d.STOCK_MANTA_RAY, ItemId.MANTA_RAY, 2, 10))
            add(OmniShopStock(d.STOCK_HALIBUT, HALIBUT, 2, 5))
            add(OmniShopStock(d.STOCK_GIANT_KRILL, GIANT_KRILL, 1, 10))
            add(OmniShopStock(d.STOCK_PRAYER_POTION_2, ItemId.PRAYER_POTION2, 1, 1))
            add(OmniShopStock(d.STOCK_RANGING_POTION_2, ItemId.RANGING_POTION2, 2, 1))
            add(OmniShopStock(d.STOCK_SUPER_COMBAT_2, ItemId.SUPER_COMBAT_POTION2, 2, 1))
        }
        return OmniShopDefinition(
            shopDbrow = if (failure) d.SHOP_FAILURE else d.SHOP_NORMAL,
            title = "Alliance Supplies",
            invId = TfaIds.Inv.SUPPLIES_SHOP,
            invCapacity = 10,
            currency = VarbitCurrency(TfaIds.Varbit.SUPPLY_POINTS, "Supply Credit", "Supply Credits"),
            stock = rows,
            quantityKeys = intArrayOf(1, 5, 6, 2), // omnishop_buy_q1..q4 of 17150/17151 (buy 1, 2, 3, 5)
        )
    }

    private fun shop(player: Player, args: List<String>) {
        val mode = args.firstOrNull()?.lowercase() ?: "normal"
        val definition = shopDefinition(failure = mode == "failure")
        if (args.getOrNull(1) == "reset") OmniShop.resetStock(player, definition)
        val currency = definition.currency as VarbitCurrency
        if (player.varManager.getBitValue(currency.varbit) == 0) player.varManager.sendBitInstant(currency.varbit, 83)
        if (mode == "classic") {
            OmniShop.openClassic(player, definition, OmniShop.stockState(player, definition))
        } else {
            OmniShop.open(player, definition)
        }
    }
}
