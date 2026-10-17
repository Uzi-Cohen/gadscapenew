package models

import cache.definitions.Location
import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import java.util.IdentityHashMap

/** World-tile rectangle, inclusive on both ends. */
data class TileRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    val width: Int get() = x2 - x1 + 1
    val height: Int get() = y2 - y1 + 1
    fun contains(x: Int, y: Int) = x in x1..x2 && y in y1..y2

    companion object {
        fun of(ax: Int, ay: Int, bx: Int, by: Int) = TileRect(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }
}

/** What a paste / clear / find & replace touches. */
/** How pasted / moved / imported areas treat ground height. */
object PasteHeights {
    const val FIT = 0     // shift the area up or down so its edges meet the ground where it lands (no cliffs)
    const val KEEP = 1    // exactly the heights it had
    const val GROUND = 2  // keep the heights already at the destination
    @Volatile var mode = FIT
}

data class TileParts(
    val heights: Boolean = true,
    val underlays: Boolean = true,
    val overlays: Boolean = true,
    val flags: Boolean = true,
    val objects: Boolean = true
)

// ---------- undo entries ----------

/** One undoable step. Already applied when it lands on the undo stack. */
sealed class Edit {
    abstract val regionIds: Set<Int>
}

class TileChange(val regionId: Int, val z: Int, val x: Int, val y: Int, val tile: RegionDefinition.Tile, val before: RegionDefinition.Tile) {
    var after: RegionDefinition.Tile = before
}

class TileEdit(val changes: List<TileChange>, val heightOnly: Boolean) : Edit() {
    override val regionIds: Set<Int> = changes.map { it.regionId }.toSet()
}

class ObjectEdit(val regionId: Int, val loc: Location, val added: Boolean) : Edit() {
    override val regionIds: Set<Int> = setOf(regionId)
}

class MultiEdit(val parts: List<Edit>) : Edit() {
    override val regionIds: Set<Int> = parts.flatMap { it.regionIds }.toSet()
}

/** An NPC spawn added (before = null), removed (after = null), moved, turned or changed. */
class NpcEdit(val spawn: utils.NpcSpawn, val before: utils.NpcSpawn.State?, val after: utils.NpcSpawn.State?) : Edit() {
    override val regionIds: Set<Int> = emptySet()
}

/** Collects tile changes during a stroke or an area operation. */
class TileRecorder {
    private val changes = IdentityHashMap<RegionDefinition.Tile, TileChange>()

    fun touch(regionId: Int, z: Int, x: Int, y: Int, tile: RegionDefinition.Tile) {
        if (!changes.containsKey(tile)) changes[tile] = TileChange(regionId, z, x, y, tile, tile.copy())
    }

    fun isEmpty() = changes.isEmpty()

    val regionIds: Set<Int> get() = changes.values.map { it.regionId }.toSet()

    fun finish(heightOnly: Boolean): TileEdit? {
        val list = changes.values.filter { it.tile != it.before }
        list.forEach { it.after = it.tile.copy() }
        return if (list.isEmpty()) null else TileEdit(list, heightOnly)
    }
}

fun RegionDefinition.Tile.assign(o: RegionDefinition.Tile) {
    cacheHeight = o.cacheHeight
    height = o.height
    attrOpcode = o.attrOpcode
    settings = o.settings
    overlayId = o.overlayId
    overlayPath = o.overlayPath
    overlayRotation = o.overlayRotation
    underlayId = o.underlayId
}

/** Keep the lookup arrays the scene builder reads in step with the tile. */
fun RegionDefinition.syncTile(z: Int, x: Int, y: Int) {
    val t = tiles[z][x][y] ?: return
    overlayIds[z][x][y] = t.overlayId
    overlayPaths[z][x][y] = t.overlayPath.toInt()
    overlayRotations[z][x][y] = t.overlayRotation.toInt()
    tileSettings[z][x][y] = t.settings.toInt() and 0xFF
    underlayIds[z][x][y] = t.underlayId
}

