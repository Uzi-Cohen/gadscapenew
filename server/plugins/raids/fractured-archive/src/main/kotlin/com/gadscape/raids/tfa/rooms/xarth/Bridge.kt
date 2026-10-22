package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.game.world.Lines
import com.gadscape.game.world.entity.player.status.PlayerBurn
import com.gadscape.game.world.entity.player.status.PlayerStatuses
import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.points.PointsConfig
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.skills.magic.spells.arceuus.hasWardOfArceuus
import com.zenyte.game.model.music.Music
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.combat.SpellElement
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import com.zenyte.game.world.entity.player.action.combat.magic.CombatSpell
import org.slf4j.LoggerFactory

/**
 * The Xarth room's single adapter to capabilities owned by other units (spec §2.7, `raids4-impl/ENGINE-*.md`,
 * `FRAMEWORK-*.md`). Everything the room needs from outside its package and the stock engine goes through here, so
 * wiring a capability that lands later is a one-file change. Status (also in `raids4-impl/ENC-xarth.md` §5):
 *
 * | Capability | Owner | Status |
 * |---|---|---|
 * | Combat traits (floor 102, water 85%, ranged 125/230/155, demonbane 80%, freeze-immune; corpse traits) | WU-04 E5-E9 | wired by `TfaBossNpc.getCombatTraits` / `CombatTraitsBinding` |
 * | Player burn stacks, BURN splat, buff-bar varbit 10964 | WU-05 E10 `PlayerBurn` | wired ([burn]) |
 * | Statuses cleared on a raid death | WU-05 `PlayerStatuses` / WU-15 death handler | wired here too ([clearStatuses], idempotent) |
 * | Spell-element hooks (douse, fire → extra corpse) and no-delay water/ice | WU-07 E17 `NPC.onSpellHit` / `getSpellHitDelay` | wired ([isWaterOrIce], [isWater], [isFire]) |
 * | Bresenham ray and the engine projectile LOS | WU-07 E18 `Lines` | wired ([bresenham], [losClipped]) |
 * | Contribution per hit (Xarth + corpses), overkill capped | WU-16 via `TfaServices.contribution` | wired by `TfaNpc` |
 * | Contribution pool size | WU-16 `points.json` (`PointsConfig`) | wired ([contributionPool]); 7000 if the section cannot be read |
 * | HP HUD single bar, npc 16684 | WU-06/WU-14 via `TfaServices.bossHud` | wired ([hudShow] / [hudHide]); until a sink is installed [HudFallback] drives E15 `HpHud` itself |
 * | Godmode skip | WU-08 `TfaDebug` | wired ([isGodmode]) |
 * | Seq prefetch 10030 on arena entry | WU-14 `hud/Prefetch` | direct RUNCLIENTSCRIPT until it exists ([prefetch]) |
 * | Music 896 "Forged Through Fire" + unlock (27,29) | WU-14 `hud/MusicUnlock` | the music table entry or the bare midi ([playMusic]); the dbrow varp bit is not set here |
 * | Ambience config 12 | WU-14 | not wired (no ambience API in the server) |
 * | Ward of Arceuus −10% damage from demons | engine (not in GadScape's Ward) | room-local [wardReduced] |
 * | Hazard tiles (flames 62713) | framework `encounter/Hazards` (not written) | room-local [FlameField] + [XarthRoom] loc spawns |
 * | Telegraphs (bomb tiles 4379) | framework `encounter/Telegraphs` (not written) | direct map graphics in [XarthRoom] |
 * | Room completion hand-off (stats row 17141, banking, exit 3 t) | WU-12/16/19 | the raid does it around `onComplete` |
 */
internal object Bridge {

    private val log = LoggerFactory.getLogger(Bridge::class.java)

    // -------------------------------------------------------------------------------------------- lines (E18)

    fun bresenham(x0: Int, y0: Int, x1: Int, y1: Int): IntArray = Lines.bresenham(x0, y0, x1, y1)

    fun tileX(packed: Int): Int = Lines.tileX(packed)

    fun tileY(packed: Int): Int = Lines.tileY(packed)

    /** The engine's projectile clipping from [from] (his centre tile) to [target] (`blast.losTest = "engine"`). */
    fun losClipped(from: Location, target: Player): Boolean = Lines.isClipped(from, target.location, 1, target.size)

