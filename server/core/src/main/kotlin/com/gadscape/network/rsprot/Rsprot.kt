package com.gadscape.network.rsprot

import com.zenyte.CacheManager
import com.zenyte.game.GameConstants
import com.zenyte.game.world.entity.player.Player
import io.netty.buffer.Unpooled
import net.rsprot.compression.HuffmanCodec
import net.rsprot.compression.provider.DefaultHuffmanCodecProvider
import net.rsprot.compression.provider.HuffmanCodecProvider
import net.rsprot.crypto.rsa.RsaKeyPair
import net.rsprot.protocol.api.AbstractNetworkServiceFactory
import net.rsprot.protocol.api.GameConnectionHandler
import net.rsprot.protocol.api.NetworkService
import net.rsprot.protocol.api.handlers.ExceptionHandlers
import net.rsprot.protocol.api.handlers.GameMessageHandlers
import net.rsprot.protocol.api.handlers.INetAddressHandlers
import net.rsprot.protocol.api.implementation.DefaultInetAddressValidator
import net.rsprot.protocol.api.handlers.LoginHandlers
import net.rsprot.protocol.api.implementation.DefaultGameMessageCounter
import net.rsprot.protocol.api.js5.Js5GroupProvider
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.loginprot.incoming.pow.challenges.sha256.DefaultSha256ProofOfWorkProvider
import net.rsprot.protocol.message.codec.incoming.provider.GameMessageConsumerRepositoryProvider
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Entry point of the rev-241 network layer (rsprot `net.rsprot:osrs-241-api`, MIT,
 * https://github.com/blurite/rsprot). Replaces the hand-written rev-211 netty pipeline.
 *
 * The factory below is modelled on OpenRune-Server's `NetworkFactory` (ISC, Copyright (c) 2025 RS Mod).
 *
 * Configuration (system properties):
 *  - `gadscape.cache.dir`     cache directory served over JS5 and used for Huffman (default CacheManager.DEFAULT_CACHE_PATH)
 *  - `gadscape.rsa.private`   login RSA private key file (see [RsprotRsa]); falls back to worlds.yml rsaPrivateExponent/rsaModulus
 *  - `gadscape.net.ports`     comma separated extra ports to listen on besides the world's port (e.g. "443")
 *  - `gadscape.js5.preload`   true = prepare every JS5 group at boot instead of on first request
 *  - `gadscape.login.token`   expected login "site settings" string (rev-211 session token); unset = not checked
 *  - `gadscape.login.skipCrc` true = do not compare the client's cache CRCs at login
 *  - `gadscape.net.maxConnectionsPerIp` game (and, separately, JS5) connections allowed per IP (default 10, rsprot's)
 *  - `gadscape.net.reconnect` false = refuse client reconnects (see [RsprotReconnect]); default true
 *  - `net.rsprot.protocol.internal.development` rsprot's per-packet validation; defaults to false on BETA/PUBLIC/PRIVATE
 *    worlds (rsprot asks for it to be off in production), true on DEVELOPER worlds
 */
object Rsprot {

    private val logger = LoggerFactory.getLogger(Rsprot::class.java)

    /** The client revision this network layer speaks (net.rsprot:osrs-241-api). */
    const val REVISION: Int = 241

    lateinit var service: NetworkService<Player>

    /** The JS5 group source (also used for the login CRC check). */
    @JvmStatic
    var js5: RsprotJs5? = null
        private set

    /** False until the world has finished loading; logins before that get LoginServerOffline. */
    @JvmStatic
    @Volatile
    var acceptLogins: Boolean = false

    @JvmStatic
    val isStarted: Boolean
        get() = ::service.isInitialized

    @JvmStatic
    fun cacheDirectory(): Path =
        Paths.get(System.getProperty("gadscape.cache.dir")?.takeIf { it.isNotBlank() } ?: CacheManager.DEFAULT_CACHE_PATH)

    @JvmStatic
    fun js5Crcs(): IntArray? = js5?.archiveCrcs

    @OptIn(ExperimentalUnsignedTypes::class, ExperimentalStdlibApi::class)
    fun start() {
        check(!isStarted) { "rsprot network service already started" }
        configureDevelopmentMode()
        val js5 = RsprotJs5(RawJs5Cache(cacheDirectory()))
        if (java.lang.Boolean.getBoolean("gadscape.js5.preload")) js5.preloadAll()
        this.js5 = js5
        val factory = GadScapeNetworkServiceFactory(js5, loadRsaKeyPair())
        service = factory.build()
        // INFO track's per-tick player/npc info packets go out through the player's rsprot session.
        RsprotInfo.sink = RsprotInfo.Sink { player, message ->
            val session = RsprotSession.of(player)
            if (session == null || !session.isActive()) {
                false
            } else {
                session.queue(message)
                true
            }
        }
        service.start()
        logger.info(
            "rsprot {} network service listening on {} (cache {})",
            REVISION, factory.ports, cacheDirectory().toAbsolutePath()
        )
    }

    private const val RSPROT_DEVELOPMENT_PROPERTY = "net.rsprot.protocol.internal.development"

    /**
     * rsprot reads its flags once, when RSProtFlags is first loaded (by the first rsprot class that needs it),
     * so this must run before anything rsprot is touched. An explicit -D always wins.
     */
    private fun configureDevelopmentMode() {
        val explicit = System.getProperty(RSPROT_DEVELOPMENT_PROPERTY)
        if (explicit == null) {
            val developerWorld = runCatching { GameConstants.WORLD_PROFILE.isDevelopment() }.getOrDefault(true)
            if (!developerWorld) System.setProperty(RSPROT_DEVELOPMENT_PROPERTY, "false")
        }
        logger.info(
            "rsprot development mode (validation of every packet): {}",
            System.getProperty(RSPROT_DEVELOPMENT_PROPERTY) ?: "true (rsprot default)"
        )
    }

    @JvmStatic
    fun shutdown() {
        if (isStarted) service.shutdownNow()
    }

    private fun loadRsaKeyPair(): RsaKeyPair {
        val file = System.getProperty("gadscape.rsa.private")?.takeIf { it.isNotBlank() }
        if (file != null) {
            val path = Paths.get(file)
            require(Files.exists(path)) { "gadscape.rsa.private points to a missing file: $path" }
            val pair = RsprotRsa.load(path)
            logger.info("Loaded login RSA key ({} bits) from {}", pair.modulus.bitLength(), path.toAbsolutePath())
            return pair
        }
        val profile = GameConstants.WORLD_PROFILE
        val exponent = profile.rsaPrivateExponent
            ?: error("No RSA key: set -Dgadscape.rsa.private=<file> or rsaPrivateExponent/rsaModulus in worlds.yml")
        val modulus = profile.rsaModulus ?: error("worlds.yml world is missing rsaModulus")
        return RsaKeyPair(java.math.BigInteger(exponent, 36), java.math.BigInteger(modulus, 36))
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
class GadScapeNetworkServiceFactory(
    private val js5: RsprotJs5,
    private val rsaKeyPair: RsaKeyPair,
) : AbstractNetworkServiceFactory<Player>() {

    override val ports: List<Int> = buildList {
        add(GameConstants.WORLD_PROFILE?.port?.takeIf { it > 0 } ?: 43594)
        System.getProperty("gadscape.net.ports")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.forEach {
            if (it !in this) add(it)
        }
    }

    override val supportedClientTypes: List<OldSchoolClientType> = listOf(OldSchoolClientType.DESKTOP)

    override fun getRsaKeyPair(): RsaKeyPair = rsaKeyPair

    override fun getHuffmanCodecProvider(): HuffmanCodecProvider {
        val data = js5.readNamedGroup(HUFFMAN_ARCHIVE, "huffman")
            ?: error("No 'huffman' group in archive $HUFFMAN_ARCHIVE of ${js5.cache.directory}")
        return DefaultHuffmanCodecProvider(HuffmanCodec.create(Unpooled.wrappedBuffer(data)))
    }

    override fun getJs5GroupProvider(): Js5GroupProvider = js5

    override fun getGameMessageConsumerRepositoryProvider(): GameMessageConsumerRepositoryProvider<Player> =
        RsprotIncoming.provider()

    override fun getGameConnectionHandler(): GameConnectionHandler<Player> = RsprotConnectionHandler()

    // 211 processed up to 32 inbound packets per tick (NetworkConstants.MAX_INBOUND_PACKETS_PER_TICK); rsprot defaults to 10 user events.
    override fun getGameMessageHandlers(): GameMessageHandlers =
        GameMessageHandlers(gameMessageCounterProvider = { DefaultGameMessageCounter(clientEventLimit = 50, userEventLimit = 32) })

    /**
     * rsprot allows 10 game and 10 JS5 connections per IP by default; the rev-211 server had no limit.
     * -Dgadscape.net.maxConnectionsPerIp=N raises it (e.g. for events where many players share one address).
     */
    override fun getINetAddressHandlers(): INetAddressHandlers {
        val limit = Integer.getInteger("gadscape.net.maxConnectionsPerIp", DEFAULT_CONNECTIONS_PER_IP)
            .coerceAtLeast(1)
        logger.info("Allowing {} game and {} JS5 connections per IP", limit, limit)
        return INetAddressHandlers(DefaultInetAddressValidator(limit))
    }

    override fun getLoginHandlers(): LoginHandlers {
        val world = GameConstants.WORLD_PROFILE?.number ?: 1
        return LoginHandlers(
            proofOfWorkProvider = DefaultSha256ProofOfWorkProvider(world),
        )
    }

    override fun getExceptionHandlers(): ExceptionHandlers<Player> =
        ExceptionHandlers(
            { ctx, cause ->
                logger.debug("Exception in channel {}", ctx.channel(), cause)
                if (ctx.channel().isActive) ctx.close()
            },
            { session, message, cause ->
                logger.error("Failed to handle {} from {}", message.javaClass.simpleName, session.inetAddress, cause)
            },
        )

    private companion object {
        private val logger = LoggerFactory.getLogger(GadScapeNetworkServiceFactory::class.java)
        private const val HUFFMAN_ARCHIVE = 10
        private const val DEFAULT_CONNECTIONS_PER_IP = 10
    }
}
