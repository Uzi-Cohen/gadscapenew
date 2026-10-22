package com.gadscape.game.world

import com.zenyte.game.packet.out.LocAdd
import com.zenyte.game.packet.out.LocAnim
import com.zenyte.game.packet.out.LocDel
import com.zenyte.game.world.`object`.WorldObject
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.Chunk

/**
 * Map graphics and locs that exist for **one player only** (E16; e.g. Ocellus P4: each player's own orbs and
 * void chains). Nothing here touches the world's collision or loc tables: the server-side map stays as it is,
 * and other players see nothing.
 *
 * - [graphic]: a one-off MAP_ANIM on a tile, sent only to [Player] (zone packet in that player's own update).
 * - [spawnLoc] / [removeLoc]: a loc that only this player sees, or hide a world loc from this player. They are
 *   remembered per player and re-sent whenever the client rebuilds that zone (the zone entering the player's
 *   scope after a map rebuild, teleport or plane change wipes the client's copy), until [restoreLoc] / [clear].
 * - [animateLoc]: a loc animation only this player sees.
 *
 * Everything is keyed by tile + loc layer (wall, wall decoration, main, ground decoration), like the client's own
 * loc slots, so a second spawn in the same slot replaces the first. World thread only.
 *
 * The private state lives in the player's temporary attributes and dies with the session; instances must call
 * [clear] when the player leaves them (the instance's tiles may be reused by another raid).
 */
object PrivateScene {

    private const val ATTRIBUTE = "gadscape_private_scene"

    private class State {
        /** slot key -> loc shown only to this player. */
        val spawned = LinkedHashMap<Long, WorldObject>()
        /** slot key -> world loc hidden from this player. */
        val hidden = LinkedHashMap<Long, WorldObject>()
        fun isEmpty() = spawned.isEmpty() && hidden.isEmpty()
    }

    // ------------------------------------------------------------------ graphics

    /**
     * Plays [graphics] on [tile] for [player] only. Returns false (nothing sent) when the tile's zone is not in the
     * player's scene scope (the client could not place it).
     */
    @JvmStatic
    fun graphic(player: Player, graphics: Graphics, tile: Location): Boolean {
        if (!inScope(player, tile)) return false
        player.packetDispatcher.sendGraphics(graphics, tile)
        return true
    }

    /** [graphic] for each of [players]. */
    @JvmStatic
    fun graphic(players: Iterable<Player>, graphics: Graphics, tile: Location) {
        for (player in players) graphic(player, graphics, tile)
    }

    // ------------------------------------------------------------------ locs

    /** Shows [loc] to [player] only (replacing whatever loc the player sees in that slot). */
    @JvmStatic
    fun spawnLoc(player: Player, loc: WorldObject) {
        val copy = WorldObject(loc)
        val state = state(player, true)!!
        val key = slotKey(copy)
        state.hidden.remove(key)
        state.spawned[key] = copy
        if (inScope(player, copy)) player.sendZoneUpdate(copy.x, copy.y, LocAdd(copy))
    }

    /**
     * Removes the loc in [loc]'s slot for [player] only: a private loc spawned with [spawnLoc] is forgotten; a world
     * loc stays in the world but is hidden from this player until [restoreLoc] or [clear].
     */
    @JvmStatic
    fun removeLoc(player: Player, loc: WorldObject) {
        val state = state(player, true)!!
        val key = slotKey(loc)
        val private = state.spawned.remove(key)
        if (private == null) state.hidden[key] = WorldObject(loc)
        val shown = private ?: loc
        if (inScope(player, shown)) player.sendZoneUpdate(shown.x, shown.y, LocDel(shown))
        if (state.isEmpty()) player.temporaryAttributes.remove(ATTRIBUTE)
    }

    /**
     * Undoes [spawnLoc] / [removeLoc] for [loc]'s slot: the player sees the world loc there again ([worldLoc], or
     * nothing when null).
     */
    @JvmStatic
    fun restoreLoc(player: Player, loc: WorldObject, worldLoc: WorldObject?) {
        val state = state(player, false) ?: return
        val key = slotKey(loc)
        val private = state.spawned.remove(key)
        val hidden = state.hidden.remove(key)
        if (state.isEmpty()) player.temporaryAttributes.remove(ATTRIBUTE)
        if (private == null && hidden == null) return
        if (!inScope(player, loc)) return
        if (worldLoc != null) player.sendZoneUpdate(worldLoc.x, worldLoc.y, LocAdd(worldLoc))
        else if (private != null) player.sendZoneUpdate(private.x, private.y, LocDel(private))
    }

