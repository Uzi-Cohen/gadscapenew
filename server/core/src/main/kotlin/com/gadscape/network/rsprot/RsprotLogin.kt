package com.gadscape.network.rsprot

import com.gadscape.api.GameDatabase
import com.gadscape.api.responses.UserLoginResponse
import com.gadscape.api.service.user.UserPlayerHandler
import com.gadscape.api.service.user.twoFactorEnabled
import com.gadscape.game.world.entity.player.onLogin
import com.gadscape.net.HardwareInfo
import com.gadscape.network.ipv4Address
import com.gadscape.network.login.AuthenticatorInfo
import com.gadscape.website.WebsiteService
import com.zenyte.game.GameConstants
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.LogoutType
import io.netty.buffer.Unpooled
import com.zenyte.game.world.entity.player.PlayerInformation
import com.zenyte.net.ClientResponse
import com.zenyte.net.NetworkConstants
import com.zenyte.net.login.AuthType
import com.zenyte.utils.TextUtils
import com.zenyte.utils.TimeUnit
import kotlinx.coroutines.runBlocking
import net.rsprot.crypto.xtea.XteaKey
import net.rsprot.protocol.api.GameConnectionHandler
import net.rsprot.protocol.api.login.GameLoginResponseHandler
import net.rsprot.protocol.loginprot.incoming.util.AuthenticationType
import net.rsprot.protocol.loginprot.incoming.util.LoginBlock
import net.rsprot.protocol.loginprot.incoming.util.OtpAuthenticationType
import net.rsprot.protocol.loginprot.outgoing.LoginResponse
import net.rsprot.protocol.loginprot.outgoing.util.AuthenticatorResponse
import org.slf4j.LoggerFactory

/**
 * rsprot calls this (on its login executor, after proof of work, RSA and session-id checks) for
 * every game login. It feeds the request into the existing LoginManager flow, exactly as the rev-211
 * `MasterHandler.LoginSubHandler` did, so account loading, password/2FA checks and saves are unchanged.
 */
class RsprotConnectionHandler : GameConnectionHandler<Player> {

    override fun onLogin(
        responseHandler: GameLoginResponseHandler<Player>,
        block: LoginBlock<AuthenticationType>,
    ) {
        try {
            handleLogin(responseHandler, block)
        } catch (e: Throwable) {
            logger.error("Login failed for '{}'", block.username, e)
            responseHandler.writeFailedResponse(LoginResponse.UnknownReplyFromLoginServer)
        }
    }

    private fun handleLogin(
        responseHandler: GameLoginResponseHandler<Player>,
        block: LoginBlock<AuthenticationType>,
    ) {
        if (!Rsprot.acceptLogins) {
            responseHandler.writeFailedResponse(LoginResponse.LoginServerOffline)
            return
        }
        val auth = block.authentication
        if (auth !is AuthenticationType.PasswordAuthentication) {
            // Token (Jagex account) logins are not supported by this server.
            responseHandler.writeFailedResponse(LoginResponse.InvalidLoginPacket)
            return
        }
        val password = auth.password.asString()
        auth.password.clear()

        val early = RsprotLoginChecks.preLoadResponse(block)
        if (early != null) {
            logger.info("Rejected login '{}': {}", block.username, early)
            responseHandler.writeFailedResponse(early.toLoginResponse())
            return
        }

        val info = PlayerInformation(
            block.username,
            password,
            if (block.resizable) 1 else 0,
            block.uuid,
            block.hostPlatformStats.toHardwareInfo(),
        )
        val request = RsprotLoginRequest(responseHandler, block, info, password, auth.otpAuthentication.toAuthenticatorInfo())
        World.addLoginRequest(request, info)
    }

