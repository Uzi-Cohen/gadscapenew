package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.encounter.Telegraphs
import com.gadscape.raids.tfa.hud.MusicUnlock
import com.gadscape.raids.tfa.hud.Prefetch
import com.gadscape.raids.tfa.points.ContributionService
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRaids
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.util.RSColour
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.InterpolatingHitBar
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject
import org.slf4j.LoggerFactory

/**
 * The Dagon'hai room's single adapter to capabilities owned by other units (spec §2.7, `raids4-impl/ENGINE-*.md`,
 * `FRAMEWORK-*.md`). Everything the room needs from outside its package and the stock engine goes through here, so
 * wiring a capability that lands later is a one-file change. Status (also in `raids4-impl/ENC-dagonhai.md`):
 *
 * | Capability | Owner | Status |
 * |---|---|---|
 * | Combat traits (no defence floor, ranged 80/80/80, poison/venom/cannon immune; imps poisonable) | WU-04 | wired by `TfaNpc` (`CombatTraitsBinding` live view) |
 * | Spell-element hook for the channel interrupt (E17) | WU-07 `NPC.onSpellHit` | wired in [DagonhaiNpc.onSpellHit] |
 * | Interpolating headbars: channel 85, pool 89 / 86 (E14) | WU-06 `InterpolatingHitBar` | wired ([chargeBar], [poolBar]) |
 * | HP HUD on the cauldron 16649 with the ritual extra bar, recoloured yellow (E15) | WU-06 `HpHud` / WU-14 `BossHpHud` | wired directly on `HpHud` ([RitualHud]); `TfaServices.bossHud` takes only `TfaBossNpc` and has no extra-bar call, so the room drives it until WU-14 offers one |
 * | NPC model override for the exploding cauldron (62603 → 62612) | engine `ModelOverride` (rsprot body customisation) | wired in [Cauldron.explodeModel] |
 * | Contribution per hit (trio pool capped, imps) | WU-16 via `TfaServices.contribution` | wired by `TfaNpc` (no-op until WU-16 installs it) |
 * | Contribution pool size | WU-16 `points.json` (`ContributionService.poolOf`) | wired ([contributionPool], 7000 fallback) |
 * | Seq prefetch 10011 on arena entry | WU-14 `hud/Prefetch` | wired: the raid sends it (`TfaRaid.enterArena`); a debug run through [prefetch] |
 * | Music 889 "Triple Threat" + unlock (varp 5238 bit 26) | WU-14 `hud/MusicUnlock` | wired: the raid plays it at start / entry; a debug run through [roomMusic] |
 * | Fight start from a hit before the transporter trigger | WU-12 `TfaRaids.requestStart` | wired ([requestStart]) |
 * | HUD viewers (arena, members who died here, spectators) | WU-12 `RoomRuntime` / WU-18 spectators | wired ([hudViewers]) |
 * | Godmode skip | WU-08 `TfaDebug` | wired ([isGodmode]) |
 * | Telegraph map spotanims | framework `encounter/Telegraphs` | wired ([telegraph]) |
 * | Hazard tiles | framework `encounter/Hazards` (lifetime tiles, damage every tick) | not used: the trio's hazards are one-shot strikes (rings, blasts, lightning) resolved by [DagonhaiFight] on their tick |
 * | Completion hand-off (stats, banking, room exit), deaths and wipes | WU-12/15/16/19 | done by the raid around `onComplete` / `onWipe` |
 */
internal object Bridge {

    private val log = LoggerFactory.getLogger(Bridge::class.java)

    // ------------------------------------------------------------------------------------------- headbars (E14)

    /** Headbar 85 on [npc] filling from empty to full over [ticks] (channel charge, spec); 0 removes it. */
    fun chargeBar(npc: NPC, ticks: Int) {
        val bar = InterpolatingHitBar(DagonhaiIds.HEADBAR_CHARGE, DagonhaiIds.HEADBAR_CHARGE_SEGMENTS)
        if (ticks <= 0) bar.remove(npc) else bar.interpolate(0.0, 1.0, ticks).show(npc)
    }