    // ------------------------------------------------------------------------------------------- spells (E17)

    fun isWater(element: SpellElement?): Boolean = element == SpellElement.WATER

    fun isFire(element: SpellElement?): Boolean = element == SpellElement.FIRE

    /** DOC: "Ice and water spells do not have any delay when used on corpses" (standard water spells, Ancient ice). */
    fun isWaterOrIce(spell: CombatSpell): Boolean = SpellElement.of(spell) == SpellElement.WATER || SpellElement.isAncientIce(spell)

    // ------------------------------------------------------------------------------------- statuses (WU-05)

    /** E10: stacks of 1 damage per 4 t (10 hits each, max 5), BURN splat 74, not reduced by prayer. */
    fun burn(player: Player, stacks: Int) {
        if (stacks > 0 && !player.isDead && !player.isFinished) PlayerBurn.apply(player, stacks)
    }

    /** Burn, weary, bleed, virus off (a death in the room). WU-15's death handler does it too; idempotent. */
    fun clearStatuses(player: Player) {
        try {
            PlayerStatuses.clear(player)
        } catch (t: Throwable) {
            log.warn("Xarth: clearing statuses of {} failed", player.username, t)
        }
    }

    /**
     * Ward of Arceuus vs a demonic attacker (Xarth is a demon, demonbane 80%): the damage loses floor(10%) of itself
     * (wiki Ward of Arceuus). GadScape's Ward only blocks Arceuus spells, so the room applies it to its own hits.
     */
    fun wardReduced(player: Player, attacker: Entity, damage: Int): Int =
        if (damage > 0 && player.hasWardOfArceuus && CombatUtilities.isDemon(attacker)) damage - damage / 10 else damage

    // ----------------------------------------------------------------------------------------------- debug

    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    // ---------------------------------------------------------------------------------- contribution (WU-16)

    /** §3.10: "Dagon'hai or Xarth 7000" from `points.json`. */
    fun contributionPool(context: TfaEncounterContext): Int =
        try {
            context.config[PointsConfig].pool(TfaRoomId.XARTH)
        } catch (t: Throwable) {
            CONTRIBUTION_POOL_FALLBACK
        }

    private const val CONTRIBUTION_POOL_FALLBACK = 7000

    // ------------------------------------------------------------------------------ prefetch and music (WU-14)

    /** RUNCLIENTSCRIPT 10030 (seq prefetch, no args) on arena entry (§3.7). */
    fun prefetch(player: Player) {
        player.packetDispatcher?.sendClientScript(TfaRoomId.XARTH.prefetchScript)
    }

    /** "Forged Through Fire" (dbrow 17008, midi 896) at the fight start (§3.7). The unlock bit (27,29) is WU-14's. */
    fun playMusic(players: Collection<Player>) {
        val music = try {
            Music.map[TRACK]
        } catch (t: Throwable) {
            null
        }
        for (p in players) {
            try {
                if (music != null) p.music.unlock(music, true) else p.packetDispatcher?.sendMusic(TfaRoomId.XARTH.midi)
            } catch (t: Throwable) {
                log.warn("Xarth music failed for {}", p.username, t)
            }
        }
    }

    const val TRACK = "Forged Through Fire"

    // -------------------------------------------------------------------------------------------- HP HUD (E15)

    fun hudShow(boss: TfaBossNpc) = TfaServices.bossHud.onShown(boss)

    fun hudHide(boss: TfaBossNpc) = TfaServices.bossHud.onHidden(boss)

    /**
     * Until WU-14 installs `TfaServices.bossHud`, the room shows its own single bar on the engine's `HpHud` (E15,
     * interface 303, varp 1683 = 16684, title "Xarth" from param 510) to the players in the arena: opened on the fight
     * start, updated each tick (hits and corpse heals), 0 while he dies, closed on completion or wipe. It switches
     * itself off (and closes what it opened) as soon as a real sink is installed.
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
                val hud = p.hpHud ?: continue
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
            }
            lastMax = max
        }

        private fun close(p: Player) {
            shown.remove(p)
            if (!p.isFinished) p.hpHud?.close()
        }

        fun closeAll() {
            shown.keys.toList().forEach(::close)
        }
    }
}
