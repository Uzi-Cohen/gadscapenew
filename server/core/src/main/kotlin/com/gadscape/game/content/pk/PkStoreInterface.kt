package com.gadscape.game.content.pk

import com.gadscape.game.content.kits.KitsTabCache
import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.Interface
import com.zenyte.game.world.entity.player.Player

/** Clicks in the PK store window; registered by [PkStore.open] the first time the store opens. */
class PkStoreInterface : Interface() {

    override fun attach() {
        put(KitsTabCache.STORE_CLOSE, "Close")
        for (i in 0 until KitsTabCache.STORE_CATEGORIES) put(KitsTabCache.STORE_FIRST_CATEGORY + i * 2 + 1, "Category $i")
        for (slot in 0 until KitsTabCache.STORE_SLOTS) put(KitsTabCache.STORE_FIRST_ITEM + slot, "Item $slot")
        put(KitsTabCache.STORE_PREV, "Previous")
        put(KitsTabCache.STORE_NEXT, "Next")
    }

    override fun open(player: Player) = PkStore.open(player)

    override fun build() {
        bind("Close") { player: Player -> player.interfaceHandler.closeInterfaces() }
        for (i in 0 until KitsTabCache.STORE_CATEGORIES) bind("Category $i") { player: Player -> PkStore.select(player, i) }
        bind("Previous") { player: Player -> PkStore.turnPage(player, -1) }
        bind("Next") { player: Player -> PkStore.turnPage(player, 1) }
        for (slot in 0 until KitsTabCache.STORE_SLOTS) {
            bind("Item $slot") { player: Player, _: Int, _: Int, option: Int ->
                val entry = PkStore.entryAt(player, slot) ?: return@bind
                when (option) {
                    1 -> PkStore.value(player, entry)
                    2 -> PkStore.buy(player, entry, 1)
                    3 -> PkStore.buy(player, entry, 5)
                    4 -> PkStore.buy(player, entry, 10)
                    5 -> player.sendInputInt("How many would you like to buy?") { amount -> PkStore.buy(player, entry, amount) }
                }
            }
        }
    }

    override fun getInterface(): GameInterface = GameInterface.PK_STORE
}
