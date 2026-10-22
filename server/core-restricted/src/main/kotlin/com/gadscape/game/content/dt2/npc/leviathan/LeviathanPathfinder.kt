package com.gadscape.game.content.dt2.npc.leviathan

import com.gadscape.game.content.dt2.area.LeviathanInstance
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.NPC

/**
 * The abyssal pathfinder (NPC 12220 LEVIATHAN_BUFF_NPC, size 3) of the Leviathan's enrage phase. It appears in the
 * north-west corner of the arena (or the next corner clockwise when that one is blocked) and walks clockwise around
 * the pool. Players inside its [radius] (2 = 5x5, awakened 3 = 7x7) around its centre tile get the buff, see
 * [LeviathanCombat].
 */
internal class LeviathanPathfinder(
    private val instance: LeviathanInstance,
    startCorner: Int,
    private val radius: Int
) : NPC(12220, cornerTiles(instance)[startCorner], true) {

    private var nextCorner = (startCorner + 1) % 4
    private var ticks = 0

    override fun spawn(): NPC {
        val npc = super.spawn()
        animation = Animation(10296) // NPC_BUFF01_SPAWN01
        graphics = Graphics(2298) // LEVIATHAN_BUFF_SPAWN
        return npc
    }

    override fun processNPC() {
        super.processNPC()
        if (isFinished) return
        if (++ticks % STEP_EVERY != 0) return
        val corners = cornerTiles(instance)
        var goal = corners[nextCorner]
        if (location.matches(goal)) {
            nextCorner = (nextCorner + 1) % 4
            goal = corners[nextCorner]
        }
        val dx = Integer.signum(goal.x - x)
        val dy = Integer.signum(goal.y - y)
        // It floats over the debris: no collision check.
        addWalkSteps(x + dx, y + dy, 1, false)
    }

    /** Whether [tile] is inside the pathfinder's aura. */
    fun covers(tile: Location): Boolean {
        if (isFinished) return false
        val middle = middleLocation
        return tile.plane == plane && Math.abs(tile.x - middle.x) <= radius && Math.abs(tile.y - middle.y) <= radius
    }

    override fun isEntityClipped(): Boolean = false

    companion object {
        /** Ticks per tile walked (not on the wiki; one lap of the arena takes about 70 seconds). */
        const val STEP_EVERY = 2

        /** South-west tiles of the pathfinder at the NW, NE, SE and SW corners of the arena ring (clockwise). */
        fun cornerTiles(instance: LeviathanInstance): List<Location> = listOf(
            instance.getLocation(2073, 6379, 0),
            instance.getLocation(2087, 6379, 0),
            instance.getLocation(2087, 6364, 0),
            instance.getLocation(2073, 6364, 0)
        )
    }
}
