package com.gadscape.game.content.tormented_demon.attacks

import com.gadscape.game.content.tormented_demon.TormentedDemon
import com.zenyte.game.world.entity.Entity

/**
 * @author Glabay | Glabay-Studios
 * @project gadscape-server
 * @social Discord: Glabay
 * @since 2024-10-16
 */
interface Attack {
    operator fun invoke(demon: TormentedDemon, target: Entity?)
}