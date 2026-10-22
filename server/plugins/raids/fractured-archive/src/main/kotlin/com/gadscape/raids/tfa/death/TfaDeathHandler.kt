package com.gadscape.raids.tfa.death

import com.gadscape.network.rsprot.CamTarget
import com.gadscape.raids.tfa.hud.BossHpHud
import com.gadscape.raids.tfa.raid.RaidGeometry
import com.gadscape.raids.tfa.raid.RaidWorld
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import org.slf4j.LoggerFactory

/**
 * Individual deaths inside the raid (spec §3.8, WU-15; "identical to Theatre of Blood" [DOC]):
 * - no item loss, no gravestone, no respawn: the death animation (836, the stock player death), then the member is a
 *   spectator of the room until it completes: hidden, locked, orb 30, varbit 16405 = 1 (the HUD sends both), camera
 *   on a living member (or the room), HP HUD of the room's boss kept;
 * - a dead member waits out of the fight: in the antechamber of a paired room, in the Ocellus corridor, or where they
 *   fell in the nexus (the Guardian targets living members only);
 * - stats: Deaths +1 (room, Overall) through the raid listeners; contribution −20 % of the banked points (§3.10);
 * - Hardcore status: any death in the death zone loses it [DOC] (the stock HCIM / HCGIM handling runs now);
 * - when the encounter completes the raid calls [revive]: full HP and prayer, stats reset (no heal for survivors).
 */
object TfaDeathHandler {

    private val log = LoggerFactory.getLogger(TfaDeathHandler::class.java)

    /** Called from the raid area's `sendDeath`. Returns true (the stock death never runs inside a raid). */
    @JvmStatic
    fun die(raid: TfaRaid, member: TfaRaidMember, room: TfaRoomId?, source: Entity?): Boolean {
        val player = member.player
        member.dead = true
        member.spectating = false
        member.diedIn = room
        member.deathTile = raid.instance.toStatic(player.location)
        member.killer = source
        if (room != null) member.deaths[room] = (member.deaths[room] ?: 0) + 1
        raid.ledger.applyDeathPenalty(member.username)
        player.animation = Animation.STOP
        player.lock()
        player.stopAll()
        try {
            if (player.prayerManager.isActive(Prayer.RETRIBUTION)) player.prayerManager.applyRetributionEffect(source)
        } catch (e: Exception) {
            log.warn("Fractured Archive: retribution failed for {}", player.username, e)
        }
        try {
            player.deathMechanics.checkHardcoreIronManDeath()
        } catch (e: Exception) {
            log.warn("Fractured Archive: hardcore check failed for {}", player.username, e)
        }
        val delay = raid.framework.death.spectateDelayTicks
        val tag = "death:${member.username}"
        raid.scheduler.schedule(1, tag) {
            if (member.dead && !member.left) player.animation = DEATH
        }
        raid.scheduler.schedule(delay, tag) {
            if (member.dead && !member.left) toSpectate(raid, member, room)
        }
        raid.afterDeath(member, room)
        return true
    }

    /** The spectate state (after the death animation). */
    private fun toSpectate(raid: TfaRaid, member: TfaRaidMember, room: TfaRoomId?) {
        val player = member.player
        player.reset()
        player.blockIncomingHits()
        player.animation = Animation.STOP
        if (player.variables.isSkulled) player.variables.setSkull(false)
        player.isHidden = true
        player.appearance.setInvisible(true)
        player.setMaximumTolerance(true)
        player.lock()
        // Out of the fight: antechamber / corridor; in the nexus they stay where they fell.
        when {
            room == TfaRoomId.OCELLUS -> RaidWorld.move(player, RaidWorld.landing(raid.translate(raid.config.raid.tiles.ocellusCorridor), 3))
            room != null && room.isPaired -> {
                val tile = RaidGeometry.antechamber(room, raid.config.raid.nexus.antechamberInset)
                RaidWorld.move(player, RaidWorld.landing(raid.translate(tile), 3))
            }
        }
        member.spectating = true
        follow(raid, member, room)
        if (room != null) raid.runtime(room)?.let { BossHpHud.showCurrent(player, it) }
        raid.hud.markDirty()
        player.sendMessage("Oh dear, you have died. If your party survives the room, you will be revived.")
    }

    /** Camera of a dead member: the first living member in the room (`framework.json spectate.deadFollowMember`), else the room. */
    @JvmStatic
    fun follow(raid: TfaRaid, member: TfaRaidMember, room: TfaRoomId?) {
        val viewer = member.player
        val target = if (raid.framework.spectate.deadFollowMember && room != null) raid.membersInArena(room).firstOrNull() else null
        if (target != null) {
            member.spectatedSlot = target.slot
            CamTarget.player(viewer, target.player)
        } else {
            member.spectatedSlot = -1
            val centre = RaidGeometry.spectateCentre(room ?: TfaRoomId.GUARDIAN)
            CamTarget.tile(viewer, raid.translate(centre))
        }
        raid.hud.markDirty()
    }

    /** §3.8: revived when the encounter completes — full HP and prayer, stats reset. The caller places them. */
    @JvmStatic
    fun revive(raid: TfaRaid, member: TfaRaidMember) {
        if (!member.dead) return
        raid.scheduler.cancel("death:${member.username}")
        restoreAppearance(member)
        val player = member.player
        player.reset()
        player.hitpoints = player.maxHitpoints
        player.prayerManager.setPrayerPoints(player.skills.getLevelForXp(SkillConstants.PRAYER))
        member.dead = false
        member.spectating = false
        member.diedIn = null
        raid.hud.markDirty()
    }

    /** Undoes the spectate state (revive, leaving the raid while dead). */
    @JvmStatic
    fun restoreAppearance(member: TfaRaidMember) {
        val player = member.player
        if (player.isFinished) return
        player.isHidden = false
        player.appearance.setInvisible(false)
        player.setMaximumTolerance(false)
        player.appearance.resetRenderAnimation()
        player.animation = Animation.STOP
        player.unlock()
        member.spectatedSlot = -1
        try {
            CamTarget.reset(player)
        } catch (e: Exception) {
            log.debug("camera reset failed for {}", player.username, e)
        }
    }

    /** The stock player death animation (836, `Player.DEATH_ANIMATION`). */
    private val DEATH: Animation get() = Player.DEATH_ANIMATION
}