/**
 * Upper planes store their height relative to the plane below, the same way the client reads it.
 * Returns true if an upper tile that is actually drawn (has a floor) moved.
 */
fun RegionDefinition.restackHeights(x: Int, y: Int): Boolean {
    var visibleMoved = false
    for (z in 1 until RegionDefinition.Z) {
        val t = tiles[z][x][y] ?: continue
        val below = tiles[z - 1][x][y]?.height ?: 0
        val ch = t.cacheHeight
        val h = below - if (ch == null) 240 else (if (ch == 1) 0 else ch) * 8
        if (h != t.height) {
            t.height = h
            if (t.underlayId.toInt() != 0 || t.overlayId.toInt() != 0) visibleMoved = true
        }
    }
    return visibleMoved
}

/** Set a plane 0 height (negative = up), keeping the stored cache value in step. */
fun RegionDefinition.Tile.setGroundHeight(h: Int) {
    val v = h.coerceIn(MIN_HEIGHT, 0)
    height = v
    cacheHeight = v / -8
}

/** Lowest (most raised) plane 0 height the map format can store: 255 * -8. */
const val MIN_HEIGHT = -2040

const val OVERLAY_SHAPES = 12

// ---------- prefabs (copy / paste / export) ----------

/** Raw tile values; underlay/overlay are the stored ids (definition id + 1, 0 = none). */
data class PrefabTile(
    val dx: Int, val dy: Int, val z: Int,
    /** Plane 0: stored height (0-255). Upper planes: stored relative height, null = default. */
    val h: Int?,
    val underlay: Int, val overlay: Int, val opcode: Int, val flags: Int
)

data class PrefabObject(val dx: Int, val dy: Int, val z: Int, val id: Int, val type: Int, val rot: Int)

data class Prefab(val width: Int, val height: Int, val tiles: List<PrefabTile>, val objects: List<PrefabObject>,
                  /** Floor it was copied from when only one floor was copied; null = all floors (paste keeps them). */
                  val basePlane: Int? = null,
                  /** Objects only: pasting adds the objects and leaves the floor and the objects already there alone. */
                  val objectsOnly: Boolean = false)

/**
 * The same prefab turned 90 degrees clockwise (seen from above, north up).
 * [size] gives an object's footprint (sizeX, sizeY) so big objects keep their corner in the right place.
 */
fun Prefab.rotatedClockwise(size: (Int) -> Pair<Int, Int>): Prefab {
    val w = width
    val tiles2 = tiles.map { t ->
        val op = t.opcode
        val op2 = if (op in 2..49) {
            val shape = (op - 2) / 4
            val rot = (op - 2) % 4
            2 + shape * 4 + ((rot + 1) and 3)
        } else op
        t.copy(dx = t.dy, dy = w - 1 - t.dx, opcode = op2)
    }
    val objects2 = objects.map { o ->
        val (sx0, sy0) = if (o.type == 10 || o.type == 11) size(o.id) else Pair(1, 1)
        val sx = if (o.rot and 1 == 1) sy0 else sx0
        o.copy(dx = o.dy, dy = w - o.dx - sx, rot = (o.rot + 1) and 3)
    }
    return Prefab(height, width, tiles2, objects2, basePlane, objectsOnly)
}

/** Shared access to the in-memory (editable) region data. */
interface RegionAccess {
    fun region(regionId: Int): RegionDefinition?
    fun locations(regionId: Int): LocationsDefinition?
}

fun regionIdOf(x: Int, y: Int): Int = ((x shr 6) shl 8) or (y shr 6)

