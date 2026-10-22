package com.gadscape.raids.tfa.spectate

import com.gadscape.network.rsprot.CamTarget
import com.gadscape.raids.tfa.hud.BossHpHud
import com.gadscape.raids.tfa.hud.MusicUnlock
import com.gadscape.raids.tfa.hud.Prefetch
import com.gadscape.raids.tfa.raid.RaidGeometry
import com.gadscape.raids.tfa.raid.RaidWorld
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory

/**
 * External spectators of one raid (spec §3.13, WU-18): ghosts inside the instance — hidden, locked, never targeted,
 * in no count — placed on the current room's centre tile. They see the HUD (974, spectate mode 2, party slot 0),
 * switch the camera between the room and members by clicking orbs, see the viewed member's inventory in 978, hear
 * (and unlock) the room music, and leave with the HUD's Leave button. At most `framework.json spectate.maxSpectators`.
 */
class SpectatorGroup(val raid: TfaRaid) {

    private val log = LoggerFactory.getLogger(SpectatorGroup::class.java)

    private class Ghost(val player: Player) {
        /** Viewed slot, -1 = room view. */
        var slot: Int = -1
        var room: TfaRoomId = TfaRoomId.GUARDIAN
        var lastInventoryHash = 0
    }

    private val ghosts = LinkedHashMap<String, Ghost>()

    val count: Int get() = ghosts.size

    fun players(): List<Player> = ghosts.values.map { it.player }

    /** (viewer, viewed slot or -1) for the HUD. */
    fun viewerSlots(): List<Pair<Player, Int>> = ghosts.values.map { it.player to it.slot }

    fun playersWatching(room: TfaRoomId): List<Player> = ghosts.values.filter { it.room == room }.map { it.player }

    fun isSpectating(player: Player): Boolean = player.username.lowercase() in ghosts

    /** The room spectators watch now: the running encounter, else the nexus (the vault after Ocellus). */
    fun currentRoom(): TfaRoomId = raid.activeRoom?.takeIf { it.isActive }?.room ?: TfaRoomId.GUARDIAN

    /** Adds [player] as a ghost; returns false (with the reason told) when refused. */
    fun add(player: Player): Boolean {
        val key = player.username.lowercase()
        if (key in ghosts) return true
        if (raid.memberOf(player) != null) {
            player.sendMessage("You can't spectate your own raid.")
            return false
        }
        if (ghosts.size >= raid.framework.spectate.maxSpectators) {
            player.sendMessage("That raid has reached the maximum number of spectators.")
            return false
        }
        val g = Ghost(player)
        ghosts[key] = g
        player.interfaceHandler.closeInterfaces()
        player.isHidden = true
        player.appearance.setInvisible(true)
        player.setMaximumTolerance(true)
        place(g, currentRoom())
        player.lock()
        raid.hud.openFor(player, 0)
        raid.hud.markDirty()
        log.info("Fractured Archive raid {}: {} spectates ({} spectators)", raid.id, player.username, ghosts.size)
        return true
    }

    private fun place(g: Ghost, room: TfaRoomId) {
        g.room = room
        g.slot = -1
        val tile = RaidWorld.landing(raid.translate(RaidGeometry.spectateCentre(room)), 4, avoidNpcs = false)
        RaidWorld.move(g.player, tile)
        CamTarget.reset(g.player)
        restoreInventoryTab(g.player)
        Prefetch.send(g.player, room)
        val rt = raid.runtime(room)
        if (rt != null && rt.isActive) {
            MusicUnlock.play(g.player, room)
            BossHpHud.showCurrent(g.player, rt)
        }
    }

    /** An orb or "Spectate Room" click (IF_SCRIPT_TRIGGER 2481737999: slot, or -1 for the room). */
    fun view(player: Player, slot: Int) {
        val g = ghosts[player.username.lowercase()] ?: return
        val member = raid.members.getOrNull(slot)
        if (slot < 0 || member == null || !member.inRaid || member.player.isFinished) {
            g.slot = -1
            CamTarget.reset(player)
            restoreInventoryTab(player)
        } else {
            g.slot = slot
            follow(g, member)
            SpectateInventory.open(player, raid, member, pings = false)
            g.lastInventoryHash = SpectateInventory.hash(member.player)
        }
        raid.hud.markDirty()
    }

