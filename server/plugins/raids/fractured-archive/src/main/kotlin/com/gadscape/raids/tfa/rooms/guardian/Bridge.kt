package com.gadscape.raids.tfa.rooms.guardian

import com.gadscape.game.world.Lines
import com.gadscape.game.world.entity.player.status.Weary
import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.CombatTraits
import com.gadscape.raids.tfa.encounter.CombatTraitsView
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.model.music.Music
import com.zenyte.game.world.entity.Toxins
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combat.NpcCombatTraits
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory

/**
 * The Archive Guardian room's single adapter to engine and framework capabilities that other units own (spec §2.7
 * E1-E18, §3 framework). Every call the room makes into code it does not own goes through here, so wiring a
 * capability that lands later is a change to this file only.
 *
 * Status (re-checked against the worktree when this unit finished; see `raids4-impl/ENC-guardian.md`):
 * | Capability | Owner | Wired to |
 * |---|---|---|
 * | Combat traits E5-E9 (weakness %, light/std/heavy ranged def, defence floor, inverted magic drain, Ancient Magicks and freeze immunity, flying) | WU-04 | wired: `TfaBossNpc.getCombatTraits()` / `CombatTraitsBinding` give a live `CombatTraitsView` of each npc's `traits` (the room switches flying / grounded / enraged by form); [traitsAdapter] is the fallback |
 * | Boss HP HUD (interface 303) E15 | WU-06/WU-14 | `TfaServices.bossHud` when installed, else the core `HpHud` of each viewer ([hudShow] / [hudUpdate] / [hudHide]) |
 * | Weary status E10 group | WU-05 | core `status.Weary` |
 * | Poison | core | `Toxins.applyToxin` |
 * | Contribution | WU-16 | `TfaServices.contribution` (through `TfaNpc`); pool [contributionPool] until points.json exists |
 * | Encounter start on Touch | WU-12 (`TfaRaid.startEncounter`) | not available: [requestStart] starts the room directly |
 * | Prefetch (RUNCLIENTSCRIPT 10013) and music 887 + unlock | WU-14 (Prefetch.kt, MusicUnlock.kt) | not available: direct client script / music handler |
 * | Godmode | WU-08 | `TfaDebug.isGodmode` |
 * | Line helper E18 (laser ray) | WU-07 | core `Lines.bresenham` ([line]) |
 * | Head bar 86 over the boss E14 | WU-06 | `NPC.overrideHitbarType` (shown with each hit; `InterpolatingHitBar.Shield60` exists for persistent bars) |
 * | NPC base-animation-set override (grounded readyanim 14746) | engine | not available: the room replays 14746 every 4 t |
 */
internal object Bridge {

    private val log = LoggerFactory.getLogger(Bridge::class.java)

    /** Room pool from §3.10 (`points.json` of WU-16 does not exist yet). */
    const val GUARDIAN_POOL = 4000

    /** Music track name of midi 887 / dbrow 17002 (§3.7). */
    private const val TRACK = "The Archive Guardian"

    // ------------------------------------------------------------------------------------------- contribution

    @JvmStatic
    fun contributionPool(): Int = GUARDIAN_POOL

    // ------------------------------------------------------------------------------------------- combat traits

    /**
     * The engine view ([NpcCombatTraits]) of the room's current [CombatTraits]: WU-04's live [CombatTraitsView]
     * (asked on every hit, so form changes apply at once). The WU-04 binder gives every raid npc the same view on
     * spawn; this is the fallback for an npc that has none.
     */
    @JvmStatic
    fun traitsAdapter(current: () -> CombatTraits): NpcCombatTraits = CombatTraitsView(current)

    // ------------------------------------------------------------------------------------------- HP HUD

    private val sinkInstalled: Boolean get() = TfaServices.bossHud !== BossHudSink.NONE

    /**
     * The boss appeared or changed form: HUD npc [hudNpcId] (16659 flying with the shield, 16660 grounded or enraged
     * with main HP, §3.7). With an installed sink the sink reads the boss; otherwise each viewer's HpHud is driven.
     */
    @JvmStatic
    fun hudShow(boss: TfaBossNpc, hudNpcId: Int, viewers: Collection<Player>) {
        if (sinkInstalled) {
            TfaServices.bossHud.onShown(boss)
            return
        }
        for (p in viewers) {
            val hud = p.hpHud
            if (hud.isOpen) hud.switchNpc(hudNpcId, boss.hitpoints, boss.maxHitpoints)
            else {
                hud.open(hudNpcId, boss.maxHitpoints)
                hud.updateValue(boss.hitpoints)
            }
        }
    }

    /** The boss's pool HP changed (the sink, if installed, already heard it from `TfaBossNpc`). */
    @JvmStatic
    fun hudUpdate(boss: TfaBossNpc, viewers: Collection<Player>) {
        if (sinkInstalled) return
        for (p in viewers) {
            val hud = p.hpHud
            if (!hud.isOpen) continue
            hud.updateMaxValue(boss.maxHitpoints)
            hud.updateValue(boss.hitpoints.coerceAtLeast(0))
        }
    }

