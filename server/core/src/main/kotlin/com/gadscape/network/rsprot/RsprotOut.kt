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

    /**
     * RUNCLIENTSCRIPT; rsprot infers the argument types from the values (int, string, long, int array).
     *
     * The 241 client binds the arguments positionally per type (the n-th int to int argument n, the n-th
     * string/array to object argument n, the n-th long to long argument n) into local arrays sized by the
     * script, so a surplus argument of any type throws before the first instruction. The arguments are
     * checked against the script's header in the cache the server serves (see [checkClientScript]).
     *
     * @return the message, or `null` (dropped) when the script does not exist in the cache.
     */
    @JvmStatic
    fun runClientScript(scriptId: Int, arguments: Array<out Any?>?): RunClientScript? {
        val values = ArrayList<Any>(arguments?.size ?: 0)
        if (arguments != null) {
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
        }
        if (!checkClientScript(scriptId, values)) return null
        return RunClientScript(scriptId, values)
    }

    /** Script ids already reported by [checkClientScript] (one WARN per script id per boot). */
    private val reportedScripts: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** A rev-211 style type descriptor ("ii", "isi", ...) some call sites still pass as their first argument. */
    private val TYPE_DESCRIPTOR = Regex("^[isWXlo]+$")

    /**
     * Checks [values] (already normalised to Int/String/Long/IntArray) against the int/object/long
     * argument counts of client script [scriptId] in the served cache. If the counts do not match and the
     * first value is a pure type-descriptor string (e.g. `"ii"`) whose removal makes them match, the string
     * is removed from [values]. Any other mismatch is logged (WARN, once per script id, with the caller) and
     * sent unchanged.
     *
     * @return `false` if the packet must not be sent: the script does not exist in the cache, so the client
     * can only fail on it.
     */
    @JvmStatic
    fun checkClientScript(scriptId: Int, values: MutableList<Any>): Boolean {
        val definition = clientScriptHeader(scriptId) ?: return true // cache not loaded: nothing to check against
        if (definition === MISSING_SCRIPT) {
            report(scriptId, "script $scriptId does not exist in the cache; packet dropped")
            return false
        }
        val wantInts = definition.intArgsCount
        val wantObjs = definition.stringArgsCount
        val wantLongs = definition.longArgumentCount
        if (countsMatch(values, 0, wantInts, wantObjs, wantLongs)) return true
        val first = values.firstOrNull()
        if (first is String && TYPE_DESCRIPTOR.matches(first) && countsMatch(values, 1, wantInts, wantObjs, wantLongs)) {
            values.removeAt(0)
            return true
        }
        var ints = 0
        var objs = 0
        var longs = 0
        for (value in values) {
            when (value) {
                is Int -> ints++
                is Long -> longs++
                else -> objs++
            }
        }
        val overflow = ints > definition.intArgumentCount || objs > definition.stringArgumentCount ||
            longs > definition.localLongCount
        report(scriptId, "script $scriptId takes $wantInts int/$wantObjs object/$wantLongs long arguments but was sent " +
            "$ints int/$objs object/$longs long " + values.joinToString(prefix = "(", postfix = ")") { describe(it) } +
            if (overflow) "; the client will throw before the script runs" else "; the missing arguments read as 0/null")
        return true
    }

    private fun countsMatch(values: List<Any>, from: Int, ints: Int, objs: Int, longs: Int): Boolean {
        var i = 0
        var o = 0
        var l = 0
        for (index in from until values.size) {
            when (values[index]) {
                is Int -> i++
                is Long -> l++
                else -> o++
            }
        }
        return i == ints && o == objs && l == longs
    }

    private fun describe(value: Any): String = when (value) {
        is String -> "\"" + value + "\""
        is Long -> value.toString() + "L"
        is IntArray -> "int[" + value.size + "]"
        else -> value.toString()
    }

    private val MISSING_SCRIPT = mgi.types.clientscript.ClientScriptDefinitions()

    /** The script's header from the served cache, [MISSING_SCRIPT] if it is not in the cache, null if no cache is loaded. */
    private fun clientScriptHeader(scriptId: Int): mgi.types.clientscript.ClientScriptDefinitions? {
        return try {
            mgi.types.clientscript.ClientScriptDefinitions.get(scriptId) ?: MISSING_SCRIPT
        } catch (e: ArrayIndexOutOfBoundsException) {
            if (scriptId < 0) null else MISSING_SCRIPT
        } catch (e: NullPointerException) {
            null // definitions not loaded (tools, tests)
        }
    }

    private fun report(scriptId: Int, problem: String) {
        if (!reportedScripts.add(scriptId)) return
        val trace = Throwable("RUNCLIENTSCRIPT $scriptId caller")
        val caller = trace.stackTrace.firstOrNull { frame ->
            val cls = frame.className
            !cls.startsWith("com.gadscape.network.rsprot.") && cls != "com.zenyte.game.packet.out.RunClientScript" &&
                cls != "com.zenyte.game.packet.PacketDispatcher" && cls != "com.zenyte.game.world.entity.player.Player" &&
                !cls.startsWith("kotlin.") && !cls.startsWith("java.")
        }
        logger.warn("RUNCLIENTSCRIPT {}: {}. Caller: {} (logged once per script)", scriptId, problem, caller, trace)
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
