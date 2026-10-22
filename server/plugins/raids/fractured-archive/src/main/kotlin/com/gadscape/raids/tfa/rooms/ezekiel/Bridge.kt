package com.gadscape.raids.tfa.rooms.ezekiel

import com.gadscape.game.world.entity.player.status.PlayerBleed
import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.Telegraphs
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.hud.MusicUnlock
import com.gadscape.raids.tfa.hud.Prefetch
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.points.ContributionService
import com.gadscape.raids.tfa.raid.TfaRaids
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.InterpolatingHitBar
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity._Location
import com.zenyte.game.world.entity.masks.ForceMovement
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.pathfinding.Flags
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory

/**
 * The Ezekiel room's single adapter to capabilities owned by other units (spec §2.7, `raids4-impl/ENGINE-*.md`,
 * `FRAMEWORK-*.md`). Every call the room makes into code it does not own goes through here, so wiring a capability
 * that lands later is a change to this file only. Status (also in `raids4-impl/ENC-ezekiel.md`):
 *
 * | Capability | Owner | Status |
 * |---|---|---|
 * | Combat traits (earth 25%, ranged 445/370/320, 60% floor = 120; swords air 200%, ranged 300, magic always hits) | WU-04 | wired: `TfaBossNpc.getCombatTraits` / `CombatTraitsBinding` give each npc a live `CombatTraitsView` of its `traits` |
 * | Bleed on non-tank cleave hits (Vardorvis rules, hitsplat 67) | WU-05 `PlayerBleed` | wired ([bleed]) |
 * | Headbars 85 (empower) and 88 (launch timer) on the swords | WU-06 E14 `InterpolatingHitBar` | wired ([launchBar], [empowerBar], [removeBars]) |
 * | Contribution per hit (boss + sword empower damage), overkill capped | WU-16 `ContributionService` via `TfaServices.contribution` | wired by `TfaNpc`; pool from `points.json` ([contributionPool], 6000 if unreadable) |
 * | HP HUD single bar, npc 16652 | WU-14 `BossHpHud` (`TfaServices.bossHud`) | wired ([hudShow] / [hudHide]; updates by `TfaBossNpc`); [HudFallback] drives E15 `HpHud` only if no sink is installed |
 * | Tank marker varbits 16408-16415 `tfa_client_primary0..7` | WU-14 `TfaHud.setPrimary` | wired in a raid ([markTank]); direct varbits in the `::tfa ezekiel` debug run |
 * | Seq prefetch 10012 on arena entry | WU-14 `Prefetch` (the raid sends it in `enterArena`) | wired ([prefetch]: the raid's own call in a raid, `Prefetch.send` in the debug run) |
 * | Music 890 "Blade of Forgotten Justice" + unlock (27,24) | WU-14 `MusicUnlock` (the raid plays it at the start and on entry) | wired ([playMusic]: raid's own call in a raid, `MusicUnlock.play` in the debug run) |
 * | Encounter start on the first transporter use, tank = that player | WU-12/13 `TfaRaid.useTransporter` → `startEncounter` | wired: the raid calls `onArenaEntry` then `start()`; [requestStart] asks `TfaRaids.requestStart` (debug run: starts itself) |
 * | Telegraph map graphics (cross tiles, impacts, heal wave) | framework `encounter/Telegraphs` | wired ([mapGraphic] → `Telegraphs.live`) |
 * | Fence collision (temporary blocked cells) | framework `encounter/Hazards` (damage tiles only, no collision) | not covered there: room-local flags ([FenceClip]) |
 * | Finale ambience 11 (dbrow 17000) | WU-14 / engine (no ambience API) | not wired |
 * | Godmode skip | WU-08 `TfaDebug` | wired ([isGodmode]) |
 * | Room completion hand-off (stats, banking, antechamber move after 3 t) | WU-12/16/19 | the raid does it around `onComplete` |
 */
internal object Bridge {

    private val log = LoggerFactory.getLogger(Bridge::class.java)

    /** §3.10: "Ezekiel or Bork 6000" [HOUSE], used only if `points.json` cannot be read. */
    private const val CONTRIBUTION_POOL_FALLBACK = 6000

    // ---------------------------------------------------------------------------------------- contribution (WU-16)

    /** The room's pool from the raid's `points.json` snapshot (WU-16). */
    fun contributionPool(context: TfaEncounterContext): Int = try {
        ContributionService.poolOf(context)
    } catch (e: Exception) {
        log.warn("Ezekiel contribution pool unreadable, using {}", CONTRIBUTION_POOL_FALLBACK, e)
        CONTRIBUTION_POOL_FALLBACK
    }

    /** True when [context] belongs to a real raid (WU-12), false in the `::tfa ezekiel` debug run. */
    fun inRaid(context: TfaEncounterContext): Boolean = TfaRaids.raidOf(context) != null

    // ---------------------------------------------------------------------------------------------- HP HUD (E15)

    private val sinkInstalled: Boolean get() = TfaServices.bossHud !== BossHudSink.NONE

