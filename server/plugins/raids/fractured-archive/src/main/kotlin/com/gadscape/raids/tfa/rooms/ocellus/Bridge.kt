package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.game.world.entity.npc.MultiHeadIconNpc
import com.gadscape.game.world.entity.npc.NpcHeadIcons
import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.model.music.Music
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.VarManager
import org.slf4j.LoggerFactory

/**
 * The Ocellus room's single adapter to engine and framework capabilities that other work units own (task rule:
 * "call the capability through ONE adapter file, so wiring it later is a one-file change"). Every function says
 * which unit owns the capability and whether it is wired to the real API or still a local fallback.
 *
 * Wired to code that exists today:
 * - E13 head icons: `NpcHeadIcons` / `MultiHeadIconNpc` (core, WU-06) → [setOverheads], [clearOverheads].
 * - E12 combat-level override: `NPC.setCombatLevelChange` (core, sent by `RsprotInfo`) → [setCombatLevel].
 * - E15 HP HUD extra bar: `HpHud.showExtraBar / updateExtraBar / hideExtraBar` (core, WU-06) → [meter*].
 * - Boss HP HUD main bar: `TfaServices.bossHud` (WU-14 installs it); while it is not installed the room drives
 *   `HpHud` itself ([hudShown], [hudHp], [hudHidden]).
 * - Prefetch RUNCLIENTSCRIPT 10016 → [prefetch]; music via `MusicHandler.unlock(music, play)` (else the midi) → [playMusic].
 * - Godmode (`TfaDebug.isGodmode`) → [isProtected].
 *
 * Still local fallbacks (see `raids4-impl/ENC-ocellus.md` "Bridge"):
 * - [requestStart]: no framework seam lets an encounter ask the raid to start it (WU-12/13); the room starts itself.
 * - [contributionPool]: `points.json` (WU-16) is not written; the spec §3.10 pool is used.
 * - [playMusic]: the dbrow music-unlock varp bit (dbrow 17009/17010, varp 27 bits 30/31) belongs to WU-14.
 * - [onKilled]: completion jingle 886, KC, stats, miniquest 16426 = 6 belong to WU-19/WU-29 (no-op here).
 */
internal object Bridge {

    private val log = LoggerFactory.getLogger(Bridge::class.java)

    /** Called once from [OcellusModule] at server launch. */
    fun install() {
        // tfa_ocellus_met (varp 5907 bit 12) is a perm var in the cache [CACHE varbit 16384]; the server must save it
        // so the intro switches to "Proceed" and the stats tab unmasks for good. WU-19 may register it too (idempotent).
        try {
            VarManager.appendPersistentVarbit(TfaIds.Varbit.OCELLUS_MET)
        } catch (t: Throwable) {
            log.warn("Ocellus: could not register varbit {} as persistent", TfaIds.Varbit.OCELLUS_MET, t)
        }
    }

    // ---- framework: raid lifecycle -----------------------------------------------------------------------------

    /**
     * The intro's "Yes" (spec §4.8 INTRO). FALLBACK: the raid (WU-12) has no "encounter asks to start" seam yet,
     * so the room starts itself; the raid sees `start()` happen through the encounter it drives.
     */
    fun requestStart(room: OcellusRoom, by: Player): Boolean {
        log.debug("Ocellus fight started by {}", by.username)
        // In a raid (fw-core), the raid starts the room (scale, timer, music, stats) and calls start() = startFight().
        if (com.gadscape.raids.tfa.raid.TfaRaids.raidOf(room.context) != null) {
            return com.gadscape.raids.tfa.raid.TfaRaids.requestStart(room.context, by)
        }
        return room.startFight()
    }

    /** FALLBACK: spec §3.10 pool for Ocellus until `points.json` (WU-16) exists. */
    fun contributionPool(): Int = 12_000

    /** FALLBACK (no-op): Ocellus killed; jingle 886, KC, stats, miniquest 16426 = 6 belong to WU-19 / WU-29. */
    @Suppress("UNUSED_PARAMETER")
    fun onKilled(room: OcellusRoom) {
    }

    // ---- debug ------------------------------------------------------------------------------------------------

    /** Players in `::tfa godmode` take no damage from the room (TfaDebug contract). */
    fun isProtected(player: Player): Boolean = TfaDebug.isGodmode(player)

    // ---- E13 head icons (WU-06) --------------------------------------------------------------------------------

    /** Shows the given prayer head icons (sprite group 440 indexes, slot 0 first) above [npc]. */
    fun setOverheads(npc: NPC, vararg prayerIcons: Int) {
        if (npc is MultiHeadIconNpc) npc.headIcons.setPrayers(*prayerIcons)
    }

    fun clearOverheads(npc: NPC) {
        if (npc is MultiHeadIconNpc) npc.headIcons.clearAll()
    }

