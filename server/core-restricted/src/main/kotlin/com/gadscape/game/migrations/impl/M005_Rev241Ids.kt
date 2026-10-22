package com.gadscape.game.migrations.impl

import com.gadscape.game.migrations.ActiveMigration
import com.gadscape.game.migrations.GameMigration
import com.gadscape.game.migrations.rev241.Rev241Ids
import com.zenyte.game.world.entity.player.Player

/**
 * GadScape's 211 item and varp ids the account holds -> the ids of the 241 cache (gadconv idmap.json), as soon as the
 * save is loaded and before the client sees anything. Repeatable: [Rev241Ids] records per account what it applied and
 * only does what a newer id map adds or changes.
 */
@Suppress("unused", "ClassName")
@ActiveMigration
class M005_Rev241Ids : GameMigration {
    override fun run(player: Player) {
        Rev241Ids.migrate(player)
    }

    override fun id(): Int = 5

    override fun phase(): GameMigration.Phase = GameMigration.Phase.LOAD

    override fun repeatable(): Boolean = true
}