/** Undo or redo an edit on the region data. Returns (regions needing a full rebuild, regions only needing a height refresh). */
fun applyEdit(access: RegionAccess, edit: Edit, undo: Boolean): Pair<Set<Int>, Set<Int>> {
    val rebuild = HashSet<Int>()
    val heights = HashSet<Int>()
    fun run(e: Edit) {
        when (e) {
            is TileEdit -> {
                for (c in e.changes) {
                    c.tile.assign(if (undo) c.before else c.after)
                    val def = access.region(c.regionId) ?: continue
                    def.syncTile(c.z, c.x, c.y)
                    val upper = def.restackHeights(c.x, c.y)
                    if (e.heightOnly && !upper) heights.add(c.regionId) else rebuild.add(c.regionId)
                }
            }
            is ObjectEdit -> {
                val locs = access.locations(e.regionId)?.locations ?: return
                if (e.added == undo) locs.remove(e.loc) else locs.add(e.loc)
                rebuild.add(e.regionId)
            }
            is MultiEdit -> (if (undo) e.parts.asReversed() else e.parts).forEach { run(it) }
            is NpcEdit -> utils.NpcSpawns.apply(e.spawn, if (undo) e.before else e.after)
        }
    }
    run(edit)
    heights.removeAll(rebuild)
    return Pair(rebuild, heights)
}

/** Area operations on the region data. Every call returns the edit it made (already applied), or null if nothing changed. */
class TileOps(private val access: RegionAccess) {

    private inline fun forTiles(rect: TileRect, planes: IntRange, fn: (regionId: Int, def: RegionDefinition, z: Int, lx: Int, ly: Int, t: RegionDefinition.Tile) -> Unit) {
        for (x in rect.x1..rect.x2) for (y in rect.y1..rect.y2) {
            val id = regionIdOf(x, y)
            val def = access.region(id) ?: continue
            val lx = x and 63
            val ly = y and 63
            for (z in planes) {
                val t = def.tiles[z][lx][ly] ?: continue
                fn(id, def, z, lx, ly, t)
            }
        }
    }

    private fun regionsIn(rect: TileRect): Set<Int> {
        val ids = HashSet<Int>()
        for (x in (rect.x1 shr 6)..(rect.x2 shr 6)) for (y in (rect.y1 shr 6)..(rect.y2 shr 6)) ids.add((x shl 8) or y)
        return ids
    }

    /** Copy an area. plane >= 0 copies only that floor, so it can be pasted onto another floor. */
    fun copy(rect: TileRect, plane: Int = -1): Prefab {
        val tiles = ArrayList<PrefabTile>()
        for (x in rect.x1..rect.x2) for (y in rect.y1..rect.y2) {
            val def = access.region(regionIdOf(x, y)) ?: continue
            for (z in 0 until RegionDefinition.Z) {
                if (plane >= 0 && z != plane) continue
                val t = def.tiles[z][x and 63][y and 63] ?: continue
                tiles.add(PrefabTile(
                    dx = x - rect.x1, dy = y - rect.y1, z = z,
                    h = if (z == 0) (t.height / -8).coerceIn(0, 255) else t.cacheHeight,
                    underlay = t.underlayId, overlay = t.overlayId,
                    opcode = t.attrOpcode, flags = t.settings.toInt() and 0xFF
                ))
            }
        }
        val objects = ArrayList<PrefabObject>()
        for (id in regionsIn(rect)) {
            val locs = access.locations(id)?.locations ?: continue
            val bx = (id shr 8) shl 6
            val by = (id and 0xFF) shl 6
            for (l in locs) {
                val wx = bx + l.x
                val wy = by + l.y
                if (plane >= 0 && l.z != plane) continue
                if (rect.contains(wx, wy)) objects.add(PrefabObject(wx - rect.x1, wy - rect.y1, l.z, l.objId, l.type, l.orientation))
            }
        }
        return Prefab(rect.width, rect.height, tiles, objects, if (plane >= 0) plane else null)
    }

