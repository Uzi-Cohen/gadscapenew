package com.gadscape.raids.tfa.rooms.moia

import com.gadscape.game.world.entity.player.status.EntityVirusHost
import com.gadscape.game.world.entity.player.status.VirusHost
import com.gadscape.game.world.entity.player.status.VirusOutbreak
import com.gadscape.game.world.entity.player.status.VirusSettings
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.zenyte.game.model.music.Music
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.InterpolatingHitBar
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.Random

/**
 * The Moia room's single adapter to engine and framework capabilities owned by other units (spec §2.7, raids4-impl
 * ENGINE-* / FRAMEWORK-* notes). Everything the room needs from outside its package goes through here, so wiring a
 * capability that lands later is a one-file change. Status per capability (also in `raids4-impl/ENC-moia.md`):
 *
 * | Capability | Owner | Status |
 * |---|---|---|
 * | Contagious cough (virus) | WU-05 `VirusOutbreak` | wired |
 * | Interpolating charge headbar 85 | E14 `InterpolatingHitBar.Charge70` | wired |
 * | HP HUD extra bar mirror (varp 5991, 16570/16571, script 10152) | E15 `HpHud` | wired (only when the HUD is open) |
 * | Displayed combat level 1064 | E12 `NPC.setCombatLevelChange` | wired |
 * | Godmode skip | WU-08 `TfaDebug` | wired |
 * | Seq prefetch 10015 on arena entry | WU-14 `hud/Prefetch` | direct RUNCLIENTSCRIPT until hud exists |
 * | Room music 892 | WU-14 `hud/MusicUnlock` | midi played directly (`sendMusic`) |
 * | Raid track unlock (dbrow 17007, varp index 27 bit 28) | WU-14 `hud/MusicUnlock` | not wired (no API yet) |
 * | Contribution pool size | WU-16 `points.json` | house value 6000 until WU-16 exposes it |
 * | Combat traits (air 80%, ranged 60/120/140, 60% floor) | WU-04 via `TfaBossNpc.getCombatTraits` | wired by the base class |
 * | Contribution per hit, HP HUD main bar | WU-16 / WU-14 via `TfaServices` | wired by the base class |
 */
internal object Bridge {

    // ------------------------------------------------------------------------------------------------- virus

    /** A [MoiaVirus] on the engine's Nex-style outbreak (WU-05) with `moia.json infect` numbers. */
    fun virus(config: MoiaConfig.Infect, random: Random): MoiaVirus {
        val host: VirusHost<Entity> = if (!config.coughAnim) EntityVirusHost else CoughAnimHost
        return EngineVirus(VirusOutbreak(virusSettings(config), random, host))
    }

    /** The engine settings for `moia.json infect`. */
    fun virusSettings(config: MoiaConfig.Infect): VirusSettings = VirusSettings(
        duration = config.duration,
        coughEvery = config.coughEvery,
        damageMin = config.dmg.first,
        damageMax = config.dmg.last,
        serpentineMultiplier = config.serpMult,
        npcDamage = config.binderDmg,
        coughText = "*Cough*",
    )

    /** [EntityVirusHost] plus the optional stock cough seq 11268 on players (`infect.coughAnim`). */
    private object CoughAnimHost : VirusHost<Entity> by EntityVirusHost {
        override fun applyCough(carrier: Entity, damage: Int, settings: VirusSettings) {
            EntityVirusHost.applyCough(carrier, damage, settings)
            if (carrier is Player) carrier.setAnimation(Animation(MoiaIds.SEQ_PLAYER_COUGH))
        }
    }

