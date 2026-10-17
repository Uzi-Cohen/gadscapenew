package com.gadscape.game.content.dt2.npc.whisperer.attacks.special.impl

import com.gadscape.game.content.North
import com.gadscape.game.content.dt2.npc.whisperer.WhispererCombat
import com.gadscape.game.content.dt2.npc.whisperer.attacks.special.WhispererSpecial
import com.gadscape.game.content.faceDir
import com.zenyte.game.task.WorldTasksManager.schedule
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import kotlin.random.Random

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-09-24
 */
object EnragedSpecialAttack : WhispererSpecial {

    override fun setup(whisperer: WhispererCombat, player: Player) {
        whisperer.hitpoints += 150
        whisperer.radius = 0
        whisperer.setLocation(whisperer.spawnLocation)
        schedule(2) { whisperer faceDir North }
    }

    override fun execute(whisperer: WhispererCombat, target: Player) {
    }

}