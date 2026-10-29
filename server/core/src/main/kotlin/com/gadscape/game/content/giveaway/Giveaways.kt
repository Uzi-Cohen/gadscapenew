package com.gadscape.game.content.giveaway

import com.gadscape.game.content.giveaway.PartyGiveawayUi.Giveaway
import com.gadscape.game.content.giveaway.PartyGiveawayUi.GiveawayConfirm
import com.google.common.eventbus.Subscribe
import com.google.gson.GsonBuilder
import com.zenyte.game.GameInterface
import com.zenyte.game.item.Item
import com.zenyte.game.world.World
import com.zenyte.game.world.broadcasts.BroadcastType
import com.zenyte.game.world.broadcasts.WorldBroadcasts
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.login.LoginManager
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import com.zenyte.plugins.events.LoginEvent
import mgi.types.component.ComponentDefinitions
import mgi.utilities.StringFormatUtil
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** An approved prize: [amount] of [itemId] per give, [stock] gives left. */
class GiveawayPrize(var itemId: Int = 0, var amount: Int = 1, var stock: Int = 0)

/** A prize won while offline, handed over at the winner's next login. */
class GiveawayDelivery(var itemId: Int = 0, var amount: Int = 0, var from: String = "")

/** One giveaway, for the Recent giveaways list. */
class GivenPrize(var giver: String = "", var winner: String = "", var itemId: Int = 0, var amount: Int = 0, var time: Long = 0)

/** data/giveaways.json: the approved prizes, prizes waiting for their winner to log in, and the latest giveaways. */
class GiveawayStore(
    var prizes: MutableList<GiveawayPrize> = ArrayList(),
    var pending: MutableMap<String, MutableList<GiveawayDelivery>> = LinkedHashMap(),
    var recent: MutableList<GivenPrize> = ArrayList(),
)

/** What one YouTuber has picked on the giveaway screen. */
class GiveawaySession {
    var page = 0
    /** The selected prize, by item id (the list can change while the screen is open). */
    var prizeItemId = -1
    var winner = ""
}

/**
 * YouTuber giveaways (::giveaway, YouTubers and up): a YouTuber gives one of the prizes the admins approved
 * (::giveawayitems) to a winner they name. Nothing is given until they confirm; the prize goes to the winner's bank
 * (queued until their next login when they are offline), the stock goes down, the world hears about it and every
 * giveaway is written to data/logs/giveaways.log.
 */
object Giveaways {
    private val logger = LoggerFactory.getLogger(Giveaways::class.java)
    private val FILE: Path = Paths.get("data", "giveaways.json")
    private val LOG: Path = Paths.get("data", "logs", "giveaways.log")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private const val SESSION_KEY = "youtuber giveaway"
    private const val RECENT_KEPT = 20
    private const val ITEM_ZOOM = 100

    private var loaded: GiveawayStore? = null

    private val store: GiveawayStore
        get() {
            loaded?.let { return it }
            val s = try {
                if (Files.exists(FILE)) Files.newBufferedReader(FILE).use { gson.fromJson(it, GiveawayStore::class.java) } else null
            } catch (e: Exception) {
                logger.error("Failed to read {}", FILE, e)
                null
            } ?: GiveawayStore()
            loaded = s
            return s
        }

    private fun save() {
        try {
            Files.createDirectories(FILE.parent)
            Files.writeString(FILE, gson.toJson(store))
        } catch (e: Exception) {
            logger.error("Failed to write {}", FILE, e)
        }
    }