    private class EngineVirus(private val outbreak: VirusOutbreak<Entity>) : MoiaVirus {
        override fun infect(carrier: Entity) = outbreak.infect(carrier)
        override fun isInfected(carrier: Entity): Boolean = outbreak.isInfected(carrier)
        override fun ticksLeft(carrier: Entity): Int = outbreak.ticksLeft(carrier)
        override fun clear(carrier: Entity) = outbreak.clear(carrier)
        override fun clearAll() = outbreak.clearAll()
        override fun tick(population: Collection<Entity>) = outbreak.tick(population)
        override val carriers: Collection<Entity> get() = outbreak.carriers
    }

    // ----------------------------------------------------------------------------------------------- headbars

    /** Shows the enrage charge on [npc]: headbar 85 sliding from full to empty over [ticks] (spec §4.4, option a). */
    fun showChargeBar(npc: NPC, ticks: Int) {
        InterpolatingHitBar(MoiaIds.HEADBAR_CHARGE, MoiaIds.HEADBAR_CHARGE_SEGMENTS)
            .interpolate(1.0, 0.0, ticks)
            .show(npc)
    }

    fun hideChargeBar(npc: NPC) {
        InterpolatingHitBar(MoiaIds.HEADBAR_CHARGE, MoiaIds.HEADBAR_CHARGE_SEGMENTS).remove(npc)
    }

    // -------------------------------------------------------------------------------------------------- HUD

    /**
     * Mirrors the charge on the HP HUD extra bar (spec §4.4 option b: varp 5991 = 1, 16570 = remaining ticks,
     * 16571 = charge ticks, script 10152). Only for players whose boss HUD is open (opened by the HUD unit).
     */
    fun hudExtraBar(players: Collection<Player>, remaining: Int, total: Int, first: Boolean) {
        for (p in players) {
            val hud = p.hpHud ?: continue
            if (!hud.isOpen) continue
            if (first || !hud.hasExtraBar()) hud.showExtraBar(remaining, total) else hud.updateExtraBar(remaining, total, false)
        }
    }

    fun hideHudExtraBar(players: Collection<Player>) {
        for (p in players) {
            val hud = p.hpHud ?: continue
            if (hud.isOpen && hud.hasExtraBar()) hud.hideExtraBar()
        }
    }

    // ------------------------------------------------------------------------------------- arena entry, music

    /**
     * Arena entry presentation (spec §3.7): seq prefetch 10015 and the room music "Daughter of Darkness" (midi 892).
     * The fight starts on the first arena entry, so this is also the "encounter start" music. The raid-track unlock
     * (dbrow 17007, varp index 27 bit 28) belongs to WU-14 `hud/MusicUnlock`; until then a server music table entry
     * is unlocked if one exists, else the midi is just played.
     */
    fun onArenaEntry(player: Player) {
        player.packetDispatcher?.sendClientScript(MoiaIds.SCRIPT_PREFETCH)
        try {
            val music = Music.map[MUSIC_TRACK]
            if (music != null) player.music.unlock(music, true) else player.packetDispatcher?.sendMusic(MoiaIds.MIDI)
        } catch (e: Exception) {
            LoggerFactory.getLogger(Bridge::class.java).warn("Moia music failed for {}", player.username, e)
        }
    }

    /** Wiki "Daughter of Darkness": track #849, "played when fighting Moia" (notes S8). */
    private const val MUSIC_TRACK = "Daughter of Darkness"

    // ------------------------------------------------------------------------------------------ combat level

    /** Sends the displayed combat level (E12 extended info). */
    fun showCombatLevel(npc: NPC, level: Int) {
        if (level > 0 && npc.combatLevelChange != level) npc.setCombatLevelChange(level)
    }

    // -------------------------------------------------------------------------------------------- misc seams

    /** True when boss damage must skip [player] (`::tfa godmode`). */
    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    /**
     * The room's contribution pool (spec §3.10: "Moia or Dreadnoth 6000" [HOUSE]). WU-16 owns `points.json`; until
     * it exposes a lookup this returns the spec value.
     */
    @Suppress("UNUSED_PARAMETER")
    fun contributionPool(context: TfaEncounterContext): Int = 6000
}