    /** Plays [animation] on [loc] for [player] only. */
    @JvmStatic
    fun animateLoc(player: Player, loc: WorldObject, animation: Animation): Boolean {
        if (!inScope(player, loc)) return false
        player.sendZoneUpdate(loc.x, loc.y, LocAnim(loc, animation))
        return true
    }

    /** The locs only [player] sees. */
    @JvmStatic
    fun spawnedLocs(player: Player): List<WorldObject> = state(player, false)?.spawned?.values?.toList() ?: emptyList()

    /** The world locs hidden from [player]. */
    @JvmStatic
    fun hiddenLocs(player: Player): List<WorldObject> = state(player, false)?.hidden?.values?.toList() ?: emptyList()

    /**
     * Forgets every private loc and hidden world loc of [player] whose tile matches [filter] (all of them by
     * default). Private locs in scope are deleted on the client; hidden world locs are re-added from
     * [worldLocAt] (the world's loc in that slot, or null for none).
     */
    @JvmStatic
    @JvmOverloads
    fun clear(
        player: Player,
        filter: (Location) -> Boolean = { true },
        worldLocAt: ((WorldObject) -> WorldObject?)? = null,
    ) {
        val state = state(player, false) ?: return
        val spawned = state.spawned.entries.iterator()
        while (spawned.hasNext()) {
            val loc = spawned.next().value
            if (!filter(loc)) continue
            spawned.remove()
            if (inScope(player, loc)) {
                val world = worldLocAt?.invoke(loc)
                if (world != null) player.sendZoneUpdate(world.x, world.y, LocAdd(world))
                else player.sendZoneUpdate(loc.x, loc.y, LocDel(loc))
            }
        }
        val hidden = state.hidden.entries.iterator()
        while (hidden.hasNext()) {
            val loc = hidden.next().value
            if (!filter(loc)) continue
            hidden.remove()
            if (inScope(player, loc)) {
                val world = worldLocAt?.invoke(loc) ?: loc
                player.sendZoneUpdate(world.x, world.y, LocAdd(world))
            }
        }
        if (state.isEmpty()) player.temporaryAttributes.remove(ATTRIBUTE)
    }

    /**
     * Called by [com.zenyte.game.world.SceneSynchronization.updateScopeInScene] right after a zone entered
     * [player]'s scope (the client just cleared it and got the world's locs): re-sends the player's private
     * changes in that zone.
     */
    @JvmStatic
    fun onZoneEnteredScope(player: Player, chunkX: Int, chunkY: Int, plane: Int) {
        val state = state(player, false) ?: return
        for (loc in state.hidden.values) {
            if (loc.plane == plane && loc.chunkX == chunkX && loc.chunkY == chunkY) {
                player.sendZoneUpdate(loc.x, loc.y, LocDel(loc))
            }
        }
        for (loc in state.spawned.values) {
            if (loc.plane == plane && loc.chunkX == chunkX && loc.chunkY == chunkY) {
                player.sendZoneUpdate(loc.x, loc.y, LocAdd(loc))
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Whether [tile]'s zone is currently synchronised to [player]'s client. */
    @JvmStatic
    fun inScope(player: Player, tile: Location): Boolean =
        tile.plane == player.plane &&
            player.chunksInScope.contains(Chunk.getChunkHash(tile.x shr 3, tile.y shr 3, tile.plane))

    /** The client loc layer of a loc shape: 0 wall, 1 wall decoration, 2 main (scenery, roofs), 3 ground decoration. */
    @JvmStatic
    fun layer(shape: Int): Int = when (shape) {
        in 0..3 -> 0
        in 4..8 -> 1
        22 -> 3
        else -> 2
    }

    private fun slotKey(loc: WorldObject): Long =
        (loc.positionHash.toLong() shl 2) or layer(loc.type).toLong()

    private fun state(player: Player, create: Boolean): State? {
        val attributes = player.temporaryAttributes
        val existing = attributes[ATTRIBUTE] as? State
        if (existing != null || !create) return existing
        return State().also { attributes[ATTRIBUTE] = it }
    }
}