    fun hudShow(boss: TfaBossNpc) {
        if (sinkInstalled) TfaServices.bossHud.onShown(boss)
    }

    fun hudHide(boss: TfaBossNpc?) {
        if (sinkInstalled && boss != null) TfaServices.bossHud.onHidden(boss)
    }

    /**
     * Until WU-14 installs `TfaServices.bossHud`, the room shows its own single bar on the engine's `HpHud` (E15,
     * interface 303, varp 1683 = 16652, varbits 6099/6100; no extra bar: 16652 has no params 2762-2769) to the players
     * in the arena: opened on the start, synced each tick, closed on completion or wipe. It switches itself off (and
     * closes what it opened) as soon as a real sink is installed.
     */
    class HudFallback(private val npcId: Int) {
        private val shown = HashMap<Player, Int>()
        private var lastMax = 0

        private val active: Boolean get() = TfaServices.bossHud === BossHudSink.NONE

        fun sync(players: Collection<Player>, current: Int, max: Int) {
            if (!active) {
                closeAll()
                return
            }
            val gone = shown.keys.filter { it !in players }
            for (p in gone) close(p)
            for (p in players) {
                if (p.isFinished) continue
                try {
                    val hud = p.hpHud
                    val last = shown[p]
                    if (last == null || !hud.isOpen) {
                        hud.open(npcId, max)
                        hud.updateValue(current)
                        shown[p] = current
                        continue
                    }
                    if (max != lastMax) hud.updateMaxValue(max)
                    if (last != current) {
                        hud.updateValue(current)
                        shown[p] = current
                    }
                } catch (e: Exception) {
                    log.warn("Ezekiel HP HUD failed for {}", p.username, e)
                    shown[p] = current
                }
            }
            lastMax = max
        }

        private fun close(p: Player) {
            shown.remove(p)
            if (!p.isFinished) p.hpHud.close()
        }

        fun closeAll() {
            shown.keys.toList().forEach(::close)
        }
    }

    // ------------------------------------------------------------------------------ prefetch and music (WU-14)

    /**
     * RUNCLIENTSCRIPT 10012 (no args) on arena entry: seq_prefetch of melee/magic/special/weaken/heal/finale/death. In a
     * raid `TfaRaid.enterArena` already sends it once per member (`Prefetch.send`); the debug run sends it here.
     */
    fun prefetch(context: TfaEncounterContext, player: Player) {
        if (inRaid(context)) return
        Prefetch.send(player, TfaRoomId.EZEKIEL)
    }

    /**
     * Music: midi 890 and unlock bit 24 of `musicmulti_27` (dbrow 17003). In a raid `TfaRaid.startEncounter` and
     * `enterArena` play it (`MusicUnlock.play`); the debug run plays it here.
     */
    fun playMusic(context: TfaEncounterContext, player: Player) {
        if (inRaid(context)) return
        MusicUnlock.play(player, TfaRoomId.EZEKIEL)
    }

    // ------------------------------------------------------------------------------------- tank marker (WU-14)

