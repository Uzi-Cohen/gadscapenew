package com.gadscape.game.content.store

import com.google.common.eventbus.Subscribe
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.container.RequestResult
import com.zenyte.game.world.entity.player.privilege.GameMode
import com.zenyte.plugins.events.LoginEvent
import mgi.utilities.StringFormatUtil
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One item waiting in a player's claim list: [amount] of [itemId], from [source] ("Lottery", "Promotion checkpoint $25"...). */
class PendingReward(var id: String = "", var itemId: Int = 0, var amount: Int = 1, var source: String = "", var time: Long = 0)

/**
 * Items waiting for a player to claim them (data/pending-rewards.json): lottery wins, promotion checkpoint and weekly
 * offer rewards, gifts given while the player was offline. They show in the store's Claim purchases panel (::donate)
 * next to the website's queued purchases; claiming one puts it in the inventory (the bank when that's full) and takes
 * it off the list.
 *
 * Call [add] from anywhere on the game thread; the player doesn't have to be online.
 */
object PendingRewards {
    private val logger = LoggerFactory.getLogger(PendingRewards::class.java)
    private val FILE: Path = Paths.get("data", "pending-rewards.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var loaded: MutableMap<String, MutableList<PendingReward>>? = null

    private val rewards: MutableMap<String, MutableList<PendingReward>>
        get() {
            loaded?.let { return it }
            val m: MutableMap<String, MutableList<PendingReward>> = try {
                if (Files.exists(FILE)) Files.newBufferedReader(FILE).use {
                    gson.fromJson<LinkedHashMap<String, MutableList<PendingReward>>>(it,
                        object : TypeToken<LinkedHashMap<String, MutableList<PendingReward>>>() {}.type)
                } else null
            } catch (e: Exception) {
                logger.error("Failed to read {}", FILE, e)
                null
            } ?: LinkedHashMap()
            loaded = m
            return m
        }

    private fun key(username: String) = StringFormatUtil.formatUsername(username)

    private fun save() {
        try {
            Files.createDirectories(FILE.toAbsolutePath().parent)
            val tmp = FILE.resolveSibling(FILE.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(rewards))
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to write {}", FILE, e)
        }
    }

    /**
     * Puts [item] on [username]'s claim list ([source] is shown with it). An online player is told; an offline one
     * hears about it at the next login.
     */
    @JvmStatic
    @Synchronized
    fun add(username: String, item: Item, source: String) {
        if (item.id < 0 || item.amount <= 0) return
        val k = key(username)
        rewards.getOrPut(k) { ArrayList() }.add(PendingReward(UUID.randomUUID().toString(), item.id, item.amount, source, System.currentTimeMillis()))
        save()
        logger.info("Pending reward for {}: {} x {} ({}) from {}", k, item.amount, item.name, item.id, source)
        World.getPlayer(username).ifPresent { p ->
            p.sendMessage(Colour.RS_GREEN.wrap("${item.amount} x ${item.name} ($source) is waiting for you - type ::donate to claim it."))
            if (p.interfaceHandler.isVisible(StoreUi.ID)) StoreScreen.render(p)
        }
    }

    /** What [username] has waiting, oldest first. */
    @JvmStatic
    @Synchronized
    fun list(username: String): List<PendingReward> = rewards[key(username)]?.toList().orEmpty()

    @JvmStatic
    @Synchronized
    fun count(username: String): Int = rewards[key(username)]?.size ?: 0

    /** Gives [player] the waiting reward [id]: inventory, else the bank (ultimate ironmen: inventory only). */
    @JvmStatic
    @Synchronized
    fun claim(player: Player, id: String): Boolean {
        val list = rewards[key(player.username)] ?: return false
        val reward = list.firstOrNull { it.id == id } ?: return false
        val item = Item(reward.itemId, reward.amount)
        if (player.inventory.hasSpaceFor(item) && player.inventory.addItem(item).result == RequestResult.SUCCESS) {
            done(player, list, reward, item, "")
            return true
        }
        val given = if (player.gameMode == GameMode.ULTIMATE_IRON_MAN) 0 else player.bank.add(item).succeededAmount
        if (given <= 0) {
            player.sendMessage(Colour.RED.wrap("You need room in your inventory${if (player.gameMode == GameMode.ULTIMATE_IRON_MAN) "" else " or bank"} to claim ${item.name}."))
            return false
        }
        if (given < reward.amount) {
            // the bank took part of it: the rest stays on the list
            reward.amount -= given
            save()
            player.sendMessage("Claimed: ${Colour.RS_GREEN.wrap("$given x ${item.name}")} (sent to your bank). Your bank is full: ${reward.amount} more are still waiting.")
            return true
        }
        done(player, list, reward, item, " (sent to your bank)")
        return true
    }

    private fun done(player: Player, list: MutableList<PendingReward>, reward: PendingReward, item: Item, where: String) {
        list.remove(reward)
        if (list.isEmpty()) rewards.remove(key(player.username))
        save()
        logger.info("{} claimed pending reward {} x {} ({}) from {}", player.username, item.amount, item.name, item.id, reward.source)
        player.sendMessage("Claimed: ${Colour.RS_GREEN.wrap("${item.amount} x ${item.name}")}$where.")
    }

    @Subscribe
    @JvmStatic
    fun onLogin(event: LoginEvent) {
        val player = event.player
        val n = count(player.username)
        if (n > 0) player.sendMessage(Colour.RS_GREEN.wrap("You have $n reward${if (n == 1) "" else "s"} waiting - type ::donate to claim them."))
        StoreWeek.onLogin(player)
    }
}
