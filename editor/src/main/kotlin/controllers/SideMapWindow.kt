package controllers

import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import cache.utils.ColorPalette
import com.displee.cache.CacheLibrary
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.*
import utils.GadTools
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A second map, from another cache, in its own small window next to yours. Drag a box on it and the
 * area goes straight onto your clipboard: hover your map and press Ctrl+V. Nothing in the other cache changes.
 */
class SideMapWindow(
    private val gadToolsModel: GadToolsModel,
    private val configuration: Configuration,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val underlayLoader: cache.loaders.UnderlayLoader,
    private val overlayLoader: cache.loaders.OverlayLoader,
    private val myRegions: cache.loaders.RegionLoader,
    private val myLocations: cache.loaders.LocationsLoader,
    private val myCenter: () -> Pair<Int, Int>?,
    private val pasteAt: (Prefab, Int, Int) -> Unit,
    private val log: (String) -> Unit
) {
    // ---- the other cache ----
    private var lib: CacheLibrary? = null
    private var old: cache.Cache317Maps? = null

    /** Old-style (317) caches: map files read straight from idx4, parsed with your own loaders' readers. */
    private inner class OldAccess(val c: cache.Cache317Maps) : RegionAccess {
        private val r = ConcurrentHashMap<Int, java.util.Optional<RegionDefinition>>()
        private val l = ConcurrentHashMap<Int, java.util.Optional<LocationsDefinition>>()
        @Synchronized override fun region(regionId: Int): RegionDefinition? = r.getOrPut(regionId) {
            java.util.Optional.ofNullable(try { c.terrain(regionId)?.let { myRegions.fromBytes(regionId, it) } } catch (e: Exception) { null }) }.orElse(null)
        @Synchronized override fun locations(regionId: Int): LocationsDefinition? = l.getOrPut(regionId) {
            java.util.Optional.ofNullable(try { c.objects(regionId)?.let { myLocations.fromBytes(regionId, it) } } catch (e: Exception) { null }) }.orElse(null)
    }
    private var access: RegionAccess? = null
    private var srcUnder: cache.loaders.UnderlayLoader? = null
    private var srcOver: cache.loaders.OverlayLoader? = null
    private var srcTex: cache.loaders.TextureLoader? = null
    private val palette = ColorPalette(0.7, 0, 512).colorPalette

    private class SourceAccess(val regions: cache.loaders.RegionLoader, val locs: cache.loaders.LocationsLoader) : RegionAccess {
        private val r = ConcurrentHashMap<Int, java.util.Optional<RegionDefinition>>()
        private val l = ConcurrentHashMap<Int, java.util.Optional<LocationsDefinition>>()
        @Synchronized override fun region(regionId: Int): RegionDefinition? = r.getOrPut(regionId) {
            java.util.Optional.ofNullable(try { regions.peek(regionId) } catch (e: Exception) { null }) }.orElse(null)
        @Synchronized override fun locations(regionId: Int): LocationsDefinition? = l.getOrPut(regionId) {
            java.util.Optional.ofNullable(try { locs.peek(regionId) } catch (e: Exception) { null }) }.orElse(null)
    }

    // ---- view ----
    private val canvas = Canvas(640.0, 560.0)
    private var cx = 3200.0; private var cy = 3200.0   // world tile at the centre of the view
    private var scale = 6.0                            // pixels per tile
    private var plane = 0
    private val images = ConcurrentHashMap<Long, java.util.Optional<WritableImage>>() // (region, plane) -> picture
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "side-map").apply { isDaemon = true } }
    private var sel: TileRect? = null
    /** Regions the other cache lists (old style), drawn even before their picture is ready. */
    private var knownRegions: Set<Int> = emptySet()
    private var dragStart: Pair<Int, Int>? = null
    private var panFrom: Pair<Double, Double>? = null
    private val lblInfo = Label("Open the other cache to start.").apply { isWrapText = true }
    private val lblHover = Label(" ")

    private fun key(region: Int, z: Int) = (region.toLong() shl 2) or z.toLong()

    private fun rgbOfHsl(hsl: Int) = palette[hsl and 0xFFFF]

    private fun tileRgb(t: RegionDefinition.Tile?): Int {
        t ?: return -1
        val u = (t.underlayId) - 1
        val o = (t.overlayId) - 1
        val under = if (u >= 0) (srcUnder ?: underlayLoader).get(u)?.color ?: -1 else -1
        if (o >= 0) {
            val d = (srcOver ?: overlayLoader).get(o)
            if (d != null) {
                val c = when {
                    d.texture >= 0 -> try { rgbOfHsl(srcTex!!.getAverageTextureRGB(d.texture)) } catch (e: Exception) { 0x707070 }
                    d.rgbColor == 0xFF00FF -> -1
                    else -> d.rgbColor
                }
                if (c >= 0) return c
            }
        }
        return under
    }

    /** 64 x 64 picture of one region on one floor (1 pixel per tile), objects shown darker, walls black. */
    // your own map, drawn faded where the other cache doesn't have a region (their client only keeps what was visited)
    private val chkMine = CheckBox("Show my map where theirs isn't downloaded").apply { isSelected = true }
    private val mine: RegionAccess? by lazy {
        try {
            val l = CacheLibrary(cache.CachePaths.cacheDir)
            object : RegionAccess {
                val rl = cache.loaders.RegionLoader(l)
                val ll = cache.loaders.LocationsLoader(l, cache.XteaManager(GadTools.editorDir.path))
                @Synchronized override fun region(regionId: Int) = try { rl.peek(regionId) } catch (e: Exception) { null }
                @Synchronized override fun locations(regionId: Int) = try { ll.peek(regionId) } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    private fun render(region: Int, z: Int): WritableImage? {
        val theirs = access ?: return null
        var a: RegionAccess = theirs
        var faded = false
        var def = theirs.region(region)
        if (def == null && chkMine.isSelected) { val m = mine; def = m?.region(region); if (def != null) { a = m!!; faded = true } }
        if (def == null) return null
        val img = WritableImage(64, 64)
        val w = img.pixelWriter
        val shade = IntArray(64 * 64)
        val walls = BooleanArray(64 * 64)
        a.locations(region)?.locations?.forEach { l ->
            if (l.z != z) return@forEach
            val i = l.x + (63 - l.y) * 64
            if (l.type in 0..3 || l.type == 9) walls[i] = true else if (l.type != 22) shade[i]++
        }
        for (x in 0 until 64) for (y in 0 until 64) {
            var c = tileRgb(def.tiles[z][x][y])
            if (c < 0 && z > 0) { w.setArgb(x, 63 - y, 0); continue } // empty upper floor: see-through
            if (c < 0) c = 0x101010
            val i = x + (63 - y) * 64
            if (walls[i]) c = 0x1a1410
            else if (shade[i] > 0) c = (((c shr 16 and 255) * 6 / 10) shl 16) or (((c shr 8 and 255) * 6 / 10) shl 8) or ((c and 255) * 6 / 10)
            if (faded) { val g = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3 * 45 / 100; c = (g shl 16) or (g shl 8) or g }
            w.setArgb(x, 63 - y, (0xFF shl 24) or c)
        }
        return img
    }

    private fun worldAt(px: Double, py: Double): Pair<Int, Int> {
        val wx = cx + (px - canvas.width / 2) / scale
        val wy = cy - (py - canvas.height / 2) / scale
        return Pair(Math.floor(wx).toInt(), Math.floor(wy).toInt())
    }

    private fun draw() {
        val g = canvas.graphicsContext2D
        g.isImageSmoothing = false
        g.fill = Color.web("#0b0907"); g.fillRect(0.0, 0.0, canvas.width, canvas.height)
        if (access == null) return
        val (x0, y1) = worldAt(0.0, 0.0)
        val (x1, y0) = worldAt(canvas.width, canvas.height)
        for (rx in (x0 shr 6)..(x1 shr 6)) for (ry in (y0 shr 6)..(y1 shr 6)) {
            if (rx !in 0..255 || ry !in 0..255) continue
            val rid = (rx shl 8) or ry
            val sx = canvas.width / 2 + (rx * 64 - cx) * scale
            val sy = canvas.height / 2 - (ry * 64 + 64 - cy) * scale
            if (rid in knownRegions) { g.fill = Color.web("#2b2014"); g.fillRect(sx, sy, 64 * scale, 64 * scale) }
            for (z in 0..plane) {
                val k = key(rid, z)
                val im = images[k]
                if (im == null) {
                    if (pending.add(k)) worker.execute {
                        val r = try { render(rid, z) } catch (e: Throwable) { null }
                        images[k] = java.util.Optional.ofNullable(r)
                        pending.remove(k)
                        Platform.runLater { draw() }
                    }
                } else im.orElse(null)?.let { g.drawImage(it, sx, sy, 64 * scale, 64 * scale) }
            }
            g.stroke = Color.web("#3a2e20"); g.lineWidth = 1.0
            g.strokeRect(sx, sy, 64 * scale, 64 * scale)
        }
        sel?.let { s ->
            val sx = canvas.width / 2 + (s.x1 - cx) * scale
            val sy = canvas.height / 2 - (s.y2 + 1 - cy) * scale
            g.stroke = Color.web("#ffd700"); g.lineWidth = 2.0
            g.strokeRect(sx, sy, s.width * scale, s.height * scale)
            g.fill = Color.web("#ffd70033"); g.fillRect(sx, sy, s.width * scale, s.height * scale)
        }
    }

    private fun open(dir: File): String {
        // old-style cache (main_file_cache.dat, most 317 private-server clients)
        val oldDat = dir.walkTopDown().maxDepth(6).filter { it.isFile && it.name.equals("main_file_cache.dat", true) }
            .sortedBy { it.path.length }.firstOrNull()
        val newDat = dir.walkTopDown().maxDepth(6).filter { it.isFile && it.name.equals("main_file_cache.dat2", true) }
            .sortedBy { it.path.length }.firstOrNull()
        if (newDat == null && oldDat != null) {
            try { lib?.close() } catch (e: Exception) {}; lib = null
            try { old?.close() } catch (e: Exception) {}
            val c = cache.Cache317Maps(oldDat.parentFile) { id, b -> myRegions.fromBytes(id, b) != null }
            old = c
            access = OldAccess(c)
            srcUnder = null; srcOver = null; srcTex = null   // colours come from your own cache
            images.clear()
            configuration.saveProp("side-map-dir", dir.path)
            log("Other map (old-style cache): ${c.info}")
            knownRegions = c.mapIndex.keys.toSet()
            // test-read a few regions so a format problem shows up as a message, not a black screen
            var ok = 0; var badGround = 0; var missing = 0
            val sample = c.mapIndex.keys.sorted().let { ks -> listOf(12342, 12598, 12850, 13878).filter { it in c.mapIndex } + ks.take(20) }.distinct()
            for (id in sample) {
                val raw = try { c.terrain(id) } catch (e: Exception) { null }
                when {
                    raw == null -> missing++
                    myRegions.fromBytes(id, raw) == null -> badGround++
                    else -> ok++
                }
            }
            log("Test read: $ok ok, $badGround unreadable ground, $missing missing files (of ${sample.size})")
            return "Opened ${oldDat.parent}: ${c.info}. Test read: $ok ok, $badGround unreadable, $missing not downloaded (of ${sample.size}). " +
                    "Dark-brown squares = regions this map has."
        }
        val dat = newDat ?: return "No main_file_cache.dat2 or main_file_cache.dat in ${dir.path}"
        val keys = listOfNotNull(dat.parentFile, dat.parentFile.parentFile).map { File(it, "xteas.json") }.firstOrNull { it.isFile }?.parentFile
            ?: GadTools.noKeysDir()
        try { lib?.close() } catch (e: Exception) {}
        val l = CacheLibrary(dat.parent)
        lib = l
        access = SourceAccess(cache.loaders.RegionLoader(l), cache.loaders.LocationsLoader(l, cache.XteaManager(keys.path)))
        srcUnder = try { cache.loaders.UnderlayLoader(l) } catch (e: Exception) { null }
        srcOver = try { cache.loaders.OverlayLoader(l) } catch (e: Exception) { null }
        srcTex = try { cache.loaders.TextureLoader(l) } catch (e: Exception) { null }
        images.clear()
        configuration.saveProp("side-map-dir", dir.path)
        return "Opened ${dat.parent}. Right-drag (or middle) to move, wheel to zoom, left-drag a box to copy it."
    }

    /** Copy the selected box (chosen floors) to the clipboard, leaving out things your cache doesn't have. */
    private fun copySelection(floor: Int, objectsOnly: Boolean): Prefab? {
        val a = access ?: return null
        val s = sel ?: return null
        val raw = TileOps(a).copy(s, floor)
        var bad = 0
        val objs = raw.objects.filter { (objectLoader.get(it.id) != null).also { ok -> if (!ok) bad++ } }
        val tiles = if (objectsOnly) emptyList() else raw.tiles.map { t ->
            var u = t.underlay; var o = t.overlay; var op = t.opcode
            if (u != 0 && underlayLoader.get(u - 1) == null && underlayLoader.get(u) == null) { u = 0; bad++ }
            if (o != 0 && overlayLoader.get(o - 1) == null && overlayLoader.get(o) == null) { o = 0; op = 0; bad++ }
            t.copy(underlay = u, overlay = o, opcode = op)
        }
        val p = raw.copy(tiles = tiles, objects = objs, objectsOnly = objectsOnly)
        gadToolsModel.clipboard = p
        gadToolsModel.lastCopyWasObject = false
        val painted = tiles.count { it.underlay != 0 || it.overlay != 0 }
        val msg = "Copied ${s.width} x ${s.height} from the other map" + (if (floor >= 0) " (floor $floor)" else " (all floors)") +
                ": $painted tiles, ${objs.size} objects" + (if (bad > 0) ", $bad left out (not in your cache)" else "") +
                ". Hover your map and press Ctrl+V."
        gadToolsModel.message = msg
        log(msg)
        return p
    }

    fun show() {
        val stage = Stage()
        val txtDir = TextField(configuration.getProp("side-map-dir")).apply { promptText = "Other cache folder (e.g. C:\\GadScape\\RoatPkz)"; HBox.setHgrow(this, Priority.ALWAYS) }
        val btnBrowse = Button("Browse...").apply {
            setOnAction {
                val d = DirectoryChooser().apply { title = "Folder with the other cache"
                    txtDir.text.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it } }
                    .showDialog(stage) ?: return@setOnAction
                txtDir.text = d.path
            }
        }
        val btnOpen = Button("Open").apply { styleClass.add("accent") }
        btnOpen.setOnAction {
            val d = File(txtDir.text.trim())
            lblInfo.text = if (d.isDirectory) try { open(d) } catch (e: Throwable) { e.printStackTrace(); "Couldn't open it: $e" } else "Pick the folder first."
            val me = myCenter() ?: Pair(3200, 3200)
            val myRegion = ((me.first shr 6) shl 8) or (me.second shr 6)
            val target = if (knownRegions.isEmpty() || myRegion in knownRegions) me else {
                val near = knownRegions.minByOrNull { r -> val dx = (r shr 8) - (myRegion shr 8); val dy = (r and 0xFF) - (myRegion and 0xFF); dx * dx + dy * dy }!!
                Pair(((near shr 8) shl 6) + 32, ((near and 0xFF) shl 6) + 32)
            }
            cx = target.first.toDouble(); cy = target.second.toDouble()
            scale = 6.0
            draw()
        }

        val cmbFloor = ComboBox<String>().apply { items.setAll("Floor 0", "Floor 1", "Floor 2", "Floor 3"); value = "Floor 0" }
        cmbFloor.valueProperty().addListener { _, _, v -> plane = v.last().toString().toInt(); draw() }
        val cmbCopy = ComboBox<String>().apply { items.setAll("All floors", "Only the floor shown"); value = "All floors" }
        val chkObj = CheckBox("Only objects (keep my floor)")
        val txtX = TextField().apply { promptText = "x"; prefColumnCount = 5 }
        val txtY = TextField().apply { promptText = "y"; prefColumnCount = 5 }
        val btnGo = Button("Go").apply {
            setOnAction {
                val x = txtX.text.trim().toIntOrNull(); val y = txtY.text.trim().toIntOrNull()
                if (x != null && y != null) { cx = x + 0.5; cy = y + 0.5; draw() }
            }
        }
        val btnMine = Button("Same spot as my map").apply {
            setOnAction { myCenter()?.let { (x, y) -> cx = x.toDouble(); cy = y.toDouble(); draw() } }
        }
        fun floorChoice() = if (cmbCopy.value.startsWith("All")) -1 else plane
        val btnCopy = Button("Copy box (then Ctrl+V in your map)").apply {
            styleClass.add("accent")
            setOnAction {
                if (sel == null) { lblInfo.text = "Left-drag a box on this map first."; return@setOnAction }
                lblInfo.text = "Copying..."
                val f = floorChoice(); val o = chkObj.isSelected
                worker.execute {
                    val msg = try { copySelection(f, o); gadToolsModel.message } catch (e: Throwable) { e.printStackTrace(); "Copy failed: $e" }
                    Platform.runLater { lblInfo.text = msg }
                }
            }
        }
        val btnSame = Button("Put it at the same spot in my map").apply {
            tooltip = Tooltip("Pastes the box onto the same coordinates in your map.")
            setOnAction {
                val s = sel ?: run { lblInfo.text = "Left-drag a box on this map first."; return@setOnAction }
                val f = floorChoice(); val o = chkObj.isSelected
                worker.execute {
                    val p = try { copySelection(f, o) } catch (e: Throwable) { null }
                    Platform.runLater {
                        if (p == null) lblInfo.text = "Copy failed."
                        else { pasteAt(p, s.x1, s.y1); lblInfo.text = "Put ${s.width} x ${s.height} at ${s.x1}, ${s.y1} in your map. Ctrl+Z undoes it." }
                    }
                }
            }
        }
        val btnNext = Button("Next region of theirs").apply {
            tooltip = Tooltip("Jumps through the regions the other map has (old-style caches).")
            setOnAction {
                if (knownRegions.isEmpty()) return@setOnAction
                val cur = ((cx.toInt() shr 6) shl 8) or (cy.toInt() shr 6)
                val sorted = knownRegions.sorted()
                val nxt = sorted.firstOrNull { it > cur } ?: sorted.first()
                cx = ((nxt shr 8) shl 6) + 32.0; cy = ((nxt and 0xFF) shl 6) + 32.0
                lblInfo.text = "Region $nxt"
                draw()
            }
        }
        val chkTop = CheckBox("Keep on top").apply { selectedProperty().addListener { _, _, v -> stage.isAlwaysOnTop = v } }

        // ---- mouse ----
        canvas.setOnMousePressed { e ->
            if (e.button == MouseButton.PRIMARY) { val w = worldAt(e.x, e.y); dragStart = w; sel = TileRect.of(w.first, w.second, w.first, w.second); draw() }
            else panFrom = Pair(e.x, e.y)
        }
        canvas.setOnMouseDragged { e ->
            val ds = dragStart
            if (e.button == MouseButton.PRIMARY && ds != null) {
                val w = worldAt(e.x, e.y); sel = TileRect.of(ds.first, ds.second, w.first, w.second)
                lblInfo.text = "Box ${sel!!.width} x ${sel!!.height} from ${sel!!.x1}, ${sel!!.y1}"
                draw()
            } else panFrom?.let { (px, py) ->
                cx -= (e.x - px) / scale; cy += (e.y - py) / scale; panFrom = Pair(e.x, e.y); draw()
            }
        }
        canvas.setOnMouseReleased { dragStart = null; panFrom = null }
        canvas.setOnMouseMoved { e ->
            val (x, y) = worldAt(e.x, e.y)
            val rid = ((x shr 6) shl 8) or (y shr 6)
            lblHover.text = "$x, $y   region $rid  ${utils.RegionNames.describe(rid)}"
        }
        canvas.setOnScroll { e ->
            val before = worldAt(e.x, e.y)
            scale = (scale * if (e.deltaY > 0) 1.25 else 0.8).coerceIn(0.5, 32.0)
            val after = worldAt(e.x, e.y)
            cx += before.first - after.first; cy += before.second - after.second
            draw()
        }
        chkMine.selectedProperty().addListener { _, _, _ -> images.clear(); draw() }
        val holder = Pane(canvas).apply { minWidth = 200.0; minHeight = 200.0 }
        canvas.widthProperty().bind(holder.widthProperty()); canvas.heightProperty().bind(holder.heightProperty())
        canvas.widthProperty().addListener { _, _, _ -> draw() }; canvas.heightProperty().addListener { _, _, _ -> draw() }

        val root = VBox(6.0,
            HBox(6.0, Label("Other map"), txtDir, btnBrowse, btnOpen).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, cmbFloor, Label("Go to"), txtX, txtY, btnGo, btnMine, btnNext, chkTop).apply { alignment = Pos.CENTER_LEFT },
            holder,
            HBox(6.0, btnCopy, btnSame).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, Label("Copy"), cmbCopy, chkObj, chkMine).apply { alignment = Pos.CENTER_LEFT },
            lblHover, lblInfo
        ).apply { padding = Insets(8.0); VBox.setVgrow(holder, Priority.ALWAYS) }
        stage.scene = Scene(root, 720.0, 760.0)
        utils.Branding.apply(stage, "other map")
        stage.setOnHidden { worker.shutdownNow(); try { lib?.close() } catch (e: Exception) {}; try { old?.close() } catch (e: Exception) {} }
        stage.show()
        if (txtDir.text.isNotBlank() && File(txtDir.text).isDirectory) btnOpen.fire() else draw()
    }
}
