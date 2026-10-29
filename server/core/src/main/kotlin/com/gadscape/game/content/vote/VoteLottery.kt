package com.gadscape.game.content.vote

import com.gadscape.game.content.store.PendingRewards
import com.google.common.eventbus.Subscribe
import com.google.gson.GsonBuilder
import com.zenyte.game.item.Item
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.broadcasts.BroadcastType
import com.zenyte.game.world.broadcasts.WorldBroadcasts
import com.zenyte.game.world.entity.player.Player
import com.zenyte.plugins.events.ServerLaunchEvent
import mgi.types.config.items.ItemDefinitions
import mgi.utilities.StringFormatUtil
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ThreadLocalRandom

/** The vote lottery's saved state (data/vote-lottery.json). */
class VoteLotteryState(
    var prizeId: Int = VoteLottery.DEFAULT_PRIZE,
    var prizeAmount: Int = 1,
    /** When this week's draw is due (epoch millis). */
    var drawAt: Long = 0,
    /** This week's entries by username. */
    var entries: LinkedHashMap<String, Int> = LinkedHashMap(),
    var lastWinner: String = "",
    var lastDrawAt: Long = 0,
)

/**
 * The weekly vote lottery: every claimed vote is one entry for the week. The draw is every Sunday at 20:00 server
 * time: one winner, picked with each player's entries as their weight, gets the prize (set by admins with
 * `::votelottery prize <itemId> <amount>`, an Infernal cape by default) through PendingRewards (the store's claim
 * list, online or not), the world hears who won, and the entries start again. Every draw is written to
 * data/logs/vote-lottery.log. All on the game thread.
 */
object VoteLottery {

    const val DEFAULT_PRIZE = 21295 // Infernal cape
    private const val DRAW_HOUR = 20
    private val DRAW_DAY = DayOfWeek.SUNDAY
    private const val CHECK_TICKS = 100 // a minute

    private val logger = LoggerFactory.getLogger(VoteLottery::class.java)
    private val file: Path = Path.of("data", "vote-lottery.json")
    private val logFile: Path = Path.of("data", "logs", "vote-lottery.log")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val state: VoteLotteryState by lazy { load() }

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        WorldTasksManager.schedule(CHECK_TICKS, CHECK_TICKS) { if (System.currentTimeMillis() >= state.drawAt) draw() }
    }

    private fun key(username: String) = StringFormatUtil.formatUsername(username)

    fun addEntry(username: String) {
        val k = key(username)
        state.entries[k] = (state.entries[k] ?: 0) + 1
        save()
    }

    val prize: Item get() = Item(state.prizeId, state.prizeAmount)
    val drawAt: Long get() = state.drawAt
    val totalEntries: Int get() = state.entries.values.sum()

    fun entries(username: String): Int = state.entries[key(username)] ?: 0

    /** [username]'s chance to win this week, in percent. */
    fun winChance(username: String): Double {
        val total = totalEntries
        return if (total <= 0) 0.0 else entries(username) * 100.0 / total
    }

    fun setPrize(itemId: Int, amount: Int) {
        state.prizeId = itemId
        state.prizeAmount = amount
        save()
    }

    /** The next draw time after [now]: Sunday 20:00 server time. */
    private fun nextDraw(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        var draw = at.with(TemporalAdjusters.nextOrSame(DRAW_DAY)).withHour(DRAW_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!draw.isAfter(at)) draw = draw.plusWeeks(1)
        return draw.toInstant().toEpochMilli()
    }

    /** Draws this week's winner (or notes that nobody entered) and starts the next week. */
    fun draw() {
        val now = System.currentTimeMillis()
        val entries = LinkedHashMap(state.entries)
        val total = entries.values.sum()
        val prize = prize
        val prizeName = itemName(prize.id)
        if (total <= 0) {
            log("draw: no entries, prize $prizeName x${prize.amount} kept for next week")
        } else {
            var roll = ThreadLocalRandom.current().nextInt(total)
            var winner = entries.keys.first()
            for ((name, count) in entries) {
                if (roll < count) { winner = name; break }
                roll -= count
            }
            val display = StringFormatUtil.formatString(winner)
            val chance = entries.getValue(winner) * 100.0 / total
            PendingRewards.add(winner, Item(prize.id, prize.amount), "Vote lottery")
            state.lastWinner = display
            state.lastDrawAt = now
            log("draw: winner $display with ${entries.getValue(winner)} of $total entries (${"%.2f".format(chance)}%) from ${entries.size} players, " +
                    "prize $prizeName x${prize.amount} (id ${prize.id})")
            WorldBroadcasts.sendMessage("<img=48><col=e59400><shad=000000>Vote lottery: $display has won ${prize.amount} x $prizeName " +
                    "with ${entries.getValue(winner)} of $total entries! Vote with ::vote to enter next week's draw.</shad></col>",
                BroadcastType.LOTTERY, true)
            World.getPlayer(winner).ifPresent {
                it.sendMessage(Colour.RS_GREEN.wrap("You won the vote lottery! Your prize is waiting in the store's claim list (::donate)."))
            }
        }
        state.entries.clear()
        state.drawAt = nextDraw(now)
        save()
    }

    /** ::votelottery for players. */
    fun describe(player: Player): List<String> {
        val prize = prize
        val left = (state.drawAt - System.currentTimeMillis()).coerceAtLeast(0)
        return listOf(
            "Vote lottery prize: ${Colour.RS_GREEN.wrap("${prize.amount} x ${itemName(prize.id)}")}",
            "Entries this week: ${totalEntries} (yours: ${entries(player.username)}), your win chance: ${"%.1f".format(winChance(player.username))}%",
            "Next draw: Sunday 20:00 server time, in ${VotePerks.formatTime(left)}. Every vote you claim is one entry." +
                    if (state.lastWinner.isNotEmpty()) " Last winner: ${state.lastWinner}." else "",
        )
    }

    private fun itemName(id: Int): String = ItemDefinitions.get(id)?.name ?: "item $id"

    private fun load(): VoteLotteryState {
        val loaded = try {
            if (Files.exists(file)) Files.newBufferedReader(file).use { gson.fromJson(it, VoteLotteryState::class.java) } else null
        } catch (e: Exception) {
            logger.error("Failed to read {}", file, e)
            null
        }
        val s = loaded ?: VoteLotteryState()
        @Suppress("SENSELESS_COMPARISON") // gson leaves a missing field null
        if (s.entries == null) s.entries = LinkedHashMap()
        if (s.drawAt <= 0) s.drawAt = nextDraw(System.currentTimeMillis())
        return s
    }

    private fun save() {
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(state))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to write {}", file, e)
        }
    }

    private fun log(line: String) {
        logger.info("Vote lottery {}", line)
        try {
            Files.createDirectories(logFile.toAbsolutePath().parent)
            Files.writeString(logFile, "${LocalDateTime.now().format(time)} $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.error("Failed to write {}", logFile, e)
        }
    }
}
