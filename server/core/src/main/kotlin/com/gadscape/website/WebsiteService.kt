package com.gadscape.website

import com.gadscape.api.model.Bond
import com.gadscape.api.service.store.notify
import com.gadscape.api.service.vote.totalVoteCredits
import com.gadscape.api.service.user.updateVoteStatisticOnPlayerDetailsTab
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import com.zenyte.game.GameConstants
import com.zenyte.game.item.Item
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.npc.drop.matrix.NPCDrops
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.entity.player.container.RequestResult
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.privilege.GameMode
import com.zenyte.game.world.entity.player.privilege.MemberRank
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import com.zenyte.net.ClientResponse
import mgi.types.config.items.ItemDefinitions
import mgi.types.config.npcs.NPCDefinitions
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Connects the game to the website (see `website:` in worlds.yml and
 * docs/game-integration.md in the website repo).
 *
 * - `::vote` / `::donate` open the website signed in as the character.
 * - `::claim` hands out everything the website has queued (votes, donations,
 *   promotions, custom orders, staff grants).
 * - Using a donator pin reports it to the website, which keeps the running
 *   total and decides the donator rank.
 * - Logins are checked against the website's bans and VPN/proxy block.
 * - The website pushes events (vote received, donation paid, ban, rank change)
 *   to [WebsiteProfile.webhookPort]; they're also polled, so nothing is missed.
 * - Player count and hiscores are pushed to the website; `::websitesync`
 *   pushes item definitions and drop tables.
 *
 * Network calls run on [pool]; anything touching a player runs on the game
 * thread through [WorldTasksManager].
 */
object WebsiteService {

    private val logger = LoggerFactory.getLogger(WebsiteService::class.java)

    private val pool = Executors.newScheduledThreadPool(4) { r ->
        Thread(r, "website").apply { isDaemon = true }
    }
    private val bannedPlayers = ConcurrentHashMap.newKeySet<String>()
    private val bannedIps = ConcurrentHashMap.newKeySet<String>()
    private val claiming = ConcurrentHashMap.newKeySet<String>()
    /** Login-check results waiting for the player to finish logging in. */
    private val loginInfo = ConcurrentHashMap<String, JsonObject>()
    private val polling = AtomicBoolean()
    private val started = AtomicBoolean()
    private val seqFile: Path = Path.of("data", "website-events.seq")
    @Volatile private var lastSeq = 0
    private var webhook: HttpServer? = null

    private lateinit var client: WebsiteClient

    private const val MYSTERY_BOX = 6199

    /** Website tier -> in-game member rank (crown, drop rate, yell). */
    private val TIER_RANKS = mapOf(
        "REGULAR" to MemberRank.PREMIUM,
        "SUPER" to MemberRank.EXPANSION,
        "ELITE" to MemberRank.EXTREME,
        "EXTREME" to MemberRank.RESPECTED,
        "RESPECTED" to MemberRank.LEGENDARY,
        "PLATINUM" to MemberRank.UBER,
    )

    @JvmStatic
    val isEnabled: Boolean get() = GameConstants.WORLD_PROFILE.isWebsiteEnabled()

