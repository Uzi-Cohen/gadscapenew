package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.game.world.Lines
import com.gadscape.game.world.entity.player.status.PlayerBurn
import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.zenyte.game.model.music.Music
import com.zenyte.game.world.Position
import com.zenyte.game.world.Projectile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject
import org.slf4j.LoggerFactory

/**
 * The Bork and the Smorks room's single adapter to capabilities owned by other units (spec §2.7 E1-E18, §3 framework;
 * `raids4-impl/ENGINE-*.md`, `FRAMEWORK-*.md`). Everything the room needs from outside its package and the stock engine
 * goes through here, so wiring a capability that lands later is a one-file change. Status (also in
 * `raids4-impl/ENC-bork.md` §5):
 *
 * | Capability | Owner | Status |
 * |---|---|---|
 * | Combat traits E5-E9 (earth 40/85%, ranged light/std/heavy, 60% floor on Bork only, freeze immunity) | WU-04 | wired: `TfaBossNpc.getCombatTraits` / `CombatTraitsBinding` read [Bork.traits] / [Smorks.traits] on every hit |
 * | Flat armour + magic resistance, guaranteed melee while dazed (E8) | WU-04 | wired: [Bork.traits] answers from the brain (12 → 10 below 66%, 0 / 0 + guaranteed melee dazed) |
 * | Player burn stacks (E10) | WU-05 `PlayerBurn` | wired ([burn]) |
 * | Line helper (E18) for the cart shadow | WU-07 `Lines.bresenham` | wired ([line]) |
 * | Dual-bar HP HUD (E15: interface 303, npc 16642, extra bar varp 5991 / varbits 16570-16571 / script 10152) | WU-06 `HpHud` + WU-14 `TfaServices.bossHud` | the room drives `HpHud.openWithExtraBar` itself ([DualHud]) while no sink is installed; once WU-14 installs one, the sink gets every Bork/Smorks event from `TfaBossNpc` and must show 16642 with Bork as the main bar and the Smorks as the extra bar |
 * | Contribution per hit, overkill capped (both NPCs) | WU-16 via `TfaServices.contribution` | wired by `TfaNpc` (no-op until WU-16 installs it) |
 * | Contribution pool size | WU-16 `points.json` | house 6000 (spec §3.10) until WU-16 exposes it ([contributionPool]) |
 * | Seq prefetch 10010 on arena entry | WU-14 `hud/Prefetch` | direct RUNCLIENTSCRIPT ([prefetch]) |
 * | Music 888 "Bork's Beats" + unlock (varp index 27 bit 25) | WU-14 `hud/MusicUnlock` | `Music` track unlock if the server knows the track, else the midi only ([music]) |
 * | `tfa_client_primary<slot>` for Bork's target | WU-14 HUD varbits | direct varbits ([PrimaryVarbits]) |
 * | Godmode skip | WU-08 `TfaDebug` | wired ([isGodmode]) |
 * | Oil hazard tiles (loc 62544) | framework `encounter/Hazards` (not written) | room-local [OilField] + [OilLocs] |
 * | Telegraphs / map graphics with delay (dust, shadow, markers) | framework `encounter/Telegraphs` (not written) | direct map graphics ([graphic]) |
 * | Second NPC spotanim slot (stun stars 4035 over the daze spot, Smorks flash 4044 over 4043) | engine (slot 0 only in `RsprotExtendedInfo`) | map graphic on the NPC's centre tile ([graphic]); exact for the size-3 Smorks, half a tile NE of Bork's centre |
 * | Encounter start on the first transporter use, room exit, stats, banking | WU-12/13/16/19 | done by the raid around `start()` / `onComplete()` |
 */
internal object Bridge {

    private val log = LoggerFactory.getLogger(Bridge::class.java)

    // ------------------------------------------------------------------------------------- contribution (WU-16)

    /** §3.10: "Ezekiel or Bork 6000" [HOUSE] until `points.json` (WU-16) exposes the pools. */
    fun contributionPool(): Int = CONTRIBUTION_POOL_FALLBACK

    private const val CONTRIBUTION_POOL_FALLBACK = 6000

    // ------------------------------------------------------------------------------------------- lines (E18)