    /**
     * A client whose connection dropped asks to continue its session ("Connection lost - attempting to
     * reestablish"). The rev-211 server answered every reconnect with a failure, so the player stayed in the
     * world for the logout timeout (20 s, 60 s in the wilderness) and had to log in again afterwards. Now the
     * player continues on the new connection if it is still in the world and the client proves it owns the
     * session (it sends back the ISAAC seed of its last login); see [RsprotReconnect].
     * -Dgadscape.net.reconnect=false restores the old behaviour.
     */
    override fun onReconnect(
        responseHandler: GameLoginResponseHandler<Player>,
        block: LoginBlock<XteaKey>,
    ) {
        try {
            if (!RsprotReconnect.enabled || !Rsprot.acceptLogins) {
                responseHandler.writeFailedResponse(LoginResponse.ConnectFail)
                return
            }
            val early = RsprotLoginChecks.reconnectResponse(block)
            if (early != null) {
                logger.info("Rejected reconnect '{}': {}", block.username, early)
                responseHandler.writeFailedResponse(early.toLoginResponse())
                return
            }
            val previousSeed = block.authentication.key.copyOf()
            WorldTasksManager.schedule { RsprotReconnect.complete(responseHandler, block, previousSeed) }
        } catch (e: Throwable) {
            logger.error("Reconnect failed for '{}'", block.username, e)
            responseHandler.writeFailedResponse(LoginResponse.ConnectFail)
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RsprotConnectionHandler::class.java)
    }
}

/** The checks the rev-211 `LoginPacketIn.getResponse()` did, on the rsprot login block. */
object RsprotLoginChecks {

    /**
     * Expected value of the login block's "site settings" string (what rev 211 called the session
     * token). Unset (default) = not checked, because a stock RuneLite 241 client sends an empty string.
     */
    private val expectedToken: String? = System.getProperty("gadscape.login.token")?.takeIf { it.isNotEmpty() }

    /** -Dgadscape.login.skipCrc=true disables the cache CRC check (like beta worlds did in rev 211). */
    private val skipCrcCheck: Boolean = java.lang.Boolean.getBoolean("gadscape.login.skipCrc")

    /** The checks that still apply to a reconnect (the player is already in the world, so no capacity checks). */
    fun reconnectResponse(block: LoginBlock<*>): ClientResponse? {
        if (block.version != Rsprot.REVISION) return ClientResponse.CLIENT_UPDATED
        if (!skipCrcCheck && !GameConstants.WORLD_PROFILE.isBeta()) {
            val crcs = Rsprot.js5Crcs()
            if (crcs != null && !block.crc.validate(crcs)) return ClientResponse.SERVER_UPDATED
        }
        if (expectedToken != null && expectedToken != block.siteSettings) return ClientResponse.BAD_SESSION_ID
        return null
    }

    fun preLoadResponse(block: LoginBlock<*>): ClientResponse? {
        if (block.version != Rsprot.REVISION) return ClientResponse.CLIENT_UPDATED
        if (!skipCrcCheck && !GameConstants.WORLD_PROFILE.isBeta()) {
            val crcs = Rsprot.js5Crcs()
            if (crcs != null && !block.crc.validate(crcs)) return ClientResponse.SERVER_UPDATED
        }
        if (expectedToken != null && expectedToken != block.siteSettings) return ClientResponse.BAD_SESSION_ID
        if (World.getPlayers().size >= NetworkConstants.PLAYER_CAP) return ClientResponse.WORLD_FULL
        if (World.isUpdating() && World.getUpdateTimer() < TimeUnit.MINUTES.toTicks(1)) return ClientResponse.UPDATE_IN_PROGRESS
        val formatted = TextUtils.formatNameForProtocol(block.username)
        if (formatted.isEmpty() || formatted.length > 12 || !TextUtils.isValidName(formatted))
            return ClientResponse.INVALID_USERNAME_OR_PASSWORD
        return null
    }
}

/**
 * Port of the rev-211 `LoginRequest`: decides the response code once the account was loaded and,
 * on success, registers the player on the world thread and switches the connection to the game.
 */
