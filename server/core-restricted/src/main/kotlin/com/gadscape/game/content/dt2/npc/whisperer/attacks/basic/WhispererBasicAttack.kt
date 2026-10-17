package com.gadscape.game.content.dt2.npc.whisperer.attacks.basic

import com.gadscape.game.content.dt2.npc.whisperer.WhispererCombat
import com.zenyte.game.world.entity.AbstractEntity

interface WhispererBasicAttack {
    operator fun invoke(whisperer: WhispererCombat, target: AbstractEntity)
}