    /** Protect from Melee's index in sprite group 440 (spec §4.8; `TfaIds.HeadIcon`). */
    const val PROTECT_FROM_MELEE = NpcHeadIcons.PROTECT_MELEE

    // ---- E12 combat level (WU-06) ------------------------------------------------------------------------------

    /** Overrides the combat level the client shows for [npc] (null keeps the cache level). */
    fun setCombatLevel(npc: NPC, level: Int?) {
        if (level != null && level > 0) npc.combatLevelChange = level
    }

    // ---- boss HP HUD, interface 303 (WU-06 / WU-14) ------------------------------------------------------------

    private val sinkInstalled: Boolean get() = TfaServices.bossHud !== BossHudSink.NONE

    /** [boss] appeared or transformed (HUD npc switch, spec §4.8: 16674 → 16675 → 16677). */
    fun hudShown(boss: TfaBossNpc, viewers: Collection<Player>) {
        if (sinkInstalled) {
            TfaServices.bossHud.onShown(boss)
            return
        }
        for (p in viewers) {
            val hud = p.hpHud ?: continue
            if (hud.isOpen) hud.switchNpc(boss.id, boss.hitpoints, boss.maxHitpoints)
            else hud.open(boss.id, boss.maxHitpoints)
            hud.updateValue(boss.hitpoints)
        }
    }

    /** [boss]'s HP changed. `TfaBossNpc` already notifies an installed sink; this covers the fallback. */
    fun hudHp(boss: TfaBossNpc, viewers: Collection<Player>) {
        if (sinkInstalled) return
        for (p in viewers) {
            val hud = p.hpHud ?: continue
            if (!hud.isOpen) hud.open(boss.id, boss.maxHitpoints)
            hud.updateValue(boss.hitpoints)
        }
    }

    fun hudHidden(boss: TfaBossNpc, viewers: Collection<Player>) {
        if (sinkInstalled) {
            TfaServices.bossHud.onHidden(boss)
            return
        }
        for (p in viewers) p.hpHud?.close()
    }

    /** P1 energy meter on the HUD extra bar (16674 params 2762-2768 [CACHE]: 7-px bar, no text). */
    fun meterShow(viewers: Collection<Player>, value: Int, max: Int) {
        for (p in viewers) {
            val hud = p.hpHud ?: continue
            if (hud.isOpen) hud.showExtraBar(value, max)
        }
    }

    fun meterUpdate(viewers: Collection<Player>, value: Int, max: Int) {
        for (p in viewers) {
            val hud = p.hpHud ?: continue
            if (!hud.isOpen) continue
            if (hud.hasExtraBar()) hud.updateExtraBar(value, max, false) else hud.showExtraBar(value, max)
        }
    }

    /** Spec §4.8 HUD: "clear 5991 at P2". */
    fun meterHide(viewers: Collection<Player>) {
        for (p in viewers) p.hpHud?.hideExtraBar()
    }

    // ---- client presentation -----------------------------------------------------------------------------------

    /** RUNCLIENTSCRIPT 10016 (Ocellus prefetch, no args) [CACHE]; spec §3.6 runs it at corridor entry. */
    fun prefetch(player: Player) {
        player.packetDispatcher?.sendClientScript(TfaIds.Script.PREFETCH_OCELLUS)
    }

    /**
     * Plays and unlocks a track for [viewers] (spec §3.7 / §4.8: "Artifacts of Old" midi 893 for P1/P2, dbrow 17009;
     * "Ascension" midi 894 for P3/P4, dbrow 17010). Through the music table entry when the server knows the track
     * (`MusicHandler.unlock(music, play)`), else the bare midi. The dbrow's unlock varp bit (27, 30/31) is WU-14's
     * `MusicUnlock` (not set here).
     */
    @Suppress("UNUSED_PARAMETER")
    fun playMusic(viewers: Collection<Player>, track: String, midi: Int, musicDbrow: Int) {
        val music = try {
            Music.map[track]
        } catch (t: Throwable) {
            null
        }
        for (p in viewers) {
            try {
                if (music != null) p.music.unlock(music, true) else p.packetDispatcher?.sendMusic(midi)
            } catch (t: Throwable) {
                log.warn("Ocellus music {} failed for {}", track, p.username, t)
            }
        }
    }

    const val TRACK_P1 = "Artifacts of Old"
    const val TRACK_P3 = "Ascension"

    // ---- varbits ------------------------------------------------------------------------------------------------

    /** Varbit 16384 `tfa_ocellus_met` [CACHE]: switches intro 16671 to 16673 (Proceed) and unmasks the stats tab. */
    fun isOcellusMet(player: Player): Boolean = player.varManager.getBitValue(TfaIds.Varbit.OCELLUS_MET) == 1

    fun setOcellusMet(player: Player) {
        player.varManager.sendBit(TfaIds.Varbit.OCELLUS_MET, 1)
    }
}
