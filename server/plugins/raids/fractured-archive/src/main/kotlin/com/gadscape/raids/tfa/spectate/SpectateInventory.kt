package com.gadscape.raids.tfa.spectate

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRaids
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.model.ui.UserInterface
import com.zenyte.game.world.entity.player.Player
import java.util.Collections
import java.util.WeakHashMap

/**
 * Interface 978 `tfa_spectate_hud_inv` (spec §3.13 / U15): the viewed member's inventory in the spectator's side panel.
 * 978:0 `items` is an empty 162x253 layer [CACHE]; it is filled with the stock `interface_inv_init` (clientscript 149,
 * 4 x 7) over a mirror inventory (`framework.json spectate.mirrorInventory`, default inv 1031, unused while Practice
 * is off [INF]) that the server keeps in sync with the viewed player's inventory.
 *
 * Pings (dead party members only): the "Ping" op on a slot tells the viewed player which slot their party member
 * pointed at, for `spectate.pingTicks`. The stock `ui_highlight_*` family (8471-8486, style dbrows 17176/17177) is used
 * when `spectate.pingScript` names its entry script; its signature is not confirmed (U15), so the default is a chat
 * message.
 */
object SpectateInventory {

    private val lastHash: MutableMap<Player, Int> = Collections.synchronizedMap(WeakHashMap())

    /** Opens 978 for [viewer] on [member]'s inventory. */
    @JvmStatic
    fun open(viewer: Player, raid: TfaRaid, member: TfaRaidMember, pings: Boolean) {
        val inv = raid.framework.spectate.mirrorInventory
        viewer.interfaceHandler.sendInterface(InterfacePosition.INVENTORY_TAB, TfaIds.Interface.SPECTATE_INV)
        val component = TfaIds.packed(TfaIds.Interface.SPECTATE_INV, TfaIds.Component.SPECTATE_ITEMS)
        viewer.packetDispatcher.sendClientScript(
            TfaIds.Script.INTERFACE_INV_INIT, component, inv, 4, 7, 0, -1,
            if (pings) "Ping" else "", "", "", "", "",
        )
        if (pings) {
            viewer.packetDispatcher.sendComponentSettings(TfaIds.Interface.SPECTATE_INV, TfaIds.Component.SPECTATE_ITEMS, 0, 27, 2)
        }
        refresh(viewer, raid, member.player)
    }

    /** Sends the mirror of [viewed]'s inventory to [viewer]. */
    @JvmStatic
    fun refresh(viewer: Player, raid: TfaRaid, viewed: Player) {
        val inv = raid.framework.spectate.mirrorInventory
        viewer.packetDispatcher.sendUpdateItemContainer(inv, -1, 0, viewed.inventory.container)
        lastHash[viewer] = hash(viewed)
    }

    @JvmStatic
    fun refreshIfChanged(viewer: Player, raid: TfaRaid, viewed: Player) {
        if (!viewer.interfaceHandler.isVisible(TfaIds.Interface.SPECTATE_INV)) return
        val h = hash(viewed)
        if (lastHash[viewer] == h) return
        refresh(viewer, raid, viewed)
    }

    /** A cheap content hash of an inventory (ids and amounts per slot). */
    @JvmStatic
    fun hash(player: Player): Int {
        var h = 1
        val items = player.inventory.container.items
        for (slot in 0 until 28) {
            val item = items[slot]
            h = 31 * h + (item?.id ?: -1)
            h = 31 * h + (item?.amount ?: 0)
        }
        return h
    }

    /** A dead member pings [slot] of the member they watch (spec §3.13). */
    @JvmStatic
    fun ping(viewer: Player, slot: Int) {
        val raid = TfaRaids.raidOf(viewer) ?: return
        val me = raid.memberOf(viewer) ?: return
        if (!me.dead || me.spectatedSlot < 0) return
        val target = raid.members.getOrNull(me.spectatedSlot)?.takeIf { it.inRaid } ?: return
        val item = target.player.inventory.getItem(slot)
        val script = raid.framework.spectate.pingScript
        if (script >= 0) {
            target.player.packetDispatcher.sendClientScript(
                script, TfaIds.packed(149, 0), slot, TfaIds.Dbrow.PING_STYLE, TfaIds.Dbrow.PING_PULSE,
            )
        }
        val what = item?.let { "${it.name} (slot ${slot + 1})" } ?: "inventory slot ${slot + 1}"
        target.player.sendMessage("<col=00ffff>${me.name} pings your $what.</col>")
        viewer.sendMessage("You ping ${target.name}'s $what.")
    }
}

/** Button handler of 978 (plugin-scanned): op1 "Ping" on a slot of 978:0. */
class SpectateInventoryInterface : UserInterface {
    override fun handleComponentClick(player: Player, interfaceId: Int, componentId: Int, slotId: Int, itemId: Int, optionId: Int, option: String) {
        if (componentId != TfaIds.Component.SPECTATE_ITEMS || slotId !in 0..27) return
        SpectateInventory.ping(player, slotId)
    }

    override fun getInterfaceIds(): IntArray = intArrayOf(TfaIds.Interface.SPECTATE_INV)
}