    /** Packed Bresenham tiles from (x0, y0) to (x1, y1), both included (core `Lines`, WU-07). */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int): IntArray = Lines.bresenham(x0, y0, x1, y1)

    // ------------------------------------------------------------------------------------------------ debug

    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    // ------------------------------------------------------------------------------- statuses (E10, WU-05)

    /** Adds [stacks] burn stacks (5 max, 1 damage per stack every 4 t, hitsplat BURN 74, prayers do not reduce it). */
    fun burn(player: Player, stacks: Int): Int =
        if (stacks <= 0 || isGodmode(player)) 0 else PlayerBurn.apply(player, stacks)

    // ------------------------------------------------------------------------- client side (WU-14 pending)

    /** RUNCLIENTSCRIPT 10010 (seq prefetch, no args) on arena entry (§3.7). */
    fun prefetch(player: Player) {
        try {
            player.packetDispatcher.sendClientScript(BorkIds.PREFETCH)
        } catch (e: Exception) {
            log.warn("Bork prefetch failed for {}", player.username, e)
        }
    }

    /** Fight start: midi 888 "Bork's Beats", unlocked through dbrow 17004 (varp index 27, bit 25) when known (§3.7). */
    fun music(player: Player) {
        try {
            val track = Music.map[TRACK]
            if (track != null) player.music.unlock(track, true)
            else player.packetDispatcher.sendMusic(BorkIds.MIDI)
        } catch (e: Exception) {
            log.warn("Bork music failed for {}", player.username, e)
        }
    }

    private const val TRACK = "Bork's Beats"

    // --------------------------------------------------------------------------- graphics and projectiles

    /** A map graphic on a live tile, [delayCycles] client cycles late (telegraphs, dust, impacts). */
    fun graphic(spot: Int, tile: Location, delayCycles: Int = 0) {
        World.sendGraphics(Graphics(spot, delayCycles.coerceIn(0, 65535), 0), tile)
    }

    /** A projectile from [from] (its middle) to [to] (an entity it follows, or a tile). */
    fun projectile(from: NPC, to: Position, projectile: Projectile) {
        World.sendProjectile(from, to, projectile)
    }

    // ------------------------------------------------------------------------------------------- movement

    /**
     * One chase step of [npc] toward [target] with the engine's NPC follow (no path-finding: like live NPCs, he walks
     * straight and can be held behind the cart). Returns false if he could not move.
     */
    fun chaseStep(npc: NPC, target: Player): Boolean {
        if (npc.isMovementRestricted) return false
        npc.resetWalkSteps()
        return npc.calcFollow(target, 1, true, false, false)
    }

    // ------------------------------------------------------------------------------------- HP HUD (E15)

    fun hudShow(boss: TfaBossNpc) = TfaServices.bossHud.onShown(boss)

    fun hudHide(boss: TfaBossNpc) = TfaServices.bossHud.onHidden(boss)

    /**
     * The "Bork and the Smorks" HUD (§3.7): interface 303 with varp 1683 = 16642 (title from its param 510, two 16 px
     * bars from params 2767/2768), main bar = Bork (varbits 6099/6100), extra bar = the Smorks (varp 5991 = 1,
     * varbits 16570/16571, RUNCLIENTSCRIPT 10152). A dead boss's bar stays at 0 [INF].
     *
     * Driven by the room for the players in the arena while no `TfaServices.bossHud` sink is installed (WU-14 pending);
     * it closes what it opened and stops as soon as a sink appears.
     */
    class DualHud(private val npcId: Int) {
        private class Shown(var main: Int, var mainMax: Int, var extra: Int, var extraMax: Int)

        private val shown = HashMap<Player, Shown>()

        private val active: Boolean get() = TfaServices.bossHud === BossHudSink.NONE

        fun sync(players: Collection<Player>, main: Int, mainMax: Int, extra: Int, extraMax: Int) {
            if (!active) {
                closeAll()
                return
            }
            val gone = shown.keys.filter { it !in players }
            for (p in gone) close(p)
            for (p in players) {
                if (p.isFinished) continue
                val hud = p.hpHud
                val s = shown[p]
                if (s == null || !hud.isOpen) {
                    hud.openWithExtraBar(npcId, main, mainMax, extra, extraMax)
                    shown[p] = Shown(main, mainMax, extra, extraMax)
                    continue
                }
                if (s.mainMax != mainMax) hud.updateMaxValue(mainMax)
                if (s.main != main) hud.updateValue(main)
                if (s.extra != extra || s.extraMax != extraMax) hud.updateExtraBar(extra, extraMax, false)
                s.main = main
                s.mainMax = mainMax
                s.extra = extra
                s.extraMax = extraMax
            }
        }

        /** Players currently shown the HUD (tests and `::tfa dump`). */
        val viewers: Int get() = shown.size

        private fun close(p: Player) {
            shown.remove(p)
            if (!p.isFinished) p.hpHud.close()
        }

        fun closeAll() {
            shown.keys.toList().forEach(::close)
        }
    }

    // -------------------------------------------------------------------------------- primary varbits (§3.7)

    /** `tfa_client_primary0..7` (varbits 16408-16415) = 1 on the slot of Bork's target, for every member. */
    class PrimaryVarbits {
        private var current: Player? = null
        private var sent = false

        fun set(members: List<Player>, target: Player?) {
            if (sent && target === current) return
            current = target
            sent = true
            val slots = minOf(members.size, BorkIds.PRIMARY_SLOTS)
            for (viewer in members) {
                if (viewer.isFinished) continue
                for (slot in 0 until slots) {
                    viewer.varManager.sendBit(BorkIds.varbitPrimary(slot), if (members[slot] === target) 1 else 0)
                }
            }
        }

        fun clear(members: List<Player>) {
            if (sent && current != null) set(members, null)
        }
    }

    // ------------------------------------------------------------------------------- hazards: the oil locs

    /**
     * The oil puddle loc 62544 on live tiles (type `smorks.oilLocType`, rotation = the splat's). A map loc already in
     * that slot is put back when the oil goes (the arena has none of type 10 off the cart, but the instance may).
     */
    class OilLocs(private val type: Int) {
        private class Placed(val loc: WorldObject, val original: WorldObject?)

        private val placed = HashMap<Int, Placed>()

        val size: Int get() = placed.size

        fun spawn(tile: Location, rotation: Int) {
            val key = tile.positionHash
            if (key in placed) return
            val original = World.getObjectOfSlot(tile, type)?.let { WorldObject(it) }
            val loc = WorldObject(BorkIds.LOC_OIL, type, rotation and 3, tile)
            World.spawnObject(loc)
            placed[key] = Placed(loc, original)
        }

        /** Loc anim [seq] on the puddle at [tile] (the fade 14576). */
        fun animate(tile: Location, seq: Int) {
            val p = placed[tile.positionHash] ?: return
            World.sendObjectAnimation(p.loc, Animation(seq))
        }

        fun remove(tile: Location) {
            val p = placed.remove(tile.positionHash) ?: return
            World.removeObject(p.loc)
            p.original?.let { World.spawnObject(it) }
        }

        fun clear() {
            for (key in placed.keys.toList()) {
                val p = placed.remove(key) ?: continue
                World.removeObject(p.loc)
                p.original?.let { World.spawnObject(it) }
            }
        }
    }
}
