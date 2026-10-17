package com.gadscape.game.content.araxxor.attacks.spec

import com.gadscape.game.content.araxxor.Araxxor
import com.gadscape.game.content.araxxor.AraxxorInstance
import com.gadscape.game.content.araxxor.attacks.AcidPool
import com.gadscape.game.content.araxxor.attacks.Attack
import com.gadscape.game.content.damage
import com.gadscape.game.content.hit
import com.gadscape.game.content.seq
import com.zenyte.game.task.WorldTasksManager.schedule
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.masks.Graphics

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-25
 */
class AcidDrip(val instance: AraxxorInstance) : Attack {

    private val projectileBlob: Projectile = Projectile(2924, 64, 32, 96, 0)

    override fun invoke(araxxor: Araxxor, target: Entity?) {
        if (target == null || target.isDead || target.isFinished) return
        araxxor seq 11478
        World.sendProjectile(araxxor, target, projectileBlob)
        val delay = araxxor.middleLocation.getDistance(target.location).toInt() - 3
        schedule(delay) {
            World.spawnObject(AcidPool(target.location))
            target.temporaryAttributes["araxxor_acid_drip"] = 6
        }
    }
}