package com.gadscape.network.rsprot

import com.zenyte.game.model.HintArrow
import com.zenyte.game.model.HintArrowPosition
import com.zenyte.game.packet.GamePacketEncoder
import com.zenyte.game.world.World
import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.DynamicRegion
import net.rsprot.protocol.api.util.ZonePartialEnclosedCacheBuffer
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.map.RebuildNormalV2
import net.rsprot.protocol.game.outgoing.map.RebuildRegionV2
import net.rsprot.protocol.game.outgoing.map.util.RebuildRegionZone
import net.rsprot.protocol.game.outgoing.misc.client.HintArrow as RsHintArrow
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import net.rsprot.protocol.game.outgoing.zone.header.UpdateZonePartialEnclosed
import net.rsprot.protocol.message.OutgoingGameMessage
import net.rsprot.protocol.message.ZoneProt
import org.slf4j.LoggerFactory

/**
 * Helpers that build the rsprot messages for the less trivial rev-211 packets in
 * `com.zenyte.game.packet.out` (called from their `toMessage()`).
 */
object RsprotOut {

    private val logger = LoggerFactory.getLogger(RsprotOut::class.java)

    /** World area sent with REBUILD_NORMAL / REBUILD_LOGIN; OpenRune-Server sends -1 (no world area). */
    const val WORLD_AREA: Int = -1

    // ------------------------------------------------------------------------------------- map

    /** REBUILD_NORMAL (V2): only the player's zone; rev 241 sends no XTEA keys. */
    @JvmStatic
    fun rebuildNormal(player: Player): RebuildNormalV2 {
        val location = player.location
        return RebuildNormalV2(location.x shr 3, location.y shr 3, WORLD_AREA)
    }

    /** REBUILD_REGION (V2): the 4x13x13 zone copy table of the dynamic map around the player. */
    @JvmStatic
    fun rebuildRegion(player: Player, reload: Boolean): RebuildRegionV2 {
        val location = player.location
        val centerZoneX = location.chunkX
        val centerZoneZ = location.chunkY
        return RebuildRegionV2(centerZoneX, centerZoneZ, reload) { zoneX, zoneZ, level ->
            val regionId = ((zoneX shr 3) shl 8) + (zoneZ shr 3)
            val region = World.getRegions().get(regionId)
            var displayedZoneX = zoneX
            var displayedZoneZ = zoneZ
            var displayedLevel = level
            var rotation = 0
            if (DynamicRegion.isDynamicRegion(region)) {
                val hash = (region as DynamicRegion).getLocationHash(zoneX and 7, zoneZ and 7, level)
                displayedZoneX = hash and 2047
                displayedZoneZ = hash shr 11 and 2047
                displayedLevel = hash shr 22 and 3
                rotation = hash shr 24 and 3
            }
            if (displayedZoneX == 0 && displayedZoneZ == 0) null
            else RebuildRegionZone(displayedZoneX, displayedZoneZ, displayedLevel, rotation)
        }
    }

    // ------------------------------------------------------------------------------------- zones

    private val zoneBuffers = ZonePartialEnclosedCacheBuffer(listOf(OldSchoolClientType.DESKTOP))
    private var zoneBufferCycle = Long.MIN_VALUE

