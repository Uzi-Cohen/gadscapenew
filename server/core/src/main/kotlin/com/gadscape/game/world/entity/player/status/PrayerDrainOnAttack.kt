package com.gadscape.game.world.entity.player.status

import com.zenyte.game.content.skills.magic.spells.arceuus.hasWardOfArceuus
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * Prayer drain on every attack, blocked or not (spec §2.7 E11; the TzKih pattern, wiki "drains prayer whether it
 * inflicts damage or not"). Dreadnoth's jellies drain 2 per attack.
 *
 * Use [onLand] on the NPC's hit (the drain happens when the attack lands, like TzKih), or [drain] directly.
 * Ward of Arceuus cuts the drain from a demon attacker by 33% ([wardReduced], spec §4.5 [INF]).
 */
object PrayerDrainOnAttack {

    /** Drains [points] prayer from [player] for an attack by [attacker] (Ward of Arceuus applied); returns the drain. */
    @JvmStatic
    fun drain(player: Player, attacker: Entity?, points: Int): Int {
        if (points <= 0 || player.isDead || player.isFinished) return 0
        val ward = player.hasWardOfArceuus && attacker != null && CombatUtilities.isDemon(attacker)
        val amount = wardReduced(points, ward)
        if (amount > 0) player.prayerManager.drainPrayerPoints(amount)
        return amount
    }

    /**
     * Chains a drain of [points] onto [hit]'s land consumer, so it happens when the attack lands whatever its damage
     * (the hit's target must be a player). Returns [hit].
     */
    @JvmStatic
    fun onLand(hit: Hit, attacker: Entity?, target: Player, points: Int): Hit {
        val previous = hit.onLandConsumer
        return hit.onLand { landed ->
            previous?.accept(landed)
            drain(target, attacker, points)
        }
    }

    /** The drain left by Ward of Arceuus: −33% (floor), so 2 → 1. */
    @JvmStatic
    fun wardReduced(points: Int, ward: Boolean): Int = if (ward) (points * 67) / 100 else points
}