    /**
     * The shared pool on [npc] (spec: "all three showing the same %"): headbar [headbar] at [pool] / [max]. Bars with
     * sticktime 0 (89) only show while they interpolate, so they are held for [HOLD_CYCLES]; 86 (sticktime 300) is
     * sent static. The room re-sends it on every change and every [KEEPALIVE_TICKS] ticks.
     */
    fun poolBar(npc: NPC, headbar: Int, pool: Int, max: Int) {
        val segments = DagonhaiIds.headbarSegments(headbar)
        val bar = InterpolatingHitBar(headbar, segments)
        val fill = bar.toSegments(if (max <= 0) 0.0 else pool.toDouble() / max)
        if (DagonhaiIds.headbarNeedsHold(headbar)) bar.interpolateCycles(fill, fill, 0, HOLD_CYCLES) else bar.fillSegments(fill)
        bar.show(npc)
    }

    fun removePoolBar(npc: NPC, headbar: Int) {
        InterpolatingHitBar(headbar, DagonhaiIds.headbarSegments(headbar)).remove(npc)
    }

    /** ~16 minutes: the longest hold the head bar block carries (32766 cycles). */
    const val HOLD_CYCLES = InterpolatingHitBar.MAX_CYCLES

    /** Re-send the pool bar at least this often (86 fades 10 t after its last update). */
    const val KEEPALIVE_TICKS = 8

    // ---------------------------------------------------------------------------------------- HP HUD (E15)

    /**
     * The cauldron's HP HUD for the players in the arena (spec §3.7 / §4.6): varp 1683 = 16649, main bar = stability /
     * 1000 (the client shows % because of param 2289), extra bar (varp 5991, 16570/16571, script 10152) = ritual
     * quarters, recoloured yellow on 303:24-26. Opened on the fight start, updated on change, closed for players who
     * leave and on completion / wipe / teardown.
     */
    class RitualHud(private val npcId: Int, private val colours: List<Int>?) {
        private val shown = HashMap<Player, IntArray>()

        fun sync(players: Collection<Player>, stability: Int, stabilityMax: Int, ritual: Int, ritualMax: Int) {
            val gone = shown.keys.filter { it !in players }
            for (p in gone) close(p)
            for (p in players) {
                if (p.isFinished) continue
                try {
                    val hud = p.hpHud ?: continue
                    val last = shown[p]
                    if (last == null || !hud.isOpen) {
                        hud.openWithExtraBar(npcId, stability, stabilityMax, ritual, ritualMax)
                        colours?.let { hud.sendExtraColorChange(rgb(it[0]), rgb(it[1]), rgb(it[2])) }
                        shown[p] = intArrayOf(stability, stabilityMax, ritual, ritualMax)
                        continue
                    }
                    if (last[1] != stabilityMax) hud.updateMaxValue(stabilityMax)
                    if (last[0] != stability) hud.updateValue(stability)
                    if (last[2] != ritual || last[3] != ritualMax) hud.updateExtraBar(ritual, ritualMax, false)
                    shown[p] = intArrayOf(stability, stabilityMax, ritual, ritualMax)
                } catch (e: Exception) {
                    log.warn("Dagon'hai HUD failed for {}", p.username, e)
                }
            }
        }

        private fun close(p: Player) {
            shown.remove(p)
            if (p.isFinished) return
            try {
                val hud = p.hpHud ?: return
                if (colours != null) hud.resetExtraColors()
                hud.close()
            } catch (e: Exception) {
                log.warn("Dagon'hai HUD close failed for {}", p.username, e)
            }
        }

        fun closeAll() {
            shown.keys.toList().forEach(::close)
        }

        private fun rgb(value: Int): RSColour = RSColour((value shr 19) and 31, (value shr 11) and 31, (value shr 3) and 31)
    }

    // ------------------------------------------------------------------------------ prefetch and music (WU-14)

    /** True when [context] is a real raid's room (WU-12 `RoomRuntime`); false for a standalone debug run. */
    fun inRaid(context: TfaEncounterContext): Boolean = TfaRaids.raidOf(context) != null

