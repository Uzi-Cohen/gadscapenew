package com.gadscape.raids.tfa.hud

import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.zenyte.game.world.entity.player.Player
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap

/**
 * The boss HP HUD, interface 303 (spec §3.7 "Boss HP HUD", WU-14 + E15), installed as `TfaServices.bossHud`:
 * - [onShown]: the boss appeared or transformed: every viewer of its room gets `HpHud.open(npcId, max)` (varp 1683
 *   = the boss's current npc id, varbits 6099/6100), or `switchNpc` if a bar is already open (Guardian 16659 ↔ 16660,
 *   Ocellus 16674 → 16675 → 16677);
 * - [onHpChanged]: current / max to every viewer;
 * - [onHidden]: the bar closes for the viewers.
 * Viewers = the living members in the boss's arena, the members who died in that encounter, and the raid's spectators.
 * A player who enters the arena while a boss is shown gets it with [showTo] (the raid calls it on arena entry).
 *
 * The extra bar (varp 5991, varbits 16570/16571, RUNCLIENTSCRIPT 10152: Bork/Smorks, Dagon'hai, Ocellus P1 meter,
 * Moia's charge) stays with the encounters, which call `HpHud.showExtraBar / updateExtraBar / hideExtraBar` on the
 * same open bar.
 */
object BossHpHud : BossHudSink {

    private val log = LoggerFactory.getLogger(BossHpHud::class.java)

    /** The boss each context currently shows (one bar per room). */
    private val shown: MutableMap<TfaEncounterContext, TfaBossNpc> = Collections.synchronizedMap(WeakHashMap())

    /** The npc id last sent to each viewer. */
    private val viewerNpc: MutableMap<Player, Int> = Collections.synchronizedMap(WeakHashMap())

    override fun onShown(boss: TfaBossNpc) {
        val ctx = contextOf(boss) ?: return
        shown[ctx] = boss
        for (p in viewers(ctx)) showTo(p, boss)
    }

    override fun onHpChanged(boss: TfaBossNpc) {
        val ctx = contextOf(boss) ?: return
        if (shown[ctx] !== boss) return
        val max = boss.maxHitpoints.coerceAtLeast(1)
        val cur = boss.hitpoints.coerceIn(0, max)
        for (p in viewers(ctx)) {
            val hud = p.hpHud ?: continue
            if (!hud.isOpen || viewerNpc[p] != boss.id) {
                showTo(p, boss)
                continue
            }
            hud.updateMaxValue(max)
            hud.updateValue(cur)
        }
    }

    override fun onHidden(boss: TfaBossNpc) {
        val ctx = contextOf(boss) ?: return
        if (shown[ctx] !== boss) return
        shown.remove(ctx)
        for (p in viewers(ctx)) hideFor(p)
    }

    /** Opens (or switches) [boss]'s bar for [player]. */
    @JvmStatic
    fun showTo(player: Player, boss: TfaBossNpc) {
        try {
            val hud = player.hpHud ?: return
            val max = boss.maxHitpoints.coerceAtLeast(1)
            val cur = boss.hitpoints.coerceIn(0, max)
            if (hud.isOpen) hud.switchNpc(boss.id, cur, max)
            else {
                hud.open(boss.id, max)
                hud.updateValue(cur)
            }
            viewerNpc[player] = boss.id
        } catch (e: Exception) {
            log.warn("Fractured Archive: HP HUD for {} failed", player.username, e)
        }
    }

    /** Shows the bar of the boss of [ctx] (if any) to [player] (arena entry, spectating). */
    @JvmStatic
    fun showCurrent(player: Player, ctx: TfaEncounterContext) {
        val boss = shown[ctx] ?: return
        if (boss.isFinished) return
        showTo(player, boss)
    }

    @JvmStatic
    fun hideFor(player: Player) {
        viewerNpc.remove(player)
        try {
            val hud = player.hpHud ?: return
            if (hud.isOpen) hud.close()
        } catch (e: Exception) {
            log.warn("Fractured Archive: closing the HP HUD failed for {}", player.username, e)
        }
    }

    /** Forgets the boss of [ctx] (room teardown). */
    @JvmStatic
    fun clear(ctx: TfaEncounterContext) {
        shown.remove(ctx)
    }

    private fun contextOf(boss: TfaBossNpc): TfaEncounterContext? = try {
        boss.context
    } catch (e: Exception) {
        null
    }

    private fun viewers(ctx: TfaEncounterContext): List<Player> {
        val rt = ctx as? RoomRuntime ?: return ctx.playersInArena()
        val raid = rt.raid
        val out = LinkedHashSet<Player>()
        out += ctx.playersInArena()
        for (m in raid.members) if (!m.left && m.dead && m.diedIn == rt.room) out += m.player
        out += raid.spectators.playersWatching(rt.room)
        return out.filter { !it.isFinished }
    }

    fun describe(): String = "BossHpHud(${shown.size} bars shown)"

    override fun toString(): String = "BossHpHud"
}
