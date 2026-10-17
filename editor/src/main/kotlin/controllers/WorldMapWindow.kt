package controllers

import cache.CachePaths
import cache.XteaManager
import cache.loaders.LocationsLoader
import cache.loaders.ObjectLoader
import cache.loaders.OverlayLoader
import cache.loaders.RegionLoader
import cache.loaders.TextureLoader
import cache.loaders.UnderlayLoader
import cache.utils.ColorPalette
import com.displee.cache.CacheLibrary
import controllers.worldRenderer.Camera
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.ImageView
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.transform.Scale
import javafx.stage.Stage
import models.GadToolsModel
import models.scene.Scene as MapScene
import utils.GadTools
import kotlin.math.max
import kotlin.math.min

/**
 * A 2D map of the whole world, drawn from the cache like the in-game world map:
 * ground colours with hill shading, walls in white and doors in red. Click anywhere to jump there.
 */
class WorldMapWindow(
    private val scene: MapScene,
    private val camera: Camera,
    private val gadToolsModel: GadToolsModel,
    private val mainRegions: RegionLoader,
    private val mainLocations: LocationsLoader,
    private val underlayLoader: UnderlayLoader,
    private val overlayLoader: OverlayLoader,
    private val textureLoader: TextureLoader,
    private val objectLoader: ObjectLoader,
    private val xtea: XteaManager
) {
    companion object {
        const val PX = 1                 // pixels per tile (zoom scales it up, pixel-sharp)
        const val CHUNK = 32             // regions per image chunk side (32 * 64 = 2048 px)
        private val built = HashMap<Int, Built>() // kept for the session: reopening is instant

        /** Map layers by region-y range: surface, instances + minigames, underground. */
        val LAYERS = listOf(
            Triple("Surface", 0, 64),
            Triple("Instances & minigames", 65, 139),
            Triple("Underground", 140, 255)
        )
    }

    private class Built(val minRx: Int, val minRy: Int, val topRy: Int, val chunks: Map<Pair<Int, Int>, WritableImage>)

    private val palette = ColorPalette(0.7, 0, 512).colorPalette

    private val content = Group()
    private val zoom = Scale(1.0, 1.0, 0.0, 0.0)
    private val viewBox = Rectangle().apply {
        fill = Color.TRANSPARENT; stroke = Color.web("#f0c450"); strokeWidth = 2.0; isMouseTransparent = true
    }
    private val lblInfo = Label("Hover the map for coordinates. Click to jump there.")
    private val progress = ProgressBar(0.0).apply { prefWidth = 220.0; isVisible = false }
    private var current: Built? = null
    private var layer = 0

    // ---- pan / zoom: screen = t + map * z ----
    private val viewport = Pane()
    private var z = 1.0
    private var tx = 0.0
    private var ty = 0.0
    private var fitted = true           // true while showing the whole map (refit on window resize)
    private var dragX = 0.0
    private var dragY = 0.0
    private val MAX_ZOOM = 16.0

    private fun mapW() = content.layoutBounds.width.coerceAtLeast(1.0)
    private fun mapH() = content.layoutBounds.height.coerceAtLeast(1.0)
    private fun fitZoom() = min(viewport.width / mapW(), viewport.height / mapH()).coerceAtLeast(0.01)

    private fun apply() {
        zoom.x = z; zoom.y = z
        content.translateX = tx; content.translateY = ty
        viewBox.strokeWidth = 2.0 / z
    }

    /** Keep the map on screen: centred when smaller than the window, edges can't drift inside when bigger. */
    private fun clampPan() {
        val w = mapW() * z; val h = mapH() * z
        val vw = viewport.width; val vh = viewport.height
        tx = if (w <= vw) (vw - w) / 2 else tx.coerceIn(vw - w, 0.0)
        ty = if (h <= vh) (vh - h) / 2 else ty.coerceIn(vh - h, 0.0)
    }

    /** Show the whole map. */
    private fun fitAll() {
        if (viewport.width <= 0 || current == null) return
        z = fitZoom()
        fitted = true
        clampPan()
        apply()
    }

    /** Zoom by [factor] keeping the map point under (sx, sy) where it is. */
    private fun zoomAt(sx: Double, sy: Double, factor: Double) {
        val mx = (sx - tx) / z; val my = (sy - ty) / z
        val nz = (z * factor).coerceIn(fitZoom(), MAX_ZOOM)
        if (nz == z) return
        z = nz
        fitted = z <= fitZoom() * 1.001
        tx = sx - mx * z; ty = sy - my * z
        clampPan()
        apply()
    }

    /** Zoom in on the area loaded in the editor. */
    private fun zoomToMe() {
        if (current == null || !scene.isLoaded()) return
        z = 3.0.coerceAtLeast(fitZoom())
        fitted = false
        val cx = viewBox.x + viewBox.width / 2; val cy = viewBox.y + viewBox.height / 2
        tx = viewport.width / 2 - cx * z; ty = viewport.height / 2 - cy * z
        clampPan()
        apply()
    }

    fun show() {
        val group = ToggleGroup()
        val layerButtons = LAYERS.mapIndexed { i, l ->
            ToggleButton(l.first).apply {
                toggleGroup = group
                isSelected = i == layer
                setOnAction { isSelected = true; if (layer != i) { layer = i; load(false) } }
            }
        }
        // open on the layer you're working in
        if (scene.isLoaded()) {
            val ry = scene.centerRegionId and 0xFF
            layer = LAYERS.indexOfFirst { ry in it.second..it.third }.coerceAtLeast(0)
            layerButtons[layer].isSelected = true
        }

        val btnFit = Button("Whole map").apply { setOnAction { fitAll() } }
        val btnOut = Button("\u2212").apply { setOnAction { zoomAt(viewport.width / 2, viewport.height / 2, 1 / 1.5) } }
        val btnIn = Button("+").apply { setOnAction { zoomAt(viewport.width / 2, viewport.height / 2, 1.5) } }
        val btnMe = Button("My location").apply {
            tooltip = Tooltip("Zoom to the area open in the editor (gold box)")
            setOnAction { zoomToMe() }
        }
        val btnRebuild = Button("Rebuild").apply {
            tooltip = Tooltip("Redraw the map from the cache (picks up exported edits)")
            setOnAction { load(true) }
        }

        content.transforms.add(zoom)
        viewport.children.setAll(content)
        viewport.style = "-fx-background-color: #0c0a09;"
        viewport.clip = Rectangle().also { r -> r.widthProperty().bind(viewport.widthProperty()); r.heightProperty().bind(viewport.heightProperty()) }
        viewport.widthProperty().addListener { _, _, _ -> if (fitted) fitAll() else { clampPan(); apply() } }
        viewport.heightProperty().addListener { _, _, _ -> if (fitted) fitAll() else { clampPan(); apply() } }

        // wheel = zoom at the mouse, drag = pan, click = jump there
        viewport.setOnScroll { e ->
            val d = if (e.deltaY != 0.0) e.deltaY else e.deltaX
            if (d != 0.0) zoomAt(e.x, e.y, Math.pow(1.2, d / 40.0))
            e.consume()
        }
        viewport.setOnMousePressed { e -> dragX = e.x; dragY = e.y }
        viewport.setOnMouseDragged { e ->
            tx += e.x - dragX; ty += e.y - dragY
            dragX = e.x; dragY = e.y
            fitted = false
            clampPan(); apply()
            viewport.cursor = javafx.scene.Cursor.CLOSED_HAND
        }
        viewport.setOnMouseReleased { viewport.cursor = javafx.scene.Cursor.DEFAULT }
        viewport.setOnMouseMoved { e ->
            val w = toWorld((e.x - tx) / z, (e.y - ty) / z) ?: return@setOnMouseMoved
            lblInfo.text = "${w.first}, ${w.second}   region ${GadTools.regionOf(w.first, w.second)}   " +
                    "(click to jump there, wheel to zoom, drag to move)"
        }
        viewport.setOnMouseClicked { e ->
            if (e.button != MouseButton.PRIMARY || !e.isStillSincePress) return@setOnMouseClicked
            val w = toWorld((e.x - tx) / z, (e.y - ty) / z) ?: return@setOnMouseClicked
            gadToolsModel.paintPlane.set(0)
            GadTools.goTo(scene, camera, w.first, w.second, 3)
            gadToolsModel.message = "Jumped to ${w.first}, ${w.second} from the world map."
            updateViewBox()
        }

        val bar = HBox(8.0, *layerButtons.toTypedArray(), Separator(), btnFit, btnOut, btnIn, btnMe, Separator(), btnRebuild, progress).apply {
            alignment = Pos.CENTER_LEFT; padding = Insets(6.0)
        }
        val root = BorderPane(viewport, bar, null, HBox(lblInfo).apply { padding = Insets(4.0, 8.0, 4.0, 8.0) }, null)
        val stage = Stage()
        stage.scene = Scene(root, 1100.0, 750.0)
        utils.Branding.apply(stage, "world map")
        stage.show()
        load(false)
    }

    /** Unscaled map pixel -> world tile. */
    private fun toWorld(mx: Double, my: Double): Pair<Int, Int>? {
        val b = current ?: return null
        if (mx < 0 || my < 0 || mx >= mapW() || my >= mapH()) return null
        val x = (mx / PX).toInt() + b.minRx * 64
        val y = (b.topRy + 1) * 64 - 1 - (my / PX).toInt()
        return Pair(x, y)
    }

    private fun updateViewBox() {
        val b = current ?: return
        if (!scene.isLoaded()) return
        val start = scene.startRegionId
        val x0 = ((start shr 8) - b.minRx) * 64 * PX
        val yTop = ((b.topRy + 1) - ((start and 0xFF) + scene.radius)) * 64 * PX
        viewBox.x = x0.toDouble(); viewBox.y = yTop.toDouble()
        viewBox.width = (scene.radius * 64 * PX).toDouble(); viewBox.height = viewBox.width
        if (!content.children.contains(viewBox)) content.children.add(viewBox)
        viewBox.toFront()
    }

    private fun show(b: Built) {
        current = b
        content.children.clear()
        for ((key, img) in b.chunks) {
            val (cx, cy) = key
            content.children.add(ImageView(img).apply {
                isSmooth = false
                layoutX = ((cx * CHUNK) * 64 * PX).toDouble()
                layoutY = ((b.topRy - (b.minRy + cy * CHUNK + CHUNK - 1)) * 64 * PX).toDouble()
            })
        }
        updateViewBox()
        // every layer opens showing the whole map
        Platform.runLater { fitAll() }
    }

    private fun load(rebuild: Boolean) {
        val key = layer
        if (!rebuild) built[key]?.let { show(it); return }
        progress.isVisible = true
        progress.progress = 0.0
        lblInfo.text = "Drawing the map from the cache..."
        val want = layer
        Thread {
            try {
                val b = build(want) { p -> Platform.runLater { progress.progress = p } }
                built[want] = b
                Platform.runLater {
                    progress.isVisible = false
                    lblInfo.text = "Wheel to zoom in and out, drag to move, click to jump there."
                    if (layer == want) show(b)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                Platform.runLater { progress.isVisible = false; lblInfo.text = "Could not draw the map: ${e.message}" }
            }
        }.apply { isDaemon = true; name = "world-map" }.start()
    }

    // ---------------- drawing ----------------

    private fun build(layerIndex: Int, onProgress: (Double) -> Unit): Built {
        // separate cache handle so drawing doesn't fight with the 3D view
        val lib = CacheLibrary(CachePaths.cacheDir)
        try {
            val regions = RegionLoader(lib)
            val locations = LocationsLoader(lib, xtea)
            val ryRange = LAYERS[layerIndex].second..LAYERS[layerIndex].third
            val found = ArrayList<Int>()
            for (rx in 0 until 128) for (ry in ryRange) {
                val has = try { lib.data(5, "m${rx}_${ry}") != null } catch (e: Exception) { false }
                if (has) found.add((rx shl 8) or ry)
            }
            if (found.isEmpty()) throw IllegalStateException("no map files in this layer")
            val minRx = found.map { it shr 8 }.minOrNull()!!
            val minRy = found.map { it and 0xFF }.minOrNull()!!
            val maxRy = found.map { it and 0xFF }.maxOrNull()!!
            val topRy = minRy + ((maxRy - minRy) / CHUNK + 1) * CHUNK - 1

            val chunkPixels = HashMap<Pair<Int, Int>, IntArray>()
            val side = CHUNK * 64 * PX
            var done = 0
            for (id in found) {
                val rx = id shr 8; val ry = id and 0xFF
                val cx = (rx - minRx) / CHUNK; val cy = (ry - minRy) / CHUNK
                val px = chunkPixels.getOrPut(Pair(cx, cy)) { IntArray(side * side) }
                // local origin of this region inside the chunk (image y goes down = south)
                val ox = ((rx - minRx) % CHUNK) * 64 * PX
                val oyTop = (CHUNK - 1 - (ry - minRy) % CHUNK) * 64 * PX
                val def = mainRegions.cached(id) ?: regions.peek(id)
                if (def != null) drawRegion(def, px, side, ox, oyTop)
                val locs = try { mainLocations.cached(id) ?: locations.peek(id) } catch (e: Exception) { null }
                if (locs != null) drawWalls(locs.locations, px, side, ox, oyTop)
                done++
                if (done % 20 == 0) onProgress(done.toDouble() / found.size)
            }
            onProgress(1.0)
            // pixels -> images on the FX thread
            val images = HashMap<Pair<Int, Int>, WritableImage>()
            val latch = java.util.concurrent.CountDownLatch(1)
            Platform.runLater {
                for ((k, arr) in chunkPixels) {
                    val img = WritableImage(side, side)
                    img.pixelWriter.setPixels(0, 0, side, side, javafx.scene.image.PixelFormat.getIntArgbInstance(), arr, 0, side)
                    images[k] = img
                }
                latch.countDown()
            }
            latch.await()
            return Built(minRx, minRy, topRy, images)
        } finally {
            try { lib.close() } catch (e: Exception) {}
        }
    }

    private fun rgbOfHsl(hsl: Int): Int = palette[hsl and 0xFFFF]

    private fun tileColor(t: cache.definitions.RegionDefinition.Tile?): Int {
        t ?: return 0
        val under = (t.underlayId) - 1
        val over = (t.overlayId) - 1
        val underRgb = if (under >= 0) underlayLoader.get(under)?.color ?: 0 else 0
        if (over >= 0) {
            val d = overlayLoader.get(over)
            if (d != null) {
                val overRgb = when {
                    d.texture >= 0 -> rgbOfHsl(textureLoader.getAverageTextureRGB(d.texture))
                    d.rgbColor == 0xFF00FF -> -1
                    else -> d.rgbColor
                }
                if (overRgb >= 0) {
                    // shaped overlays cover part of the tile: mix with the underlay
                    return if (t.attrOpcode > 1 && (t.attrOpcode - 2) / 4 > 0 && under >= 0) mix(overRgb, underRgb) else overRgb
                }
            }
        }
        return if (under >= 0) underRgb else 0
    }

    private fun mix(a: Int, b: Int): Int =
        (((a shr 16 and 255) + (b shr 16 and 255)) / 2 shl 16) or
                (((a shr 8 and 255) + (b shr 8 and 255)) / 2 shl 8) or
                (((a and 255) + (b and 255)) / 2)

    private fun shade(rgb: Int, f: Double): Int {
        val r = ((rgb shr 16 and 255) * f).toInt().coerceIn(0, 255)
        val g = ((rgb shr 8 and 255) * f).toInt().coerceIn(0, 255)
        val b = ((rgb and 255) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawRegion(def: cache.definitions.RegionDefinition, px: IntArray, side: Int, ox: Int, oyTop: Int) {
        for (x in 0 until 64) for (y in 0 until 64) {
            // plane 0, or plane 1 where a bridge flag lifts it
            val t0 = def.tiles[0][x][y]
            val bridge = t0 != null && (t0.settings.toInt() and 2) != 0
            val t = if (bridge) def.tiles[1][x][y] ?: t0 else t0
            val c = tileColor(t)
            if (c == 0) continue
            // hill shading: compare with the tile to the north-west
            val h = t0?.height ?: 0
            val hn = def.tiles[0][max(0, x - 1)][min(63, y + 1)]?.height ?: h
            val f = (1.0 + (hn - h) / 160.0).coerceIn(0.7, 1.3)
            val argb = shade(c, f)
            val sx = ox + x * PX
            val sy = oyTop + (63 - y) * PX
            for (dx in 0 until PX) for (dy in 0 until PX) px[(sy + dy) * side + sx + dx] = argb
        }
    }

    private fun drawWalls(locs: List<cache.definitions.Location>, px: IntArray, side: Int, ox: Int, oyTop: Int) {
        val white = 0xFFEEEEEE.toInt()
        val red = 0xFFCC2222.toInt()
        for (l in locs) {
            if (l.z != 0) continue
            if (l.type !in 0..3 && l.type != 9) continue
            val def = objectLoader.get(l.objId)
            val door = def?.actions?.any { it != null && (it.equals("Open", true) || it.equals("Close", true)) } == true
            val col = if (door) red else white
            val sx = ox + l.x * PX
            val sy = oyTop + (63 - l.y) * PX
            fun edge(side0: Int) {
                // 0 = west, 1 = north, 2 = east, 3 = south
                for (i in 0 until PX) {
                    val (x, y) = when (side0) {
                        0 -> Pair(sx, sy + i)
                        1 -> Pair(sx + i, sy)
                        2 -> Pair(sx + PX - 1, sy + i)
                        else -> Pair(sx + i, sy + PX - 1)
                    }
                    if (x in 0 until side && y in 0 until side) px[y * side + x] = col
                }
            }
            when (l.type) {
                0 -> edge(l.orientation and 3)
                2 -> { edge(l.orientation and 3); edge((l.orientation + 1) and 3) }
                1, 3 -> { if (sx in 0 until side && sy in 0 until side) px[sy * side + sx] = col }
                9 -> for (i in 0 until PX) {
                    val x = sx + i
                    val y = if (l.orientation and 1 == 0) sy + PX - 1 - i else sy + i
                    if (x in 0 until side && y in 0 until side) px[y * side + x] = col
                }
            }
        }
    }
}
