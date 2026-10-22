package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.lobby.LobbyClock
import com.gadscape.raids.tfa.lobby.LobbyConfig
import com.zenyte.game.item.Item
import com.zenyte.game.model.item.pluginextensions.ItemPlugin
import com.zenyte.game.model.item.pluginextensions.bindKt
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.actions.NPCPlugin
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import org.slf4j.LoggerFactory

/**
 * The "Guardian of Armadyl" messenger, npc 16699 `tfa_messenger` (stand 813, op Talk-to) [CACHE], and the Alliance's
 * message, obj 34657 (Read / Destroy) [DOC wiki Alliance's message] (spec §3.1).
 *
 * Live: once WGS is complete the messenger greets the player and hands over the message [DOC wiki Guardian of
 * Armadyl (messenger)]. Here: when the attunement gate opens ([TfaEntryGate.check]) the messenger appears next to the
 * player for `lobby.json messenger.despawnTicks`, hands over the message and the miniquest starts (16426 = 1).
 * The dialogue wording is [HOUSE] (the wiki transcript is not public yet).
 */
object MessengerService {

    private val log = LoggerFactory.getLogger(MessengerService::class.java)

    /** Player attribute: the messenger delivered the message once (a lost message is replaced by Talk-to). */
    const val DELIVERED_ATTRIBUTE = "tfa_alliance_message_delivered"

    private val spawned = HashMap<Player, NPC>()

    @JvmStatic
    fun deliver(player: Player) {
        val cfg = TfaConfig.current[LobbyConfig].messenger
        Miniquest.onStarted(player)
        if (player.getBooleanAttribute(DELIVERED_ATTRIBUTE)) return
        player.putBooleanAttribute(DELIVERED_ATTRIBUTE, true)
        giveMessage(player)
        if (!cfg.enabled) return
        try {
            spawned.remove(player)?.let { if (!it.isFinished) it.finish() }
            val tile = freeTileNear(player.location) ?: return
            val messenger = NPC(TfaIds.Npc.MESSENGER, tile, Direction.SOUTH, 0)
            messenger.spawn()
            messenger.setFaceEntity(player)
            spawned[player] = messenger
            LobbyClock.queue.cancel(tag(player))
            LobbyClock.queue.schedule(cfg.despawnTicks, tag(player)) { remove(player) }
            player.dialogue(messenger) {
                npc("Guardian of Armadyl", "${player.name}! The Guthixian Alliance sends word. Ivy Sophista asked me to deliver this to you in person.")
                item(Item(TfaIds.Obj.ALLIANCES_MESSAGE), "The Guardian of Armadyl hands you a message.")
                npc("Guardian of Armadyl", "Read it, then make for the Ancient Guthixian Temple. The Alliance needs you.")
            }
        } catch (e: Exception) {
            log.warn("Fractured Archive messenger could not appear for {}", player.username, e)
        }
    }

    /** Talk-to on the messenger. */
    fun talk(player: Player, target: NPC) {
        val missing = !player.containsItem(TfaIds.Obj.ALLIANCES_MESSAGE) && Miniquest.stage(player) < Miniquest.ENTERED_LOBBY
        player.dialogue(target) {
            if (missing) {
                npc("Guardian of Armadyl", "Lost the Alliance's message already? Here, take another copy.").executeAction { giveMessage(player) }
            } else {
                npc("Guardian of Armadyl", "The Alliance awaits you at the Ancient Guthixian Temple. Crawl through the opening beneath it.")
            }
        }
    }

    @JvmStatic
    fun remove(player: Player) {
        LobbyClock.queue.cancel(tag(player))
        spawned.remove(player)?.let { if (!it.isFinished) it.finish() }
    }

    private fun giveMessage(player: Player) {
        if (player.containsItem(TfaIds.Obj.ALLIANCES_MESSAGE)) return
        player.inventory.addOrDrop(Item(TfaIds.Obj.ALLIANCES_MESSAGE))
    }

    private fun tag(player: Player) = "messenger:${player.username}"

    private fun freeTileNear(origin: Location): Location? {
        for ((dx, dy) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1, 1 to 1, -1 to 1, 1 to -1, -1 to -1)) {
            val x = origin.x + dx
            val y = origin.y + dy
            if (World.isFloorFree(origin.plane, x, y)) return Location(x, y, origin.plane)
        }
        return null
    }

    /** The message text [HOUSE: written from the intro transcript; the item transcript is not public yet]. */
    val MESSAGE_LINES = listOf(
        "Adventurer,",
        "The Stone of Jas was not the only thing Guthix hid beneath his temple. Deep below lies an Archive, built to keep his most dangerous artefacts out of the wrong hands.",
        "Lucien's followers are preparing to attack it, and they are not alone. The Guardians of Armadyl and the Temple Knights are mobilising, but we need you.",
        "Meet us at the Ancient Guthixian Temple. You will find a way into the Archive through the opening beneath it.",
        "- Ivy Sophista, on behalf of the Guthixian Alliance",
    )
}

/** Talk-to on the messenger 16699. Plugin-scanned. */
class MessengerPlugin : NPCPlugin() {
    override fun handle() {
        bind("Talk-to") { player, npc -> MessengerService.talk(player, npc) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.MESSENGER)
}

/** "Read" on the Alliance's message 34657: shows the letter and marks it read (the intro scene branches on it). */
class AlliancesMessagePlugin : ItemPlugin() {
    override fun handle() {
        bindKt("Read") {
            player.putBooleanAttribute(Miniquest.MESSAGE_READ_ATTRIBUTE, true)
            Miniquest.onStarted(player)
            player.dialogue {
                MessengerService.MESSAGE_LINES.forEach { plain(it) }
            }
        }
    }

    override fun getItems(): IntArray = intArrayOf(TfaIds.Obj.ALLIANCES_MESSAGE)
}
