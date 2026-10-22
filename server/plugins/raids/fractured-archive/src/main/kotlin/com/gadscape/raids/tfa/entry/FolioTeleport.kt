package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport
import com.zenyte.game.content.skills.magic.spells.teleports.TeleportType
import com.zenyte.game.item.Item
import com.zenyte.game.model.item.ItemOnItemAction
import com.zenyte.game.model.item.pluginextensions.ItemPlugin
import com.zenyte.game.model.item.pluginextensions.bindKt
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player

/**
 * Archivist's folio (spec §3.1) [DOC wiki Archivist's folio]: 34722 inert (Read / Charge), 34723 charged
 * (Read / Charge / Check). "Read" teleports to the raid lobby; each Ascension shard gives 5 charges [DOC]; the cap is
 * 1000 [HOUSE] (`tfa.json folio`). Charges live in varbit 16322 `charges_archivists_folio_quantity` (16 bits) [CACHE];
 * a folio that runs out turns back into the inert one.
 *
 * The teleport uses the engine's scroll teleport structure [INF: no folio-specific seq or spot exists in the 2746
 * diff, so the stock item-teleport look is used]; one charge is used when the teleport actually starts.
 */
object Folio {

    @JvmStatic
    fun charges(player: Player): Int = player.varManager.getBitValue(TfaIds.Varbit.CHARGES_FOLIO)

    private fun setCharges(player: Player, value: Int) {
        player.varManager.sendBit(TfaIds.Varbit.CHARGES_FOLIO, value.coerceIn(0, 65535))
    }

    /** How many of [available] shards fit under the cap (pure). */
    @JvmStatic
    fun shardsAccepted(charges: Int, available: Int, cfg: RaidConfig.Folio): Int {
        if (available <= 0 || cfg.chargesPerShard <= 0) return 0
        val room = (cfg.chargeCap - charges).coerceAtLeast(0) / cfg.chargesPerShard
        return minOf(available, room)
    }

    fun read(player: Player, item: Item, slot: Int) {
        if (item.id == TfaIds.Obj.FOLIO_INERT || charges(player) <= 0) {
            player.sendMessage("The folio has no charges. Charge it with ascension shards to use it.")
            return
        }
        LobbyTeleport(slot).teleport(player)
    }

    fun check(player: Player) {
        val n = charges(player)
        player.sendMessage("Your archivist's folio has ${if (n == 0) "no" else n} charge${if (n == 1) "" else "s"}.")
    }

    /** Asks how many shards to use, then charges. */
    fun promptCharge(player: Player) {
        val cfg = TfaConfig.current.raid.folio
        val have = player.inventory.getAmountOf(TfaIds.Obj.ASCENSION_SHARDS)
        if (have <= 0) {
            player.sendMessage("You need ascension shards to charge the folio. Each shard gives ${cfg.chargesPerShard} charges.")
            return
        }
        if (shardsAccepted(charges(player), have, cfg) <= 0) {
            player.sendMessage("Your folio cannot hold any more charges.")
            return
        }
        player.sendInputInt("How many shards would you like to use? (1 shard = ${cfg.chargesPerShard} charges)") { amount ->
            charge(player, amount)
        }
    }

    /** Uses up to [requested] shards from the inventory. */
    fun charge(player: Player, requested: Int) {
        val cfg = TfaConfig.current.raid.folio
        val slot = folioSlot(player) ?: return
        val have = player.inventory.getAmountOf(TfaIds.Obj.ASCENSION_SHARDS)
        val used = shardsAccepted(charges(player), minOf(have, requested.coerceAtLeast(0)), cfg)
        if (used <= 0) {
            player.sendMessage("Your folio cannot hold any more charges.")
            return
        }
        player.inventory.deleteItem(TfaIds.Obj.ASCENSION_SHARDS, used)
        setCharges(player, charges(player) + used * cfg.chargesPerShard)
        if (player.inventory.getItem(slot)?.id == TfaIds.Obj.FOLIO_INERT) {
            player.inventory.set(slot, Item(TfaIds.Obj.FOLIO))
        }
        player.sendMessage("You add $used ascension shard${if (used == 1) "" else "s"} to the folio. It now has ${charges(player)} charges.")
    }

    private fun folioSlot(player: Player): Int? {
        for (slot in 0 until 28) {
            val id = player.inventory.getItem(slot)?.id ?: continue
            if (id == TfaIds.Obj.FOLIO || id == TfaIds.Obj.FOLIO_INERT) return slot
        }
        return null
    }

    /** The folio's teleport to `tiles.lobbyArrival`; [slot] is where the folio sits (it may turn inert). */
    private class LobbyTeleport(private val slot: Int) : Teleport {
        override fun getType(): TeleportType = TeleportType.SCROLL_TELEPORT

        override fun getDestination(): Location {
            val t = TfaConfig.current.raid.tiles.lobbyArrival
            return Location(t.x, t.y, t.plane)
        }

        override fun getLevel(): Int = 0

        override fun getExperience(): Double = 0.0

        override fun getRandomizationDistance(): Int = 0

        override fun getRunes(): Array<Item>? = null

        override fun getWildernessLevel(): Int = Teleport.WILDERNESS_LEVEL

        override fun isCombatRestricted(): Boolean = false

        override fun onUsage(player: Player) {
            val left = (charges(player) - 1).coerceAtLeast(0)
            setCharges(player, left)
            if (left == 0) {
                val item = player.inventory.getItem(slot)
                if (item != null && item.id == TfaIds.Obj.FOLIO) player.inventory.set(slot, Item(TfaIds.Obj.FOLIO_INERT))
                player.sendMessage("Your archivist's folio has run out of charges.")
            } else {
                player.sendMessage("Your archivist's folio has $left charge${if (left == 1) "" else "s"} left.")
            }
        }
    }
}

/** Read / Charge / Check on the folio (both forms). Plugin-scanned. */
class FolioPlugin : ItemPlugin() {
    override fun handle() {
        bindKt("Read") { Folio.read(player, item, slotId) }
        bindKt("Charge") { Folio.promptCharge(player) }
        bindKt("Check") { Folio.check(player) }
    }

    override fun getItems(): IntArray = intArrayOf(TfaIds.Obj.FOLIO, TfaIds.Obj.FOLIO_INERT)
}

/** Ascension shards on the folio (either way round). Plugin-scanned. */
class FolioChargeAction : ItemOnItemAction {
    override fun handleItemOnItemAction(player: Player, from: Item, to: Item, fromSlot: Int, toSlot: Int) {
        Folio.promptCharge(player)
    }

    override fun getItems(): IntArray = intArrayOf(TfaIds.Obj.ASCENSION_SHARDS, TfaIds.Obj.FOLIO, TfaIds.Obj.FOLIO_INERT)

    override fun getMatchingPairs(): Array<ItemOnItemAction.ItemPair> = arrayOf(
        ItemOnItemAction.ItemPair.of(TfaIds.Obj.ASCENSION_SHARDS, TfaIds.Obj.FOLIO),
        ItemOnItemAction.ItemPair.of(TfaIds.Obj.ASCENSION_SHARDS, TfaIds.Obj.FOLIO_INERT),
    )
}
