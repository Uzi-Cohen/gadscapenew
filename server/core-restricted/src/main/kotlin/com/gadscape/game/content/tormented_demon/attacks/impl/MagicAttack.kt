package com.gadscape.game.content.tormented_demon.attacks.impl

import com.gadscape.game.content.damage
import com.gadscape.game.content.hit
import com.gadscape.game.content.seq
import com.gadscape.game.content.spotanim
import com.gadscape.game.content.tormented_demon.TormentedDemon
import com.gadscape.game.content.tormented_demon.attacks.Attack
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.AbstractEntity
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-16
 */
class MagicAttack : Attack {

    // 241: spotanim 2853 LUC2_UNDEAD_DEMON_FIREBALL_PROJ + impact 2854 (was 1166, a Dragon Slayer quest projectile).
    private val projectile : Projectile =
        Projectile(2853, 64, 32, 96, 0)

    override fun invoke(demon: TormentedDemon, target: Entity?) {
        if (target == null) return
        demon seq demon.getMagicSpellAnimation()
        var damageMax = 31
        if (target is Player && target.prayerManager.isActive(Prayer.PROTECT_FROM_MAGIC))
            damageMax = 0
        val damage = CombatUtilities.getRandomMaxHit(demon, damageMax, AttackType.MAGIC, target)
        val delay = World.sendProjectile(demon, target, projectile)
        val hit = demon hit target damage damage
        hit.onLand { target.graphics = Graphics(2854) }
        target.scheduleHit(demon, hit, delay)
    }
}