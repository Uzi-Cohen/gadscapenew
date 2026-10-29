package com.gadscape.game.content.store

import com.google.common.eventbus.Subscribe
import com.zenyte.game.item.Item
import com.zenyte.game.world.entity.player.GameCommands.Command
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import com.zenyte.plugins.events.ServerLaunchEvent
import mgi.types.config.items.ItemDefinitions

/**
 * Admin commands of the store screen (registered at server start, like the other plugin commands):
 *
 * - `::storeoffer <text>` sets the Weekly offer text; `::storeoffer default` puts the default back; `::storeoffer` shows it.
 * - `::storeofferreward <item id> <every $>` sets what the offer gives (1 item per that many dollars paid this week,
 *   through the claim list); `::storeofferreward none` stops it. The text doesn't change the reward, and back.
 */
object StoreCommands {

    @Subscribe
    @JvmStatic
    fun onServerLaunch(@Suppress("UNUSED_PARAMETER") event: ServerLaunchEvent) = register()

    fun register() {
        Command(PlayerPrivilege.ADMINISTRATOR, "storeoffer", "Sets the store's weekly offer text (::storeoffer default to reset).") cmd@{ p, args ->
            val text = args.joinToString(" ").trim()
            when {
                text.isEmpty() -> {
                    p.sendMessage("Weekly offer: ${StoreWeek.offerText()}")
                    p.sendMessage("::storeoffer <text> | ::storeoffer default | ::storeofferreward <item id> <every \$> | none")
                }
                text.equals("default", ignoreCase = true) -> {
                    StoreWeek.setOffer(null)
                    p.sendMessage("The weekly offer is the default again: ${StoreWeek.DEFAULT_OFFER}")
                }
                else -> {
                    StoreWeek.setOffer(text.take(200))
                    p.sendMessage("Weekly offer set: ${text.take(200)}")
                }
            }
        }

        Command(PlayerPrivilege.ADMINISTRATOR, "storeofferreward", "Sets what the weekly offer gives per dollars paid.") cmd@{ p, args ->
            if (args.firstOrNull()?.equals("none", ignoreCase = true) == true) {
                StoreWeek.setOfferReward(-1, 0)
                p.sendMessage("The weekly offer gives nothing now (the text stays).")
                return@cmd
            }
            val id = args.getOrNull(0)?.toIntOrNull()
            val dollars = args.getOrNull(1)?.removePrefix("$")?.toIntOrNull()
            if (id == null || ItemDefinitions.get(id) == null || dollars == null || dollars <= 0) {
                val o = StoreWeek.offer
                p.sendMessage(if (o.rewardItemId < 0 || o.everyCents <= 0) "The weekly offer gives nothing."
                    else "The weekly offer gives 1 x ${Item(o.rewardItemId).name} per \$${o.everyCents / 100} paid this week.")
                p.sendMessage("Usage: ::storeofferreward <item id> <every \$> | ::storeofferreward none")
                return@cmd
            }
            StoreWeek.setOfferReward(id, dollars * 100)
            p.sendMessage("The weekly offer gives 1 x ${Item(id).name} per \$$dollars paid this week.")
        }
    }
}
