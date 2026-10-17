package controllers

import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import com.google.gson.GsonBuilder
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.*
import utils.GadTools
import java.io.File

/**
 * Take a whole area (all floors, tiles and objects) out of another cache - e.g. the Grand Exchange from a
 * newer OSRS cache - and put it on the clipboard, so Ctrl+V places it anywhere (Ctrl+R turns it).
 * Objects, underlays and overlays that don't exist in your own cache are left out and counted.
 */
class AreaFromCacheWindow(
    private val gadToolsModel: GadToolsModel,
    private val configuration: Configuration,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val underlayLoader: cache.loaders.UnderlayLoader,
    private val overlayLoader: cache.loaders.OverlayLoader,
    private val pasteAt: (Prefab, Int, Int) -> Unit,
    private val log: (String) -> Unit,
    private val myRegions: cache.loaders.RegionLoader,
    private val myLocations: cache.loaders.LocationsLoader
) {
    /** round = only the tiles inside the circle that fits the box (for round places like the GE booths). */
    /**
     * dropFloors: in the real game some places sit one floor up on a "bridge" (the GE centre does), so its
     * objects are stored on floor 1. dropFloors = 1 takes only floor 1+ and puts it one floor lower, onto your ground.
     */
    data class Preset(val name: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int, val round: Boolean = false,
                      val objectsOnly: Boolean = false, val dropFloors: Int = 0) {
        fun inside(dx: Int, dy: Int): Boolean {
            if (!round) return true
            val cx = (x2 - x1) / 2.0; val cy = (y2 - y1) / 2.0
            val r = minOf(cx, cy) + 0.5
            return (dx - cx) * (dx - cx) + (dy - cy) * (dy - cy) <= r * r
        }
    }

    private val presets get() = POPULAR

    companion object {
    /** Rough boxes around well-known places (world coordinates). Adjust the numbers if you want more or less. */
    val POPULAR = listOf(
        // measured from the build 211 cache: tower at 3164,3489, booths at 3162-3167 x 3487-3492 (stored on floor 1)
        Preset("GE tower + booths", 3162, 3487, 3167, 3492, objectsOnly = true, dropFloors = 1),
        Preset("Grand Exchange (whole, with stands)", 3140, 3465, 3191, 3516),
        Preset("Edgeville", 3076, 3474, 3127, 3519),
        Preset("Varrock square", 3197, 3413, 3232, 3446),
        Preset("Varrock west bank", 3178, 3427, 3197, 3449),
        Preset("Falador park + square", 2954, 3360, 3010, 3396),
        Preset("Lumbridge castle", 3199, 3200, 3229, 3236),
        Preset("Draynor village", 3075, 3236, 3111, 3265),
        Preset("Al Kharid bank + palace", 3265, 3155, 3305, 3185),
        Preset("Camelot castle", 2741, 3483, 2779, 3519),
        Preset("Seers' Village", 2700, 3460, 2740, 3500),
        Preset("Catherby", 2798, 3424, 2832, 3452),
        Preset("Ardougne market", 2648, 3294, 2678, 3322),
        Preset("Ferox Enclave", 3120, 3610, 3160, 3646),
        Preset("Duel arena / Emir's Arena", 3340, 3220, 3392, 3286),
        Preset("Custom (type the corners)", 0, 0, 0, 0)
    )
    }

    private class SourceAccess(val regions: cache.loaders.RegionLoader, val locs: cache.loaders.LocationsLoader) : RegionAccess {
        private val r = HashMap<Int, RegionDefinition?>()
        private val l = HashMap<Int, LocationsDefinition?>()
        override fun region(regionId: Int) = r.getOrPut(regionId) { try { regions.peek(regionId) } catch (e: Exception) { null } }
        override fun locations(regionId: Int) = l.getOrPut(regionId) { try { locs.peek(regionId) } catch (e: Exception) { null } }
    }

    private fun findCache(dir: File): Pair<File, File>? {
        val dat = dir.walkTopDown().maxDepth(6).filter { it.isFile && it.name.equals("main_file_cache.dat2", true) }
            .sortedBy { it.path.length }.firstOrNull() ?: return null
        val keys = listOfNotNull(dat.parentFile, dat.parentFile?.parentFile).map { File(it, "xteas.json") }.firstOrNull { it.isFile }
            ?: dir.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name.equals("xteas.json", true) }
        // no keys: fine for caches whose maps aren't encrypted (most private servers)
        return Pair(dat.parentFile, keys?.parentFile ?: GadTools.noKeysDir())
    }

    fun show() {
        val txtDir = TextField(configuration.getProp("area-source-dir")).apply { prefColumnCount = 36 }
        val btnDir = Button("Browse...")
        val cmbPreset = ComboBox<Preset>().apply {
            items.setAll(presets)
            setCellFactory { object : ListCell<Preset>() { override fun updateItem(p: Preset?, e: Boolean) { super.updateItem(p, e); text = p?.name } } }
            buttonCell = object : ListCell<Preset>() { override fun updateItem(p: Preset?, e: Boolean) { super.updateItem(p, e); text = p?.name } }
        }
        val fx1 = TextField(); val fy1 = TextField(); val fx2 = TextField(); val fy2 = TextField()
        listOf(fx1, fy1, fx2, fy2).forEach { it.prefColumnCount = 5 }
        cmbPreset.valueProperty().addListener { _, _, p ->
            if (p != null && p.x2 > 0) { fx1.text = "${p.x1}"; fy1.text = "${p.y1}"; fx2.text = "${p.x2}"; fy2.text = "${p.y2}" }
        }
        cmbPreset.selectionModel.selectFirst()
        val btnUseSel = Button("Use my selection's corners").apply {
            setOnAction {
                val s = gadToolsModel.selection ?: return@setOnAction
                fx1.text = "${s.x1}"; fy1.text = "${s.y1}"; fx2.text = "${s.x2}"; fy2.text = "${s.y2}"
            }
        }
        val lbl = Label("Pick the cache folder (the one you unpacked, with xteas.json), an area, then Copy. " +
                "Hover where it should go and press Ctrl+V (Ctrl+R turns it first). Or Paste at the same spot.").apply { isWrapText = true; prefWidth = 560.0 }
        val btnCopy = Button("Copy area").apply { styleClass.add("accent") }
        val btnSame = Button("Copy + paste at the same spot")
        val btnSave = Button("Copy + save as prefab")
        val stage = Stage()

        btnDir.setOnAction {
            val d = DirectoryChooser().apply { title = "Folder with the other cache"
                txtDir.text.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it } }
                .showDialog(stage) ?: return@setOnAction
            txtDir.text = d.path
        }

        fun run(after: (Prefab, TileRect) -> Unit) {
            val dir = File(txtDir.text.trim())
            if (!utils.OtherCache.exists(dir)) { lbl.text = "No main_file_cache.dat2 or .dat in ${dir.path} (or anywhere inside it). Check the folder really has the cache files."; return }
            configuration.saveProp("area-source-dir", dir.path)
            val x1 = fx1.text.trim().toIntOrNull(); val y1 = fy1.text.trim().toIntOrNull()
            val x2 = fx2.text.trim().toIntOrNull(); val y2 = fy2.text.trim().toIntOrNull()
            if (x1 == null || y1 == null || x2 == null || y2 == null) { lbl.text = "Fill in all four corners."; return }
            val rect = TileRect.of(x1, y1, x2, y2)
            if (rect.width * rect.height > 256 * 256) { lbl.text = "That area is too big (max 256 x 256 tiles)."; return }
            lbl.text = "Reading ${rect.width} x ${rect.height} tiles from ${dir.path}..."
            listOf(btnCopy, btnSame, btnSave).forEach { it.isDisable = true }
            gadToolsModel.clipboardLoading = cmbPreset.value?.name ?: "the area"
            Thread({
                val msg = try {
                    val other = utils.OtherCache.open(dir, myRegions, myLocations)
                    try {
                        val access = other.access
                        val raw = TileOps(access).copy(rect)
                        if (raw.tiles.isEmpty()) "Nothing found there (wrong coordinates, or that cache has no keys for it)."
                        else {
                            // leave out things your cache doesn't have, so the client never sees unknown ids
                            var badObj = 0; var badUnder = 0; var badOver = 0
                            val objs = raw.objects.filter { (objectLoader.get(it.id) != null).also { ok -> if (!ok) badObj++ } }
                            val tiles = raw.tiles.map { t ->
                                var u = t.underlay; var o = t.overlay; var op = t.opcode
                                if (u != 0 && underlayLoader.get(u - 1) == null && underlayLoader.get(u) == null) { u = 0; badUnder++ }
                                if (o != 0 && overlayLoader.get(o - 1) == null && overlayLoader.get(o) == null) { o = 0; op = 0; badOver++ }
                                t.copy(underlay = u, overlay = o, opcode = op)
                            }
                            // round presets (picked and corners unchanged): keep only the circle
                            val pre = cmbPreset.value?.takeIf { it.x1 == rect.x1 && it.y1 == rect.y1 && it.x2 == rect.x2 && it.y2 == rect.y2 }
                            val drop = pre?.dropFloors ?: 0
                            val p = raw.copy(
                                tiles = if (pre?.objectsOnly == true) emptyList() else tiles.filter { (pre?.inside(it.dx, it.dy) ?: true) && it.z >= drop }.map { it.copy(z = it.z - drop) },
                                objects = objs.filter { (pre?.inside(it.dx, it.dy) ?: true) && it.z >= drop }.map { it.copy(z = it.z - drop) },
                                objectsOnly = pre?.objectsOnly == true)
                            javafx.application.Platform.runLater { gadToolsModel.clipboard = p; gadToolsModel.lastCopyWasObject = false; after(p, rect) }
                            val regions = HashSet<Int>().apply { for (x in (rect.x1 shr 6)..(rect.x2 shr 6)) for (y in (rect.y1 shr 6)..(rect.y2 shr 6)) add((x shl 8) or y) }
                            val noKeys = regions.filter { access.locations(it) == null }
                            "Copied ${rect.width} x ${rect.height} tiles, ${objs.size} objects." +
                                    (if (badObj + badUnder + badOver > 0) " Left out (not in your cache): $badObj objects, $badUnder ground colours, $badOver overlays." else "") +
                                    (if (noKeys.isNotEmpty()) " No objects for region(s) ${noKeys.joinToString()} (missing key)." else "") +
                                    " Hover a tile and press Ctrl+V (Ctrl+R turns it)."
                        }
                    } finally { other.close() }
                } catch (e: Throwable) {
                    e.printStackTrace(); "Couldn't read that cache: $e"
                }
                log(msg)
                javafx.application.Platform.runLater {
                    gadToolsModel.clipboardLoading = null
                    lbl.text = msg; gadToolsModel.message = msg
                    listOf(btnCopy, btnSame, btnSave).forEach { it.isDisable = false }
                }
            }, "area-from-cache").apply { isDaemon = true }.start()
        }

        btnCopy.setOnAction { run { _, _ -> } }
        btnSame.setOnAction { run { p, r -> pasteAt(p, r.x1, r.y1) } }
        btnSave.setOnAction {
            run { p, _ ->
                val name = (cmbPreset.value?.name ?: "area").replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').toLowerCase()
                val f = File(File(GadTools.editorDir, "prefabs").apply { mkdirs() }, "$name.json")
                try { f.writeText(GsonBuilder().create().toJson(p)); lbl.text = lbl.text + " Saved as prefabs\\${f.name} (Area tab > Import prefab)." }
                catch (e: Exception) { lbl.text = "Couldn't save the prefab: ${e.message}" }
            }
        }

        val grid = GridPane().apply {
            hgap = 6.0; vgap = 8.0
            addRow(0, Label("Other cache"), HBox(6.0, txtDir, btnDir))
            addRow(1, Label("Area"), cmbPreset)
            addRow(2, Label("Corners"), HBox(6.0, Label("SW x"), fx1, Label("y"), fy1, Label("NE x"), fx2, Label("y"), fy2).apply { alignment = Pos.CENTER_LEFT })
            add(btnUseSel, 1, 3)
        }
        val root = VBox(10.0, grid, HBox(6.0, btnCopy, btnSame, btnSave), lbl).apply { padding = Insets(12.0) }
        stage.scene = Scene(root)
        utils.Branding.apply(stage, "bring an area")
        stage.show()
    }
}