    private fun follow(g: Ghost, member: TfaRaidMember) {
        val target = member.player
        if (!target.location.withinDistance(g.player.location, 30) || target.plane != g.player.plane) {
            RaidWorld.move(g.player, Location(target.x, target.y, target.plane))
        }
        CamTarget.player(g.player, target)
    }

    /** HUD "Leave" (IF_SCRIPT_TRIGGER 1373305897 arg 2): back to the lobby. */
    fun leave(player: Player) {
        val g = ghosts.remove(player.username.lowercase()) ?: return
        restore(g.player)
        val t = raid.config.raid.tiles.lobbyArrival
        RaidWorld.move(player, Location(t.x, t.y, t.plane))
    }

    fun onEncounterStarted(rt: RoomRuntime) {
        for (g in ghosts.values) if (g.slot < 0) place(g, rt.room) else {
            g.room = rt.room
            MusicUnlock.play(g.player, rt.room)
            Prefetch.send(g.player, rt.room)
        }
    }

    fun onEncounterCompleted(@Suppress("UNUSED_PARAMETER") rt: RoomRuntime) {}

    fun onMemberLeft(member: TfaRaidMember) {
        for (g in ghosts.values) if (g.slot == member.slot) view(g.player, -1)
        // Dead members watching the one who left fall back to the room view.
        for (m in raid.members) if (m.dead && m.spectatedSlot == member.slot) {
            m.spectatedSlot = -1
            CamTarget.reset(m.player)
        }
    }

    /** A ghost left the area by itself (teleport, logout): forget it. */
    fun onLeftArea(player: Player) {
        val g = ghosts.remove(player.username.lowercase()) ?: return
        restore(g.player)
    }

    /** Keeps followed members in view and mirrors their inventory. */
    fun tick() {
        val gone = ghosts.values.filter { it.player.isFinished || it.player.isNulled }
        for (g in gone) ghosts.remove(g.player.username.lowercase())
        for (g in ghosts.values) {
            if (g.slot < 0) continue
            val member = raid.members.getOrNull(g.slot)
            if (member == null || !member.inRaid) {
                view(g.player, -1)
                continue
            }
            val target = member.player
            if (!target.location.withinDistance(g.player.location, 30) || target.plane != g.player.plane) follow(g, member)
            val h = SpectateInventory.hash(target)
            if (h != g.lastInventoryHash) {
                g.lastInventoryHash = h
                SpectateInventory.refresh(g.player, raid, target)
            }
        }
        // Dead party members mirror the member they watch, too.
        for (m in raid.members) {
            if (!m.dead || m.left || m.spectatedSlot < 0) continue
            val viewed = raid.members.getOrNull(m.spectatedSlot) ?: continue
            if (!viewed.inRaid) continue
            SpectateInventory.refreshIfChanged(m.player, raid, viewed.player)
        }
    }

    fun removeAll() {
        for (g in ghosts.values.toList()) leave(g.player)
        ghosts.clear()
    }

    private fun restore(player: Player) {
        if (player.isFinished) return
        raid.hud.closeFor(player)
        BossHpHud.hideFor(player)
        player.isHidden = false
        player.appearance.setInvisible(false)
        player.setMaximumTolerance(false)
        player.unlock()
        CamTarget.reset(player)
        restoreInventoryTab(player)
    }

    private fun restoreInventoryTab(player: Player) {
        try {
            if (player.interfaceHandler.isVisible(com.gadscape.raids.tfa.ids.TfaIds.Interface.SPECTATE_INV)) {
                player.interfaceHandler.closeInterface(InterfacePosition.INVENTORY_TAB)
                GameInterface.INVENTORY_TAB.open(player)
            }
        } catch (e: Exception) {
            log.debug("restoring the inventory tab failed for {}", player.username, e)
        }
    }
}
