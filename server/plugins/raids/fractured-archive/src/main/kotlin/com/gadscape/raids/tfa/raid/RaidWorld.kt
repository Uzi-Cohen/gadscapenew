package com.gadscape.raids.tfa.raid

import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.CharacterLoop
import com.zenyte.game.world.`object`.WorldObject

/** Small world helpers shared by the raid framework (teleports, landing tiles, loc swaps). */
object RaidWorld {

    /** True if a player may stand on [tile]: walkable floor and no NPC footprint on it. */
    @JvmStatic
    fun isStandable(tile: Location, avoidNpcs: Boolean = true): Boolean {
        if (!World.isFloorFree(tile.plane, tile.x, tile.y)) return false
        if (!avoidNpcs) return true
        return CharacterLoop.find(tile, 6, NPC::class.java) { npc ->
            !npc.isFinished && npc.plane == tile.plane &&
                tile.x >= npc.x && tile.x < npc.x + npc.size && tile.y >= npc.y && tile.y < npc.y + npc.size
        }.isEmpty()
    }

    /**
     * [near] itself if it is standable, else the closest standable tile within [radius] (rings outward, deterministic
     * order), else [near]. [spread] > 0 first tries a random tile within that distance ([random] supplies the roll).
     */
    @JvmStatic
    fun landing(near: Location, radius: Int, spread: Int = 0, random: java.util.Random? = null): Location {
        if (spread > 0 && random != null) {
            val dx = random.nextInt(spread * 2 + 1) - spread
            val dy = random.nextInt(spread * 2 + 1) - spread
            val candidate = Location(near.x + dx, near.y + dy, near.plane)
            if (isStandable(candidate)) return candidate
        }
        if (isStandable(near)) return near
        for (r in 1..radius) {
            for (dx in -r..r) for (dy in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val candidate = Location(near.x + dx, near.y + dy, near.plane)
                if (isStandable(candidate)) return candidate
            }
        }
        return near
    }

    /** Moves [player] to [tile] (instant teleport; the client rebuilds its map if needed) and faces [facing]. */
    @JvmStatic
    fun move(player: Player, tile: Location, facing: Compass? = null) {
        player.resetWalkSteps()
        player.setLocation(tile)
        if (facing != null) player.setFaceLocation(Location(tile.x + facing.dx, tile.y + facing.dy, tile.plane))
    }

    @JvmStatic
    fun direction(compass: Compass): Direction = when (compass) {
        Compass.N -> Direction.NORTH
        Compass.NE -> Direction.NORTH_EAST
        Compass.E -> Direction.EAST
        Compass.SE -> Direction.SOUTH_EAST
        Compass.S -> Direction.SOUTH
        Compass.SW -> Direction.SOUTH_WEST
        Compass.W -> Direction.WEST
        Compass.NW -> Direction.NORTH_WEST
    }

    /** Spawns (or replaces, same tile and slot) a type-10 loc. Its config animation plays on the client. */
    @JvmStatic
    fun spawnLoc(id: Int, tile: Location, rotation: Int, type: Int = 10): WorldObject {
        val obj = WorldObject(id, type, rotation, tile)
        World.spawnObject(obj)
        return obj
    }
}
