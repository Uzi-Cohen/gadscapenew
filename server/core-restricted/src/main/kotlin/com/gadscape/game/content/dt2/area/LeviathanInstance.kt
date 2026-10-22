package com.gadscape.game.content.dt2.area

import com.gadscape.game.content.dt2.npc.DT2BossDifficulty
import com.gadscape.game.content.dt2.npc.leviathan.LeviathanCombat
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.DynamicArea
import com.zenyte.game.world.region.area.plugins.CannonRestrictionPlugin
import com.zenyte.game.world.region.area.plugins.FullMovementPlugin
import com.zenyte.game.world.region.dynamicregion.AllocatedArea
import com.zenyte.game.world.region.dynamicregion.MapBuilder

class LeviathanInstance(
    allocatedArea: AllocatedArea,
    val player: Player,
    val difficulty: DT2BossDifficulty = DT2BossDifficulty.NORMAL,
    private val debugMechanics : Boolean = true
) : DynamicArea(allocatedArea, STATIC_CHUNK_X, STATIC_CHUNK_Y),
    FullMovementPlugin,
    CannonRestrictionPlugin {

    private var leviathan: LeviathanCombat? = null

    /** Static (non-instanced) coordinates of the arena floor around the pool, see [arenaTiles]. */
    private var cachedArenaTiles: List<Location>? = null

    /**
     * Every walkable instance tile of the arena ring (The Scar, region 8291: the floor is the plane-1 bridge layer
     * around the 7x7 pool at 2078..2084, 6369..6375), computed from the instance collision once it is built.
     */
    fun arenaTiles(): List<Location> {
        cachedArenaTiles?.let { return it }
        val tiles = ArrayList<Location>()
        for (x in ARENA_MIN_X..ARENA_MAX_X) {
            for (y in ARENA_MIN_Y..ARENA_MAX_Y) {
                if (isPoolTile(x, y)) continue
                val tile = getLocation(x, y, 0)
                if (World.isFloorFree(tile)) tiles.add(tile)
            }
        }
        cachedArenaTiles = tiles
        return tiles
    }

    /** Whether an instance tile is the pool or touches it (diagonals included): the bite range. */
    fun isNextToPool(tile: Location): Boolean {
        val x = tile.x - getX(POOL_MIN_X) + POOL_MIN_X
        val y = tile.y - getY(POOL_MIN_Y) + POOL_MIN_Y
        return x in (POOL_MIN_X - 1)..(POOL_MAX_X + 1) && y in (POOL_MIN_Y - 1)..(POOL_MAX_Y + 1)
    }

    /** Whether an instance tile is inside the arena bounds. */
    fun isInArena(tile: Location): Boolean {
        val x = tile.x - getX(ARENA_MIN_X) + ARENA_MIN_X
        val y = tile.y - getY(ARENA_MIN_Y) + ARENA_MIN_Y
        return x in ARENA_MIN_X..ARENA_MAX_X && y in ARENA_MIN_Y..ARENA_MAX_Y
    }

    private fun isPoolTile(x: Int, y: Int) = x in POOL_MIN_X..POOL_MAX_X && y in POOL_MIN_Y..POOL_MAX_Y

    override fun name(): String = "Leviathan Instance"

    companion object {
        fun createInstance(p: Player, difficulty: DT2BossDifficulty = DT2BossDifficulty.NORMAL) : LeviathanInstance {
            // The arena spans 2064..2103 x 6352..6399 (chunks 258..262 x 794..799).
            val area: AllocatedArea = MapBuilder.findEmptyChunk(5, 6)
            return LeviathanInstance(area, p, difficulty)
        }

        /** The Scar, region 8291: the instance copies chunks from (2064, 6352). (It used to copy Vardorvis' 1096, 3392.) */
        const val STATIC_CHUNK_X = 2064 shr 3
        const val STATIC_CHUNK_Y = 6352 shr 3

        /** The Leviathan's pool (its 7x7 footprint), static coordinates. */
        const val POOL_MIN_X = 2078
        const val POOL_MIN_Y = 6369
        const val POOL_MAX_X = 2084
        const val POOL_MAX_Y = 6375

        /** Bounding box of the walkable arena ring, static coordinates. */
        const val ARENA_MIN_X = 2071
        const val ARENA_MAX_X = 2092
        const val ARENA_MIN_Y = 6362
        const val ARENA_MAX_Y = 6384

        fun outside() = outsideLocation
        fun inside() = insideLocation

        private val insideLocation = Location(2071, 6368, 0)
        private val outsideLocation = Location(2069, 6368, 0)
    }

    override fun enter(player: Player) {

    }

    override fun leave(player: Player, logout: Boolean) {
        if(player.hpHud.isOpen)
            player.hpHud.close()
        player.blockIncomingHits(1)
        player.interfaceHandler.closeInterface(InterfacePosition.OVERLAY)
        player.packetDispatcher.sendClientScript(7021, 255)
        if (logout)
            player.forceLocation(outside())
    }

    override fun destroyRegion() {
        super.destroyRegion()
        if(leviathan != null) {
            leviathan!!.finish()
            leviathan = null
        }
    }


    override fun constructed() {
        leviathan = LeviathanCombat(this.getLocation(POOL_MIN_X, POOL_MIN_Y, 0), this)
        leviathan!!.spawn()

        transferPlayer()
    }

    private fun transferPlayer() {
        player.setLocation(getLocation(inside()))
        player.faceDirection(Direction.EAST)
        player.sendMessage("You scale the small rocks.")
    }

    fun debugMechanic(msg: String) {
        if (debugMechanics) player.sendDeveloperMessage(msg)
    }

    override fun processMovement(player: Player?, x: Int, y: Int): Boolean {
        return true
    }
}

