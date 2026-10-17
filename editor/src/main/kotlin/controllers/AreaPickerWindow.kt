package controllers

import cache.definitions.LocationsDefinition
import cache.definitions.RegionDefinition
import com.google.gson.GsonBuilder
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.image.WritableImage
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.*
import utils.GadTools
import java.io.File
import java.util.concurrent.Executors

/**
 * A picker of ready-made areas: the popular places (GE, Edgeville, ...) taken from another cache,
 * plus every area you saved yourself. Pick one, then hover the map and press Ctrl+V to place it
 * (Ctrl+R turns it, and after placing you can drag it with the Select area tool).
 */
class AreaPickerWindow(
    private val gadToolsModel: GadToolsModel,
    private val configuration: Configuration,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val underlayLoader: cache.loaders.UnderlayLoader,
    private val overlayLoader: cache.loaders.OverlayLoader,
    private val copyFromMap: (TileRect) -> Prefab,
    private val pasteAt: (Prefab, Int, Int) -> Unit,
    private val log: (String) -> Unit,
    private val myRegions: cache.loaders.RegionLoader,
    private val myLocations: cache.loaders.LocationsLoader
) {
    /** One row: a popular place (rect in the other cache) or a saved area (file). */
    inner class Entry(val name: String, val preset: AreaFromCacheWindow.Preset?, val file: File?) {
        @Volatile var prefab: Prefab? = null
        @Volatile var thumb: Image? = null
        @Volatile var note: String = if (preset != null) "${preset.x2 - preset.x1 + 1} x ${preset.y2 - preset.y1 + 1}  \u2022  popular place" else "your saved area"
    }

    private val gson = GsonBuilder().create()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "area-picker").apply { isDaemon = true } }
    private val prefabDir get() = File(GadTools.editorDir, "prefabs").apply { mkdirs() }
    /** Popular places already read from the other cache are kept here, so they open instantly next time. */
    private val popularDir get() = File(prefabDir, "popular").apply { mkdirs() }
    private fun slug(n: String) = n.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').toLowerCase()

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

    /** Read a popular place from the other cache, leaving out anything your own cache doesn't have. */
    private fun loadPreset(p: AreaFromCacheWindow.Preset): Pair<Prefab?, String> {
        val dir = File(configuration.getProp("area-source-dir"))
        if (!utils.OtherCache.exists(dir))
            return Pair(null, "Pick the other cache folder at the top first (for example C:\\GadScape\\OSRS211).")
        val other = utils.OtherCache.open(dir, myRegions, myLocations)
        try {
            val access = other.access
            val raw = TileOps(access).copy(TileRect.of(p.x1, p.y1, p.x2, p.y2))
            if (raw.tiles.isEmpty()) return Pair(null, "Nothing found for ${p.name} in that cache.")
            var bad = 0
            val objs = raw.objects.filter { (objectLoader.get(it.id) != null).also { ok -> if (!ok) bad++ } }
            val tiles = raw.tiles.map { t ->
                var u = t.underlay; var o = t.overlay; var op = t.opcode
                if (u != 0 && underlayLoader.get(u - 1) == null && underlayLoader.get(u) == null) { u = 0; bad++ }
                if (o != 0 && overlayLoader.get(o - 1) == null && overlayLoader.get(o) == null) { o = 0; op = 0; bad++ }
                t.copy(underlay = u, overlay = o, opcode = op)
            }
            // round places: keep only what's inside the circle
            val keptObjs = objs.filter { p.inside(it.dx, it.dy) && it.z >= p.dropFloors }.map { it.copy(z = it.z - p.dropFloors) }
            val keptTiles = if (p.objectsOnly) emptyList() else tiles.filter { p.inside(it.dx, it.dy) && it.z >= p.dropFloors }.map { it.copy(z = it.z - p.dropFloors) }
            val pf = raw.copy(tiles = keptTiles, objects = keptObjs, objectsOnly = p.objectsOnly)
            try { File(popularDir, slug(p.name) + ".json").writeText(gson.toJson(pf)) } catch (e: Exception) {}
            return Pair(pf, "${objs.size} objects" + if (bad > 0) ", $bad things left out (not in your cache)" else "")
        } finally { other.close() }
    }

    private fun readPrefab(f: File): Prefab? = try { gson.fromJson(f.readText(), Prefab::class.java)?.takeIf { it.tiles != null && it.objects != null } } catch (e: Exception) { null }

    /** A small top-down picture: ground colours, darker where there are objects. */
    private fun thumbnail(p: Prefab, size: Int = 72): Image {
        val w = p.width.coerceAtLeast(1); val h = p.height.coerceAtLeast(1)
        val scale = size.toDouble() / maxOf(w, h)
        val iw = (w * scale).toInt().coerceAtLeast(1); val ih = (h * scale).toInt().coerceAtLeast(1)
        val colors = IntArray(w * h)
        for (t in p.tiles) {
            if (t.z != 0 || t.dx !in 0 until w || t.dy !in 0 until h) continue
            var c = if (t.underlay > 0) underlayLoader.get(t.underlay - 1)?.color ?: 0x3a3a3a else 0x202020
            if (t.overlay > 0) overlayLoader.get(t.overlay - 1)?.let { d ->
                c = when { d.texture >= 0 -> 0x6e6e6e; d.rgbColor == 0xFF00FF -> c; else -> d.rgbColor }
            }
            colors[t.dx + (h - 1 - t.dy) * w] = c
        }
        val busy = IntArray(w * h)
        for (o in p.objects) if (o.dx in 0 until w && o.dy in 0 until h) busy[o.dx + (h - 1 - o.dy) * w]++
        val img = WritableImage(iw, ih)
        for (y in 0 until ih) for (x in 0 until iw) {
            val i = (x / scale).toInt().coerceIn(0, w - 1) + (y / scale).toInt().coerceIn(0, h - 1) * w
            var c = colors[i]
            if (busy[i] > 0) c = (((c shr 16 and 255) * 55 / 100) shl 16) or (((c shr 8 and 255) * 55 / 100) shl 8) or ((c and 255) * 55 / 100)
            img.pixelWriter.setArgb(x, y, (0xFF shl 24) or c)
        }
        return img
    }

    fun show() {
        val entries = FXCollections.observableArrayList<Entry>()
        fun reload() {
            val list = ArrayList<Entry>()
            AreaFromCacheWindow.POPULAR.filter { it.x2 > 0 }.forEach { list.add(Entry(it.name, it, File(popularDir, slug(it.name) + ".json"))) }
            prefabDir.listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name.toLowerCase() }?.forEach {
                list.add(Entry("\u2605 " + it.nameWithoutExtension.replace('-', ' '), null, it))
            }
            entries.setAll(list)
            // pictures: saved ones are quick; popular ones only if they were read before (otherwise on first pick)
            worker.execute {
                for (e in list) {
                    val pf = e.file?.takeIf { it.isFile }?.let { readPrefab(it) } ?: continue
                    e.prefab = pf
                    e.thumb = thumbnail(pf)
                    if (e.preset == null) e.note = "${pf.width} x ${pf.height}  \u2022  your saved area"
                    Platform.runLater { listView.refresh() }
                }
            }
        }

        val search = TextField().apply { promptText = "Search areas (GE, bank, castle...)" }
        val filtered = FilteredList(entries)
        search.textProperty().addListener { _, _, q -> filtered.setPredicate { e -> q.isBlank() || e.name.toLowerCase().contains(q.trim().toLowerCase()) } }
        listView.items = filtered
        listView.setCellFactory {
            object : ListCell<Entry>() {
                override fun updateItem(e: Entry?, empty: Boolean) {
                    super.updateItem(e, empty)
                    if (empty || e == null) { text = null; graphic = null; return }
                    val pic = ImageView(e.thumb).apply { fitWidth = 72.0; fitHeight = 72.0; isPreserveRatio = true }
                    val box = javafx.scene.layout.StackPane(pic).apply { minWidth = 76.0; minHeight = 76.0; style = "-fx-background-color: #14100c;" }
                    val title = Label(e.name).apply { style = "-fx-font-weight: bold;" }
                    val sub = Label(e.note).apply { styleClass.add("subtle") }
                    graphic = HBox(10.0, box, VBox(4.0, title, sub)).apply { alignment = Pos.CENTER_LEFT }
                    text = null
                }
            }
        }

        val txtDir = TextField(configuration.getProp("area-source-dir")).apply { promptText = "Other cache folder (e.g. C:\\GadScape\\OSRS211)"; HBox.setHgrow(this, Priority.ALWAYS) }
        txtDir.textProperty().addListener { _, _, v -> configuration.saveProp("area-source-dir", v.trim()) }
        val stage = Stage()
        val btnDir = Button("Browse...").apply {
            setOnAction {
                val d = DirectoryChooser().apply { title = "Folder with the other cache"
                    txtDir.text.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it } }
                    .showDialog(stage) ?: return@setOnAction
                txtDir.text = d.path
            }
        }
        val lbl = Label("Double-click an area to pick it. Then hover the map and press Ctrl+V to place it (Ctrl+R turns it). " +
                "After placing, drag it with the Select area tool to fine-tune.").apply { isWrapText = true }

        fun withPrefab(e: Entry, then: (Prefab) -> Unit) {
            e.prefab?.let { then(it); return }
            val short = e.name.removePrefix("\u2605 ")
            lbl.text = "Reading $short... (only slow the first time, can take 10-20 seconds)"
            gadToolsModel.clipboardLoading = short
            gadToolsModel.message = lbl.text
            log(lbl.text)
            worker.execute {
                var pf: Prefab? = null
                var info: String
                try {
                    val r = when {
                        e.preset != null -> loadPreset(e.preset)
                        e.file != null -> Pair(readPrefab(e.file), if (e.file.isFile) "" else "File missing: ${e.file.path}")
                        else -> Pair(null, "")
                    }
                    pf = r.first; info = r.second
                    if (pf != null) {
                        e.prefab = pf
                        try { e.thumb = thumbnail(pf) } catch (t: Throwable) { t.printStackTrace() }
                        if (info.isNotBlank()) e.note = "${pf.width} x ${pf.height}  \u2022  $info"
                    }
                } catch (x: Throwable) {
                    x.printStackTrace()
                    info = "Couldn't read $short: $x"
                }
                val done = pf
                val msg = info
                Platform.runLater {
                    gadToolsModel.clipboardLoading = null
                    listView.refresh()
                    if (done == null) {
                        lbl.text = msg.ifBlank { "Couldn't open $short." }
                        gadToolsModel.message = lbl.text
                        log(lbl.text)
                    } else then(done)
                }
            }
        }
        fun pick(e: Entry) = withPrefab(e) { full ->
            val pf = if (chkObjectsOnly.isSelected) full.copy(tiles = emptyList(), objectsOnly = true) else full
            gadToolsModel.clipboard = pf
            gadToolsModel.lastCopyWasObject = false
            gadToolsModel.message = "Picked ${e.name.removePrefix("\u2605 ")} (${pf.width} x ${pf.height}). Hover a tile and press Ctrl+V to place it (Ctrl+R turns it)."
            lbl.text = gadToolsModel.message
            log(gadToolsModel.message)
        }

        chkObjectsOnly.tooltip = Tooltip("Takes just the objects (buildings, booths, trees...) and leaves your floor as it is.")
        listView.setOnMouseClicked { if (it.clickCount == 2) listView.selectionModel.selectedItem?.let { e -> pick(e) } }
        val btnPick = Button("Pick").apply { styleClass.add("accent"); setOnAction { listView.selectionModel.selectedItem?.let { pick(it) } } }
        val btnSame = Button("Place at its real spot").apply {
            tooltip = Tooltip("Popular places only: puts it exactly where it is in the real game.")
            setOnAction {
                val e = listView.selectionModel.selectedItem ?: return@setOnAction
                val p = e.preset ?: run { lbl.text = "Saved areas don't have a real spot: pick it and Ctrl+V where you want it."; return@setOnAction }
                withPrefab(e) { pf -> pasteAt(pf, p.x1, p.y1); lbl.text = "Placed ${e.name} at ${p.x1}, ${p.y1}. Ctrl+Z undoes it." }
            }
        }
        val btnAdd = Button("Save my selection here").apply {
            tooltip = Tooltip("Adds the area you selected on the map (Select area tool) to this list.")
            setOnAction {
                val sel = gadToolsModel.selection ?: run { lbl.text = "Select an area on the map first (Select area tool, =)."; return@setOnAction }
                val name = TextInputDialog("my area").apply { headerText = "Name for this area"; title = "Save area"; initOwner(stage) }
                    .showAndWait().orElse(null)?.trim()?.takeIf { it.isNotEmpty() } ?: return@setOnAction
                val pf = copyFromMap(sel)
                try { File(prefabDir, slug(name) + ".json").writeText(gson.toJson(pf)); lbl.text = "Saved \"$name\"."; reload() }
                catch (x: Exception) { lbl.text = "Couldn't save: ${x.message}" }
            }
        }
        val btnDelete = Button("Delete").apply {
            setOnAction {
                val e = listView.selectionModel.selectedItem ?: return@setOnAction
                if (e.preset != null) { lbl.text = "Popular places can't be deleted (only your own saved areas)."; return@setOnAction }
                e.file?.delete(); reload()
            }
        }

        val root = VBox(8.0,
            HBox(6.0, Label("Other cache"), txtDir, btnDir).apply { alignment = Pos.CENTER_LEFT },
            search, listView,
            HBox(6.0, btnPick, btnSame, btnAdd, btnDelete),
            chkObjectsOnly,
            lbl
        ).apply { padding = Insets(10.0); VBox.setVgrow(listView, Priority.ALWAYS) }
        stage.scene = Scene(root, 520.0, 680.0)
        utils.Branding.apply(stage, "areas")
        stage.setOnHidden { worker.shutdownNow() }
        stage.show()
        reload()
    }

    private val listView = ListView<Entry>()
    private val chkObjectsOnly = CheckBox("Only the objects (keep my floor)")
}