    /**
     * UPDATE_ZONE_PARTIAL_ENCLOSED for one zone: every appended zone packet (LOC_ADD_CHANGE, LOC_DEL,
     * LOC_ANIM, LOC_MERGE, MAP_ANIM, MAP_PROJANIM, OBJ_ADD/DEL/COUNT, SOUND_AREA) in the given order.
     * [zoneTileX]/[zoneTileZ] are the absolute tile coordinates of (any tile in) the zone.
     */
    @JvmStatic
    fun zonePartialEnclosed(player: Player, zoneTileX: Int, zoneTileZ: Int, packets: List<GamePacketEncoder>): UpdateZonePartialEnclosed? {
        val prots = ArrayList<ZoneProt>(packets.size)
        for (packet in packets) {
            val message = try {
                packet.toMessage()
            } catch (e: Throwable) {
                logger.error("Failed to build zone packet {}", packet.javaClass.simpleName, e)
                null
            }
            when (message) {
                null -> RsprotDrops.dropped(packet.javaClass.simpleName)
                is ZoneProt -> prots += message
                else -> {
                    logger.warn("{} is not a zone packet, sent on its own", packet.javaClass.simpleName)
                    RsprotSession.of(player)?.queue(message)
                }
            }
            try {
                packet.logChecked(player)
            } catch (e: Exception) {
                logger.error("Failed to logChecked", e)
            }
        }
        if (prots.isEmpty()) return null
        val base = player.lastLoadedMapRegionTile ?: player.location
        val zoneX = ((zoneTileX shr 3) shl 3) - 8 * (base.chunkX - 6)
        val zoneZ = ((zoneTileZ shr 3) shl 3) - 8 * (base.chunkY - 6)
        if (zoneX !in 0..103 || zoneZ !in 0..103) return null
        releaseZoneBuffersIfNewCycle()
        val payload = zoneBuffers.computeZoneForClient(OldSchoolClientType.DESKTOP, prots)
        return UpdateZonePartialEnclosed(zoneX, zoneZ, player.plane, payload)
    }

    /** The cache buffer must be released once per world cycle; done lazily on the first zone of a new cycle. */
    private fun releaseZoneBuffersIfNewCycle() {
        val cycle = WorldThread.WORLD_CYCLE
        if (cycle != zoneBufferCycle) {
            zoneBufferCycle = cycle
            zoneBuffers.releaseBuffers()
        }
    }

    // ------------------------------------------------------------------------------------- misc

    /** RUNCLIENTSCRIPT; rsprot infers the argument types from the values (int, string, long). */
    @JvmStatic
    fun runClientScript(scriptId: Int, arguments: Array<out Any?>?): RunClientScript {
        if (arguments == null || arguments.isEmpty()) return RunClientScript(scriptId, emptyList())
        val values = ArrayList<Any>(arguments.size)
        for (argument in arguments) {
            values += when (argument) {
                null -> -1
                is String -> argument
                is Long -> argument
                is Int -> argument
                is Number -> argument.toInt()
                is Boolean -> if (argument) 1 else 0
                is Char -> argument.code
                is IntArray -> argument
                else -> argument.toString()
            }
        }
        return RunClientScript(scriptId, values)
    }

    @JvmStatic
    fun hintArrow(arrow: HintArrow?): RsHintArrow {
        if (arrow == null) return RsHintArrow(RsHintArrow.ResetHintArrow)
        if (arrow.position == HintArrowPosition.ENTITY) {
            val target = arrow.target ?: return RsHintArrow(RsHintArrow.ResetHintArrow)
            return if (target.entityType == Entity.EntityType.NPC) RsHintArrow(RsHintArrow.NpcHintArrow(target.index))
            else RsHintArrow(RsHintArrow.PlayerHintArrow(target.index))
        }
        return RsHintArrow(RsHintArrow.TileHintArrow(arrow.x, arrow.y, arrow.height.toInt() and 0xFF, arrow.position.positionHash))
    }

    // Seeded per boot (211 sent random ids): the client drops a (world, counter) it saw in its last 100 messages, even from a previous boot.
    private val messageCounter = java.util.concurrent.atomic.AtomicInteger((System.currentTimeMillis() / 1000).toInt())

    /** Per-world chat message counter (the client uses world id + counter to drop duplicate messages). */
    @JvmStatic
    fun nextMessageCounter(): Int = messageCounter.incrementAndGet() and 0xFFFFFF

    /** Some rev-211 packets have no rev-241 equivalent; see docs/rev241-net.md. */
    @JvmStatic
    fun none(): OutgoingGameMessage? = null
}
