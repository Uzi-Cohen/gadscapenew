package models

import javafx.beans.property.BooleanProperty
import javafx.beans.property.IntegerProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A world tile the user pinned with the P key. */
data class PinnedTile(val x: Int, val y: Int, val z: Int, val objectIds: List<Int>)

enum class TerrainTool(val label: String) {
    INSPECT("Inspect (no edits)"),
    RAISE("Raise"),
    LOWER("Lower"),
    FLATTEN("Flatten"),
    SMOOTH("Smooth"),
    REMOVE("Remove object"),
    SET_HEIGHT("Set exact height"),
    NOISE("Roughen (noise)"),
    UNDERLAY("Paint underlay"),
    OVERLAY("Paint overlay"),
    FLAGS("Paint tile flags"),
    SELECT("Select area"),
    NPC("NPCs (place, drag, turn, remove)");

    val paints: Boolean get() = this == UNDERLAY || this == OVERLAY || this == FLAGS
    val heights: Boolean get() = this == RAISE || this == LOWER || this == FLATTEN || this == SMOOTH || this == SET_HEIGHT || this == NOISE
}

/** Tile flag bits (the "settings" value in the map file). */
enum class TileFlag(val bit: Int, val label: String) {
    BLOCKED(1, "Blocked / unwalkable (1)"),
    BRIDGE(2, "Bridge: floor 1 here is walked on as ground (2)"),
    ROOF(4, "Remove roof (4)"),
    LOWER_Z(8, "Render on plane below (8)"),
    NO_MAP(16, "Hide on minimap (16)")
}

/** A placed object picked on the map: which region file it lives in, and its entry there. */
data class SelectedObject(val regionId: Int, val loc: cache.definitions.Location) {
    val worldX: Int get() = ((regionId shr 8) shl 6) + loc.x
    val worldY: Int get() = ((regionId and 0xFF) shl 6) + loc.y
}

class GadToolsModel {
    val pinned: ObjectProperty<PinnedTile> = SimpleObjectProperty()

    val tool: ObjectProperty<TerrainTool> = SimpleObjectProperty(TerrainTool.INSPECT)
    /** Brush radius in tiles, 0 = single tile. */
    val brushSize: IntegerProperty = SimpleIntegerProperty(3)
    /** Height change per step for raise/lower, and blend speed for smooth. */
    val strength: IntegerProperty = SimpleIntegerProperty(8)

    // paint tools (definition ids, -1 = none / erase)
    val underlayId: IntegerProperty = SimpleIntegerProperty(-1)
    val overlayId: IntegerProperty = SimpleIntegerProperty(-1)
    val overlayShape: IntegerProperty = SimpleIntegerProperty(0)
    val overlayRotation: IntegerProperty = SimpleIntegerProperty(0)
    /** Plane the paint and flag tools write to. Height tools always work on plane 0. */
    val paintPlane: IntegerProperty = SimpleIntegerProperty(0)
    /** Draw floors above paintPlane too (normally hidden so you can work inside buildings). */
    val showAllFloors: BooleanProperty = SimpleBooleanProperty(false)
    /** On floor 1+ a copy takes only that floor (so it can be pasted on another floor) unless this is ticked. */
    val copyAllFloors: BooleanProperty = SimpleBooleanProperty(false)
    /** What Ctrl+C takes: -2 = auto (ground: all floors, floor 1-3: that floor), -3 = the floor I'm on, -1 = all floors, 0..3 = that floor. */
    val copyFloor = javafx.beans.property.SimpleIntegerProperty(-2)
    /** The floor a copy should come from, or -1 for every floor. */
    fun floorToCopy(): Int {
        val plane = paintPlane.get()
        return when (val c = copyFloor.get()) {
            -2 -> if (plane > 0 && !copyAllFloors.get()) plane else -1
            -3 -> plane
            else -> c
        }
    }
    val flagMask: IntegerProperty = SimpleIntegerProperty(TileFlag.BLOCKED.bit)
    /** Stored map height (0-255, 8 world units each) for the Set exact height tool. */
    val exactHeight: IntegerProperty = SimpleIntegerProperty(0)
    /** Tint tiles that carry any of the flagMask bits on paintPlane. */
    val showFlags: BooleanProperty = SimpleBooleanProperty(false)

    @Volatile var selection: TileRect? = null
    @Volatile var clipboard: Prefab? = null
    /** Name of an area still being read into the clipboard (area picker), null when nothing is loading. */
    @Volatile var clipboardLoading: String? = null
    @Volatile var pasteParts: TileParts = TileParts()

    /** Work the UI hands to the render thread. Returning an edit puts it on the undo stack. */
    val actions = ConcurrentLinkedQueue<() -> Edit?>()

    val undoRequested = AtomicBoolean(false)
    val redoRequested = AtomicBoolean(false)
    /** Goes up by one each time an undo or redo request has been dealt with (the message says how it went). */
    val undoRedoDone = java.util.concurrent.atomic.AtomicLong()
    val rotateRequested = AtomicBoolean(false)
    /** Ctrl+S: save (export) the map locally, nothing is sent to the server. */
    val saveRequested = AtomicBoolean(false)
    val tileNumbersRequested = AtomicBoolean(false)
    /** Numbers drawn on the 3D map: 0 = off, 1 = the floor I'm on, 2 = all floors. */
    val tileLabelMode: IntegerProperty = SimpleIntegerProperty(0)
    /** Last "on" mode, for Ctrl+T. */
    var tileLabelLastOn = 2
    /** Something was changed since the last save (set by every edit, undo and redo). */
    val unsaved = AtomicBoolean(false)
    /** C: hold a copy of the object under the mouse. M: pick it up (move it). */
    /** An object clicked on the map (tool 1). Ctrl+C / R / Delete / M and dragging act on it. */
    @Volatile var selectedObject: SelectedObject? = null
    /** NPC tool: the spawn picked on the map, and what a click on an empty tile places. */
    @Volatile var selectedNpc: utils.NpcSpawn? = null
    val npcPlaceId: IntegerProperty = SimpleIntegerProperty(-1)
    val npcPlaceRadius: IntegerProperty = SimpleIntegerProperty(3)
    /** Bumped whenever spawns change, so panels can refresh. */
    val npcVersion: IntegerProperty = SimpleIntegerProperty(0)
    /** Object copied with Ctrl+C (pasted with Ctrl+V). */
    @Volatile var objectClipboard: cache.definitions.Location? = null
    /** True when the last Ctrl+C copied an object rather than an area. */
    @Volatile var lastCopyWasObject: Boolean = false
    val copyObjectRequested = AtomicBoolean(false)   // copy the selected object
    val pasteObjectRequested = AtomicBoolean(false)  // paste the copied object at the hovered tile
    val moveObjectRequested = AtomicBoolean(false)   // pick up the selected object
    val deleteRequested = AtomicBoolean(false)
    @Volatile var undoCount: Int = 0
    @Volatile var redoCount: Int = 0
    /** Short feedback line shown in the tools panel. */
    @Volatile var message: String = ""
    /** Set by the AI bridge: the renderer puts the next finished frame in it. */
    @Volatile var screenshotRequest: java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage>? = null
    /** Frames the 3D view has started; a screenshot is only taken from a frame started after [screenshotAfterFrame]. */
    @Volatile var framesStarted = 0L
    @Volatile var screenshotAfterFrame = 0L
}
