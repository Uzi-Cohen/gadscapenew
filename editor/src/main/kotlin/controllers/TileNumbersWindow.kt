package controllers

import cache.definitions.RegionDefinition
import javafx.animation.AnimationTimer
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.VPos
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import models.GadToolsModel
import models.HoverModel
import models.TerrainTool
import utils.EditorRegions
import utils.GadTools

/**
 * Tile numbers: the underlay / overlay number of every tile around the mouse, on all four floors.
 * Click a tile to paint with it, or type numbers in and use them. Opened and closed from View > Tile numbers.
 */
class TileNumbersWindow private constructor(
    private val gadToolsModel: GadToolsModel,
    private val hoverModel: HoverModel,
    private val scene: models.scene.Scene,
    private val regions: EditorRegions,
    private val underlayLoader: cache.loaders.UnderlayLoader,
    private val overlayLoader: cache.loaders.OverlayLoader
) {
    companion object {
        private var open: TileNumbersWindow? = null
        val showing = javafx.beans.property.SimpleBooleanProperty(false)

        /** Show or hide the window (the View menu tick and the Paint tab button both call this). */
        fun toggle(on: Boolean, gadToolsModel: GadToolsModel, hoverModel: HoverModel, scene: models.scene.Scene, regions: EditorRegions,
                   underlayLoader: cache.loaders.UnderlayLoader, overlayLoader: cache.loaders.OverlayLoader) {
            if (on) {
                val w = open ?: TileNumbersWindow(gadToolsModel, hoverModel, scene, regions, underlayLoader, overlayLoader).also { open = it }
                w.show()
            } else open?.stage?.hide()
        }
    }

    private val stage = Stage()
    private var size = 9                  // tiles across (odd)
    private val cell = 38.0
    private var centre: Pair<Int, Int>? = null
    private var locked = false
    private var lastDrawn = ""
    private var findUnder: Int? = null
    private var findOver: Int? = null
    private val canvases = Array(4) { Canvas() }
    private val floorLabels = Array(4) { Label("Floor $it").apply { styleClass.add("section-header") } }
    private val lblInfo = Label("Hover the 3D map.").apply { isWrapText = true }
    private val chkAll = CheckBox("All floors").apply { isSelected = true }
    private val chkLock = CheckBox("Stop following the mouse")
    private val timer = object : AnimationTimer() {
        var last = 0L
        override fun handle(now: Long) {
            if (now - last < 120_000_000L) return
            last = now
            if (!locked) hoveredWorld()?.let { centre = it }
            redraw(false)
        }
    }

    /** One tile's numbers, read the same way the Pick tool (I) reads them. */
    private class TileNums(val under: Int, val over: Int, val shape: Int, val rot: Int, val flags: Int, val height: Int)

    private fun tileAt(x: Int, y: Int, z: Int): TileNums? {
        val def: RegionDefinition = try { regions.region(GadTools.regionOf(x, y)) } catch (e: Exception) { null } ?: return null
        val t = def.tiles[z][x and 63][y and 63] ?: return null
        val under = (t.underlayId) - 1
        val over = if (t.attrOpcode > 0) (t.overlayId) - 1 else -1
        val shape = if (t.attrOpcode > 1) (t.attrOpcode - 2) / 4 else 0
        val rot = if (t.attrOpcode > 1) (t.attrOpcode - 2) and 3 else 0
        val ground = def.tiles[0][x and 63][y and 63]
        return TileNums(under, over, shape, rot, t.settings.toInt() and 0xFF, ((ground?.height ?: 0) / -8))
    }

    private fun hoveredWorld(): Pair<Int, Int>? {
        val h = hoverModel.hovered.get() ?: return null
        return GadTools.sceneToWorld(scene, h.x, h.y)
    }

    private fun rgb(c: Int) = Color.rgb(c shr 16 and 255, c shr 8 and 255, c and 255)

    private fun tileColor(n: TileNums): Color? {
        if (n.over >= 0) {
            val d = overlayLoader.get(n.over)
            if (d != null) return if (d.texture < 0 && d.rgbColor != 0xFF00FF) rgb(d.rgbColor) else Color.web("#6d6457")
        }
        if (n.under >= 0) {
            val u = underlayLoader.get(n.under)
            if (u != null) return rgb(u.color)
        }
        return null
    }

    /** World tile under a point of floor [z]'s grid. */
    private fun cellAt(px: Double, py: Double): Pair<Int, Int>? {
        val c = centre ?: return null
        val half = size / 2
        val gx = (px / cell).toInt(); val gy = (py / cell).toInt()
        if (gx !in 0 until size || gy !in 0 until size) return null
        return Pair(c.first - half + gx, c.second + half - gy)
    }

    private fun redraw(force: Boolean) {
        val c = centre
        val sig = "$c|$size|${chkAll.isSelected}|${gadToolsModel.paintPlane.get()}|$findUnder|$findOver|${gadToolsModel.unsaved.get()}|${System.nanoTime() / 2_000_000_000L}"
        if (!force && sig == lastDrawn) return
        lastDrawn = sig
        val half = size / 2
        for (z in 0..3) {
            val cv = canvases[z]
            val shown = chkAll.isSelected || z == gadToolsModel.paintPlane.get()
            cv.isVisible = shown; cv.isManaged = shown
            floorLabels[z].isVisible = shown; floorLabels[z].isManaged = shown
            if (!shown) continue
            cv.width = size * cell; cv.height = size * cell
            val g = cv.graphicsContext2D
            g.fill = Color.web("#0b0907"); g.fillRect(0.0, 0.0, cv.width, cv.height)
            if (c == null) continue
            g.textAlign = TextAlignment.CENTER
            g.textBaseline = VPos.CENTER
            for (gx in 0 until size) for (gy in 0 until size) {
                val wx = c.first - half + gx; val wy = c.second + half - gy
                val sx = gx * cell; val sy = gy * cell
                val n = tileAt(wx, wy, z)
                val col = n?.let { tileColor(it) }
                if (col != null) { g.fill = col.deriveColor(0.0, 1.0, 0.55, 1.0); g.fillRect(sx, sy, cell, cell) }
                val hit = n != null && ((findUnder != null && n.under == findUnder) || (findOver != null && n.over == findOver))
                g.stroke = when { hit -> Color.web("#4fc3f7"); gx == half && gy == half -> Color.web("#ffd700"); else -> Color.web("#2a2119") }
                g.lineWidth = if (hit || (gx == half && gy == half)) 2.0 else 1.0
                g.strokeRect(sx + 0.5, sy + 0.5, cell - 1, cell - 1)
                if (n == null) continue
                g.fill = Color.WHITE
                g.font = Font.font("System", FontWeight.BOLD, 10.0)
                g.fillText(if (n.under >= 0) "U${n.under}" else "U-", sx + cell / 2, sy + cell * 0.32)
                g.fill = if (n.over >= 0) Color.web("#ffd27a") else Color.web("#9a8f80")
                g.fillText(if (n.over >= 0) "O${n.over}" else "O-", sx + cell / 2, sy + cell * 0.68)
            }
        }
    }

    private fun describe(x: Int, y: Int, z: Int): String {
        val n = tileAt(x, y, z) ?: return "$x, $y, floor $z: no map data here."
        return "$x, $y, floor $z (region ${GadTools.regionOf(x, y)})\n" +
                "Underlay ${if (n.under < 0) "none" else n.under}   Overlay ${if (n.over < 0) "none" else "${n.over} (shape ${n.shape}, rotation ${n.rot * 90}°)"}\n" +
                "Flags ${n.flags}   Height ${n.height}"
    }

    private fun use(n: TileNums, z: Int, where: String) {
        gadToolsModel.underlayId.set(n.under)
        gadToolsModel.overlayId.set(n.over)
        if (n.over >= 0) { gadToolsModel.overlayShape.set(n.shape); gadToolsModel.overlayRotation.set(n.rot) }
        gadToolsModel.paintPlane.set(z)
        gadToolsModel.tool.set(if (n.over >= 0) TerrainTool.OVERLAY else TerrainTool.UNDERLAY)
        gadToolsModel.message = "Painting with $where: underlay ${if (n.under < 0) "none" else n.under}, overlay ${if (n.over < 0) "none" else n.over}, floor $z."
        lblInfo.text = gadToolsModel.message
    }

    fun show() {
        if (stage.scene == null) build()
        stage.show(); stage.toFront()
        showing.set(true)
        timer.start()
        redraw(true)
    }

    private fun build() {
        // ---- the four floors, 2 x 2 ----
        val grid = GridPane().apply { hgap = 10.0; vgap = 4.0 }
        for (z in 0..3) {
            val cv = canvases[z]
            cv.setOnMouseMoved { e -> cellAt(e.x, e.y)?.let { (x, y) -> lblInfo.text = describe(x, y, z) } }
            cv.setOnMouseClicked { e ->
                val (x, y) = cellAt(e.x, e.y) ?: return@setOnMouseClicked
                if (e.button == MouseButton.PRIMARY) {
                    val n = tileAt(x, y, z) ?: return@setOnMouseClicked
                    use(n, z, "tile $x, $y")
                } else if (e.button == MouseButton.SECONDARY) {
                    // right-click: centre the grid there and stop following the mouse
                    centre = Pair(x, y); chkLock.isSelected = true
                    redraw(true)
                }
            }
            val box = VBox(2.0, floorLabels[z], cv)
            grid.add(box, z % 2, z / 2)
        }

        // ---- size, lock, go to ----
        val cmbSize = ComboBox<String>().apply { items.setAll("5 x 5", "9 x 9", "13 x 13", "17 x 17"); value = "9 x 9" }
        cmbSize.valueProperty().addListener { _, _, v -> size = v.substringBefore(" ").toInt(); redraw(true); stage.sizeToScene() }
        chkAll.selectedProperty().addListener { _, _, _ -> redraw(true); stage.sizeToScene() }
        chkLock.selectedProperty().addListener { _, _, v -> locked = v }
        val txtX = TextField().apply { promptText = "x"; prefColumnCount = 5 }
        val txtY = TextField().apply { promptText = "y"; prefColumnCount = 5 }
        val btnGo = Button("Show").apply { setOnAction {
            val x = txtX.text.trim().toIntOrNull(); val y = txtY.text.trim().toIntOrNull()
            if (x == null || y == null) { lblInfo.text = "Type the tile's x and y."; return@setOnAction }
            centre = Pair(x, y); chkLock.isSelected = true; redraw(true)
            lblInfo.text = describe(x, y, gadToolsModel.paintPlane.get())
        } }

        // ---- use by number ----
        val txtU = TextField().apply { promptText = "underlay #"; prefColumnCount = 6 }
        val txtO = TextField().apply { promptText = "overlay #"; prefColumnCount = 6 }
        val cmbShape = ComboBox<Int>().apply { items.setAll((0 until models.OVERLAY_SHAPES).toList()); value = 0; tooltip = Tooltip("Overlay shape (0 = full tile)") }
        val cmbRot = ComboBox<String>().apply { items.setAll("0°", "90°", "180°", "270°"); value = "0°" }
        val btnUse = Button("Paint with these").apply { styleClass.add("accent"); setOnAction {
            val u = txtU.text.trim().let { if (it.isEmpty() || it == "-") -1 else it.toIntOrNull() }
            val o = txtO.text.trim().let { if (it.isEmpty() || it == "-") -1 else it.toIntOrNull() }
            if (u == null || o == null) { lblInfo.text = "Numbers only (leave a box empty for none)."; return@setOnAction }
            if (u >= 0 && underlayLoader.get(u) == null) { lblInfo.text = "Your cache has no underlay $u."; return@setOnAction }
            if (o >= 0 && overlayLoader.get(o) == null) { lblInfo.text = "Your cache has no overlay $o."; return@setOnAction }
            if (u < 0 && o < 0) { lblInfo.text = "Type an underlay or an overlay number."; return@setOnAction }
            use(TileNums(u, o, cmbShape.value ?: 0, cmbRot.selectionModel.selectedIndex.coerceAtLeast(0), 0, 0),
                gadToolsModel.paintPlane.get(), "the numbers you typed")
        } }
        val btnFind = Button("Mark these").apply {
            tooltip = Tooltip("Outline every tile in the grid with that underlay / overlay number")
            setOnAction {
                findUnder = txtU.text.trim().toIntOrNull(); findOver = txtO.text.trim().toIntOrNull()
                redraw(true)
                lblInfo.text = if (findUnder == null && findOver == null) "Marks cleared." else "Tiles with those numbers are outlined in blue."
            }
        }

        val note = Label("U = underlay (ground colour), O = overlay (paths, floors, water). Left-click a tile to paint with it, " +
                "right-click to pin the grid there. Gold box = the tile under the mouse.").apply { isWrapText = true; styleClass.add("subtle") }
        val root = VBox(6.0,
            HBox(6.0, cmbSize, chkAll, chkLock).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, Label("Tile"), txtX, txtY, btnGo).apply { alignment = Pos.CENTER_LEFT },
            grid,
            lblInfo,
            Separator(),
            Label("Use a tile by its number").apply { styleClass.add("section-header") },
            HBox(6.0, txtU, txtO, Label("shape"), cmbShape, Label("rotation"), cmbRot).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, btnUse, btnFind).apply { alignment = Pos.CENTER_LEFT },
            note
        ).apply { padding = Insets(8.0); maxWidth = 2 * 17 * cell + 40 }
        note.maxWidth = 2 * size * cell + 10
        stage.scene = Scene(ScrollPane(root).apply { isFitToWidth = true })
        utils.Branding.apply(stage, "tile numbers")
        stage.isAlwaysOnTop = true
        stage.setOnHidden { timer.stop(); showing.set(false) }
        redraw(true)
        stage.sizeToScene()
    }
}
