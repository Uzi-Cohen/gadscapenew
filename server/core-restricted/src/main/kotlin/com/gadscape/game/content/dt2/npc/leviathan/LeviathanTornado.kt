package com.gadscape.game.content.dt2.npc.leviathan

import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Utils
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player

/**
 * Awakened Leviathan: from 50% hitpoints a tornado (NPC 12222 LEVIATHAN_TORNADO) chases the player one tile per tick
 * and hits 40+ on contact (wiki). It then disappears and a new one forms in the farthest corner a few ticks later.
 */
internal class LeviathanTornado(
    tile: Location,
    private val player: Player,
    private val boss: NPC
) : NPC(12222, tile, true) {

    override fun processNPC() {
        super.processNPC()
        if (isFinished || boss.isFinished || boss.isDead || player.isFinished) return
        if (location.matches(player.location)) {
            player.applyHit(Hit(boss, Utils.random(40, 50), HitType.TYPELESS))
            finish()
            WorldTasksManager.schedule(RESPAWN_TICKS) {
                // NPC.spawn() puts it back on its spawn corner.
                if (!boss.isFinished && !boss.isDead && !player.isFinished && isFinished) {
                    spawn()
                }
            }
            return
        }
        val dx = Integer.signum(player.x - x)
        val dy = Integer.signum(player.y - y)
        addWalkSteps(x + dx, y + dy, 1, false)
    }

    override fun isEntityClipped(): Boolean = false

    companion object {
        const val RESPAWN_TICKS = 8
    }
}
