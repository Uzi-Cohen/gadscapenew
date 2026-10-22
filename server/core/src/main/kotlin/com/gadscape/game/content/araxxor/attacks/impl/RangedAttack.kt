package com.gadscape.game.content.araxxor.attacks.impl

import com.gadscape.game.content.araxxor.Araxxor
import com.gadscape.game.content.araxxor.attacks.Attack
import com.gadscape.game.content.damage
import com.gadscape.game.content.hit
import com.gadscape.game.content.seq
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-20
 */
class RangedAttack: Attack {

    // 241: spotanim 1621 ARAXXOR_RANGED_PROJECTILE (was 2924 ARAXXOR_POOLS_PROJ placeholder).
    private val projectileBlob : Projectile = Projectile(1621, 64, 32, 64, 0)

    override fun invoke(araxxor: Araxxor, target: Entity?) {
        if (target == null) return
        araxxor seq 11476 // NPC_ARAXXOR_01_ATTACK_RANGED_01
        var maxHit = 34
        if ((target as Player).prayerManager.isActive(Prayer.PROTECT_FROM_MISSILES))
            maxHit = (maxHit * 0.20).toInt()
        val damage = CombatUtilities.getRandomMaxHit(araxxor, maxHit, AttackType.RANGED, target)
        val delay = World.sendProjectile(araxxor, target, projectileBlob)
        val hit = araxxor hit target damage damage

        target.scheduleHit(araxxor, hit, delay)
    }
}