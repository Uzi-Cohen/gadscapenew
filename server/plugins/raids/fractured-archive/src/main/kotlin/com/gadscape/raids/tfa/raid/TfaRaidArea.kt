package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport
import com.zenyte.game.world.Position
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.DynamicArea
import com.zenyte.game.world.region.GlobalAreaManager
import com.zenyte.game.world.region.area.plugins.CannonRestrictionPlugin
import com.zenyte.game.world.region.area.plugins.CycleProcessPlugin
import com.zenyte.game.world.region.area.plugins.DeathPlugin
import com.zenyte.game.world.region.area.plugins.LogoutPlugin
import com.zenyte.game.world.region.area.plugins.TeleportPlugin

/**
 * The raid's map area (spec §2.4, §2.5, §3.8, §3.11): one dynamic area over the whole 24 x 40-chunk block.
 * - [process] drives [TfaRaid.process] once per tick (GlobalAreaManager runs it for every mapped dynamic area);
 * - multicombat everywhere, no cannon (§3.11);
 * - deaths go to the raid ([sendDeath], spec §3.8): spectating, wipes and gravestones are the raid's;
 * - logout and teleports out leave the raid (`tfa.json teleportOut`, `logout`): next login is the lobby arrival tile;
 * - the map is copied lazily by [TfaBlockInstance], so [constructRegion] only maps the area.
 */
class TfaRaidArea internal constructor(val raid: TfaRaid) :
    DynamicArea(raid.instance.area, TfaBlock.STATIC_CHUNK_X, TfaBlock.STATIC_CHUNK_Y),
    CycleProcessPlugin, DeathPlugin, LogoutPlugin, TeleportPlugin, CannonRestrictionPlugin {

    private var registered = false

    /** Maps the area without copying anything (the instance copies regions on demand). */
    override fun constructRegion() {
        if (registered) return
        registered = true
        GlobalAreaManager.add(this)
        constructed = true
        constructed()
    }

    override fun constructed() {}

    /** Unmaps the area (the raid frees the map itself). */
    internal fun unregister() {
        if (!registered) return
        registered = false
        GlobalAreaManager.remove(this)
    }

    /** The raid releases itself; an empty area must not destroy the map early. */
    override fun cleared() {}

    override fun name(): String = "The Fractured Archive #${raid.id}"

    override fun isRaidArea(): Boolean = true

    override fun isMultiwayArea(position: Position): Boolean = true

    /** A login into a released raid map (server restart mid-raid) lands on the lobby arrival tile. */
    override fun onLoginLocation(): Location {
        val t = raid.config.raid.tiles.lobbyArrival
        return Location(t.x, t.y, t.plane)
    }

    override fun enter(player: Player) {
        player.viewDistance = Player.SCENE_DIAMETER
    }

    override fun leave(player: Player, logout: Boolean) {
        player.resetViewDistance()
        raid.onLeftArea(player, logout)
        if (logout) {
            // Next login: the lobby arrival tile with the player's items (spec §3.8 logout).
            val t = raid.config.raid.tiles.lobbyArrival
            player.forceLocation(Location(t.x, t.y, t.plane))
        }
    }

    override fun process() {
        raid.process()
    }

    override fun onLogout(player: Player) {
        raid.onLeftArea(player, true)
    }

    // ------------------------------------------------------------------------------------------------- teleports

    override fun canTeleport(player: Player, teleport: Teleport): Boolean {
        val member = raid.memberOf(player)
        if (member != null && member.dead) {
            player.sendMessage("You can't teleport while you are spectating.")
            return false
        }
        if (member == null) return true // ghosts and strangers
        return when (raid.config.raid.teleportOut) {
            RaidConfig.TeleportOutRule.LEAVE_RAID -> true // leaving the area removes them (leave())
            RaidConfig.TeleportOutRule.BLOCKED -> {
                player.sendMessage("You can't teleport out of the Archive.")
                false
            }
        }
    }

    override fun restrictionMessage(): String = "You can't place a cannon in the Archive."

    // ------------------------------------------------------------------------------------------------- deaths

    /** Item-loss rule of the death being processed right now (the wipe flow sets it per player). */
    internal var safeOverride: Boolean? = null

    /** Raid deaths never reach the stock death code except through the wipe flow, which sets [safeOverride]. */
    override fun isSafe(): Boolean = safeOverride ?: true

    /** The ring of life does nothing in the raid (ToB precedent; a death is a spectate state, §3.8). */
    override fun isRingOfLifeEffective(): Boolean = false

    /** HC group lives are lost on any death in the TFA death zone [DOC]. */
    override fun loseHardcoreIronGroupLive(): Boolean = true

    override fun getDeathInformation(): String? = null

    override fun getRespawnLocation(): Location {
        val t = raid.config.raid.tiles.lobbyArrival
        return Location(t.x, t.y, t.plane)
    }

    /** Gravestones of a wipe sit in the lobby (4123,5558) [HOUSE] (spec §3.8 step 2). */
    override fun gravestoneLocation(): Location {
        val t = raid.config.raid.tiles.lobbyGravestone
        return Location(t.x, t.y, t.plane)
    }

    override fun sendDeath(player: Player, source: Entity?): Boolean = raid.onDeath(player, source)

    override fun toString(): String = "TfaRaidArea(${raid.id})"

    companion object {
        /** Lobby region (static, not instanced). */
        val lobbyRegion: Int get() = TfaIds.Region.LOBBY
    }
}
