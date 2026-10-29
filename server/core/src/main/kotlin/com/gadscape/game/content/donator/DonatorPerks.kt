package com.gadscape.game.content.donator

import com.gadscape.website.claimedPinCents
import com.google.common.eventbus.Subscribe
import com.zenyte.game.GameInterface
import com.zenyte.game.content.donation.DonatorZoneTeleports
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport
import com.zenyte.game.content.skills.magic.spells.teleports.TeleportType
import com.zenyte.game.item.Item
import com.zenyte.game.util.Colour
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.GameCommands
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.entity.player.privilege.MemberRank
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import com.zenyte.game.world.region.DynamicArea
import com.zenyte.game.world.region.area.plugins.DeathPlugin
import com.zenyte.game.world.region.area.plugins.TeleportPlugin
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import com.zenyte.plugins.events.ServerLaunchEvent
import java.util.concurrent.TimeUnit

private var Player.specCommandAt: Long by persistentAttribute("donator_spec_at", 0L)
private var Player.unskullCommandAt: Long by persistentAttribute("donator_unskull_at", 0L)

/**
 * The donator commands and the rules they share: ::bank (Super+), ::spec / ::hp / ::unskull (Legendary+, with the
 * rank's cooldown), ::settele and ::tele 1-5 (Divine), ::dzone and ::donator (every rank).
 */
object DonatorPerks {

    const val SAVED_TELEPORTS = 5

    /**
     * Why [player] can't use a donator shortcut where they stand (bank, restores, saved teleports), or null when they
     * can: not in the Wilderness, a dangerous area or an instance (bosses, raids, minigames), and not fighting.
     */
    @JvmStatic
    fun unsafeReason(player: Player): String? {
        val area = player.area
        return when {
            WildernessArea.isWithinWilderness(player) -> "You can't do that in the Wilderness."
            area is DynamicArea -> "You can't do that inside an instance."
            area is DeathPlugin && !area.isSafe -> "You can't do that here."
            player.isUnderCombat -> "You can't do that during combat."
            else -> null
        }
    }

    /** ::bank: staff anywhere, Super Gad Donators and up in safe places. */
    @JvmStatic
    fun bank(player: Player) {
        if (player.isLocked) return
        if (!player.isStaff) {
            if (!player.memberRank.hasBankCommand()) {
                player.sendMessage("Banking from anywhere is a ${MemberRank.SUPER} perk. Type ::donator to see the ranks.")
                return
            }
            unsafeReason(player)?.let { player.sendMessage(it); return }
        }
        GameInterface.BANK.open(player)
    }

    @JvmStatic
    fun restoreSpecial(player: Player) {
        if (!canRestore(player)) return
        val wait = waitSeconds(player.specCommandAt, player.memberRank.specCooldownSeconds)
        if (wait > 0) {
            player.sendMessage("You can restore again in $wait seconds.")
            return
        }
        player.combatDefinitions.specialEnergy = 100
        player.specCommandAt = System.currentTimeMillis()
        player.sendMessage(Colour.RS_GREEN.wrap("Your special attack has been restored."))
    }

    /** ::hp: health and prayer back to full; shares ::spec's cooldown. */
    @JvmStatic
    fun restoreHealth(player: Player) {
        if (!canRestore(player)) return
        val wait = waitSeconds(player.specCommandAt, player.memberRank.specCooldownSeconds)
        if (wait > 0) {
            player.sendMessage("You can restore again in $wait seconds.")
            return
        }
        val hitpoints = player.skills.getLevelForXp(SkillConstants.HITPOINTS)
        if (player.hitpoints < hitpoints) player.setHitpoints(hitpoints)
        val prayer = player.skills.getLevelForXp(SkillConstants.PRAYER)
        if (player.prayerManager.prayerPoints < prayer) player.prayerManager.setPrayerPoints(prayer)
        player.specCommandAt = System.currentTimeMillis()
        player.sendMessage(Colour.RS_GREEN.wrap("Your health and prayer have been restored."))
    }

    @JvmStatic
    fun unskull(player: Player) {
        if (!canRestore(player)) return
        if (!player.variables.isSkulled) {
            player.sendMessage("You don't have a skull.")
            return
        }
        val wait = waitSeconds(player.unskullCommandAt, player.memberRank.unskullCooldownSeconds)
        if (wait > 0) {
            player.sendMessage("You can remove your skull again in $wait seconds.")
            return
        }
        player.variables.setSkull(false)
        player.unskullCommandAt = System.currentTimeMillis()
        player.sendMessage(Colour.RS_GREEN.wrap("Your skull has been removed."))
    }

    private fun canRestore(player: Player): Boolean {
        if (player.isLocked || player.isDead) return false
        if (!player.memberRank.hasRestoreCommands()) {
            player.sendMessage("This command is a ${MemberRank.LEGENDARY} perk. Type ::donator to see the ranks.")
            return false
        }
        unsafeReason(player)?.let { player.sendMessage(it); return false }
        return true
    }