    private fun log(line: String) {
        try {
            Files.createDirectories(LOG.parent)
            Files.writeString(LOG, "[${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}] $line\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.error("Failed to write {}", LOG, e)
        }
    }

    /** YouTubers and every rank above them (staff don't inherit YouTuber, so this goes by the rank order). */
    @JvmStatic
    fun mayGive(player: Player): Boolean = player.privilege.ordinal >= PlayerPrivilege.YOUTUBER.ordinal

    @JvmStatic
    fun mayManage(player: Player): Boolean = player.privilege.eligibleTo(PlayerPrivilege.ADMINISTRATOR)

    private fun session(player: Player): GiveawaySession =
        player.temporaryAttributes.getOrPut(SESSION_KEY) { GiveawaySession() } as GiveawaySession

    private fun selected(s: GiveawaySession): GiveawayPrize? = store.prizes.firstOrNull { it.itemId == s.prizeItemId }

    private fun itemName(id: Int) = Item(id).name

    /** ::giveaway */
    @JvmStatic
    fun open(player: Player) {
        if (!mayGive(player)) return
        if (player.isLocked) {
            player.sendMessage("You can't do that right now.")
            return
        }
        // the screens come from the editor's cache patch (PartyGiveawayPatch): a cache without them shows nothing
        if (!ComponentDefinitions.containsInterface(Giveaway.ID) || !ComponentDefinitions.containsInterface(GiveawayConfirm.ID)) {
            player.sendMessage("The giveaway screen isn't in the game's cache yet.")
            return
        }
        GameInterface.GIVEAWAY.open(player)
    }

    fun render(player: Player) {
        val s = session(player)
        val prizes = store.prizes
        val pages = maxOf(1, (prizes.size + Giveaway.PRIZE_SLOTS - 1) / Giveaway.PRIZE_SLOTS)
        s.page = s.page.coerceIn(0, pages - 1)
        val d = player.packetDispatcher
        for (slot in 0 until Giveaway.PRIZE_SLOTS) {
            val tile = Giveaway.tile(slot)
            val prize = prizes.getOrNull(s.page * Giveaway.PRIZE_SLOTS + slot)
            d.sendComponentVisibility(Giveaway.ID, tile, prize == null)
            if (prize == null) continue
            d.sendComponentItem(Giveaway.ID, tile + Giveaway.TILE_ITEM_OFFSET, prize.itemId, ITEM_ZOOM)
            d.sendComponentText(Giveaway.ID, tile + Giveaway.TILE_AMOUNT_OFFSET, (if (prize.stock > 0) "" else "<col=ff0000>") + "x${prize.stock}")
            val frame = if (prize.itemId == s.prizeItemId) PartyGiveawayUi.Sprites.BUTTON_SELECTED else PartyGiveawayUi.Sprites.BUTTON
            for (k in frame.indices) d.sendClientScript(44, (Giveaway.ID shl 16) or (tile + Giveaway.TILE_FRAME_OFFSET + k), frame[k])
        }
        d.sendComponentText(Giveaway.ID, Giveaway.PAGE, if (prizes.isEmpty()) "No prizes approved yet" else "Page ${s.page + 1} / $pages")
        d.sendComponentVisibility(Giveaway.ID, Giveaway.PREV, s.page <= 0)
        d.sendComponentVisibility(Giveaway.ID, Giveaway.NEXT, s.page >= pages - 1)

        d.sendComponentText(Giveaway.ID, Giveaway.WINNER_NAME, if (s.winner.isEmpty()) "<col=9f9f9f>Click to enter a name" else "<col=ffffff>${s.winner}")
        d.sendComponentText(Giveaway.ID, Giveaway.WINNER_STATUS, when {
            s.winner.isEmpty() -> ""
            World.getPlayer(s.winner).isPresent -> "Status: <col=00ff00>Online</col>"
            accountExists(s.winner) -> "Status: <col=ff9040>Offline</col> (gets it on their next login)"
            else -> "Status: <col=ff0000>No such player</col>"
        })
        val prize = selected(s)
        d.sendComponentVisibility(Giveaway.ID, Giveaway.PRIZE_ITEM, prize == null)
        if (prize == null) {
            d.sendComponentText(Giveaway.ID, Giveaway.PRIZE_NAME, "No prize selected")
            d.sendComponentText(Giveaway.ID, Giveaway.PRIZE_INFO, "Pick one of the approved prizes.")
        } else {
            d.sendComponentItem(Giveaway.ID, Giveaway.PRIZE_ITEM, prize.itemId, ITEM_ZOOM)
            d.sendComponentText(Giveaway.ID, Giveaway.PRIZE_NAME, itemName(prize.itemId))
            d.sendComponentText(Giveaway.ID, Giveaway.PRIZE_INFO, "Amount: <col=ffffff>${prize.amount}</col>   Stock left: " +
                    (if (prize.stock > 0) "<col=ffffff>" else "<col=ff0000>") + prize.stock)
        }

        val recent = store.recent.asReversed()
        for (i in Giveaway.RECENT.indices) {
            val g = recent.getOrNull(i)
            d.sendComponentText(Giveaway.ID, Giveaway.RECENT[i], when {
                g != null -> "<col=ffffff>${g.winner}</col> - ${itemName(g.itemId)} x${g.amount} - ${ago(g.time)}"
                i == 0 -> "Nothing has been given away yet."
                else -> ""
            })
        }
    }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
            minutes < 60 * 24 -> (minutes / 60).let { "$it hour${if (it == 1L) "" else "s"} ago" }
            else -> (minutes / (60 * 24)).let { "$it day${if (it == 1L) "" else "s"} ago" }
        }
    }

    private fun accountExists(name: String): Boolean =
        Files.exists(LoginManager.PLAYER_SAVE_DIRECTORY.resolve(StringFormatUtil.formatUsername(name) + ".json"))

    /** A click on the giveaway screen. */
    fun click(player: Player, component: Int) {
        if (!mayGive(player)) return
        val s = session(player)
        val offset = component - Giveaway.TILE_FIRST
        if (offset >= 0 && offset % Giveaway.TILE_STRIDE == 0 && offset / Giveaway.TILE_STRIDE < Giveaway.PRIZE_SLOTS) {
            val prize = store.prizes.getOrNull(s.page * Giveaway.PRIZE_SLOTS + offset / Giveaway.TILE_STRIDE) ?: return
            s.prizeItemId = prize.itemId
            render(player)
            return
        }
        when (component) {
            Giveaway.PREV -> { s.page--; render(player) }
            Giveaway.NEXT -> { s.page++; render(player) }
            Giveaway.WINNER_BOX -> player.sendInputString("Who won the giveaway?") { input ->
                session(player).winner = input.trim().take(12)
                if (player.interfaceHandler.isVisible(Giveaway.ID)) render(player)
            }
            Giveaway.GIVE -> askConfirm(player)
        }
    }

    /** Why [s]'s giveaway can't go ahead now, or null. */
    private fun refusal(player: Player, s: GiveawaySession): String? {
        val prize = selected(s)
        return when {
            s.winner.isEmpty() -> "Enter the winner's name first."
            prize == null -> "Pick a prize first."
            prize.stock <= 0 -> "There's no stock left of that prize."
            StringFormatUtil.formatUsername(s.winner) == player.username -> "You can't give a prize to yourself."
            !World.getPlayer(s.winner).isPresent && !accountExists(s.winner) -> "There's no player called ${s.winner}."
            else -> null
        }
    }

    private fun askConfirm(player: Player) {
        val s = session(player)
        refusal(player, s)?.let {
            player.sendMessage(it)
            return
        }
        GameInterface.GIVEAWAY_CONFIRM.open(player)
    }

    fun renderConfirm(player: Player) {
        val s = session(player)
        val prize = selected(s) ?: return
        val d = player.packetDispatcher
        d.sendComponentText(GiveawayConfirm.ID, GiveawayConfirm.QUESTION, "Give this prize to <col=ffffff>${s.winner}</col>?")
        d.sendComponentItem(GiveawayConfirm.ID, GiveawayConfirm.ITEM, prize.itemId, ITEM_ZOOM)
        d.sendComponentText(GiveawayConfirm.ID, GiveawayConfirm.ITEM_NAME, "${itemName(prize.itemId)} x${prize.amount}")
    }

    /** A click on the confirm screen: Confirm gives the prize, both go back to the giveaway screen. */
    fun clickConfirm(player: Player, component: Int) {
        when (component) {
            GiveawayConfirm.CONFIRM -> {
                give(player)
                GameInterface.GIVEAWAY.open(player)
            }
            GiveawayConfirm.CANCEL -> GameInterface.GIVEAWAY.open(player)
        }
    }

    private fun give(player: Player) {
        if (!mayGive(player)) return
        val s = session(player)
        refusal(player, s)?.let {
            player.sendMessage(it)
            return
        }
        val prize = selected(s) ?: return
        prize.stock--
        val item = Item(prize.itemId, prize.amount)
        val online = World.getPlayer(s.winner)
        val winnerName = online.map { it.name }.orElse(StringFormatUtil.formatString(s.winner))
        if (online.isPresent) {
            deliver(online.get(), item, player.name)
        } else {
            queue(StringFormatUtil.formatUsername(s.winner), item, player.name)
        }
        store.recent.add(GivenPrize(player.name, winnerName, item.id, item.amount, System.currentTimeMillis()))
        while (store.recent.size > RECENT_KEPT) store.recent.removeAt(0)
        save()
        log("${player.username} (${player.privilege.prettyName}) gave ${item.amount} x ${item.name} (${item.id}) to $winnerName" +
                (if (online.isPresent) "" else " (offline, queued)") + "; stock left ${prize.stock}")
        player.sendMessage("You gave ${item.amount} x ${item.name} to $winnerName.")
        WorldBroadcasts.sendMessage("${PlayerPrivilege.YOUTUBER.crown().crownTag}<col=ff0000><shad=000000>${player.name} gave " +
                "${item.amount} x ${item.name} to $winnerName in a giveaway!</shad></col>", BroadcastType.LOTTERY, false)
    }

    /** Puts [item] in [winner]'s bank; what doesn't fit waits for their next login. */
    private fun deliver(winner: Player, item: Item, from: String) {
        val result = winner.bank.add(Item(item.id, item.amount))
        val given = result.succeededAmount
        if (given > 0) winner.sendMessage("<col=ff0000>$from gave you ${given} x ${item.name} in a giveaway! It's in your bank.</col>")
        if (given < item.amount) {
            queue(winner.username, Item(item.id, item.amount - given), from)
            winner.sendMessage("<col=ff0000>Your bank is full: the rest of your giveaway prize comes on your next login.</col>")
        }
    }

    private fun queue(username: String, item: Item, from: String) {
        store.pending.getOrPut(username) { ArrayList() }.add(GiveawayDelivery(item.id, item.amount, from))
        save()
    }

    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        val player = event.player
        val waiting = store.pending.remove(player.username) ?: return
        save()
        for (d in waiting) deliver(player, Item(d.itemId, d.amount), d.from)
        log("Delivered ${waiting.size} queued giveaway prize(s) to ${player.username} at login")
    }

    /** ::giveawayitems [add id amount stock | remove id | stock id stock] (admins). */
    @JvmStatic
    fun manage(player: Player, args: Array<String>) {
        if (!mayManage(player)) return
        fun int(i: Int): Int? = args.getOrNull(i)?.replace(",", "")?.toIntOrNull()
        when (args.getOrNull(0)?.lowercase()) {
            "add" -> {
                val id = int(1); val amount = int(2) ?: 1; val stock = int(3) ?: 1
                if (id == null || id < 0 || amount < 1 || stock < 0) {
                    player.sendMessage("Usage: ::giveawayitems add <item id> <amount per give> <stock>")
                    return
                }
                val existing = store.prizes.firstOrNull { it.itemId == id }
                if (existing != null) {
                    existing.amount = amount
                    existing.stock = stock
                } else store.prizes.add(GiveawayPrize(id, amount, stock))
                save()
                log("${player.username} approved ${itemName(id)} ($id): $amount per give, stock $stock")
                player.sendMessage("Approved ${itemName(id)}: $amount per give, stock $stock.")
            }
            "remove" -> {
                val id = int(1)
                if (id == null || !store.prizes.removeIf { it.itemId == id }) {
                    player.sendMessage("Usage: ::giveawayitems remove <item id> (an approved prize)")
                    return
                }
                save()
                log("${player.username} removed ${itemName(id)} ($id) from the approved prizes")
                player.sendMessage("Removed ${itemName(id)} from the approved prizes.")
            }
            "stock" -> {
                val id = int(1); val stock = int(2)
                val prize = store.prizes.firstOrNull { it.itemId == id }
                if (prize == null || stock == null || stock < 0) {
                    player.sendMessage("Usage: ::giveawayitems stock <item id> <stock> (an approved prize)")
                    return
                }
                prize.stock = stock
                save()
                log("${player.username} set the stock of ${itemName(prize.itemId)} (${prize.itemId}) to $stock")
                player.sendMessage("${itemName(prize.itemId)} now has $stock in stock.")
            }
            else -> {
                if (store.prizes.isEmpty()) player.sendMessage("No prizes are approved yet.")
                for (p in store.prizes) player.sendMessage("${itemName(p.itemId)} (${p.itemId}): ${p.amount} per give, stock ${p.stock}")
                player.sendMessage("::giveawayitems add <item id> <amount per give> <stock> | remove <item id> | stock <item id> <stock>")
            }
        }
    }
}
