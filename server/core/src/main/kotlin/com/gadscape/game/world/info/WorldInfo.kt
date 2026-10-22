package com.gadscape.game.world.info

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File

/*
 * RECONSTRUCTED — not original source.
 *
 * The package com.gadscape.game.world.info was absent from the published
 * repository. These classes were rebuilt from (a) the call sites across the
 * server and (b) the exact key structure of worlds.yml.
 *
 * Semantics inferred, not verified:
 *   isDevelopment() == visibility DEVELOPER
 *   isBeta()        == visibility BETA
 *   isPublic()      == visibility PUBLIC
 * If the original meant something else, behaviour differs subtly. For a
 * localhost dev server this mapping is what you want regardless.
 */

@Serializable
enum class WorldVisibility {
    DEVELOPER,
    BETA,
    PUBLIC,
    PRIVATE
}

@Serializable
data class DatabaseProfile(
    val enabled: Boolean = false,
    val databaseUrl: String = "",
    val databasePort: Int = 5432,
    val databaseName: String = "",
    val databaseUser: String = "",
    val databasePassword: String = ""
)

@Serializable
data class ApiProfile(
    @SerialName("enabled") val isEnabled: Boolean = false,
    val scheme: String = "http",
    val host: String = "localhost",
    val port: Int = 8080,
    val token: String = "",

    // Port the game's OWN inbound callback server (APICallbackServer) listens on -
    // separate from `port` above, which is the outbound target (gadscape-api) the
    // game calls OUT to. Always bound to 127.0.0.1 only; never exposed externally.
    val callbackPort: Int = 6969
)

/**
 * Link to the website (gadscape.com): voting, donations, ::claim, donator
 * ranks, bans and VPN checks. Independent of [ApiProfile] - see
 * com.gadscape.website.WebsiteService.
 */
@Serializable
data class WebsiteProfile(
    val enabled: Boolean = false,
    /** e.g. https://gadscape.com - no trailing slash. */
    val siteUrl: String = "",
    /** Same value as GAME_API_KEY on the website. */
    val apiKey: String = "",
    /** Port for the website's instant push (GAME_SERVER_WEBHOOK_URL = http://<host>:<port>/gadscape/events). 0 = poll only. */
    val webhookPort: Int = 0,
    val pollSeconds: Int = 15,
    /** If the website can't be reached at login: true lets players in (cached bans still apply), false refuses. */
    val allowLoginWhenSiteDown: Boolean = true
)

@Serializable
data class WorldProfile(

    val number: Int = 1,
    val host: String = "localhost",
    val port: Int = 43594,

    val activity: String = "",
    val logback: String = "logback",

    @SerialName("private") val `private`: Boolean = false,
    val verifyPasswords: Boolean = true,
    val useWhitelist: Boolean = false,
    val whitelistedUsernames: List<String> = emptyList(),

    /**
     * Usernames of the GadScape owner account(s): only they can use the owner-only commands (::checkowner,
     * ::isolemnlysweariamuptonogood, ::magicbuffet, ...). Empty = nobody. See GameConstants.owners().
     */
    val owners: List<String> = emptyList(),

    val visibility: WorldVisibility = WorldVisibility.DEVELOPER,
    val location: String = "UNITED_STATES_OF_AMERICA",
    val types: List<String> = emptyList(),

    val api: ApiProfile? = null,
    val logsDatabase: DatabaseProfile? = null,
    val mainDatabase: DatabaseProfile? = null,

    val discordToken: String? = null,

    /**
     * Where Discord embeds load their pictures from (no trailing slash): item icons are <base>/items/<id>.png and the
     * other pictures <base>/<name>.png. Default: <website>/images/discord. See com.gadscape.tools.discord.DiscordUtil.
     */
    val discordImageBase: String? = null,

    val website: WebsiteProfile? = null,

    // Login RSA keypair (server holds the private exponent + modulus; the matching
    // public exponent + modulus is compiled into the client). Generate with a fresh
    // KeyPairGenerator("RSA", 1024) pair - never reuse a keypair that has ever been
    // committed to source control.
    val rsaPrivateExponent: String? = null,
    val rsaModulus: String? = null
) {

    fun isDevelopment(): Boolean = visibility == WorldVisibility.DEVELOPER

    fun isBeta(): Boolean = visibility == WorldVisibility.BETA

    fun isPublic(): Boolean = visibility == WorldVisibility.PUBLIC

    fun isPrivate(): Boolean = `private` || visibility == WorldVisibility.PRIVATE

    fun verify2FA(): Boolean = verifyPasswords


    fun isApiEnabled(): Boolean = api?.isEnabled == true

    fun isMainDatabaseEnabled(): Boolean = mainDatabase?.enabled == true

    fun isLogsDatabaseEnabled(): Boolean = logsDatabase?.enabled == true

    fun isDiscordEnabled(): Boolean = !discordToken.isNullOrBlank()

    fun isWebsiteEnabled(): Boolean = website?.enabled == true && !website.siteUrl.isBlank() && !website.apiKey.isBlank()
}

@Serializable
data class WorldConfig(
    val worlds: Map<String, WorldProfile> = emptyMap()
) {

    operator fun get(key: String): WorldProfile? = worlds[key]

    fun keys(): Set<String> = worlds.keys

    companion object {

        private val yaml = Yaml(
            configuration = YamlConfiguration(
                strictMode = false
            )
        )

        @JvmStatic
        fun fromYAML(file: File): WorldConfig {
            require(file.exists()) {
                "worlds.yml not found at ${file.absolutePath} " +
                    "(the server must be launched with its module dir as the working directory)"
            }
            return yaml.decodeFromString<WorldConfig>(file.readText())
        }
    }
}
