package com.gadscape.game.migrations

import com.zenyte.game.world.entity.player.Player

interface GameMigration {
    fun run(player: Player)
    fun id() : Int

    /**
     * When the migration runs. [Phase.LOGIN] (the default): from the login event, after the login has been sent to
     * the client. [Phase.LOAD]: as soon as the save is loaded into the player, before anything is sent (for data that
     * must be right before the client sees it, such as item and var ids).
     */
    fun phase(): Phase = Phase.LOGIN

    /**
     * A repeatable migration runs on every login (in its phase) and keeps its own per-account record of what it already
     * did; it neither checks nor moves the account's migration version. Use it for a migration whose work can grow
     * later (one driven by a data file). A [Phase.LOAD] migration must be repeatable.
     */
    fun repeatable(): Boolean = false

    enum class Phase { LOAD, LOGIN }
}
