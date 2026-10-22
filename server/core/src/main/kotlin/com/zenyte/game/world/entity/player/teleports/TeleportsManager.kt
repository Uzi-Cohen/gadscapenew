package com.zenyte.game.world.entity.player.teleports

import com.gadscape.cache.interfaces.teleports.Category
import com.gadscape.cache.interfaces.teleports.Destination
import com.gadscape.cache.interfaces.teleports.TeleportsList
import com.zenyte.game.GameInterface
import com.zenyte.game.model.ui.InterfacePosition
import com.zenyte.game.world.entity.player.Player

/**
 * State of the GadScape teleport menu (interface 1601) for one player: selected category, favourites and the
 * previous destination (used by the magic stone "Previous teleport" op, the home nexus and the teleport wizard).
 *
 * @author Jire
 */
class TeleportsManager(
    @Transient
    private val player: Player
) {

    @Transient
    var selectedCategory: Category? = null

    @Transient
    var searchSelected: Boolean = false

    @Transient
    var previousDestination: Destination? = null

    /**
     * Saved by category and name so the previous teleport survives a relog (and a reordering of the menu). Names alone
     * are not unique ("Bandit Camp" is in Training and Cities, "Slayer Tower" in Training and Dungeons, at different tiles).
     */
    var previousDestinationName: String? = null

    /** Category of [previousDestinationName]; null in saves made before it was stored (restored by name only). */
    var previousDestinationCategory: String? = null

    var favoriteDestinations: MutableList<Int> = ArrayList()

    fun initialize(teleportsManager: TeleportsManager?) {
        if (teleportsManager == null) {
            return
        }
        @Suppress("SENSELESS_COMPARISON")
        if (teleportsManager.favoriteDestinations != null) {
            favoriteDestinations = teleportsManager.favoriteDestinations
        }
        val name = teleportsManager.previousDestinationName
        if (name != null) {
            val categoryName = teleportsManager.previousDestinationCategory
            val destination = findDestination(categoryName, name)
            previousDestination = destination
            previousDestinationName = name
            previousDestinationCategory = if (destination != null) categoryOf(destination)?.name else categoryName
        }
    }

    /** Re-sends the "Previous teleport: <name>" label after login. */
    fun refreshPreviousTeleportLabel() {
        val destination = previousDestination ?: return
        player.packetDispatcher.sendClientScript(PREVIOUS_TELEPORT_LABEL_SCRIPT, destination.name)
    }

    fun setPreviousTeleport(destination: Destination) {
        previousDestination = destination
        previousDestinationName = destination.name
        previousDestinationCategory = categoryOf(destination)?.name
        player.packetDispatcher.sendClientScript(PREVIOUS_TELEPORT_LABEL_SCRIPT, destination.name)
    }

    fun attemptTeleport(destination: Destination?) {
        if (destination == null) {
            player.sendMessage("You don't have a previous teleport.")
            return
        }

        if (player.isLocked || player.interfaceHandler.containsInterface(InterfacePosition.CENTRAL)) {
            player.sendMessage("You can't do that right now.")
            return
        }

        DestinationTeleport(destination).teleport(player)
    }

    fun attemptOpen() {
        if (player.isLocked) {
            player.sendMessage("You can't do that right now.")
            return
        }
        GameInterface.TELEPORTS.open(player)
    }

    /** Opens the menu on [category] (by its lower-case name, e.g. "training teleports"); falls back to the last category. */
    fun attemptOpen(categoryName: String) {
        val category = TeleportsList.categories[categoryName.lowercase()]
        if (category != null) {
            selectedCategory = category
            player.varManager.sendVar(CATEGORY_VARP, category.id)
        }
        attemptOpen()
    }

    fun updateFavorites() {
        val favoriteData = IntArray(FAVORITE_VARPS.size)

        @Suppress("SENSELESS_COMPARISON")
        if (favoriteDestinations != null) {
            for (destinationId in favoriteDestinations) {
                val index = destinationId shr 5
                val pos = destinationId and 31
                if (index >= favoriteData.size) {
                    favoriteDestinations.clear()
                    break
                } else {
                    favoriteData[index] = favoriteData[index] or (1 shl pos)
                }
            }
        }

        for (i in FAVORITE_VARPS.indices) {
            player.varManager.sendVar(FAVORITE_VARPS[i], favoriteData[i])
        }
    }

    companion object {
        private fun categoryOf(destination: Destination): Category? =
            TeleportsList.teleports.categories.firstOrNull { category -> category.destinations.any { it === destination } }
                ?: TeleportsList.teleports.categories.firstOrNull { category -> destination in category.destinations }

        /** [name] in the category called [categoryName]; by name alone for old saves or a renamed category. */
        private fun findDestination(categoryName: String?, name: String): Destination? {
            if (categoryName != null) {
                val category = TeleportsList.teleports.categories.firstOrNull { it.name.equals(categoryName, ignoreCase = true) }
                category?.destinations?.firstOrNull { it.name == name }?.let { return it }
            }
            return TeleportsList.allTeleports.firstOrNull { it.name == name }
        }

        /**
         * Category varp: written by the server (spellbook/nexus) and by client script 10520, read by scripts 10504/10527.
         * It is the vanilla temp varp 261 (also used by a few other interfaces); see content-work/teleports-shops/CACHE_NEEDS.md
         * before moving it, the client scripts must change in the same release.
         */
        const val CATEGORY_VARP = 261

        /**
         * Favourite bit-sets, 32 destinations per varp, read by client script 10522 in this order (allTeleports index
         * shr 5 selects the varp). 7 varps = 224 destinations; TeleportInterfaceDialog refuses favourites beyond that.
         */
        @JvmField
        val FAVORITE_VARPS = intArrayOf(263, 264, 265, 266, 262, 3808, 3809)

        /** Sets varcstr 1500, the magic stone's "Previous teleport: <name>" op text. */
        const val PREVIOUS_TELEPORT_LABEL_SCRIPT = 10592
    }

}
