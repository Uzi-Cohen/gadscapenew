package com.gadscape.game.content.gambling

import com.google.common.eventbus.Subscribe
import com.zenyte.game.GameInterface
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.ObjectId
import com.zenyte.game.world.`object`.WorldObject
import com.zenyte.plugins.events.ServerLaunchEvent

/**
 * The physical Blackjack table for the existing gambling room at (3366, 6939, plane 2) -
 * reachable via ::gambling/::gamble/::dice/::fp. Placed in the small walled alcove south of the
 * currency-exchange booth (x 3356-3365, y 6950-6955), which was already built into the map and
 * completely empty - it doesn't collide with any of the 6 Flower Poker tile pairs (y 6934-6943),
 * the Grand Exchange booth (y 6936-6941), or the banker NPCs there.
 *
 * There's no dedicated "blackjack table" object in this era's asset set, so a plain table
 * ([ObjectId.TABLE]) stands in for it. Since object clicks dispatch globally by id in this
 * engine, the handler is guarded to only react at this exact tile - every other table in the
 * game is left untouched.
 */
val BLACKJACK_TABLE_LOCATION: Location = Location(3360, 6952, 2)

class BlackjackTableAction : ObjectAction {

    override fun getObjects() = arrayOf(ObjectId.TABLE)

    override fun handleObjectAction(
        player: Player,
        `object`: WorldObject,
        name: String,
        optionId: Int,
        option: String,
    ) {
        if (!`object`.location.equals(BLACKJACK_TABLE_LOCATION.x, BLACKJACK_TABLE_LOCATION.y, BLACKJACK_TABLE_LOCATION.plane)) {
            return
        }
        GameInterface.BLACKJACK.open(player)
    }

    companion object {
        @Subscribe
        @JvmStatic
        fun onGameLaunched(event: ServerLaunchEvent) {
            val existing = World.getObjectWithId(BLACKJACK_TABLE_LOCATION, ObjectId.TABLE)
            if (existing == null) {
                World.spawnObject(WorldObject(ObjectId.TABLE, 10, 0, BLACKJACK_TABLE_LOCATION))
            }
        }
    }
}
