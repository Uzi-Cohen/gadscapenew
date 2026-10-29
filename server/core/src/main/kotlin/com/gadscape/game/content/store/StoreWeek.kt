package com.gadscape.game.content.store

import com.gadscape.game.item.CustomItemId
import com.gadscape.website.WebsiteService
import com.google.gson.GsonBuilder
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.entity.player.Player
import mgi.utilities.StringFormatUtil
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The weekly offer: [text] null = the default text; every [everyCents] paid this week gives [rewardItemId] (-1 = none). */
class StoreOffer(var text: String? = null, var rewardItemId: Int = CustomItemId.COSMETIC_MYSTERY_BOX, var everyCents: Int = 2_000)

/** One player's week: payments this server saw claimed ([seen] reward ids), the checkpoints and offer items given. */
class StoreWeekPlayer(
    var localCents: Int = 0,
    var seen: MutableSet<String> = LinkedHashSet(),
    var checkpoints: MutableSet<Int> = LinkedHashSet(),
    var offerGiven: Int = 0,
    var lastCents: Int = 0,
)

/** data/store-week.json */
class StoreWeekFile(
    var offer: StoreOffer = StoreOffer(),
    var weekStart: Long = 0,
    var players: MutableMap<String, StoreWeekPlayer> = LinkedHashMap(),
)

/**
 * The store's week (Monday 00:00 to Sunday 23:59, server time): what each player paid this week, the promotion
 * checkpoints and the weekly offer.
 *
 * "Paid this week" comes from the website (WebsiteService.storeWeekCents, GET /api/game/store/week). Until the website
 * answers that, it's the sum of the purchases this server saw claimed this week ([recordClaim]). Checkpoint and offer
 * rewards are put on the claim list ([PendingRewards]) once a week each, as soon as the total reaches them.
 */
object StoreWeek {
    private val logger = LoggerFactory.getLogger(StoreWeek::class.java)
    private val FILE: Path = Paths.get("data", "store-week.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    const val DEFAULT_OFFER = "Every \$20 spent this week adds 1 Cosmetic mystery box."

    private var loaded: StoreWeekFile? = null

    private val data: StoreWeekFile
        get() {
            val d = loaded ?: (try {
                if (Files.exists(FILE)) Files.newBufferedReader(FILE).use { gson.fromJson(it, StoreWeekFile::class.java) } else null
            } catch (e: Exception) {
                logger.error("Failed to read {}", FILE, e)
                null
            } ?: StoreWeekFile()).also { loaded = it }
            val start = weekStart()
            if (d.weekStart != start) {
                // a new week: totals, checkpoints and offer items start again
                d.weekStart = start
                d.players.clear()
                save()
            }
            return d
        }

    private fun save() {
        val d = loaded ?: return
        try {
            Files.createDirectories(FILE.toAbsolutePath().parent)
            val tmp = FILE.resolveSibling(FILE.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(d))
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to write {}", FILE, e)
        }
    }

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** This week's Monday 00:00, server time, in epoch millis. */
    @JvmStatic
    fun weekStart(): Long = LocalDate.now(zone()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Next Monday 00:00 (the end of this week), epoch millis. */
    @JvmStatic
    fun weekEnd(): Long = LocalDate.now(zone()).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        .atStartOfDay(zone()).toInstant().toEpochMilli()

    /** "Ends in 3d 4h 12m" */
    fun endsIn(): String {
        val left = Duration.ofMillis((weekEnd() - System.currentTimeMillis()).coerceAtLeast(0))
        return "Ends in ${left.toDays()}d ${left.toHoursPart()}h ${left.toMinutesPart()}m"
    }

    private fun key(username: String) = StringFormatUtil.formatUsername(username)

    @Synchronized
    private fun week(username: String): StoreWeekPlayer = data.players.getOrPut(key(username)) { StoreWeekPlayer() }

    val offer: StoreOffer
        @Synchronized get() = data.offer

    @Synchronized
    fun offerText(): String = data.offer.text ?: DEFAULT_OFFER

    @Synchronized
    fun setOffer(text: String?) {
        data.offer.text = text
        save()
    }

    @Synchronized
    fun setOfferReward(itemId: Int, everyCents: Int) {
        data.offer.rewardItemId = itemId
        data.offer.everyCents = everyCents
        save()
    }

    /** What this server saw [username] pay this week (claimed purchases), in cents. */
    @Synchronized
    fun localCents(username: String): Int = week(username).localCents

    /** The last total known for [username] this week (website or local), for the screen before the website answers. */
    @Synchronized
    fun lastCents(username: String): Int = week(username).let { maxOf(it.lastCents, it.localCents) }

    /**
     * A website reward [player] was just given (WebsiteService.deliver): money paid for it counts for the week once
     * (by reward id), as the fallback when the website can't tell the week's total.
     */
    @Synchronized
    fun recordClaim(player: Player, rewardId: String, paidCents: Int) {
        if (paidCents <= 0) return
        val w = week(player.username)
        if (!w.seen.add(rewardId)) return
        w.localCents += paidCents
        save()
    }

    /**
     * The week's total for [player] is [cents]: gives every checkpoint and offer reward it reaches that wasn't given
     * this week yet (to the claim list). Game thread.
     */
    @Synchronized
    fun update(player: Player, cents: Int) {
        val w = week(player.username)
        w.lastCents = maxOf(w.lastCents, cents)
        val given = ArrayList<String>()
        for (cp in StoreCheckpoints.all) {
            if (cents < cp.cents || !w.checkpoints.add(cp.cents)) continue
            val source = "Promotion checkpoint \$${cp.cents / 100}"
            for (item in cp.rewards) PendingRewards.add(player.username, Item(item.id, item.amount), source)
            given += "\$${cp.cents / 100}"
            logger.info("{} reached the {} promotion checkpoint ({} cents this week)", player.username, source, cents)
        }
        val o = data.offer
        if (o.rewardItemId >= 0 && o.everyCents > 0) {
            val earned = cents / o.everyCents
            if (earned > w.offerGiven) {
                PendingRewards.add(player.username, Item(o.rewardItemId, earned - w.offerGiven), "Weekly offer")
                w.offerGiven = earned
            }
        }
        save()
        if (given.isNotEmpty())
            player.sendMessage(Colour.RS_GREEN.wrap("Promotion checkpoint${if (given.size == 1) "" else "s"} ${given.joinToString()} reached! The rewards are in your ::donate claim list."))
    }

    /** Items the offer has given [username] this week. */
    @Synchronized
    fun offerGiven(username: String): Int = week(username).offerGiven

    /** At login: asks the website for the week's total, so rewards reached on the website show up straight away. */
    fun onLogin(player: Player) {
        if (WebsiteService.isEnabled) check(player)
    }

    /** Gets the week's total (website, else this server's count) and gives what it reaches. */
    fun check(player: Player) {
        WebsiteService.storeWeekCents(player) { cents -> update(player, total(player, cents)) }
    }

    /** The total to show and reward: the website's when it answered, else what this server saw. */
    fun total(player: Player, siteCents: Int?): Int = siteCents ?: localCents(player.username)
}
