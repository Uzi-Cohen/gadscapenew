package com.gadscape.website

import com.gadscape.api.model.Bond
import com.gadscape.api.model.VoteSite
import com.gadscape.api.service.store.notify
import com.gadscape.api.service.vote.VotePlayerHandler
import com.gadscape.api.service.vote.totalVoteCredits
import com.gadscape.api.service.user.updateVoteStatisticOnPlayerDetailsTab
import com.gadscape.game.content.donator.DonatorRanks
import com.gadscape.game.content.donator.totalDonatedCents
import com.gadscape.game.content.store.SiteReward
import com.gadscape.game.content.store.StoreScreen
import com.gadscape.game.content.store.StoreWeek
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
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import com.zenyte.net.ClientResponse
import mgi.types.config.items.ItemDefinitions
import mgi.types.config.npcs.NPCDefinitions
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
 * - `::vote` opens the website signed in as the character; the vote screen's Vote opens one toplist
 *   ([commandVoteSite]) and its Claim takes that site's confirmed votes ([claimSiteVotes]).
 * - `::donate` opens the in-game store screen (StoreScreen) when the cache has it, whose Checkout / Open
 *   store website buttons open the signed store link ([openStore]).
 * - `::claim` hands out everything the website has queued (votes, donations,
 *   promotions, custom orders, staff grants, DONATOR_RANK).
 * - Using a donator pin or bond reports it to the website, which keeps the running
 *   pin total; the donator rank is the one the lifetime total reaches (DonatorRanks), never lower.
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
        info.int("claimedCents")?.let { player.claimedPinCents = it }
        applyTier(player, info.obj("tier")?.str("key"), announce = false)
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
    fun commandVote(player: Player) =
        openLink(player, "/api/game/vote-link", GameConstants.SERVER_VOTE_URL, "Opening the vote page...")

    /**
     * The vote screen's Vote button: the website's signed-in vote link for [site] (`site` = the VoteSite name; the
     * website sends the player on to that toplist, or to its vote page). [fallbackUrl] when the link isn't configured
     * or the website can't be reached.
     */
    @JvmStatic
    fun commandVoteSite(player: Player, site: VoteSite, fallbackUrl: String) {
        val message = "Opening ${site.fancyName} for ${player.name}. Your vote there counts for this character."
        if (!isEnabled) {
            player.sendMessage(message)
            player.packetDispatcher.sendURL(fallbackUrl)
            return
        }
        val name = player.name
        pool.execute {
            val url = try {
                client.post("/api/game/vote-link", json("player" to name, "site" to site.name)).str("url")
            } catch (e: IOException) {
                logger.warn("Website vote link failed: {}", e.message)
                null
            }
            WorldTasksManager.schedule {
                player.sendMessage(message)
                player.packetDispatcher.sendURL(url ?: fallbackUrl)
            }
        }
    }

    /** ::donate: the store screen when the cache has it (StorePatch), else straight to the store page. */
    @JvmStatic
    fun commandDonate(player: Player) {
        if (StoreScreen.available) StoreScreen.open(player)
        else openStore(player, null)
    }

    /**
     * Opens the signed store link; [cart] (`pin1:2,bond10:1`, see docs/store-website-api.md) goes to the website with
     * the link request and on the link itself, so the store page can fill its cart. Null = just the store.
     */
    @JvmStatic
    fun openStore(player: Player, cart: String?) =
        openLink(player, "/api/game/donate-link", GameConstants.SERVER_STORE_URL,
            if (cart == null) "Opening the donation store..." else "Opening the store with your cart...", cart)

    /** Opens the signed-in page, or the plain page when the website link isn't configured. */
    private fun openLink(player: Player, path: String, fallbackUrl: String, message: String, cart: String? = null) {
        if (!isEnabled) {
            player.sendMessage(message)
            player.packetDispatcher.sendURL(withCart(fallbackUrl, cart))
            return
        }
        val name = player.name
        pool.execute {
            val url = try {
                client.post(path, json("player" to name, "cart" to cart)).str("url")?.let { withCart(it, cart) }
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

    /** [url] with `cart=<cart>` in its query (before any #fragment), unless it has a cart already. */
    private fun withCart(url: String, cart: String?): String {
        if (cart.isNullOrEmpty() || url.contains("cart=")) return url
        val hash = url.indexOf('#')
        val base = if (hash >= 0) url.substring(0, hash) else url
        val fragment = if (hash >= 0) url.substring(hash) else ""
        // the keys and amounts are [a-z0-9]: ':' and ',' are left as they are, the website reads them as written
        return base + (if (base.contains('?')) "&" else "?") + "cart=" + cart + fragment
    }

    /**
     * This week's payments of [player] in cents (GET /api/game/store/week, Monday 00:00 to Sunday 23:59 server time),
     * given to [done] on the game thread; null when the link is off, the website can't be reached or doesn't know
     * that endpoint yet (StoreWeek then uses what this server saw claimed).
     */
    fun storeWeekCents(player: Player, done: (Int?) -> Unit) {
        if (!isEnabled) {
            done(null)
            return
        }
        val path = "/api/game/store/week?player=${URLEncoder.encode(player.name, StandardCharsets.UTF_8)}" +
                "&from=${StoreWeek.weekStart()}&to=${StoreWeek.weekEnd()}"
        pool.execute {
            val cents = try {
                client.get(path, timeoutSeconds = 6).int("cents")
            } catch (e: IOException) {
                logger.debug("Store week total for {} not available: {}", player.name, e.message)
                null
            }
            WorldTasksManager.schedule { done(cents) }
        }
    }

    /**
     * What the website has queued for [player] without reserving it (GET /api/game/claims/pending), for the store's
     * claim list; [done] gets null on the game thread when it can't tell (the screen then shows one "Website purchases"
     * row that claims everything like ::claim). Claiming always goes through [commandClaim].
     */
    fun storeClaimList(player: Player, done: (List<SiteReward>?) -> Unit) {
        if (!isEnabled) {
            done(null)
            return
        }
        val path = "/api/game/claims/pending?player=${URLEncoder.encode(player.name, StandardCharsets.UTF_8)}"
        pool.execute {
            val list = try {
                client.get(path, timeoutSeconds = 6).getAsJsonArray("rewards")?.mapNotNull { el ->
                    val r = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    SiteReward(r.str("id"), r.str("label") ?: r.str("code") ?: "Reward", (r.int("amount") ?: 1).coerceAtLeast(1),
                        r.int("itemId")?.takeIf { ItemDefinitions.get(it) != null })
                }
            } catch (e: IOException) {
                logger.debug("Website claim list for {} not available: {}", player.name, e.message)
                null
            } catch (e: RuntimeException) {
                logger.warn("Website claim list for {} unreadable: {}", player.name, e.message)
                null
            }
            WorldTasksManager.schedule { done(list) }
        }
    }

    /** Money paid for a claimed reward (for the store's week when the website can't tell it): see docs/store-website-api.md. */
    private fun paidCents(reward: JsonObject): Int {
        reward.int("paidCents")?.let { return it.coerceAtLeast(0) }
        val code = reward.str("code")
        if (code != "PIN") (reward.int("donatedCents") ?: reward.int("amountCents"))?.let { return it.coerceAtLeast(0) }
        if (code == "PIN" && reward.str("source")?.uppercase(Locale.ROOT) in PAID_SOURCES)
            return ((reward.int("valueCents") ?: 0) * (reward.int("amount") ?: 1)).coerceAtLeast(0)
        return 0
    }

    /** Reward sources that are a purchase (a pin from a vote, promotion or staff grant isn't money paid). */
    private val PAID_SOURCES = setOf("DONATION", "PURCHASE", "STORE", "ORDER", "CUSTOM_ORDER")

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
                    WorldTasksManager.schedule {
                        player.sendMessage("You have nothing to claim.")
                        StoreScreen.afterWebsiteClaim(player)
                    }
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
            for ((index, reward) in rewards.withIndex()) {
                val ok = give(player, reward)
                val label = reward.str("label") ?: "Reward"
                val amount = reward.int("amount") ?: 1
                if (ok) {
                    delivered.add(reward.str("id"))
                    // the store's "paid this week" when the website can't tell it (StoreWeek)
                    StoreWeek.recordClaim(player, reward.str("id") ?: "$batchId-$index", paidCents(reward))
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
            StoreScreen.afterWebsiteClaim(player)
        }
        complete(player.name, batchId, delivered)
    }

    /** Tells the website which rewards of [batchId] were given (the rest stay queued), then frees [name]'s claim. */
    private fun complete(name: String, batchId: String, delivered: JsonArray) {
        val body = json("batchId" to batchId, "delivered" to delivered)
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

    /**
     * The vote screen's Claim for [site]: takes the player's queued rewards like ::claim but gives only the confirmed
     * votes of [site] (a vote reward that names no site counts as any site's); everything else is reported as not
     * given, so it stays queued for ::claim. [done] gets the number of votes given (game thread; -1 when nothing was
     * asked: a claim already running, or the website couldn't be reached).
     */
    @JvmStatic
    fun claimSiteVotes(player: Player, site: VoteSite, done: (Int) -> Unit) {
        val name = player.name
        if (!claiming.add(key(name))) {
            done(-1)
            return
        }
        player.sendMessage("Checking for your ${site.fancyName} vote...")
        pool.execute {
            try {
                val res = client.post("/api/game/claims", json("player" to name))
                val batchId = res.str("batchId")
                val rewards = res.getAsJsonArray("rewards")?.map { it.asJsonObject }.orEmpty()
                if (res.has("error") || batchId == null || rewards.isEmpty()) {
                    claiming.remove(key(name))
                    WorldTasksManager.schedule { done(0) }
                    return@execute
                }
                WorldTasksManager.schedule { done(deliverVotes(player, batchId, rewards, site)) }
            } catch (e: IOException) {
                claiming.remove(key(name))
                logger.warn("Vote claim failed for {}: {}", name, e.message)
                WorldTasksManager.schedule {
                    player.sendMessage("Couldn't reach the website. Your vote is safe - try again shortly.")
                    done(-1)
                }
            }
        }
    }

    /** Gives [site]'s confirmed votes of a claim batch; the rest of the batch is reported as not given. */
    private fun deliverVotes(player: Player, batchId: String, rewards: List<JsonObject>, site: VoteSite): Int {
        val delivered = JsonArray()
        if (player.isLoggedIn) {
            for (reward in rewards) {
                if (reward.str("code") != "VOTE_POINTS" || reward.str("source") != "VOTE") continue
                val rewardSite = voteSite(reward)
                if (rewardSite != null && rewardSite != site) continue
                val id = reward.str("id") ?: continue
                VotePlayerHandler.giveVoteBenefits(player, (reward.int("amount") ?: 1).coerceAtLeast(1), site)
                delivered.add(id)
            }
        }
        complete(player.name, batchId, delivered)
        return delivered.size()
    }

    /** The toplist a vote reward is from: its `site`, else its label ("RuneLocus vote"); null when it says neither. */
    private fun voteSite(reward: JsonObject): VoteSite? =
        VoteSite.find(reward.str("site")) ?: reward.str("label")?.let { label ->
            val folded = VoteSite.fold(label)
            VoteSite.entries.firstOrNull { folded.contains(it.key) }
        }

    /** Gives one reward. False = keep it queued for the next ::claim. */
    private fun give(player: Player, reward: JsonObject): Boolean {
        val code = reward.str("code") ?: return false
        val amount = (reward.int("amount") ?: 1).coerceAtLeast(1)
        val itemId = reward.int("itemId")
        // DONATOR_RANK: rank 1..6 (and the points that come with it); false (no valid rank) keeps it queued
        if (code == DonatorRanks.REWARD_CODE) return DonatorRanks.claimRank(player, reward)
        return when (code) {
            "VOTE_POINTS" -> {
                if (reward.str("source") == "VOTE") {
                    // a confirmed vote: the vote screen's benefits and perks (bonus weeks, boss counter, vote perks...)
                    VotePlayerHandler.giveVoteBenefits(player, amount, voteSite(reward))
                } else {
                    player.totalVoteCredits += amount
                    player.updateVoteStatisticOnPlayerDetailsTab()
                }
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
        }.also { given -> if (given) DonatorRanks.countDonation(player, reward) }
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

    // ---- donator pins ----------------------------------------------------------------------------

    /**
     * Redeems [count] of the donator pin or bond [bond] (one at a time, or a whole stack of the stackable $1 pin):
     * removes them, reports their value to the website (which keeps the pin total), gives them back if that fails,
     * adds [donatorPoints], and raises the rank to what the lifetime total now reaches (DonatorRanks, never lower).
     */
    @JvmStatic
    @JvmOverloads
    fun redeemPin(player: Player, bond: Bond, count: Int = 1, donatorPoints: Int = 0) {
        if (count <= 0) return
        val pin = Item(bond.id, count)
        val dollars = bond.amount * count
        if (player.inventory.deleteItem(pin).result != RequestResult.SUCCESS) return
        if (!isEnabled) {
            redeemPinLocally(player, pin, dollars, donatorPoints)
            return
        }
        player.lock()
        player.dialogue { loading("Claiming your ${pin.name}...") }
        val redeemId = UUID.randomUUID().toString()
        val body = json("player" to player.name, "valueCents" to dollars * 100, "redeemId" to redeemId)
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
                    player.dialogue { item(pin, "Couldn't reach the website, so your ${pin.name} was returned. Try again in a moment.") }
                    return@schedule
                }
                player.claimedPinCents = result.int("claimedCents") ?: (player.claimedPinCents + dollars * 100)
                player.donatorPoints += donatorPoints
                DonatorRanks.refresh(player, announce = true)
                pinDialogue(player, pin, dollars, donatorPoints)
            }
        }
    }

    /** Keeps the donator total on the character when the website link isn't configured. */
    private fun redeemPinLocally(player: Player, pin: Item, dollars: Int, donatorPoints: Int) {
        player.claimedPinCents += dollars * 100
        player.donatorPoints += donatorPoints
        DonatorRanks.refresh(player, announce = true)
        pinDialogue(player, pin, dollars, donatorPoints)
    }

    private fun pinDialogue(player: Player, pin: Item, dollars: Int, donatorPoints: Int) = player.dialogue {
        item(pin, "${Colour.DARK_BLUE.wrap("$$dollars")} has been added to your donator total, " +
                "making ${Colour.DARK_BLUE.wrap(DonatorRanks.formatUsd(player.totalDonatedCents))}." +
                (if (donatorPoints > 0) " You get ${Colour.DARK_BLUE.wrap("$donatorPoints")} donator points." else "") +
                (if (player.memberRank.isDonator) "<br>Rank: ${Colour.RS_GREEN.wrap(player.memberRank.toString())}" else ""))
    }

    /**
     * The donator rank comes from the lifetime total (pins as the website reports them plus claimed purchases, see
     * DonatorRanks), not from the website's tier key: it only ever goes up.
     */
    private fun applyTier(player: Player, @Suppress("UNUSED_PARAMETER") tierKey: String?, announce: Boolean) {
        DonatorRanks.refresh(player, announce)
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
                data.int("claimedCents")?.let { online.claimedPinCents = it }
                applyTier(online, data.str("tier"), announce = true)
            }
            "VOTE_RECEIVED" -> online?.let { p ->
                WorldTasksManager.schedule {
                    // the vote screen shows it on that site's row (Claim)
                    VotePlayerHandler.onWebsiteVote(p, VoteSite.find(data.str("site")))
                    p.notify("Your vote on ${data.str("site") ?: "the toplist"} was received!<br>Claim it on ::vote (or with ::claim).")
                }
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