    /** Paste with the prefab's south-west corner on (ox, oy). Objects already on the target tiles are replaced when objects are pasted. */
    fun paste(p: Prefab, ox: Int, oy: Int, parts: TileParts, targetPlane: Int = -1): Edit? {
        // a single-floor copy lands on the floor you're on; an all-floors copy keeps its floors
        val dz = if (p.basePlane != null && targetPlane >= 0) targetPlane - p.basePlane else 0
        val rec = TileRecorder()
        val restack = HashSet<Long>()
        // ground heights: fit the area to where it lands, keep its own, or leave the ground as it is
        val hMode = PasteHeights.mode
        var hOffset = 0
        if (parts.heights && hMode == PasteHeights.FIT) {
            var srcSum = 0L; var dstSum = 0L; var n = 0
            for (pt in p.tiles) {
                if (pt.z != 0 || pt.z + dz != 0) continue
                if (pt.dx != 0 && pt.dy != 0 && pt.dx != p.width - 1 && pt.dy != p.height - 1) continue
                val x = ox + pt.dx; val y = oy + pt.dy
                val t = access.region(regionIdOf(x, y))?.tiles?.get(0)?.get(x and 63)?.get(y and 63) ?: continue
                srcSum += pt.h ?: 0; dstSum += (t.height / -8).coerceIn(0, 255); n++
            }
            if (n > 0) hOffset = ((dstSum - srcSum) / n).toInt()
        }
        for (pt in p.tiles) {
            val x = ox + pt.dx
            val y = oy + pt.dy
            val z = pt.z + dz
            if (z !in 0 until RegionDefinition.Z) continue
            val id = regionIdOf(x, y)
            val def = access.region(id) ?: continue
            val lx = x and 63
            val ly = y and 63
            val t = def.tiles[z][lx][ly] ?: continue
            rec.touch(id, z, lx, ly, t)
            // heights mean different things on the ground (absolute) and upper floors (relative): only copy like for like
            if (parts.heights && hMode != PasteHeights.GROUND && (pt.z == 0) == (z == 0)) {
                if (z == 0) t.setGroundHeight(-(((pt.h ?: 0) + hOffset).coerceIn(0, 255)) * 8) else t.cacheHeight = pt.h
                restack.add((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
            }
            // moving a floor onto another one: only bring what the copied tile has, so empty tiles
            // (most of an upper floor) don't wipe the ground and leave dead tiles
            val crossFloor = dz != 0
            if (parts.underlays && !(crossFloor && pt.underlay == 0)) t.underlayId = pt.underlay
            if (parts.overlays && !(crossFloor && pt.overlay == 0)) {
                t.attrOpcode = pt.opcode
                t.overlayId = pt.overlay
                t.overlayPath = (if (pt.opcode > 1) (pt.opcode - 2) / 4 else 0).toByte()
                t.overlayRotation = (if (pt.opcode > 1) (pt.opcode - 2) and 3 else 0).toByte()
            }
            if (parts.flags) t.settings = if (crossFloor) ((t.settings.toInt() and 0x0A) or (pt.flags and 0x15)).toByte() else pt.flags.toByte()
            def.syncTile(z, lx, ly)
        }
        for (k in restack) {
            val x = (k shr 32).toInt()
            val y = k.toInt()
            access.region(regionIdOf(x, y))?.restackHeights(x and 63, y and 63)
        }
        val parts2 = ArrayList<Edit>()
        rec.finish(false)?.let { parts2.add(it) }
        if (parts.objects) {
            val target = TileRect(ox, oy, ox + p.width - 1, oy + p.height - 1)
            val onlyFloor = if (p.basePlane != null) p.basePlane + dz else -1
            if (!p.objectsOnly) removeObjects(target) { onlyFloor < 0 || it.z == onlyFloor }?.let { parts2.add(it) }
            for (o in p.objects) {
                val x = ox + o.dx
                val y = oy + o.dy
                val z = o.z + dz
                if (z !in 0 until RegionDefinition.Z) continue
                val id = regionIdOf(x, y)
                val locs = access.locations(id)?.locations ?: continue
                val loc = Location(o.id, o.type, o.rot, x and 63, y and 63, z)
                locs.add(loc)
                parts2.add(ObjectEdit(id, loc, true))
            }
        }
        return when (parts2.size) { 0 -> null; 1 -> parts2[0]; else -> MultiEdit(parts2) }
    }

    fun removeObjects(rect: TileRect, filter: (Location) -> Boolean = { true }): Edit? {
        val edits = ArrayList<Edit>()
        for (id in regionsIn(rect)) {
            val locs = access.locations(id)?.locations ?: continue
            val bx = (id shr 8) shl 6
            val by = (id and 0xFF) shl 6
            val gone = locs.filter { rect.contains(bx + it.x, by + it.y) && filter(it) }
            gone.forEach { l -> locs.remove(l); edits.add(ObjectEdit(id, l, false)) }
        }
        return if (edits.isEmpty()) null else MultiEdit(edits)
    }

    /** Wipe the chosen parts in an area (heights are left alone). */
    fun clear(rect: TileRect, parts: TileParts, planes: IntRange): Edit? {
        val rec = TileRecorder()
        forTiles(rect, planes) { id, def, z, lx, ly, t ->
            rec.touch(id, z, lx, ly, t)
            if (parts.underlays) t.underlayId = 0
            if (parts.overlays) { t.attrOpcode = 0; t.overlayId = 0; t.overlayPath = 0; t.overlayRotation = 0 }
            if (parts.flags) t.settings = 0
            def.syncTile(z, lx, ly)
        }
        val list = ArrayList<Edit>()
        rec.finish(false)?.let { list.add(it) }
        if (parts.objects) removeObjects(rect) { it.z in planes }?.let { list.add(it) }
        return when (list.size) { 0 -> null; 1 -> list[0]; else -> MultiEdit(list) }
    }

    /**
     * Bring everything in an area that ended up on an upper floor back down to the ground (plane 0):
     * tile paint (underlay, overlay + shape, flags) and every object. Ground heights are kept.
     * Where an upper floor has nothing painted, the ground tile is left as it is.
     */
    fun moveToGround(rect: TileRect, clearBridgeFlags: Boolean): Pair<Edit?, String> =
        moveFloors(rect, listOf(1, 2, 3), 0, clearBridgeFlags)

    /**
     * Move everything in an area from the [from] floors onto floor [to]: tile paint (underlay only if the
     * source has one, overlay + shape), the safe flags, and every object. Heights stay where they are.
     * When several source floors have paint on the same tile, the one nearest to [to] wins.
     */
    fun moveFloors(rect: TileRect, from: List<Int>, to: Int, clearBridgeFlags: Boolean): Pair<Edit?, String> {
        val sources = from.filter { it in 0..3 && it != to }.sortedByDescending { Math.abs(it - to) }
        val rec = TileRecorder()
        var tilesMoved = 0
        for (x in rect.x1..rect.x2) for (y in rect.y1..rect.y2) {
            val id = regionIdOf(x, y)
            val def = access.region(id) ?: continue
            val lx = x and 63
            val ly = y and 63
            val ground = def.tiles[to][lx][ly] ?: continue
            var touchedGround = false
            // farthest floor first, so the nearest floor's paint is what ends up on the target
            for (z in sources) {
                val t = def.tiles[z][lx][ly] ?: continue
                val hasPaint = t.underlayId.toInt() != 0 || t.overlayId.toInt() != 0
                val hasFlags = t.settings.toInt() != 0
                if (!hasPaint && !hasFlags) continue
                if (!touchedGround) { rec.touch(id, to, lx, ly, ground); touchedGround = true }
                rec.touch(id, z, lx, ly, t)
                if (hasPaint) {
                    // only bring what the source tile actually has; an empty underlay must not wipe the
                    // target's underlay (that left "dead", undrawn tiles). The ground keeps its own
                    // underlay when moving up, so lifting a floor never leaves holes in the ground.
                    if (t.underlayId.toInt() != 0 && z != 0) ground.underlayId = t.underlayId
                    if (t.overlayId.toInt() != 0) {
                        ground.overlayId = t.overlayId
                        ground.attrOpcode = t.attrOpcode
                        ground.overlayPath = t.overlayPath
                        ground.overlayRotation = t.overlayRotation
                    }
                }
                // carry blocked / remove roof / hide on minimap down; not the floor-linking flags (bridge, render below)
                if (hasFlags) ground.settings = (ground.settings.toInt() or (t.settings.toInt() and 0x15)).toByte()
                if (z != 0) t.underlayId = 0
                t.overlayId = 0; t.attrOpcode = 0; t.overlayPath = 0; t.overlayRotation = 0
                t.settings = (t.settings.toInt() and if (z == 0) 0x0A else 0).toByte() // ground keeps bridge/render-below
                def.syncTile(z, lx, ly)
                tilesMoved++
            }
            // the bridge flag makes the floor above count as ground; clear it when asked
            val base = def.tiles[0][lx][ly]
            if (clearBridgeFlags && base != null && (base.settings.toInt() and 2) != 0) {
                if (base === ground) { if (!touchedGround) { rec.touch(id, 0, lx, ly, ground); touchedGround = true } }
                else rec.touch(id, 0, lx, ly, base)
                base.settings = (base.settings.toInt() and 2.inv()).toByte()
                def.syncTile(0, lx, ly)
            }
            if (touchedGround) def.syncTile(to, lx, ly)
        }
        val edits = ArrayList<Edit>()
        rec.finish(false)?.let { edits.add(it) }
        var objectsMoved = 0
        for (id in regionsIn(rect)) {
            val locs = access.locations(id)?.locations ?: continue
            val bx = (id shr 8) shl 6
            val by = (id and 0xFF) shl 6
            val up = locs.filter { it.z in sources && rect.contains(bx + it.x, by + it.y) }
            for (l in up) {
                val down = l.copy(z = to)
                locs.remove(l)
                locs.add(down)
                edits.add(ObjectEdit(id, l, false))
                edits.add(ObjectEdit(id, down, true))
                objectsMoved++
            }
        }
        val msg = "Moved $tilesMoved tiles and $objectsMoved objects from floor ${sources.sorted().joinToString("+")} to floor $to" +
                if (clearBridgeFlags) " (bridge flags cleared)." else "."
        val edit = when (edits.size) { 0 -> null; 1 -> edits[0]; else -> MultiEdit(edits) }
        return Pair(edit, msg)
    }

    /**
     * Repair "dead" tiles in an area on [plane]: tiles with no underlay (they show as holes, or the
     * uncovered part of a shaped overlay shows as a hole) get the most common underlay around them.
     */
    fun fillHoles(rect: TileRect, plane: Int): Pair<Edit?, Int> {
        val rec = TileRecorder()
        var fixed = 0
        fun underlayAt(x: Int, y: Int): Int {
            val def = access.region(regionIdOf(x, y)) ?: return 0
            return (def.tiles[plane][x and 63][y and 63]?.underlayId?.toInt() ?: 0) and 0xFF
        }
        // decide first, then write, so freshly filled tiles don't spread into each other
        val plan = ArrayList<Triple<Int, Int, Int>>()
        for (x in rect.x1..rect.x2) for (y in rect.y1..rect.y2) {
            if (underlayAt(x, y) != 0) continue
            var pick = 0
            for (r in 1..4) {
                val counts = HashMap<Int, Int>()
                for (dx in -r..r) for (dy in -r..r) {
                    val u = underlayAt(x + dx, y + dy)
                    if (u != 0) counts[u] = (counts[u] ?: 0) + 1
                }
                if (counts.isNotEmpty()) { pick = counts.maxBy { it.value }!!.key; break }
            }
            if (pick != 0) plan.add(Triple(x, y, pick))
        }
        for ((x, y, u) in plan) {
            val id = regionIdOf(x, y)
            val def = access.region(id) ?: continue
            val t = def.tiles[plane][x and 63][y and 63] ?: continue
            rec.touch(id, plane, x and 63, y and 63, t)
            t.underlayId = u
            def.syncTile(plane, x and 63, y and 63)
            fixed++
        }
        return Pair(rec.finish(false), fixed)
    }

    /** Run a change on every tile of an area on the given planes. */
    fun fill(rect: TileRect, planes: IntRange, heightOnly: Boolean, change: (RegionDefinition.Tile, Int) -> Unit): Edit? {
        val rec = TileRecorder()
        val upperMoved = HashSet<Int>()
        forTiles(rect, planes) { id, def, z, lx, ly, t ->
            rec.touch(id, z, lx, ly, t)
            change(t, z)
            def.syncTile(z, lx, ly)
            if (def.restackHeights(lx, ly)) upperMoved.add(id)
        }
        return rec.finish(heightOnly && upperMoved.isEmpty())
    }

    enum class RemapKind(val label: String) { OBJECT("Object id"), UNDERLAY("Underlay id"), OVERLAY("Overlay id") }

    /** How many times an id is used in the given regions (or only inside rect). */
    fun count(kind: RemapKind, id: Int, regionIds: Collection<Int>, rect: TileRect?): Int {
        var n = 0
        when (kind) {
            RemapKind.OBJECT -> for (rid in regionIds) {
                val bx = (rid shr 8) shl 6
                val by = (rid and 0xFF) shl 6
                access.locations(rid)?.locations?.forEach { if (it.objId == id && (rect == null || rect.contains(bx + it.x, by + it.y))) n++ }
            }
            else -> for (rid in regionIds) {
                val def = access.region(rid) ?: continue
                for (z in 0 until RegionDefinition.Z) for (x in 0 until 64) for (y in 0 until 64) {
                    if (rect != null && !rect.contains(def.baseX + x, def.baseY + y)) continue
                    val t = def.tiles[z][x][y] ?: continue
                    val stored = if (kind == RemapKind.UNDERLAY) t.underlayId else t.overlayId
                    if (stored == id + 1) n++
                }
            }
        }
        return n
    }

    /** Replace every use of one id with another. Ids are definition ids (not the stored +1 form). */
    fun remap(kind: RemapKind, from: Int, to: Int, regionIds: Collection<Int>, rect: TileRect?): Edit? {
        when (kind) {
            RemapKind.OBJECT -> {
                val edits = ArrayList<Edit>()
                for (rid in regionIds) {
                    val locs = access.locations(rid)?.locations ?: continue
                    val bx = (rid shr 8) shl 6
                    val by = (rid and 0xFF) shl 6
                    val hits = locs.filter { it.objId == from && (rect == null || rect.contains(bx + it.x, by + it.y)) }
                    for (l in hits) {
                        locs.remove(l)
                        val n = l.copy(objId = to)
                        locs.add(n)
                        edits.add(ObjectEdit(rid, l, false))
                        edits.add(ObjectEdit(rid, n, true))
                    }
                }
                return if (edits.isEmpty()) null else MultiEdit(edits)
            }
            else -> {
                val rec = TileRecorder()
                for (rid in regionIds) {
                    val def = access.region(rid) ?: continue
                    for (z in 0 until RegionDefinition.Z) for (x in 0 until 64) for (y in 0 until 64) {
                        if (rect != null && !rect.contains(def.baseX + x, def.baseY + y)) continue
                        val t = def.tiles[z][x][y] ?: continue
                        if (kind == RemapKind.UNDERLAY && (t.underlayId) == from + 1) {
                            rec.touch(rid, z, x, y, t); t.underlayId = (to + 1); def.syncTile(z, x, y)
                        } else if (kind == RemapKind.OVERLAY && (t.overlayId) == from + 1) {
                            rec.touch(rid, z, x, y, t); t.overlayId = (to + 1); def.syncTile(z, x, y)
                        }
                    }
                }
                return rec.finish(false)
            }
        }
    }
}

/** For an edit that was just made: (regions needing a full rebuild, regions only needing a height refresh). */
fun refreshSets(edit: Edit): Pair<Set<Int>, Set<Int>> = when (edit) {
    is TileEdit -> if (edit.heightOnly) Pair(emptySet(), edit.regionIds) else Pair(edit.regionIds, emptySet())
    is ObjectEdit -> Pair(edit.regionIds, emptySet())
    is NpcEdit -> Pair(emptySet(), emptySet())
    is MultiEdit -> {
        val parts = edit.parts.map { refreshSets(it) }
        val rebuild = parts.flatMap { it.first }.toSet()
        Pair(rebuild, parts.flatMap { it.second }.toSet() - rebuild)
    }
}