class RsprotLoginRequest(
    private val responseHandler: GameLoginResponseHandler<Player>,
    private val block: LoginBlock<*>,
    private val info: PlayerInformation,
    private val password: String,
    private val authInfo: AuthenticatorInfo,
) {

    private val hostAddress: String
        get() = runCatching { responseHandler.ctx.ipv4Address().hostAddress }.getOrDefault("0.0.0.0")

    private fun getResponseCode(player: Player?): ClientResponse {
        val early = RsprotLoginChecks.preLoadResponse(block)
        if (early != null) return early

        val username = info.username
        if (username.isEmpty()
            || username.first() == '_'
            || username.last() == '_'
            || username.first() == ' '
            || username.last() == ' '
            || username.length > 12
            || !TextUtils.isValidName(username)
        )
            return ClientResponse.INVALID_USERNAME_OR_PASSWORD

        val world = GameConstants.WORLD_PROFILE

        if (world.useWhitelist && !world.whitelistedUsernames.contains(username))
            return ClientResponse.CLOSED_BETA

        if (!world.verifyPasswords)
            return ClientResponse.LOGIN_OK

        if (player != null) {
            if (!world.isApiEnabled()) {
                if (world.isMainDatabaseEnabled()) {
                    val loginResponse = runBlocking {
                        GameDatabase.validateUserLogin(info.displayname, info.plainPassword)
                    }
                    return when (loginResponse) {
                        UserLoginResponse.InvalidPassword,
                        UserLoginResponse.UserNotExist -> ClientResponse.INVALID_USERNAME_OR_PASSWORD
                        is UserLoginResponse.Success -> {
                            player.user = loginResponse.user
                            ClientResponse.LOGIN_OK
                        }
                    }
                } else {
                    if (password != player.playerInformation.plainPassword) {
                        return ClientResponse.INVALID_USERNAME_OR_PASSWORD
                    }
                }
            } else {
                val loginResult = UserPlayerHandler.validateLogin(player, hostAddress)
                if (loginResult != ClientResponse.LOGIN_OK)
                    return loginResult
                if (world.verify2FA()) {
                    if (player.twoFactorEnabled) {
                        player.authenticator.isEnabled = true
                        when (val type = authInfo.type) {
                            AuthType.NORMAL -> return ClientResponse.PROMPT_AUTHENTICATOR

                            AuthType.TRUSTED_AUTHENTICATION,
                            AuthType.UNTRUSTED_AUTHENTICATION -> {
                                if (authInfo.code > 0) {
                                    if (!UserPlayerHandler.validate2FA(player, authInfo.code))
                                        return ClientResponse.WRONG_AUTHENICATOR_CODE
                                    if (type == AuthType.TRUSTED_AUTHENTICATION)
                                        player.authenticator.trust()
                                } else
                                    return ClientResponse.WRONG_AUTHENICATOR_CODE
                            }

                            AuthType.TRUSTED_COMPUTER -> {
                                val result = player.authenticator.validate(authInfo.identifier)
                                if (result != ClientResponse.LOGIN_OK) return ClientResponse.PROMPT_AUTHENTICATOR
                            }
                        }
                    } else
                        player.authenticator.isEnabled = true
                }
            }
        }
        if (World.containsPlayer(username)) return ClientResponse.ALREADY_ONLINE
        // Website bans, VPN/proxy block and IP logging (no-op unless website.enabled).
        WebsiteService.checkLogin(info.displayname, hostAddress)?.let { return it }
        return ClientResponse.LOGIN_OK
    }

    /** Called by LoginManager (login pool thread) once the account was loaded or the load failed. */
    fun loaded(player: Player?, timeout: Boolean) {
        if (timeout) {
            fail(ClientResponse.LOGIN_EXCEEDED)
            return
        }
        if (player == null) {
            fail(ClientResponse.ERROR_LOADING_PROFILE)
            return
        }
        val response = try {
            getResponseCode(player)
        } catch (e: Throwable) {
            logger.error("Failed to validate login of '{}'", info.username, e)
            ClientResponse.UNEXPECTED_LOGINSERVER_RESPONSE
        }
        if (response != ClientResponse.LOGIN_OK) {
            fail(response)
            return
        }
        postLogin(player)
    }

    private fun postLogin(player: Player) {
        player.createLogger()
        WorldTasksManager.schedule {
            try {
                register(player)
            } catch (e: Throwable) {
                logger.error("Failed to register '{}'", info.username, e)
                fail(ClientResponse.UNEXPECTED_LOGINSERVER_RESPONSE)
            }
        }
    }

    /** Runs on the world thread. */
    private fun register(player: Player) {
        if (World.containsPlayer(info.username)) {
            fail(ClientResponse.ALREADY_ONLINE)
            return
        }
        if (!responseHandler.ctx.channel().isActive) {
            logger.info("'{}' disconnected before the login completed.", info.username)
            return
        }
        if (!World.addPlayer(player)) {
            fail(ClientResponse.WORLD_FULL)
            return
        }

        val authenticator = player.authenticator
        val authenticatorResponse =
            if (authenticator.isAuthenticated) AuthenticatorResponse.AuthenticatorCode(authenticator.randomUID)
            else AuthenticatorResponse.NoAuthenticator
        val privilege = player.privilege
        val ok = LoginResponse.Ok(
            authenticatorResponse,
            privilege.loginCode,
            privilege.isPMod,
            player.index,
            true, // members (rev 211 sent 1 = 400 friends/ignores)
            accountHash(info.username),
            info.userIdentifier.toLong(),
            accountHash(info.username),
        )
        val rsprotSession = try {
            responseHandler.writeSuccessfulResponse(ok, block)
        } catch (e: Throwable) {
            logger.error("Failed to write the login response for '{}'", info.username, e)
            World.removePlayer(player)
            return
        }
        val session = RsprotSession(player, rsprotSession)
        session.seed = block.seed.copyOf()
        player.session = session
        rsprotSession.setDisconnectionHook { session.onDisconnected() }
        try {
            enterGame(player, session)
        } catch (e: Throwable) {
            // The client already got LoginResponse.Ok, so no login error code can be sent any more.
            logger.error("Failed to bring '{}' into the game; closing the connection.", info.username, e)
            session.close()
            World.removePlayer(player)
        }
    }

    private fun enterGame(player: Player, session: RsprotSession) {
        player.areaManager.onLogin(player)
        player.loadMapRegions(true)

        // INFO track: allocates the player/npc/world-entity info. REBUILD_LOGIN (which carries the GPI
        // init block built from that player info) must be the very first game packet.
        RsprotInfo.login(player)
        session.infoLoggedIn = true
        val rebuildLogin = RsprotInfo.rebuildLogin(player)
        if (rebuildLogin != null) {
            session.queue(rebuildLogin)
        } else {
            logger.warn("No REBUILD_LOGIN for '{}' (RsprotInfo is still the NET-branch stub); the client will not load the map.", info.username)
        }

        player.afterLoadMapRegions()
        player.isInitialized = true
        player.onLogin()
        session.flush()
        logger.info("'{}' logged in from {} (index {})", info.username, session.getHostAddress(), player.index)
    }

    private fun fail(response: ClientResponse) {
        logger.info("Login of '{}' answered {}", info.username, response)
        responseHandler.writeFailedResponse(response.toLoginResponse())
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RsprotLoginRequest::class.java)

        /** A stable, non-zero per-account value (RuneLite keys its per-account config on it). */
        fun accountHash(username: String): Long {
            val name = TextUtils.formatNameForProtocol(username)
            var hash = 1125899906842597L
            for (c in name) hash = 31 * hash + c.code
            return (hash and Long.MAX_VALUE).coerceAtLeast(1)
        }
    }
}

