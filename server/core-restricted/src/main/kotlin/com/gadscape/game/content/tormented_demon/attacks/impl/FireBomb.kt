package com.gadscape.game.content.tormented_demon.attacks.impl

import com.gadscape.game.content.*
import com.gadscape.game.content.tormented_demon.TormentedDemon
import com.gadscape.game.content.tormented_demon.attacks.Attack
import com.zenyte.game.task.WorldTasksManager.schedule
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.AbstractEntity
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import kotlin.random.Random

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-16
 */
class FireBomb : Attack {

    /*
     * briefly bind the player in place roughly every 60 ticks and disable their run,
     * then release two firebombs;
     * one on the player's current position when the attack was used,
     * and one in a 3x3 AoE from the first shot.
     *
     * The demon will change its combat style after this attack.
     */

    private val fireBombProjectile : Projectile =
        Projectile(2855, 64, 32, 128, 0)

    override fun invoke(demon: TormentedDemon, target: Entity?) {
        if (target == null) return
        target.isRun = false
        // Animate the attack
        demon seq demon.getFireSkullThrowAnimation()
        // grab the locations of the bombs
        val targetLocation = target.location.copy()
        val secondBombLocation = getSecondBombLocation(targetLocation)
        // throw the bombs
        World.sendProjectile(demon.location, targetLocation, fireBombProjectile)
        World.sendProjectile(demon.location, secondBombLocation, fireBombProjectile)
        // Schedule the hits
        schedule(4) {
            World.sendGraphics(Graphics(2856), targetLocation) // LUC2_UNDEAD_DEMON_FIREBALL_LARGE_IMPACT
            if (target.location == targetLocation)
                target.scheduleHit(demon, demon hit target damage Random.nextInt(40, 45), 0)
        }
        schedule(5) {
            World.sendGraphics(Graphics(2856), secondBombLocation)
            if (target.location == secondBombLocation)
                target.scheduleHit(demon, demon hit target damage Random.nextInt(40, 45), 0)
        }
        // reset the Player Accuracy Boost
        demon.accuracyBoostTimer.reset()
    }

    private fun getSecondBombLocation(location: Location?): Location {
        val xOffset = Random.nextInt(-1, 2)
        val yOffset = Random.nextInt(-1, 2)
        val tempLocation = Location(location?.offset(Pair(xOffset, yOffset)))
        return tempLocation
    }
}