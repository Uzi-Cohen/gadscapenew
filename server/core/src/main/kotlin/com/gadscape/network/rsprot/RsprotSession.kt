package com.gadscape.network.rsprot

import com.zenyte.game.packet.GamePacketEncoder
import com.zenyte.game.packet.GamePacketEncoderMode
import com.zenyte.game.packet.out.UpdateZonePartialEnclosed
import com.zenyte.game.world.WorldThread
import com.zenyte.game.world.entity.player.LogoutType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import com.zenyte.net.game.ServerEvent
import com.zenyte.utils.TimeUnit
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.rsprot.protocol.api.Session
import net.rsprot.protocol.game.outgoing.varp.VarpLarge
import net.rsprot.protocol.game.outgoing.varp.VarpReset
import net.rsprot.protocol.game.outgoing.varp.VarpSmall
import net.rsprot.protocol.message.OutgoingGameMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The server-side [com.zenyte.net.Session] of a logged-in player, backed by an rsprot [Session].
 *
 * Replaces the rev-211 `MasterHandler`/`WorldSubHandler`: outgoing [GamePacketEncoder]s are turned into
 * rsprot messages via [GamePacketEncoder.toMessage] and queued; incoming messages are decoded by rsprot
 * and handed to the consumers in [RsprotIncoming] when the world thread calls [process].
 */