fun OtpAuthenticationType.toAuthenticatorInfo(): AuthenticatorInfo = when (this) {
    is OtpAuthenticationType.TrustedComputer -> AuthenticatorInfo(AuthType.TRUSTED_COMPUTER, -1, identifier)
    is OtpAuthenticationType.TrustedAuthenticator -> AuthenticatorInfo(AuthType.TRUSTED_AUTHENTICATION, otp, -1)
    is OtpAuthenticationType.UntrustedAuthentication -> AuthenticatorInfo(AuthType.UNTRUSTED_AUTHENTICATION, otp, -1)
    else -> AuthenticatorInfo(AuthType.NORMAL, -1, -1)
}

fun net.rsprot.protocol.loginprot.incoming.util.HostPlatformStats.toHardwareInfo(): HardwareInfo = HardwareInfo(
    cpuFeatures = cpuFeatures,
    osId = osType,
    osVersion = osVersion,
    javaVendorId = javaVendor,
    javaVersionMajor = javaVersionMajor,
    javaVersionMinor = javaVersionMinor,
    javaVersionUpdate = javaVersionPatch,
    heap = javaMaxMemoryMb,
    logicalProcessors = javaAvailableProcessors,
    physicalMemory = systemMemory,
    clockSpeed = systemSpeed,
    graphicCardReleaseMonth = gpuDriverMonth,
    graphicCardReleaseYear = gpuDriverYear,
    cpuCount = cpuCount1,
    cpuBrandType = cpuCount2,
    cpuModel = cpuSignature,
    graphicCardManufacture = gpuDxName,
    graphicCardName = gpuGlName,
    dxVersion = gpuDxVersion,
    cpuManufacture = cpuManufacturer,
    cpuName = cpuBrand,
    isArch64Bit = os64Bit,
    isApplet = applet,
)

