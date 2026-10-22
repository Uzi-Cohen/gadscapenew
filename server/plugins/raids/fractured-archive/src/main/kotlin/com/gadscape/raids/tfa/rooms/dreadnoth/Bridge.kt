package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.game.world.entity.player.status.PrayerDrainOnAttack
import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.skills.magic.spells.arceuus.hasWardOfArceuus
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.ForceMovement
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import com.zenyte.game.world.`object`.WorldObject

/**
 * The Dreadnoth room's single adapter to capabilities owned by other units (spec §2.7, `raids4-impl/ENGINE-*.md`,
 * `FRAMEWORK-*.md`). Everything the room needs from outside its package and the stock engine goes through here, so
 * wiring a capability that lands later is a one-file change. Status (also in `raids4-impl/ENC-dreadnoth.md`):
 *
 * | Capability | Owner | Status |
 * |---|---|---|
 * | Combat traits (fire 85%, ranged 100/80/80, freeze 40%, demonbane 25%, no floor; jelly traits) | WU-04 | wired by `TfaBossNpc.getCombatTraits` / `CombatTraitsBinding` |
 * | Prayer drain on every jelly attack, Ward of Arceuus −33% | WU-05 E11 `PrayerDrainOnAttack` | wired ([drainPrayerOnAttack]) |
 * | Ward of Arceuus −10% damage from demons (wiki: floor(10%) taken off) | engine (not in GadScape's Ward) | room-local [wardReduced] |
 * | Contribution per hit (boss + jellies), overkill capped | WU-16 via `TfaServices.contribution` | wired by `TfaNpc` |
 * | HP HUD single bar, npc 16665 | WU-06/WU-14 via `TfaServices.bossHud` | wired ([hudShow] / [hudHide]; updates by `TfaBossNpc`); while no sink is installed, [HudFallback] drives E15 `HpHud` itself |
 * | Godmode skip | WU-08 `TfaDebug` | wired ([isGodmode]) |
 * | Seq prefetch 10014 on arena entry | WU-14 `hud/Prefetch` | direct RUNCLIENTSCRIPT until it exists |
 * | Music 895 "Hellatine" + unlock (27,27) | WU-14 `hud/MusicUnlock` | plays midi 895 only; unlock not wired |
 * | Contribution pool size | WU-16 `points.json` | house value 6000 (spec §3.10) until WU-16 exposes it |
 * | Blood hazard tiles (loc 62609) | framework `encounter/Hazards` | room-local [BloodField] + direct loc spawn here |
 * | Telegraphs (4209 shadow, 4210 forming jelly) | framework `encounter/Telegraphs` | direct map graphic here |
 * | Room completion hand-off (stats, banking) | WU-12/16/19 | the raid does it around `onComplete` |
 */
internal object Bridge {

    // -------------------------------------------------------------------------------------------- HP HUD (E15)

    fun hudShow(boss: TfaBossNpc) = TfaServices.bossHud.onShown(boss)

    fun hudHide(boss: TfaBossNpc) = TfaServices.bossHud.onHidden(boss)

    /**
     * Until WU-14 installs `TfaServices.bossHud`, the room shows its own single bar on the engine's `HpHud` (E15,
     * interface 303, varp 1683 = 16665, varbits 6099/6100) to the players in the arena: opened on the fight start,
     * updated each tick, held at 0 during the death wave (spec §4.5 "Completion"), closed on completion or wipe.
     * It switches itself off (and closes what it opened) as soon as a real sink is installed.
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

    /** RUNCLIENTSCRIPT 10014 (seq prefetch, no args) on arena entry (§3.7). */
    fun prefetch(player: Player) {
        player.packetDispatcher.sendClientScript(TfaRoomId.DREADNOTH.prefetchScript)
    }

    /** Room music at fight start (§3.7): midi 895. The unlock (varp index 27 bit 27, dbrow 17006) is WU-14's. */
    fun roomMusic(player: Player) {
        player.packetDispatcher.sendMusic(TfaRoomId.DREADNOTH.midi)
    }

    // ---------------------------------------------------------------------------------- contribution (WU-16)

    /** §3.10: "Moia or Dreadnoth 6000" [HOUSE] until `points.json` (WU-16) exposes the pools. */
    fun contributionPool(): Int = CONTRIBUTION_POOL_FALLBACK

    private const val CONTRIBUTION_POOL_FALLBACK = 6000

    // ------------------------------------------------------------------------------------- statuses (WU-05)

    /** E11: drain on every jelly attack whatever its damage; Ward of Arceuus vs a demon −33%. */
    fun drainPrayerOnAttack(player: Player, attacker: Entity, points: Int): Int =
        PrayerDrainOnAttack.drain(player, attacker, points)

    /**
     * Ward of Arceuus vs a demonic attacker (Dreadnoth and the jellies are demons): the damage loses floor(10%) of
     * itself (wiki Ward of Arceuus, Mod Ash 10 Jun 2021). GadScape's Ward only blocks Arceuus spells, so the room
     * applies it to its own hits until the engine does.
     */
    fun wardReduced(player: Player, attacker: Entity, damage: Int): Int =
        if (damage > 0 && player.hasWardOfArceuus && CombatUtilities.isDemon(attacker)) damage - damage / 10 else damage

    // ------------------------------------------------------------------------------------------------ debug

    fun isGodmode(player: Player): Boolean = TfaDebug.isGodmode(player)

    // ------------------------------------------------------------------------------------- telegraphs (fw)

    /** A map graphic on a live tile (4209 landing shadow, 4210 forming jelly). */
    fun telegraph(spot: Int, tile: Location) {
        World.sendGraphics(Graphics(spot), tile)
    }

    // ------------------------------------------------------------------------------------ exact move (E-npc)

    /**
     * Moves [npc] to [to] now (if it is not there) and shows the flight from [from] between client cycles
     * [startCycle]..[endCycle] (OSRS exact move: the server position changes at once, the client interpolates).
     */
    fun exactMove(npc: NPC, from: Location, to: Location, startCycle: Int, endCycle: Int, direction: Int) {
        if (!npc.location.matches(to)) npc.setLocation(to)
        npc.forceMovement = ForceMovement(from, startCycle, to, endCycle, direction)
    }

    // ------------------------------------------------------------------------------------- hazards (fw)

    /**
     * The blood loc 62609 on a live tile, as the ToB template does (type 10, random rotation, no clipping: the loc
     * has blockwalk=no). A map loc already in that slot (the arena's edge candles 62535-62537 / 47447) is put back
     * when the blood goes, so the arena dressing survives.
     */
    class BloodLocs {
        private class Placed(val blood: WorldObject, val original: WorldObject?)

        private val placed = HashMap<Int, Placed>()

        fun spawn(tile: Location, rotation: Int) {
            val key = tile.positionHash
            if (key in placed) return
            val original = World.getObjectOfSlot(tile, WorldObject.DEFAULT_TYPE)?.let { WorldObject(it) }
            val blood = WorldObject(DreadnothIds.LOC_BLOOD, WorldObject.DEFAULT_TYPE, rotation, tile)
            World.spawnObject(blood)
            placed[key] = Placed(blood, original)
        }

        fun remove(tile: Location) {
            val p = placed.remove(tile.positionHash) ?: return
            World.removeObject(p.blood)
            p.original?.let { World.spawnObject(it) }
        }

        fun clear() {
            placed.keys.toList().forEach { key ->
                val p = placed.remove(key) ?: return@forEach
                World.removeObject(p.blood)
                p.original?.let { World.spawnObject(it) }
            }
        }
    }
}
