package com.gadscape.game.content.bountyhunter.teleport

import com.gadscape.game.content.bountyhunter.getTarget
import com.gadscape.game.content.bountyhunter.getWildernessLevel
import com.gadscape.game.content.bountyhunter.isBountyPaired
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.variables.TickVariable
import com.zenyte.game.world.region.GlobalAreaManager
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import kotlin.random.Random

/**
 * Shared rules for "Teleport to Bounty Target" (spell, BountyTargetStructure) and the Target teleport tablet.
 *
 * Adapted from OSRS (oldschool.runescape.wiki/w/Teleport_to_Target) to GadScape's wilderness Bounty Hunter:
 * both players must be in the wilderness, the caster must not be teleblocked or in combat, five teleports per
 * target, the landing tile is within 6 squares of the target, never deeper in the wilderness than the target and
 * never multi-way when the target stands in single-way combat.
 *
 * @author Glabay | Glabay-Studios (original), rewritten for the 241 PvP pass.
 */
object TeleportToTarget {

    /** Teleports allowed per assigned target (OSRS: five). */
    const val TELEPORTS_PER_TARGET = 5
    private const val COUNT_ATTR = "bh target teleports"
    private const val COUNT_TARGET_ATTR = "bh target teleports target"
    private const val LANDING_RADIUS = 6
    private const val LANDING_ATTEMPTS = 40

    /** Checks every restriction; sends the reason to the player when [message] is true. */
    @JvmStatic
    @JvmOverloads
    fun canTeleportToTarget(player: Player, message: Boolean = true): Boolean {
        fun deny(text: String): Boolean {
            if (message) player.sendMessage(text)
            return false
        }
        if (!player.isBountyPaired()) return deny("You do not have a bounty hunter target.")
        val target = player.getTarget().orElse(null) ?: return deny("You do not have a bounty hunter target.")
        if (target.isFinished || target.isNulled || target.isDead) return deny("Your target can't be reached right now.")
        if (!WildernessArea.isWithinWilderness(player)) return deny("You need to be in the Wilderness to teleport to your target.")
        if (!WildernessArea.isWithinWilderness(target)) return deny("Your target is not in the Wilderness.")
        if (player.variables.getTime(TickVariable.TELEBLOCK) > 0) return deny("A teleport block has been cast on you.")
        if (player.isUnderCombat) return deny("You can't teleport to your target while you are in combat.")
        if (teleportsUsed(player) >= TELEPORTS_PER_TARGET) return deny("You've reached your quota of teleportations to this target.")
        return true
    }

    @JvmStatic
    fun teleportsUsed(player: Player): Int {
        val target = player.getTarget().orElse(null) ?: return 0
        val forTarget = player.temporaryAttributes[COUNT_TARGET_ATTR] as? String
        if (!target.username.equals(forTarget, true)) return 0
        return (player.temporaryAttributes[COUNT_ATTR] as? Int) ?: 0
    }

    @JvmStatic
    fun countTeleport(player: Player) {
        val target = player.getTarget().orElse(null) ?: return
        val used = teleportsUsed(player)
        player.temporaryAttributes[COUNT_TARGET_ATTR] = target.username
        player.temporaryAttributes[COUNT_ATTR] = used + 1
    }

    /** Instant teleport used by the tablet after its own animation. */
    @JvmStatic
    fun teleportToTarget(player: Player) {
        val target = player.getTarget().orElse(null) ?: return
        countTeleport(player)
        player.teleport(landingLocation(target))
    }

    /** A free, walkable tile near the target, falling back to the target's own tile. */
    @JvmStatic
    fun landingLocation(target: Player): Location {
        val centre = target.location
        val targetLevel = target.getWildernessLevel()
        val targetMulti = World.isMultiwayArea(centre, GlobalAreaManager.getArea(centre))
        repeat(LANDING_ATTEMPTS) {
            val tile = Location(
                centre.x + Random.nextInt(-LANDING_RADIUS, LANDING_RADIUS + 1),
                centre.y + Random.nextInt(-LANDING_RADIUS, LANDING_RADIUS + 1),
                centre.plane
            )
            if (isValidTile(tile, targetLevel, targetMulti)) return tile
        }
        return Location(centre)
    }

    private fun isValidTile(tile: Location, targetLevel: Int, targetMulti: Boolean): Boolean {
        if (!World.isFloorFree(tile, 1)) return false
        if (World.getObjectWithType(tile, 10) != null || World.getObjectWithType(tile, 11) != null) return false
        if (!WildernessArea.isWithinWilderness(tile.x, tile.y)) return false
        val level = WildernessArea.getWildernessLevel(tile).orElse(0)
        if (level > targetLevel) return false
        if (!targetMulti && World.isMultiwayArea(tile, GlobalAreaManager.getArea(tile))) return false
        return true
    }
}
