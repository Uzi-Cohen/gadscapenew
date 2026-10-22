package com.gadscape.raids.tfa.encounter

import com.gadscape.raids.tfa.debug.TfaDebug
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.`object`.WorldObject
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap

/**
 * Timed hazard tiles (spec §2.1 `encounter/Hazards.kt`: oil, blood, flames, void), resolved by the raid at the end of
 * each tick after the encounter (§2.5 step 4: "hazard tiles apply damage to players standing on them at the end of
 * the tick").
 *
 * An encounter makes one [HazardField] per hazard kind and adds tiles to it:
 * ```
 * private val oil = Hazards.field(context, "oil", HazardField.Spec(locId = TfaIds.Loc.BORK_OIL, damage = 6..7,
 *     onStand = { p -> PlayerBurn.add(p) }))   // values from the room's config, ids from TfaIds
 * oil.add(tile, lifetime = cfg.oil.lifetimeTicks)          // static tile
 * ```
 * The raid ticks every field of the active encounter ([tick]) and clears them on teardown ([clear]); a standalone
 * debug run calls [tick] itself. Godmode players (`::tfa godmode`) take no hazard damage.
 */
object Hazards {

    private val log = LoggerFactory.getLogger(Hazards::class.java)
    private val fields: MutableMap<TfaEncounterContext, MutableList<HazardField>> = Collections.synchronizedMap(WeakHashMap())

    /** A new field of [context] (keep the returned object; [name] is for dumps). */
    @JvmStatic
    fun field(context: TfaEncounterContext, name: String, spec: HazardField.Spec): HazardField {
        val f = HazardField(context, name, spec)
        synchronized(fields) { fields.getOrPut(context) { ArrayList() } += f }
        return f
    }

    @JvmStatic
    fun fieldsOf(context: TfaEncounterContext): List<HazardField> = synchronized(fields) { fields[context]?.toList() ?: emptyList() }

    /** Expires tiles and damages players standing on them (raid tick, after the encounter). */
    @JvmStatic
    fun tick(context: TfaEncounterContext) {
        for (f in fieldsOf(context)) {
            try {
                f.tick()
            } catch (e: Exception) {
                log.error("Fractured Archive: hazard field {} failed", f.name, e)
            }
        }
    }

    /** Removes every tile of every field of [context] (teardown, wipe). */
    @JvmStatic
    fun clear(context: TfaEncounterContext) {
        val list = synchronized(fields) { fields.remove(context) } ?: return
        for (f in list) f.clear()
    }
}

/**
 * Pure bookkeeping of timed tiles: tile → expiry tick, plus the tick counter. Unit-tested in `HazardTilesTest`.
 */
class HazardTiles {
    private val expiry = LinkedHashMap<StaticTile, Int>()

    var now: Int = 0
        private set

    /** Adds (or refreshes) [tile] for [lifetime] ticks (≤ 0 = until removed). Returns true if it was new. */
    fun add(tile: StaticTile, lifetime: Int): Boolean {
        val isNew = tile !in expiry
        expiry[tile] = if (lifetime <= 0) Int.MAX_VALUE else now + lifetime
        return isNew
    }

    fun remove(tile: StaticTile): Boolean = expiry.remove(tile) != null

    operator fun contains(tile: StaticTile): Boolean = tile in expiry

    val tiles: Set<StaticTile> get() = expiry.keys.toSet()

    val size: Int get() = expiry.size

    /** Advances one tick; returns the tiles that expired. */
    fun advance(): List<StaticTile> {
        now++
        val gone = expiry.filterValues { it <= now }.keys.toList()
        for (t in gone) expiry.remove(t)
        return gone
    }

    fun clear(): List<StaticTile> {
        val all = expiry.keys.toList()
        expiry.clear()
        return all
    }
}

/**
 * One kind of hazard tile of an encounter. Tiles are static; locs and graphics go to the translated tile.
 */
class HazardField internal constructor(val context: TfaEncounterContext, val name: String, val spec: Spec) {

    /**
     * @property locId loc spawned on each tile while it lasts (type [locType]), or -1 for none.
     * @property graphic map graphic shown on each tile, re-sent every [graphicEvery] ticks (0 = once), or -1.
     * @property damage damage per [damageEvery] ticks to each player standing on a tile at the end of the tick
     *   (`0..0` = none). Rolled with the encounter's random.
     * @property hitType hitsplat type of that damage.
     * @property onStand extra effect on a player standing on a tile at the end of a damage tick (e.g. a burn stack).
     */
    data class Spec(
        val locId: Int = -1,
        val locType: Int = 22,
        val graphic: Int = -1,
        val graphicEvery: Int = 0,
        val damage: IntRange = 0..0,
        val damageEvery: Int = 1,
        val hitType: HitType = HitType.TYPELESS,
        val onStand: ((Player) -> Unit)? = null,
    )

    private val state = HazardTiles()
    private val locs = HashMap<StaticTile, WorldObject>()

    val tiles: Set<StaticTile> get() = state.tiles

    operator fun contains(tile: StaticTile): Boolean = tile in state

    /** Adds [tile] for [lifetime] ticks (≤ 0 = until removed). */
    fun add(tile: StaticTile, lifetime: Int, rotation: Int = 0) {
        val isNew = state.add(tile, lifetime)
        if (!isNew) return
        val live = context.instance.translate(tile)
        if (spec.locId >= 0) {
            val obj = WorldObject(spec.locId, spec.locType, rotation and 3, live)
            World.spawnObject(obj)
            locs[tile] = obj
        }
        if (spec.graphic >= 0) World.sendGraphics(Graphics(spec.graphic), live)
    }

    fun remove(tile: StaticTile) {
        if (!state.remove(tile)) return
        despawn(tile)
    }

    fun clear() {
        for (t in state.clear()) despawn(t)
    }

    /** True if [player] stands on a tile of this field. */
    fun isOn(player: Player): Boolean {
        val s = context.instance.toStatic(player.location) ?: return false
        return s in state
    }

    internal fun tick() {
        for (t in state.advance()) despawn(t)
        val now = state.now
        if (spec.graphic >= 0 && spec.graphicEvery > 0 && now % spec.graphicEvery == 0) {
            for (t in state.tiles) World.sendGraphics(Graphics(spec.graphic), context.instance.translate(t))
        }
        val hasDamage = spec.damage.last > 0 || spec.onStand != null
        if (!hasDamage || now % spec.damageEvery.coerceAtLeast(1) != 0 || state.size == 0) return
        for (p in context.playersInArena()) {
            if (p.isDead || p.isFinished || TfaDebug.isGodmode(p)) continue
            val s = context.instance.toStatic(p.location) ?: continue
            if (s !in state) continue
            val lo = spec.damage.first
            val hi = spec.damage.last
            val dmg = if (hi <= 0) 0 else lo + context.random.nextInt(hi - lo + 1)
            if (dmg > 0) p.applyHit(Hit(dmg, spec.hitType))
            spec.onStand?.invoke(p)
        }
    }

    private fun despawn(tile: StaticTile) {
        val obj = locs.remove(tile) ?: return
        try {
            World.removeObject(obj)
        } catch (_: Exception) {
            // the map may already be gone (instance released)
        }
    }

    override fun toString(): String = "HazardField($name, ${state.size} tiles)"
}
