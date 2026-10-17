package com.gadscape.game.content.araxxor.attacks

import com.gadscape.game.content.araxxor.Araxxor
import com.zenyte.game.world.entity.Entity

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-20
 */
interface Attack {
    operator fun invoke(araxxor: com.gadscape.game.content.araxxor.Araxxor, target: Entity?)
}