    /**
     * Seq prefetch 10011 on arena entry (§3.7). In a raid, `TfaRaid.enterArena` already sends it once per member
     * (`hud/Prefetch`); a standalone debug run sends it here.
     */
    fun prefetch(context: TfaEncounterContext, player: Player) {
        if (!inRaid(context)) Prefetch.send(player, TfaRoomId.DAGONHAI)
    }

    /**
     * "Triple Threat" (midi 889) and its unlock (dbrow 17005, varp 5238 bit 26) at fight start and on arena entry while
     * it runs (§3.7). In a raid, `TfaRaid.startEncounter` / `enterArena` play it (`hud/MusicUnlock`); a standalone
     * debug run plays it here.
     */
    fun roomMusic(context: TfaEncounterContext, player: Player) {
        if (!inRaid(context)) MusicUnlock.play(player, TfaRoomId.DAGONHAI)
    }

    /** Asks the raid to start the fight (it fixes the scale, timer, music, stats, then calls `start()`). */
    fun requestStart(context: TfaEncounterContext, by: Player?): Boolean = TfaRaids.requestStart(context, by)

    /**
     * Who sees the cauldron's HP HUD: the living members in the arena, the members who died in this encounter and the
     * raid's spectators watching this room (the viewers `hud/BossHpHud` uses for single-bar bosses). A debug run: the
     * players in the arena.
     */
    fun hudViewers(context: TfaEncounterContext): List<Player> {
        val rt = context as? RoomRuntime ?: return context.playersInArena()
        val out = LinkedHashSet<Player>()
        out += context.playersInArena()
        try {
            for (m in rt.raid.members) if (!m.left && m.dead && m.diedIn == rt.room) out += m.player
            out += rt.raid.spectators.playersWatching(rt.room)
        } catch (e: Exception) {
            log.warn("Dagon'hai HUD viewers failed", e)
        }
        return out.filter { !it.isFinished }
    }

    // ---------------------------------------------------------------------------------- contribution (WU-16)

    /** §3.10 pool from `points.json` (WU-16 `ContributionService`): "Dagon'hai or Xarth 7000" by default. */
    fun contributionPool(context: TfaEncounterContext): Int = try {
        ContributionService.poolOf(context).takeIf { it > 0 } ?: CONTRIBUTION_POOL_FALLBACK
    } catch (e: Exception) {
        CONTRIBUTION_POOL_FALLBACK
    }

    private const val CONTRIBUTION_POOL_FALLBACK = 7000

    // ----------------------------------------------------------------------------------------- telegraphs (fw)

    /** A map spotanim on static [tile] (framework `encounter/Telegraphs`). */
    fun telegraph(context: TfaEncounterContext, spot: Int, tile: StaticTile, height: Int, delayCycles: Int) {
        Telegraphs.tile(context, spot, tile, delayCycles, height)
    }

    // ------------------------------------------------------------------------------------------------ debug

    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    // -------------------------------------------------------------------------------------------- the world

    /** A Zenyte projectile from a [DagonhaiConfig.ProjectileSpec] (slope in client units → the constructor's degrees). */
    fun projectile(spot: Int, spec: DagonhaiConfig.ProjectileSpec): Projectile =
        Projectile(spot, spec.startHeight, spec.endHeight, spec.delay, (spec.slope * 90 + 32) / 64, spec.duration, 0, spec.perTile)

    /**
     * Loc anim [seq] on the Book of Life and Death (62552, type 10, rotation 3 at static (4049,5597)) at [tile]: the
     * copied map's loc if it is there, else a loc anim sent for those values.
     */
    fun animateBook(tile: Location, seq: Int) {
        val book = World.getObjectWithId(tile, DagonhaiIds.LOC_BOOK)
        if (book != null) World.sendObjectAnimation(book, Animation(seq))
        else World.sendObjectAnimation(DagonhaiIds.LOC_BOOK, WorldObject.DEFAULT_TYPE, BOOK_ROTATION, tile, Animation(seq))
    }

    /** r16215_locs: `16215|62552|4049|5597|0|10|3`. */
    private const val BOOK_ROTATION = 3
}
