package com.gadscape.game.content.dt2.npc.vardorvis.attacks.impl

import com.gadscape.game.content.dt2.npc.vardorvis.attacks.Attack

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-08
 */
data object AutoAttack : Attack {
    override fun getRequiredHp(): Double = 100.00
    override fun getFirstRate() = 100
    override fun getSecondRate() = 100
    override fun getEnrageRate() = 100
    override fun getEnabled(): Boolean = true
    override fun getAutoHeal(): Boolean = true
}