/** rev-211 login response code -> rsprot login response. */
fun ClientResponse.toLoginResponse(): LoginResponse = when (this) {
    ClientResponse.INVALID_USERNAME_OR_PASSWORD -> LoginResponse.InvalidUsernameOrPassword
    ClientResponse.BANNED -> LoginResponse.Banned
    ClientResponse.ALREADY_ONLINE -> LoginResponse.Duplicate
    ClientResponse.SERVER_UPDATED, ClientResponse.CLIENT_UPDATED, ClientResponse.MOBILE_CLIENT_UPDATED -> LoginResponse.ClientOutOfDate
    ClientResponse.WORLD_FULL -> LoginResponse.ServerFull
    ClientResponse.LOGIN_SERVER_OFFLINE -> LoginResponse.LoginServerOffline
    ClientResponse.TOO_MANY_CONNECTIONS -> LoginResponse.IPLimit
    ClientResponse.BAD_SESSION_ID -> LoginResponse.BadSessionId
    ClientResponse.CREDENTIALS_UNSECURE -> LoginResponse.ForcePasswordChange
    ClientResponse.MEMBERS_WORLD -> LoginResponse.NeedMembersAccount
    ClientResponse.LOGIN_INCOMPLETE -> LoginResponse.InvalidSave
    ClientResponse.UPDATE_IN_PROGRESS -> LoginResponse.UpdateInProgress
    ClientResponse.LOGIN_EXCEEDED -> LoginResponse.TooManyAttempts
    ClientResponse.IN_MEMBERS_AREA -> LoginResponse.InMembersArea
    ClientResponse.ACCOUNT_LOCKED -> LoginResponse.Locked
    ClientResponse.CLOSED_BETA -> LoginResponse.ClosedBetaInvitedOnly
    ClientResponse.INVALID_LOGIN_SERVER_REQUEST -> LoginResponse.InvalidLoginServer
    ClientResponse.JUST_LEFT_WORLD -> LoginResponse.HopBlocked
    ClientResponse.MALFORMED_LOGIN_PACKET -> LoginResponse.InvalidLoginPacket
    ClientResponse.NO_REPLY_FROM_LOGINSERVER -> LoginResponse.LoginServerNoReply
    ClientResponse.ERROR_LOADING_PROFILE -> LoginResponse.LoginServerLoadError
    ClientResponse.UNEXPECTED_LOGINSERVER_RESPONSE -> LoginResponse.UnknownReplyFromLoginServer
    ClientResponse.COMPUTER_ADDRESS_BLOCKED -> LoginResponse.IPBlocked
    ClientResponse.SERVICE_UNAVAILABLE -> LoginResponse.ServiceUnavailable
    ClientResponse.MUST_SET_DISPLAYNAME -> LoginResponse.DisplayNameRequired
    ClientResponse.ACCOUNT_BILLING_ISSUE -> LoginResponse.NegativeCredit
    ClientResponse.PROMPT_AUTHENTICATOR -> LoginResponse.Authenticator
    ClientResponse.WRONG_AUTHENICATOR_CODE -> LoginResponse.InvalidAuthenticatorCode
    ClientResponse.LOGIN_ATTEMPT_TIMED_OUT -> LoginResponse.Timeout
    ClientResponse.YOU_WERE_SIGNED_OUT -> LoginResponse.Kick
    ClientResponse.FAILED_TO_LOGIN_TRY_AGAIN -> LoginResponse.LoginFail1
    ClientResponse.FAILED_TO_LOGIN_TRY_AGAIN_2 -> LoginResponse.LoginFail2
    ClientResponse.PROMPT_DATE_OF_BIRTH -> LoginResponse.UpdateDob
    ClientResponse.DATE_OF_BIRTH_NEEDS_REVIEW -> LoginResponse.DobReview
    else -> LoginResponse.UnknownReplyFromLoginServer
}