    /** Same normalisation as the website: lowercase, spaces/underscores/hyphens folded. */
    private fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), "_")

    // ---- lifecycle --------------------------------------------------------------------------

    @JvmStatic
    fun start() {
        val profile = GameConstants.WORLD_PROFILE.website
        if (!isEnabled || profile == null) {
            logger.info("Website link disabled (set website.enabled, siteUrl and apiKey in worlds.yml).")
            return
        }
        if (!started.compareAndSet(false, true)) return
        client = WebsiteClient(profile)
        lastSeq = runCatching { Files.readString(seqFile).trim().toInt() }.getOrDefault(0)

        pool.execute(::refreshBans)
        pool.scheduleWithFixedDelay(::refreshBans, 5, 5, TimeUnit.MINUTES)
        pool.scheduleWithFixedDelay(::pollEvents, 5, profile.pollSeconds.toLong().coerceAtLeast(5), TimeUnit.SECONDS)
        pool.scheduleWithFixedDelay(::pushPlayerCount, 10, 60, TimeUnit.SECONDS)
        // Keep the website hiscores fresh for players who stay logged in for hours.
        pool.scheduleWithFixedDelay({ WorldTasksManager.schedule { pushHiscores(World.getPlayers().filterNotNull()) } }, 5, 15, TimeUnit.MINUTES)
        if (profile.webhookPort > 0) startWebhook(profile.webhookPort, profile.apiKey)
        logger.info("Website link started -> {}", profile.siteUrl)
    }

    // ---- login ----------------------------------------------------------------------------------

    /**
     * Called from the login handshake (off the game thread). Returns null to allow
     * the login, or the response to refuse it with.
     */
    @JvmStatic
    fun checkLogin(name: String, ip: String): ClientResponse? {
        if (!isEnabled) return null
        if (bannedPlayers.contains(key(name)) || bannedIps.contains(ip)) return ClientResponse.BANNED
        return try {
            val res = client.post("/api/game/login", json("player" to name, "ip" to ip), timeoutSeconds = 6)
            if (res.get("allow")?.asBoolean == true) {
                loginInfo[key(name)] = res
                null
            } else {
                val reason = res.str("reason").orEmpty()
                logger.info("Website refused login for {} from {}: {}", name, ip, reason)
                when {
                    reason.contains("VPN", ignoreCase = true) || reason.contains("connection", ignoreCase = true) ->
                        ClientResponse.COMPUTER_ADDRESS_BLOCKED
                    else -> ClientResponse.BANNED
                }
            }
        } catch (e: IOException) {
            logger.warn("Website login check failed for {}: {}", name, e.message)
            if (GameConstants.WORLD_PROFILE.website?.allowLoginWhenSiteDown == true) null else ClientResponse.LOGIN_SERVER_OFFLINE
        }
    }

    /** Called once the player is fully logged in (game thread). */
    @JvmStatic
    fun onLogin(player: Player) {
        if (!isEnabled) return
        val info = loginInfo.remove(key(player.name)) ?: return
        info.obj("tier")?.str("key")?.let { applyTier(player, it, announce = false) }
        info.int("claimedCents")?.let { player.claimedPinCents = it }
        val pending = info.int("pendingRewards") ?: 0
        if (pending > 0)
            player.sendMessage(Colour.RS_GREEN.wrap("You have $pending reward${if (pending == 1) "" else "s"} waiting. Type ::claim to collect them."))
    }

    @JvmStatic
    fun onLogout(player: Player) {
        if (!isEnabled) return
        pushHiscores(listOf(player))
    }

    // ---- commands -------------------------------------------------------------------------------

    @JvmStatic
    fun commandVote(player: Player) = openLink(player, "/api/game/vote-link", "Opening the vote page...")

    @JvmStatic
    fun commandDonate(player: Player) = openLink(player, "/api/game/donate-link", "Opening the donation store...")

    private fun openLink(player: Player, path: String, message: String) {
        val name = player.name
        pool.execute {
            val url = try {
                client.post(path, json("player" to name)).str("url")
            } catch (e: IOException) {
                logger.warn("Website link failed: {}", e.message)
                null
            }
            WorldTasksManager.schedule {
                if (url == null) player.sendMessage("Couldn't reach the website. Try again in a moment.")
                else {
                    player.sendMessage(message)
                    player.packetDispatcher.sendURL(url)
                }
            }
        }
    }

    /** ::claim */
    @JvmStatic
    fun commandClaim(player: Player) {
        val name = player.name
        if (!claiming.add(key(name))) return
        player.sendMessage("Checking for rewards...")
        pool.execute {
            try {
                val res = client.post("/api/game/claims", json("player" to name))
                if (res.has("error")) {
                    claiming.remove(key(name))
                    WorldTasksManager.schedule { player.sendMessage("You can't claim rewards: ${res.str("reason") ?: "account blocked"}.") }
                    return@execute
                }
                val batchId = res.str("batchId")
                val rewards = res.getAsJsonArray("rewards")?.map { it.asJsonObject }.orEmpty()
                if (batchId == null || rewards.isEmpty()) {
                    claiming.remove(key(name))
                    WorldTasksManager.schedule { player.sendMessage("You have nothing to claim.") }
                    return@execute
                }
                WorldTasksManager.schedule { deliver(player, batchId, rewards) }
            } catch (e: IOException) {
                claiming.remove(key(name))
                logger.warn("Claim failed for {}: {}", name, e.message)
                WorldTasksManager.schedule { player.sendMessage("Couldn't reach the website. Your rewards are safe - try again shortly.") }
            }
        }
    }

    private fun deliver(player: Player, batchId: String, rewards: List<JsonObject>) {
        val delivered = JsonArray()
        val lines = mutableListOf<String>()
        var left = 0
        if (player.isLoggedIn) {
            for (reward in rewards) {
                val ok = give(player, reward)
                val label = reward.str("label") ?: "Reward"
                val amount = reward.int("amount") ?: 1
                if (ok) {
                    delivered.add(reward.str("id"))
                    lines += label + if (amount > 1 && reward.str("code") != "VOTE_POINTS" && reward.str("code") != "DONATOR_POINTS") " x$amount" else ""
                } else left++
            }
            for (line in lines) player.sendMessage("Claimed: ${Colour.RS_GREEN.wrap(line)}")
            if (left > 0) player.sendMessage(Colour.RED.wrap("$left reward(s) couldn't be given yet (no space, or still being made). Type ::claim again later."))
            if (lines.isNotEmpty()) player.dialogue {
                plain(buildString {
                    append(Colour.RS_GREEN.wrap("Rewards claimed!"))
                    lines.take(5).forEach { append("<br>").append(it) }
                    if (lines.size > 5) append("<br>...and ${lines.size - 5} more (see chat).")
                })
            }
        }
        val body = json("batchId" to batchId, "delivered" to delivered)
        val name = player.name
        pool.execute {
            for (attempt in 0 until 5) {
                try {
                    client.post("/api/game/claims/complete", body)
                    break
                } catch (e: IOException) {
                    logger.warn("claims/complete failed (attempt {}) for batch {}: {}", attempt + 1, batchId, e.message)
                    Thread.sleep(2000L shl attempt)
                }
            }
            claiming.remove(key(name))
        }
    }

    /** Gives one reward. False = keep it queued for the next ::claim. */
    private fun give(player: Player, reward: JsonObject): Boolean {
        val code = reward.str("code") ?: return false
        val amount = (reward.int("amount") ?: 1).coerceAtLeast(1)
        val itemId = reward.int("itemId")
        return when (code) {
            "VOTE_POINTS" -> {
                player.totalVoteCredits += amount
                player.updateVoteStatisticOnPlayerDetailsTab()
                true
            }
            "DONATOR_POINTS" -> {
                player.donatorPoints += amount
                true
            }
            "PIN" -> {
                val id = itemId ?: Bond.entries.find { it.amount * 100 == reward.int("valueCents") }?.id
                id != null && giveItem(player, Item(id, amount))
            }
            "MYSTERY_BOX" -> giveItem(player, Item(itemId ?: MYSTERY_BOX, amount))
            // ITEM, CUSTOM_ITEM, BOSS_SPAWNER, CUSTOM_MAP: only once staff attached an item.
            else -> itemId != null && giveItem(player, Item(itemId, amount))
        }
    }

    /** Inventory first, then the bank (ultimate ironmen: inventory only). */
    private fun giveItem(player: Player, item: Item): Boolean {
        if (player.inventory.hasSpaceFor(item))
            return player.inventory.addItem(item).result == RequestResult.SUCCESS
        if (player.gameMode == GameMode.ULTIMATE_IRON_MAN) return false
        return player.bank.add(item).result == RequestResult.SUCCESS
    }

    /** ::link CODE */
    @JvmStatic
    fun commandLink(player: Player, code: String) {
        val name = player.name
        pool.execute {
            val message = try {
                client.post("/api/game/link", json("player" to name, "code" to code)).str("message") ?: "Done."
            } catch (e: IOException) {
                "Couldn't reach the website. Try again shortly."
            }
            WorldTasksManager.schedule { player.sendMessage(message) }
        }
    }

    /** ::dpoints */
    @JvmStatic
    fun commandPoints(player: Player) {
        player.sendMessage("Donator points: ${Colour.RS_GREEN.wrap(player.donatorPoints.toString())} - " +
                "pins claimed: ${Colour.RS_GREEN.wrap("$" + "%.2f".format(player.claimedPinCents / 100.0))} - " +
                "vote points: ${Colour.RS_GREEN.wrap(player.totalVoteCredits.toString())}")
    }

    // ---- donator pins ---------------------------------------------------------------------------

    /**
     * Redeems a donator pin: removes it, reports it to the website (which keeps
     * the total and decides the rank), and gives it back if that fails.
     */
    @JvmStatic
    fun redeemPin(player: Player, bond: Bond) {
        val pin = Item(bond.id, 1)
        if (player.inventory.deleteItem(pin).result != RequestResult.SUCCESS) return
        player.lock()
        player.dialogue { loading("Claiming your donator pin...") }
        val redeemId = UUID.randomUUID().toString()
        val body = json("player" to player.name, "valueCents" to bond.amount * 100, "redeemId" to redeemId)
        pool.execute {
            var res: JsonObject? = null
            // Same redeemId on every retry, so the website never counts it twice.
            for (attempt in 0 until 4) {
                try {
                    res = client.post("/api/game/pins/redeem", body)
                    break
                } catch (e: IOException) {
                    logger.warn("Pin redeem failed (attempt {}) {}: {}", attempt + 1, redeemId, e.message)
                    Thread.sleep(1500L shl attempt)
                }
            }
            val result = res
            WorldTasksManager.schedule {
                player.unlock()
                if (result == null) {
                    player.inventory.addOrDrop(pin)
                    player.dialogue { item(pin, "Couldn't reach the website, so your pin was returned. Try again in a moment.") }
                    return@schedule
                }
                val total = result.int("claimedCents") ?: 0
                player.claimedPinCents = total
                val tier = result.obj("tier")
                tier?.str("key")?.let { applyTier(player, it, announce = true) }
                player.dialogue {
                    item(pin, "${Colour.DARK_BLUE.wrap("$${bond.amount}")} has been added to your donator total, " +
                            "making ${Colour.DARK_BLUE.wrap("$" + "%.2f".format(total / 100.0))}." +
                            (tier?.str("name")?.let { "<br>Rank: ${Colour.RS_GREEN.wrap(it)}" } ?: ""))
                }
            }
        }
    }

    private fun applyTier(player: Player, tierKey: String?, announce: Boolean) {
        val target = TIER_RANKS[tierKey] ?: return
        val current = player.memberRank
        if (current == MemberRank.AMASCUT || current.id >= target.id) return
        player.memberRank = target
        if (announce) player.sendMessage(Colour.RS_GREEN.wrap("Your donator rank is now $target!"))
    }

    // ---- bans & events --------------------------------------------------------------------------

    private fun refreshBans() {
        try {
            val res = client.get("/api/game/bans")
            val players = res.getAsJsonArray("players").mapNotNull { it.asJsonObject.str("player") }.toSet()
            val ips = res.getAsJsonArray("ips").mapNotNull { it.asJsonObject.str("ip") }.toSet()
            bannedPlayers.retainAll(players); bannedPlayers.addAll(players)
            bannedIps.retainAll(ips); bannedIps.addAll(ips)
        } catch (e: Exception) {
            logger.warn("Ban list refresh failed: {}", e.message)
        }
    }

    private fun pollEvents() {
        if (!polling.compareAndSet(false, true)) return
        try {
            while (true) {
                val res = client.get("/api/game/events?after=$lastSeq&limit=200")
                val events = res.getAsJsonArray("events") ?: break
                if (events.size() == 0) break
                for (el in events) {
                    val ev = el.asJsonObject
                    runCatching { handleEvent(ev) }.onFailure { logger.error("Website event failed: $ev", it) }
                    lastSeq = ev.int("seq") ?: lastSeq
                }
                runCatching { Files.createDirectories(seqFile.parent); Files.writeString(seqFile, lastSeq.toString()) }
                client.post("/api/game/events", json("upTo" to lastSeq))
                if (events.size() < 200) break
            }
        } catch (e: Exception) {
            logger.debug("Website event poll failed: {}", e.message)
        } finally {
            polling.set(false)
        }
    }

    private fun handleEvent(ev: JsonObject) {
        val type = ev.str("type")
        val playerKey = ev.str("player")
        val data = ev.obj("data") ?: JsonObject()
        val online = playerKey?.let { k -> World.getPlayers().firstOrNull { it != null && key(it.name) == k } }
        when (type) {
            "PLAYER_BANNED" -> {
                playerKey?.let(bannedPlayers::add)
                data.getAsJsonArray("ips")?.forEach { bannedIps.add(it.asString) }
                if (online != null) WorldTasksManager.schedule { online.logout(true) }
            }
            "PLAYER_UNBANNED" -> {
                playerKey?.let(bannedPlayers::remove)
                pool.execute(::refreshBans)
            }
            "TIER_CHANGED" -> if (online != null) WorldTasksManager.schedule {
                data.str("tier")?.let { applyTier(online, it, announce = true) }
                data.int("claimedCents")?.let { online.claimedPinCents = it }
            }
            "VOTE_RECEIVED" -> online?.let { p ->
                WorldTasksManager.schedule { p.notify("Your vote on ${data.str("site") ?: "the toplist"} was received!<br>Type ::claim to collect your reward.") }
            }
            "DONATION_PAID" -> online?.let { p ->
                WorldTasksManager.schedule { p.notify("Thank you for your donation!<br>Type ::claim to collect your rewards.") }
            }
            "REWARDS_PENDING" -> online?.let { p ->
                WorldTasksManager.schedule { p.sendMessage(Colour.RS_GREEN.wrap("New rewards are waiting for you. Type ::claim to collect them.")) }
            }
            "ACCOUNT_LINKED" -> online?.let { p ->
                WorldTasksManager.schedule { p.sendMessage("Linked to forum account ${data.str("forumAccount")}.") }
            }
        }
    }

    // The push only triggers a poll, so every event is handled once, in order, by pollEvents.
    private fun startWebhook(port: Int, apiKey: String) {
        try {
            val server = HttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/gadscape/events") { exchange ->
                val body = exchange.requestBody.use { it.readAllBytes() }
                val sig = exchange.requestHeaders.getFirst("X-GadScape-Signature")
                val ok = sig != null && MessageDigest.isEqual(sig.toByteArray(), sign(apiKey, body).toByteArray())
                exchange.sendResponseHeaders(if (ok) 200 else 401, -1)
                exchange.close()
                if (ok) pool.execute(::pollEvents)
            }
            server.executor = pool
            server.start()
            webhook = server
            logger.info("Website webhook listening on :{}/gadscape/events", port)
        } catch (e: IOException) {
            logger.error("Couldn't start the website webhook listener on port $port", e)
        }
    }

    private fun sign(key: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    // ---- data pushes ----------------------------------------------------------------------------

    private fun pushPlayerCount() {
        try {
            val online = World.getPlayers().count { it != null && !it.isHidden }
            client.post("/api/game/status", json("playersOnline" to online))
        } catch (e: Exception) {
            logger.debug("Player count push failed: {}", e.message)
        }
    }

    /** Snapshot skills on the game thread, send off it. */
    private fun pushHiscores(players: List<Player>) {
        val rows = JsonArray()
        for (p in players) {
            if (p.hasPrivilege(PlayerPrivilege.ADMINISTRATOR) || p.skills.totalLevel < 50) continue
            val skills = JsonObject()
            for (i in 0 until SkillConstants.COUNT) {
                skills.add(SkillConstants.SKILLS[i].uppercase(Locale.ROOT), json(
                    "level" to p.skills.getLevelForXp(i),
                    "xp" to p.skills.getExperience(i).toLong(),
                ))
            }
            rows.add(json("name" to p.name, "skills" to skills))
        }
        if (rows.size() == 0) return
        pool.execute {
            rows.chunked(200).forEach { chunk ->
                try {
                    client.post("/api/game/hiscores", json("players" to chunk))
                } catch (e: Exception) {
                    logger.warn("Hiscores push failed: {}", e.message)
                }
            }
        }
    }

    /** ::websitesync - pushes item definitions and drop tables to the website. */
    @JvmStatic
    fun syncGameData(player: Player) {
        player.sendMessage("Sending items and drop tables to the website, this takes a few minutes...")
        pool.execute {
            var items = 0
            var npcs = 0
            try {
                val all = ItemDefinitions.getDefinitions().filterNotNull()
                    .filter { !it.isNoted && !it.isPlaceholder && it.name.isNotBlank() && it.name != "null" }
                for (chunk in all.chunked(200)) {
                    val arr = JsonArray()
                    for (def in chunk) arr.add(json(
                        "id" to def.id,
                        "name" to def.name.take(120),
                        "value" to def.price.coerceAtLeast(0),
                        "examine" to def.examine?.take(500),
                    ))
                    client.post("/api/game/items", json("items" to arr), timeoutSeconds = 60)
                    items += chunk.size
                }

                val seen = HashSet<String>()
                val tables = NPCDrops.getAllTables().values.mapNotNull { table ->
                    val def = NPCDefinitions.get(table.npcId) ?: return@mapNotNull null
                    val name = def.name?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                    if (!seen.add(name.lowercase())) return@mapNotNull null
                    val drops = JsonArray()
                    for (drop in table.drops ?: emptyArray()) {
                        val itemDef = ItemDefinitions.get(drop.itemId) ?: continue
                        val qty = if (drop.maxAmount > drop.minAmount) "${drop.minAmount}-${drop.maxAmount}" else "${drop.minAmount}"
                        val entry = json("itemId" to drop.itemId, "itemName" to itemDef.name.take(120), "quantity" to qty)
                        if (drop.isAlways || table.weight <= 0 || drop.baseRate <= 0) entry.addProperty("rarity", "ALWAYS")
                        else entry.addProperty("rate", "1/${(table.weight.toDouble() / drop.baseRate).roundToInt().coerceAtLeast(1)}")
                        drops.add(entry)
                    }
                    if (drops.size() == 0) null
                    else json("name" to name.take(120), "combatLevel" to def.combatLevel.coerceAtLeast(0), "drops" to drops)
                }
                for (chunk in tables.chunked(25)) {
                    val arr = JsonArray()
                    chunk.forEach(arr::add)
                    client.post("/api/game/drops", json("npcs" to arr), timeoutSeconds = 60)
                    npcs += chunk.size
                }
                WorldTasksManager.schedule { player.sendMessage("Website sync done: $items items, $npcs drop tables.") }
            } catch (e: Exception) {
                logger.error("Website sync failed", e)
                WorldTasksManager.schedule { player.sendMessage("Website sync stopped after $items items, $npcs drop tables: ${e.message}") }
            }
        }
    }

    private fun JsonArray.chunked(size: Int): List<JsonArray> =
        toList().chunked(size).map { part -> JsonArray().also { a -> part.forEach(a::add) } }
}