    @JvmStatic
    fun hudHide(boss: TfaBossNpc?, viewers: Collection<Player>) {
        if (sinkInstalled) {
            if (boss != null) TfaServices.bossHud.onHidden(boss)
            return
        }
        for (p in viewers) p.hpHud.close()
    }

    // ------------------------------------------------------------------------------------------- lines (E18)

    /** The Bresenham tiles from ([x0], [y0]) to ([x1], [y1]), both included, in order (core `Lines`, WU-07). */
    @JvmStatic
    fun line(x0: Int, y0: Int, x1: Int, y1: Int): List<Tile> =
        Lines.bresenham(x0, y0, x1, y1).map { Tile(Lines.tileX(it), Lines.tileY(it)) }

    // ------------------------------------------------------------------------------------------- headbars

    /**
     * Shows head bar config [type] over [npc] (E14 headbars 85-88; the Guardian's shield uses 86), or the default
     * size bar with -1. A type the server's cache does not have is ignored (the default bar stays).
     */
    @JvmStatic
    fun setHeadbar(npc: NPC, type: Int) {
        if (type >= 0) {
            val known = try {
                mgi.types.config.HitbarDefinitions.get(type) != null
            } catch (e: Exception) {
                false
            }
            if (!known) {
                log.warn("Head bar {} is not in the server cache; {} keeps its default bar", type, npc.id)
                return
            }
        }
        npc.overrideHitbarType(type)
    }

    // ------------------------------------------------------------------------------------------- client side

    /** Prefetch on arena entry: RUNCLIENTSCRIPT 10013 (no args) seq_prefetches the 19 Guardian and vine seqs (§3.7). */
    @JvmStatic
    fun prefetch(player: Player) {
        try {
            player.packetDispatcher.sendClientScript(TfaIds.Script.PREFETCH_GUARDIAN)
        } catch (e: Exception) {
            log.warn("Archive Guardian prefetch failed for {}", player.username, e)
        }
    }

    /** Music on the wake: midi 887 "The Archive Guardian", unlocked through dbrow 17002 (varp index 27, bit 23). */
    @JvmStatic
    fun playMusic(player: Player) {
        try {
            val music = Music.map[TRACK]
            if (music != null) player.music.unlock(music, true)
            else player.packetDispatcher.sendMusic(TfaIds.Midi.GUARDIAN)
        } catch (e: Exception) {
            log.warn("Archive Guardian music failed for {}", player.username, e)
        }
    }

    // ------------------------------------------------------------------------------------------- statuses and hits

    /** Weary (§4.1 [HOUSE], Nightmare drowsy copy): +1 attack delay, run off, [ticks] long, refreshed on re-apply. */
    @JvmStatic
    fun applyWeary(player: Player, ticks: Int) {
        if (ticks > 0 && !TfaDebug.isGodmode(player)) Weary.apply(player, ticks)
    }

    /** Starts a poison of [damage] on [player] (mushroom burst). */
    @JvmStatic
    fun applyPoison(player: Player, damage: Int, source: NPC) {
        if (damage > 0 && !TfaDebug.isGodmode(player)) player.toxins.applyToxin(Toxins.ToxinType.POISON, damage, source)
    }

    /** True for players boss damage must skip (`::tfa godmode`). */
    @JvmStatic
    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    /**
     * Lands [damage] of [type] from [source] on [player] now (the hit is processed on the player's next hit pass, the
     * same tick when the player is processed after the encounter). Prayer effects are already in [damage]; the
     * room's npcs return 1.0 from their prayer multipliers so the engine does not apply them again.
     */
    @JvmStatic
    fun hit(player: Player, source: NPC, damage: Int, type: HitType) {
        if (TfaDebug.isGodmode(player) || player.isDead || player.isFinished) return
        CombatUtilities.delayHit(source, -1, player, Hit(source, damage.coerceAtLeast(0), type))
    }

    // ------------------------------------------------------------------------------------------- raid flow

    /**
     * The Touch on the Inert Guardian (§2.3: `TfaRaid.startEncounter(GUARDIAN)`, which also locks joins and starts
     * the room timer). WU-12 has no such entry point yet, so the room starts itself; returns true if it started.
     */
    @JvmStatic
    fun requestStart(room: ArchiveGuardianRoom, player: Player): Boolean {
        if (room.started) return false
        // In a raid (fw-core), the raid starts the room: join lock, timer, scale, music and stats, then start().
        if (com.gadscape.raids.tfa.raid.TfaRaids.raidOf(room.context) != null) {
            com.gadscape.raids.tfa.raid.TfaRaids.requestStart(room.context, player)
            return room.started
        }
        log.info("Archive Guardian touched by {}: starting the room (standalone run)", player.username)
        room.start()
        return room.started
    }
}
