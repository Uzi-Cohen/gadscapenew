package com.gadscape.raids.tfa.hud

import com.gadscape.raids.tfa.BossHudSink
import com.gadscape.raids.tfa.encounter.TfaBossNpc
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.RoomRuntime
import com.gadscape.raids.tfa.raid.TfaRoomId
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
 *
 * Composite bars ([registerComposite]): one HUD npc carrying two bosses, main bar + extra bar. Bork and the Smorks
 * (§3.7, `rooms/bork/Bridge.kt` DualHud contract): varp 1683 = 16642 (its param 510 title, params 2767/2768 the two
 * 16 px bars [CACHE]), main = Bork 16640, extra = the Smorks 16641; a dead boss's bar stays at 0 [INF] and the HUD
 * closes when both are gone. The room's own DualHud stands down as soon as this sink is installed.
 */
object BossHpHud : BossHudSink {

    private val log = LoggerFactory.getLogger(BossHpHud::class.java)

    /** The boss each context currently shows (one bar per room). */
    private val shown: MutableMap<TfaEncounterContext, TfaBossNpc> = Collections.synchronizedMap(WeakHashMap())

    /** The npc id last sent to each viewer. */
    private val viewerNpc: MutableMap<Player, Int> = Collections.synchronizedMap(WeakHashMap())

    /** A HUD npc that shows [mainId] on the main bar and [extraId] on the extra bar of [room]. */
    data class Composite(val room: TfaRoomId, val hudNpc: Int, val mainId: Int, val extraId: Int)

    private class CompositeState(val spec: Composite) {
        var main: TfaBossNpc? = null
        var extra: TfaBossNpc? = null
        var mainGone = false
        var extraGone = false
        var mainMax = 1
        var extraMax = 1

        fun values(): IntArray {
            val m = main
            val e = extra
            if (m != null && m.maxHitpoints > 0) mainMax = m.maxHitpoints
            if (e != null && e.maxHitpoints > 0) extraMax = e.maxHitpoints
            val mc = if (mainGone || m == null) 0 else m.hitpoints.coerceIn(0, mainMax)
            val ec = if (extraGone || e == null) 0 else e.hitpoints.coerceIn(0, extraMax)
            return intArrayOf(mc, mainMax, ec, extraMax)
        }
    }

    private val composites = java.util.concurrent.ConcurrentHashMap<TfaRoomId, Composite>()

    private val compositeShown: MutableMap<TfaEncounterContext, CompositeState> =
        Collections.synchronizedMap(WeakHashMap())

    /** Last composite values sent to each viewer (main, mainMax, extra, extraMax). */
    private val compositeSent: MutableMap<Player, IntArray> = Collections.synchronizedMap(WeakHashMap())

    init {
        // Bork and the Smorks: HUD handler 16642 "Bork and the Smorks" [CACHE npc 16642 params 510/2767/2768].
        registerComposite(Composite(TfaRoomId.BORK, TfaIds.Npc.BORK_SMORKS_HUD, TfaIds.Npc.BORK, TfaIds.Npc.SMORKS))
    }

    @JvmStatic
    fun registerComposite(composite: Composite) {
        composites[composite.room] = composite
    }

    private fun compositeOf(ctx: TfaEncounterContext, boss: TfaBossNpc): Composite? {
        val c = composites[ctx.room] ?: return null
        return if (boss.id == c.mainId || boss.id == c.extraId) c else null
    }

    private fun compositeEvent(ctx: TfaEncounterContext, c: Composite, boss: TfaBossNpc, hidden: Boolean) {
        val state = compositeShown.getOrPut(ctx) { CompositeState(c) }
        if (boss.id == c.mainId) {
            state.main = boss
            state.mainGone = hidden
        } else {
            state.extra = boss
            state.extraGone = hidden
        }
        val bothGone = (state.main == null || state.mainGone) && (state.extra == null || state.extraGone)
        if (hidden && bothGone) {
            compositeShown.remove(ctx)
            for (p in viewers(ctx)) hideFor(p)
            return
        }
        for (p in viewers(ctx)) showCompositeTo(p, state)
    }

    private fun showCompositeTo(player: Player, state: CompositeState) {
        try {
            val hud = player.hpHud ?: return
            val v = state.values()
            val last = compositeSent[player]
            if (!hud.isOpen || viewerNpc[player] != state.spec.hudNpc || !hud.hasExtraBar() || last == null) {
                if (hud.isOpen) hud.close()
                hud.openWithExtraBar(state.spec.hudNpc, v[0], v[1], v[2], v[3])
                viewerNpc[player] = state.spec.hudNpc
                compositeSent[player] = v
                return
            }
            if (last[1] != v[1]) hud.updateMaxValue(v[1])
            if (last[0] != v[0]) hud.updateValue(v[0])
            if (last[2] != v[2] || last[3] != v[3]) hud.updateExtraBar(v[2], v[3], false)
            compositeSent[player] = v
        } catch (e: Exception) {
            log.warn("Fractured Archive: composite HP HUD for {} failed", player.username, e)
        }
    }

    override fun onShown(boss: TfaBossNpc) {
        val ctx = contextOf(boss) ?: return
        compositeOf(ctx, boss)?.let { return compositeEvent(ctx, it, boss, hidden = false) }
        shown[ctx] = boss
        for (p in viewers(ctx)) showTo(p, boss)
    }

    override fun onHpChanged(boss: TfaBossNpc) {
        val ctx = contextOf(boss) ?: return
        compositeOf(ctx, boss)?.let { c ->
            val state = compositeShown[ctx]
            // Before the spawn (applyScaling) nothing is shown yet; a hit after the death keeps the bar at 0.
            if (state != null) compositeEvent(ctx, c, boss, hidden = if (boss.id == c.mainId) state.mainGone else state.extraGone)
            return
        }
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
        compositeOf(ctx, boss)?.let { c ->
            if (compositeShown.containsKey(ctx)) compositeEvent(ctx, c, boss, hidden = true)
            return
        }
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
            if (hud.isOpen && hud.hasExtraBar() && viewerNpc[player] in compositeHudNpcs()) {
                hud.close()
                hud.open(boss.id, max)
                hud.updateValue(cur)
            } else if (hud.isOpen) hud.switchNpc(boss.id, cur, max)
            else {
                hud.open(boss.id, max)
                hud.updateValue(cur)
            }
            viewerNpc[player] = boss.id
            compositeSent.remove(player)
        } catch (e: Exception) {
            log.warn("Fractured Archive: HP HUD for {} failed", player.username, e)
        }
    }

    /** Shows the bar of the boss of [ctx] (if any) to [player] (arena entry, spectating). */
    @JvmStatic
    fun showCurrent(player: Player, ctx: TfaEncounterContext) {
        compositeShown[ctx]?.let { return showCompositeTo(player, it) }
        val boss = shown[ctx] ?: return
        if (boss.isFinished) return
        showTo(player, boss)
    }

    @JvmStatic
    fun hideFor(player: Player) {
        viewerNpc.remove(player)
        compositeSent.remove(player)
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
        compositeShown.remove(ctx)
    }

    private fun compositeHudNpcs(): Set<Int> = composites.values.mapTo(HashSet()) { it.hudNpc }

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

    fun describe(): String = "BossHpHud(${shown.size} bars, ${compositeShown.size} composite bars shown)"

    override fun toString(): String = "BossHpHud"
}
