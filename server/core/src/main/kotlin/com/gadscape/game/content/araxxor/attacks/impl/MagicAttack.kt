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
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-20
 */
class MagicAttack: Attack {

    // 241: spotanim 1622 ARAXXOR_MAGIC_PROJECTILE (was 2934 AMOXLIATL_ATTACK_PROJ placeholder), impact 1625 ARAXXOR_MAGIC_IMPACT.
    private val projectileBlob : Projectile = Projectile(1622, 64, 32, 64, 0)

    override fun invoke(araxxor: Araxxor, target: Entity?) {
        if (target == null) return
        araxxor seq 11479
        var maxHit = 21
        if ((target as Player).prayerManager.isActive(Prayer.PROTECT_FROM_MAGIC))
            maxHit = (maxHit * 0.20).toInt()
        val damage = CombatUtilities.getRandomMaxHit(araxxor, maxHit, AttackType.MAGIC, target)
        val delay = World.sendProjectile(araxxor, target, projectileBlob)
        val hit = araxxor hit target damage damage
        hit.onLand { target.graphics = Graphics(1625) }

        target.scheduleHit(araxxor, hit, delay)
    }
}