/**
 * Moves a still-logged-in player onto the connection of a reconnecting client (world thread).
 *
 * The client keeps its scene, interfaces and variables over a reconnect, but anything sent while the old
 * connection was dead is lost, so the state is sent again: the player/NPC info restarts from the GPI block
 * in the reconnect response, every varp is re-sent with its last value, the map is rebuilt and every zone
 * re-synchronised (ground items, spawned/removed objects), and inventory, equipment, skills and run energy are
 * refreshed. Modal screens (bank, trade, dialogues) are closed, since their contents may have changed unseen.
 */
object RsprotReconnect {

    private val logger = LoggerFactory.getLogger(RsprotReconnect::class.java)

    /** -Dgadscape.net.reconnect=false answers every reconnect with ConnectFail (the rev-211 behaviour). */
    @JvmStatic
    val enabled: Boolean = System.getProperty("gadscape.net.reconnect")?.toBoolean() ?: true

    /** Size of the GPI block a reconnect response carries (rsprot LoginResponse.ReconnectOk.PLAYER_INFO_BLOCK_SIZE). */
    private const val PLAYER_INFO_BLOCK_SIZE = 4608

    fun complete(
        responseHandler: GameLoginResponseHandler<Player>,
        block: LoginBlock<*>,
        previousSeed: IntArray,
    ) {
        val name = TextUtils.formatNameForProtocol(block.username)
        var answered = false
        try {
            if (!responseHandler.ctx.channel().isActive) return
            val player: Player? = World.getPlayerByUsername(name)
            val old: RsprotSession? = if (player != null) RsprotSession.of(player) else null
            if (player == null || old == null) {
                refuse(responseHandler, name, "not in the world")
                return
            }
            if (player.isFinished || old.replaced) {
                refuse(responseHandler, name, "already leaving")
                return
            }
            val logoutType = player.logoutType
            if (logoutType != LogoutType.NONE && logoutType != LogoutType.CONNECTION_LOST) {
                refuse(responseHandler, name, "logging out ($logoutType)")
                return
            }
            if (old.seed?.contentEquals(previousSeed) != true) {
                refuse(responseHandler, name, "seed mismatch")
                return
            }
            RsprotInfo.onReconnect(player)
            val gpi = Unpooled.buffer(PLAYER_INFO_BLOCK_SIZE)
            RsprotInfo.writeAbsolutePlayerPositions(player, gpi)
            val rsprotSession = responseHandler.writeSuccessfulResponse(LoginResponse.ReconnectOk(gpi), block, old.rsprot)
            answered = true
            old.retire()
            val session = RsprotSession(player, rsprotSession, old.sentVarps)
            session.seed = block.seed.copyOf()
            session.infoLoggedIn = true
            player.session = session
            rsprotSession.setDisconnectionHook { session.onDisconnected() }
            if (player.logoutType == LogoutType.CONNECTION_LOST) player.logoutType = LogoutType.NONE
            resync(player, session)
            session.flush()
            logger.info("'{}' reconnected from {} (index {})", name, session.getHostAddress(), player.index)
        } catch (e: Throwable) {
            logger.error("Reconnect of '{}' failed", name, e)
            // Before the answer the client can still be told; after it, the resync failed part-way and the
            // session timeout / next login takes over.
            if (!answered) runCatching { responseHandler.writeFailedResponse(LoginResponse.ConnectFail) }
        }
    }

    private fun refuse(responseHandler: GameLoginResponseHandler<Player>, name: String, reason: String) {
        logger.info("Reconnect of '{}' refused: {}", name, reason)
        responseHandler.writeFailedResponse(LoginResponse.ConnectFail)
    }

    private fun resync(player: Player, session: RsprotSession) {
        val dispatcher = player.packetDispatcher
        for (entry in session.sentVarpsSnapshot().int2IntEntrySet()) {
            dispatcher.sendConfig(entry.intKey, entry.intValue)
        }
        runCatching { player.interfaceHandler.closeInterfaces() }
            .onFailure { logger.error("Failed to close the interfaces of {} after a reconnect", player, it) }
        player.chunksInScope.clear()
        player.loadMapRegions(false)
        player.updateScopeInScene()
        player.inventory.refreshAll()
        player.equipment.refreshAll()
        player.skills.refresh()
        dispatcher.sendRunEnergy()
        player.sendPlayerOptions()
    }
}