    /**
     * Varbits 16408-16415 `tfa_client_primary0..7` (§3.7, read by no client script, set for RuneLite plugins) on the
     * tank's slot. In a raid through `TfaHud.setPrimary` (diffed and pushed by the HUD each tick; the raid also marks
     * the first transporter user at the start and clears the marks at completion). In the debug run: sent directly,
     * slot = index in the context's member list.
     */
    fun markTank(context: TfaEncounterContext, old: Player?, new: Player?) {
        val raid = TfaRaids.raidOf(context)
        if (raid != null) {
            if (old != null && old != new) raid.memberOf(old)?.let { raid.hud.setPrimary(it, false) }
            if (new != null) raid.memberOf(new)?.let { raid.hud.setPrimary(it, true) }
            return
        }
        val members = context.members
        val bits = TfaIds.Varbit.PRIMARY
        for (viewer in members) {
            if (viewer.isFinished) continue
            for (slot in 0 until bits.size) {
                val value = if (new != null && slot < members.size && members[slot] == new) 1 else 0
                try {
                    viewer.varManager.sendBit(bits[slot], value)
                } catch (e: Exception) {
                    log.debug("primary varbit {} not sent to {}", bits[slot], viewer.username, e)
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------- statuses (WU-05)

    /** Vardorvis bleed (3 every 3 t for 15 t, every tick while moving; hitsplat 67). */
    fun bleed(player: Player) {
        if (!TfaDebug.isGodmode(player)) PlayerBleed.apply(player)
    }

    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    // ------------------------------------------------------------------------------------------------- hits

    /**
     * Lands [damage] of [type] from [source] on [player] (processed on the player's next hit pass). Prayer effects are
     * already in [damage]; Ezekiel returns 1.0 prayer multipliers so the engine does not apply them again.
     */
    fun hit(player: Player, source: NPC, damage: Int, type: HitType) {
        if (TfaDebug.isGodmode(player) || player.isDead || player.isFinished) return
        CombatUtilities.delayHit(source, -1, player, Hit(source, damage.coerceAtLeast(0), type))
    }

    /** A sourceless typeless hit (cross bursts, flying swords): no retaliation, no prayer. */
    fun hazardHit(player: Player, damage: Int) {
        if (TfaDebug.isGodmode(player) || player.isDead || player.isFinished || damage <= 0) return
        player.applyHit(Hit(damage, HitType.TYPELESS))
    }

    // --------------------------------------------------------------------------------------------- headbars (E14)

    /** Headbar 88 (orange heat 60) filling from empty to full over [ticks]: the launch timer. */
    fun launchBar(npc: NPC, ticks: Int) {
        try {
            InterpolatingHitBar.Heat60().interpolate(0.0, 1.0, ticks.coerceAtLeast(1)).show(npc)
        } catch (e: Exception) {
            log.debug("launch bar failed", e)
        }
    }

    /**
     * Headbar 85 (yellow charge 70) at [fraction], held for [ticks] (its sticktime is 1 cycle, so a static bar is sent
     * as a from = to interpolation lasting until the launch).
     */
    fun empowerBar(npc: NPC, fraction: Double, ticks: Int) {
        try {
            val bar = InterpolatingHitBar.Charge70()
            val fill = bar.toSegments(fraction)
            bar.interpolateCycles(fill, fill, 0, (ticks.coerceAtLeast(1)) * EzekielIds.CYCLES_PER_TICK).show(npc)
        } catch (e: Exception) {
            log.debug("empower bar failed", e)
        }
    }

    fun removeBars(npc: NPC) {
        try {
            InterpolatingHitBar.Heat60().remove(npc)
            InterpolatingHitBar.Charge70().remove(npc)
        } catch (e: Exception) {
            log.debug("bar removal failed", e)
        }
    }

    // ------------------------------------------------------------------------------------- telegraphs (fw)

    /** A map graphic on a live tile (cross tiles, finale impacts, the heal wave). */
    fun mapGraphic(spot: Int, tile: Location) {
        Telegraphs.live(spot, tile)
    }

    // ------------------------------------------------------------------------------------------- exact move

    /**
     * Shows [entity] moving from its current tile to [to] over client cycles 0..[cycles] (OSRS exact move, relative to
     * the tile it stands on when the update is written). The server position is not changed here.
     */
    fun exactMove(entity: com.zenyte.game.world.entity.Entity, to: Location, startCycle: Int, cycles: Int, angle: Int) {
        entity.forceMovement = ForceMovement(Location(entity.location), startCycle, to, cycles.coerceAtLeast(startCycle + 1), angle)
    }

    /**
     * Moves [player] to [to] at once and shows the roll/strafe from the old tile over [cycles] (the engine's own
     * `Player.forceMovement` pattern: position first, then an exact move from the old tile, relative to the new one).
     */
    fun dodgeMove(player: Player, to: Location, cycles: Int, angle: Int) {
        val from = Location(player.location)
        player.resetWalkSteps()
        player.setLocation(to)
        player.forceMovement = ForceMovement(from, 0, to, cycles.coerceAtLeast(1), angle)
    }

    // ------------------------------------------------------------------------------------- hazards (fw)

    /**
     * Temporary collision on the finale fence cells (`Flags.OBJECT` on tiles that had none, the way a blocking loc
     * marks a tile), removed by [clear]. Only the tiles this instance added are cleared again.
     */
    class FenceClip {
        private val added = ArrayList<Location>()

        fun block(tile: Location) {
            val region = World.getRegion(_Location.getRegionId(tile.x, tile.y), true)
            val mask = World.getMask(tile.plane, tile.x, tile.y)
            if (mask and Flags.OBJECT != 0) return
            region.addFlag(tile.plane, tile.x and 63, tile.y and 63, Flags.OBJECT)
            added += Location(tile)
        }

        fun clear() {
            for (t in added) {
                World.getRegion(_Location.getRegionId(t.x, t.y), true).removeFlag(t.plane, t.x and 63, t.y and 63, Flags.OBJECT)
            }
            added.clear()
        }

        val size: Int get() = added.size
    }

    /** True when a player may stand on [tile] (no blocking loc or wall-object flag). */
    fun standable(tile: Location): Boolean = World.isFloorFree(tile.plane, tile.x, tile.y)

    // ------------------------------------------------------------------------------------------------ raid flow

    /**
     * The first transporter use starts the room (§2.4): in a raid `TfaRaid.useTransporter` calls `startEncounter` itself
     * (scale, timer, music, stats, then `start()`); [requestStart] asks the raid the same way and starts the room
     * directly only in the `::tfa ezekiel` debug run.
     */
    fun requestStart(room: EzekielRoom, player: Player): Boolean {
        if (room.started) return false
        if (!TfaRaids.requestStart(room.context, player)) {
            log.info("Ezekiel started by {} (debug run, no TfaRaid)", player.username)
            room.start()
        }
        return room.started
    }
}