    private fun waitSeconds(lastUse: Long, cooldownSeconds: Int): Long {
        val left = lastUse + TimeUnit.SECONDS.toMillis(cooldownSeconds.toLong()) - System.currentTimeMillis()
        return if (left <= 0) 0 else TimeUnit.MILLISECONDS.toSeconds(left + 999)
    }

    // ---- ::settele / ::tele 1-5 (Divine) ----------------------------------------------------------------------

    private fun savedKey(slot: Int) = "donator_teleport_$slot"

    @JvmStatic
    fun isTeleportSlot(arg: String): Boolean = arg.toIntOrNull()?.let { it in 1..SAVED_TELEPORTS } == true

    @JvmStatic
    fun saveTeleport(player: Player, args: Array<String>) {
        if (!player.memberRank.hasSavedTeleports()) {
            player.sendMessage("Saved teleports are a ${MemberRank.DIVINE} perk. Type ::donator to see the ranks.")
            return
        }
        val slot = args.firstOrNull()?.toIntOrNull()
        if (slot == null || slot !in 1..SAVED_TELEPORTS) {
            player.sendMessage("Use ::settele 1-$SAVED_TELEPORTS to save where you stand, then ::tele 1-$SAVED_TELEPORTS to go back.")
            return
        }
        unsafeReason(player)?.let { player.sendMessage(it); return }
        if (player.area is TeleportPlugin) {
            player.sendMessage("You can't save a teleport here.")
            return
        }
        player.addAttribute(savedKey(slot), player.location.positionHash)
        player.sendMessage(Colour.RS_GREEN.wrap("Teleport $slot saved: ${player.x}, ${player.y}, ${player.plane}. Type ::tele $slot to come back."))
    }

    @JvmStatic
    fun teleport(player: Player, slot: Int) {
        if (!player.memberRank.hasSavedTeleports()) {
            player.sendMessage("Saved teleports are a ${MemberRank.DIVINE} perk. Type ::donator to see the ranks.")
            return
        }
        if (player.isLocked) return
        val hash = player.getNumericAttribute(savedKey(slot)).toInt()
        if (hash == 0) {
            player.sendMessage("You haven't saved teleport $slot yet. Stand where you want it and type ::settele $slot.")
            return
        }
        savedTeleport(Location(hash)).teleport(player)
    }

    private fun savedTeleport(destination: Location) = object : Teleport {
        override fun getType() = TeleportType.GADSCAPE_PORTAL_TELEPORT
        override fun getDestination() = destination
        override fun getLevel() = 0
        override fun getExperience() = 0.0
        override fun getRandomizationDistance() = 0
        override fun getRunes(): Array<Item>? = null
        override fun getWildernessLevel() = 20
        override fun isCombatRestricted() = true
    }

    // ---- registration ------------------------------------------------------------------------------------------

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        GameCommands.Command(PlayerPrivilege.PLAYER, arrayOf("donator", "donor", "donators"),
            "Shows the donator ranks and their perks.") { p, _ -> DonatorBenefits.open(p) }
        GameCommands.Command(PlayerPrivilege.PLAYER, "dzone", "Teleports you to the donator zone of your rank.") { p, _ ->
            if (p.isLocked) return@Command
            val zone = DonatorZoneTeleports.forRank(p.memberRank)
            if (zone == null) p.sendMessage("The donator zone is for donators. Type ::donator to see the ranks.")
            else zone.teleport(p)
        }
        GameCommands.Command(PlayerPrivilege.PLAYER, "unskull", "Removes your skull (Legendary Gad Donators and up).") { p, _ -> unskull(p) }
        GameCommands.Command(PlayerPrivilege.PLAYER, "settele", "Saves a teleport spot: ::settele 1-5 (Divine Gad Donators).") { p, args ->
            saveTeleport(p, args)
        }
        GameCommands.Command(PlayerPrivilege.DEVELOPER, "setdonated",
            "Sets a player's donated total (pins part) in dollars, then gives the rank it reaches. Arguments: dollars [player]") { p, args ->
            val dollars = args.firstOrNull()?.toDoubleOrNull()
            if (dollars == null || dollars < 0) {
                p.sendMessage("Use ::setdonated <dollars> [player], e.g. ::setdonated 189.95")
                return@Command
            }
            val target = if (args.size > 1) World.getPlayer(args.drop(1).joinToString(" ")).orElse(null) else p
            if (target == null) {
                p.sendMessage("That player isn't online.")
                return@Command
            }
            target.claimedPinCents = Math.round(dollars * 100).toInt()
            DonatorRanks.refresh(target, announce = true)
            p.sendMessage("${target.name}: total donated ${DonatorRanks.formatUsd(target.totalDonatedCents)}, rank ${target.memberRank}.")
        }
    }
}
