package com.gadscape.network.rsprot

import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import net.rsprot.protocol.game.outgoing.camera.CamTargetV4
import org.slf4j.LoggerFactory

/**
 * CAM_TARGET (E3, rsprot [CamTargetV4]): points [Player]'s camera at another player, an NPC or the centre of
 * a tile, and back at the player himself.
 *
 * The client follows the target by its index in its own player/NPC list. If the target is not in the
 * viewer's build area (or leaves it), the client looks at the local player again until it can find the
 * target, so keep spectators inside the same instance as the target. A tile target needs the tile inside the
 * viewer's build area too.
 *
 * Everything here is sent directly on the viewer's rsprot session, in order with the packets queued by
 * `player.send(...)` in the same tick. Calls for players without an rsprot session (bots, tests) do nothing.
 *
 * Used by spectating (§3.13 of the Fractured Archive spec): `CamTarget.player(spectator, member)` when an orb
 * is clicked, `CamTarget.tile(spectator, roomCentre)` for the room view and `CamTarget.reset(spectator)` on
 * leave.
 */
object CamTarget {

    private val log = LoggerFactory.getLogger(CamTarget::class.java)

    /** Temporary attribute holding a short description of the current target (for debug dumps). */
    const val ATTRIBUTE = "rsprot_cam_target"

    /** Looks at [target] (another player); [target] == [viewer] is the same as [reset]. */
    @JvmStatic
    fun player(viewer: Player, target: Player): Boolean {
        val index = target.index
        if (index !in 1..2047) return false
        return send(viewer, CamTargetV4.PlayerCamTarget(index), if (target === viewer) null else "player ${target.username}")
    }

    /** Looks at [target]. */
    @JvmStatic
    fun npc(viewer: Player, target: NPC): Boolean {
        val index = target.index
        if (index !in 0..0xFFFF) return false
        return send(viewer, CamTargetV4.NpcCamTarget(index), "npc ${target.id}@$index")
    }

    /** Looks at the centre of [tile] (absolute coordinates). */
    @JvmStatic
    fun tile(viewer: Player, tile: Location): Boolean = tile(viewer, tile.x, tile.y, tile.plane)

    /** Looks at the centre of tile ([x], [y], [plane]) (absolute coordinates). */
    @JvmStatic
    fun tile(viewer: Player, x: Int, y: Int, plane: Int): Boolean {
        if (plane !in 0..3 || x !in 0..16383 || y !in 0..16383) return false
        return send(viewer, CamTargetV4.CoordGridTarget(plane, x, y), "tile $x,$y,$plane")
    }

    /** Looks at the viewer himself again (the client's default target). */
    @JvmStatic
    fun reset(viewer: Player): Boolean {
        val index = viewer.index
        if (index !in 1..2047) return false
        return send(viewer, CamTargetV4.PlayerCamTarget(index), null)
    }

    /** The current target as set through this object ("player Name", "npc 16683@12", "tile x,y,z"), or null. */
    @JvmStatic
    fun describe(viewer: Player): String? = viewer.temporaryAttributes[ATTRIBUTE] as? String

    private fun send(viewer: Player, type: CamTargetV4.CamTargetType, description: String?): Boolean {
        val session = RsprotSession.of(viewer) ?: return false
        return try {
            session.queue(CamTargetV4(type))
            if (description == null) viewer.temporaryAttributes.remove(ATTRIBUTE)
            else viewer.temporaryAttributes[ATTRIBUTE] = description
            true
        } catch (e: Exception) {
            log.error("CAM_TARGET {} for {} failed", type, viewer, e)
            false
        }
    }
}