class RsprotSession(
    val player: Player,
    /** The rsprot session. INFO/other code may queue rsprot messages directly via [queue]. */
    val rsprot: Session<Player>,
    /**
     * The last value of every varp sent since the login (carried over to the new session on a reconnect,
     * which sends them again: packets lost while the connection was down are otherwise never repeated).
     */
    internal val sentVarps: Int2IntOpenHashMap = Int2IntOpenHashMap(),
) : com.zenyte.net.Session {

    /** The ISAAC seed of the login (or last reconnect) on this connection; a reconnecting client sends it back. */
    @Volatile
    internal var seed: IntArray? = null

    /** True once a reconnect moved the player to a new connection: this one must not log the player out. */
    @Volatile
    internal var replaced = false

    private val loginCycle = WorldThread.WORLD_CYCLE

    @Volatile
    private var lastPacketProcessedCycle = WorldThread.WORLD_CYCLE

    private val closed = AtomicBoolean()

    /** Set by the MOUSE_IDLE consumer so a burst of idle packets only counts once per tick (as in rev 211). */
    @JvmField
    var idleEventThisTick = false

    /** True once [RsprotInfo.login] ran for this session (so [close] calls [RsprotInfo.logout] exactly once). */
    @Volatile
    internal var infoLoggedIn = false

    override fun getHostAddress(): String = rsprot.inetAddress

    override fun process(): Boolean {
        idleEventThisTick = false
        if (closed.get()) return false
        if (!player.logoutType.processSession()) return false
        if (!isActive()) return false
        if (isExpired()) return false
        val processed = try {
            rsprot.processIncomingPackets(player)
        } catch (e: Throwable) {
            logger.error("Failed to process incoming packets for {}", player, e)
            0
        }
        if (processed > 0) {
            lastPacketProcessedCycle = WorldThread.WORLD_CYCLE
            return true
        }
        return false
    }

    override fun send(event: ServerEvent): Boolean {
        if (closed.get()) return false
        if (event !is GamePacketEncoder) {
            RsprotDrops.unsupported(event.javaClass.name)
            return false
        }
        val message = try {
            event.toMessage()
        } catch (e: Throwable) {
            logger.error("Failed to build rsprot message for {}", event.javaClass.simpleName, e)
            return false
        }
        if (message == null) {
            // an enclosed zone update with nothing (left) to send, or outside the build area: nothing to drop
            if (event !is UpdateZonePartialEnclosed) RsprotDrops.dropped(event.javaClass.simpleName)
            return false
        }
        when (message) {
            is VarpSmall -> recordVarp(message.id, message.value)
            is VarpLarge -> recordVarp(message.id, message.value)
            is VarpReset -> synchronized(sentVarps) { sentVarps.clear() }
        }
        queue(message)
        if (event.encoderMode() == GamePacketEncoderMode.WRITE_FLUSH) {
            rsprot.flush()
        }
        return true
    }

    /** Queues an rsprot message for this player; it is written on the next [flush]. */
    fun queue(message: OutgoingGameMessage) {
        rsprot.queue(message)
    }

    override fun flush() {
        rsprot.flush()
    }

    /**
     * Called once when the player is finished (Player.postFinish). Releases the player's info
     * protocol slot and closes the connection after the pending packets (e.g. LOGOUT) are written.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (infoLoggedIn) {
            infoLoggedIn = false
            try {
                RsprotInfo.logout(player)
            } catch (e: Throwable) {
                logger.error("RsprotInfo.logout failed for {}", player, e)
            }
        }
        val channel = rsprot.ctx.channel()
        rsprot.requestClose()
        // rsprot only closes the channel from its flush; if nothing was pending, close it ourselves shortly
        // after so the client still has a chance to read the LOGOUT packet.
        if (channel.isActive) {
            channel.eventLoop().schedule({ if (channel.isActive) channel.close() }, 2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun recordVarp(id: Int, value: Int) {
        synchronized(sentVarps) { sentVarps.put(id, value) }
    }

    /** A copy of [sentVarps] (id -> last value sent). */
    internal fun sentVarpsSnapshot(): Int2IntOpenHashMap = synchronized(sentVarps) { Int2IntOpenHashMap(sentVarps) }

    /**
     * Retires this connection after a reconnect moved the player to a new one: no logout, no info release
     * (the new session owns the player's info slot), just close the old channel.
     */
    internal fun retire() {
        replaced = true
        infoLoggedIn = false
        if (!closed.compareAndSet(false, true)) return
        try {
            rsprot.requestClose()
        } catch (e: Throwable) {
            logger.debug("requestClose of a replaced session failed for {}", player, e)
        }
        val channel = rsprot.ctx.channel()
        if (channel.isActive) channel.close()
    }

    override fun isActive(): Boolean = !closed.get() && rsprot.ctx.channel().isActive

    // right after login, allow more silence (the client may freeze while it loads the map)
    private val sessionExpirationTimeout: Long
        get() = if (WorldThread.WORLD_CYCLE - loginCycle < 500) normalSessionExpirationTimeout.coerceAtLeast(200)
        else normalSessionExpirationTimeout

    private val normalSessionExpirationTimeout: Long
        get() = runCatching {
            if (player.areaManager?.area is WildernessArea || player.getBooleanTemporaryAttribute("gambling"))
                SESSION_EXPIRATION_TIMEOUT_WILDERNESS
            else
                SESSION_EXPIRATION_TIMEOUT_SAFE
        }.getOrElse { SESSION_EXPIRATION_TIMEOUT_SAFE }

    override fun isExpired(): Boolean = WorldThread.WORLD_CYCLE - lastPacketProcessedCycle >= sessionExpirationTimeout

    /** Called by rsprot (netty thread) when the connection drops; the world thread logs the player out. */
    internal fun onDisconnected() {
        if (replaced) return // the player already continues on a reconnected session
        try {
            if (player.logoutType == LogoutType.NONE) player.logoutType = LogoutType.CONNECTION_LOST
        } catch (e: Exception) {
            logger.error("Failed to mark {} as disconnected", player, e)
        }
    }

    override fun toString(): String =
        "RsprotSession(player=${runCatching { player.username }.getOrNull()}, address=${getHostAddress()}, " +
            "active=${isActive()}, expired=${isExpired()})"

    companion object {
        private val logger = LoggerFactory.getLogger(RsprotSession::class.java)

        private val SESSION_EXPIRATION_TIMEOUT_WILDERNESS = TimeUnit.SECONDS.toTicks(60)
        private val SESSION_EXPIRATION_TIMEOUT_SAFE = TimeUnit.SECONDS.toTicks(20)

        /** The rsprot session of [player], or null when the player is not connected through rsprot. */
        @JvmStatic
        fun of(player: Player): RsprotSession? = player.session as? RsprotSession
    }
}

/** Logs, once per packet type, the outgoing packets rev 241 / rsprot has no equivalent for. */
object RsprotDrops {
    private val logger = LoggerFactory.getLogger(RsprotDrops::class.java)
    private val seen = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun dropped(name: String) {
        if (seen.add(name)) logger.warn("Dropping outgoing {}: no rev-241 (rsprot) equivalent. (logged once)", name)
    }

    @JvmStatic
    fun unsupported(name: String) {
        if (seen.add(name)) logger.warn("Dropping outgoing {}: not a GamePacketEncoder. (logged once)", name)
    }
}
