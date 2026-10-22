package com.gadscape.game.content.bountyhunter.teleport

import com.gadscape.game.content.bountyhunter.getTarget
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport
import com.zenyte.game.content.skills.magic.spells.teleports.TeleportType
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.model.item.pluginextensions.ItemPlugin
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.player.Player

/**
 * Target teleport tablet: same rules as the spell (see [TeleportToTarget]), tablet break animation, the tablet is
 * only used up when the teleport actually starts.
 *
 * @author Glabay | Glabay-Studios (original)
 */
class BountyHunterTargetTab : ItemPlugin() {
    override fun handle() {
        bind("Break") { player, _, _ ->
            if (!TeleportToTarget.canTeleportToTarget(player)) return@bind
            val target = player.getTarget().orElse(null) ?: return@bind
            TeleportType.TABLET_TELEPORT.structure.teleport(player, TargetTabletTeleport(player, target))
        }
    }

    override fun getItems(): IntArray = intArrayOf(ItemId.TARGET_TELEPORT)

    private class TargetTabletTeleport(private val caster: Player, private val target: Player) : Teleport {
        private val runes = arrayOf(Item(ItemId.TARGET_TELEPORT))
        override fun getType(): TeleportType = TeleportType.TABLET_TELEPORT
        override fun getDestination(): Location =
            if (target.isFinished || target.isNulled || caster.getTarget().orElse(null) != target) Location(caster.location)
            else TeleportToTarget.landingLocation(target)
        override fun getLevel(): Int = 0
        override fun getExperience(): Double = 0.0
        override fun getRandomizationDistance(): Int = 0
        override fun getRunes(): Array<Item> = runes
        override fun getWildernessLevel(): Int = Int.MIN_VALUE
        override fun isCombatRestricted(): Boolean = false
        override fun onUsage(player: Player) = TeleportToTarget.countTeleport(player)
    }
}
