package controllers

import cache.CachePaths
import com.displee.cache.CacheLibrary
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.Configuration
import utils.GadTools
import java.io.File

/**
 * Compare another cache (e.g. another server's client cache) with yours and list every map region that
 * is different there: that's where their custom areas are. Import the ones you want.
 */
class CacheCompareWindow(
    private val configuration: Configuration,
    private val importRegions: (File, File, List<Int>) -> Unit,
    private val goTo: (Int, Int) -> Unit,
    private val log: (String) -> Unit,
    private val myRegions: cache.loaders.RegionLoader,
    private val myLocations: cache.loaders.LocationsLoader
) {
    data class Diff(val regionId: Int, val what: String) {
        val x get() = (regionId shr 8) shl 6
        val y get() = (regionId and 0xFF) shl 6
        val place: String by lazy { utils.RegionNames.describe(regionId) }
        override fun toString() = (if (place.isNotEmpty()) "$place  -  " else "(no name)  -  ") + "$what   [region $regionId, ${x}-${x + 63}, ${y}-${y + 63}]"
    }

    private fun label(newHere: Boolean, ground: Boolean, objs: Boolean) = when {
        newHere -> "NEW (you don't have this region)"
        ground && objs -> "ground + objects differ"
        ground -> "ground differs"
        else -> "objects differ"
    }

    private fun jhash(s: String): Int { var h = 0; for (c in s) h = h * 31 + c.toInt(); return h }

    /** name hash -> crc of every map file (m = ground, l = objects) in index 5. */
    private fun mapCrcs(lib: CacheLibrary): Map<Int, Int> =
        lib.index(5).archives().associate { it.hashName to it.crc }

    fun show() {
        val txtDir = TextField(configuration.getProp("compare-dir")).apply { promptText = "The other cache folder"; HBox.setHgrow(this, Priority.ALWAYS) }
        val stage = Stage()
        val btnDir = Button("Browse...").apply {
            setOnAction {
                val d = DirectoryChooser().apply { title = "Folder with the other cache"
                    txtDir.text.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it } }
                    .showDialog(stage) ?: return@setOnAction
                txtDir.text = d.path
            }
        }
        val all = javafx.collections.FXCollections.observableArrayList<Diff>()
        val filtered = javafx.collections.transformation.FilteredList(all)
        val list = ListView<Diff>(filtered).apply { selectionModel.selectionMode = SelectionMode.MULTIPLE }
        val txtFind = TextField().apply { promptText = "Find a place: edgeville, varrock, zulrah, wilderness, 12342..."; HBox.setHgrow(this, Priority.ALWAYS) }
        val chkNamed = CheckBox("Only named places")
        val cmbSort = ComboBox(javafx.collections.FXCollections.observableArrayList("Sort by name", "Sort by region id")).apply { value = "Sort by name" }
        fun refilter() {
            val q = txtFind.text.trim().toLowerCase()
            filtered.setPredicate { d -> (!chkNamed.isSelected || utils.RegionNames.exact(d.regionId) != null) &&
                    (q.isEmpty() || d.place.toLowerCase().contains(q) || d.regionId.toString().startsWith(q) || d.what.toLowerCase().contains(q)) }
        }
        fun resort() {
            val sorted = if (cmbSort.value == "Sort by region id") all.sortedBy { it.regionId }
                else all.sortedWith(compareBy({ it.place.isEmpty() }, { it.place.removePrefix("near ").removePrefix("under ").toLowerCase() }, { it.regionId }))
            all.setAll(sorted)
        }
        txtFind.textProperty().addListener { _, _, _ -> refilter() }
        chkNamed.setOnAction { refilter() }
        cmbSort.setOnAction { resort() }
        val lbl = Label("Pick the other cache and click Compare. Every region listed is different there (custom built, or newer).").apply { isWrapText = true }
        var cacheDir: File? = null
        var keysDir: File? = null

        val btnCompare = Button("Compare").apply { styleClass.add("accent") }
        btnCompare.setOnAction {
            val dir = File(txtDir.text.trim())
            if (!utils.OtherCache.exists(dir)) { lbl.text = "No main_file_cache.dat2 or .dat in that folder."; return@setOnAction }
            configuration.saveProp("compare-dir", dir.path)
            cacheDir = dir
            keysDir = GadTools.noKeysDir()
            lbl.text = "Comparing..."
            btnCompare.isDisable = true
            Thread({
                val result = try {
                    val other = utils.OtherCache.open(dir, myRegions, myLocations)
                    val mine = CacheLibrary(CachePaths.cacheDir)
                    val myKeys = try { cache.XteaManager(GadTools.editorDir.path) } catch (e: Exception) { null }
                    try {
                        val diffs = ArrayList<Diff>()
                        if (!other.oldStyle) {
                            // same format: compare the file checksums (fast)
                            val oLib = CacheLibrary(other.folder.path)
                            val a = try { mapCrcs(oLib) } finally { oLib.close() }
                            val b = mapCrcs(mine)
                            for (rx in 0 until 256) for (ry in 0 until 256) {
                                val m = jhash("m${rx}_$ry"); val l = jhash("l${rx}_$ry")
                                if (!a.containsKey(m) && !a.containsKey(l)) continue
                                val ground = a[m] != null && a[m] != b[m]
                                val objs = a[l] != null && a[l] != b[l]
                                val newHere = !b.containsKey(m) && !b.containsKey(l)
                                if (ground || objs) diffs.add(Diff((rx shl 8) or ry, label(newHere, ground, objs)))
                            }
                        } else {
                            // old-style cache: compare the map files themselves, region by region
                            for (id in other.regionIds!!.sorted()) {
                                val name = "${id shr 8}_${id and 0xFF}"
                                val myM = try { mine.data(5, "m$name") } catch (e: Exception) { null }
                                val myL = try { mine.data(5, "l$name", myKeys?.getKeys(id)) ?: mine.data(5, "l$name") } catch (e: Exception) { null }
                                val oM = try { other.rawTerrain(id) } catch (e: Exception) { null }
                                val oL = try { other.rawObjects(id) } catch (e: Exception) { null }
                                if (oM == null && oL == null) continue
                                val ground = oM != null && (myM == null || !oM.contentEquals(myM))
                                val objs = oL != null && (myL == null || !oL.contentEquals(myL))
                                if (ground || objs) diffs.add(Diff(id, label(myM == null && myL == null, ground, objs)))
                            }
                        }
                        Pair(diffs, "${diffs.size} regions are different in that cache (${other.describe}).")
                    } finally { other.close(); try { mine.close() } catch (e: Exception) {} }
                } catch (e: Throwable) { e.printStackTrace(); Pair(emptyList<Diff>(), "Couldn't compare: $e") }
                log(result.second)
                Platform.runLater { all.setAll(result.first); resort(); refilter(); lbl.text = result.second + " Search a place name above, select the ones you want and Import them."; btnCompare.isDisable = false }
            }, "cache-compare").apply { isDaemon = true }.start()
        }

        val btnImport = Button("Import selected").apply {
            setOnAction {
                val sel = list.selectionModel.selectedItems.map { it.regionId }
                if (sel.isEmpty() || cacheDir == null) { lbl.text = "Compare first, then select regions."; return@setOnAction }
                val a = Alert(Alert.AlertType.CONFIRMATION, "Replace ${sel.size} region(s) here with the other cache's version (ground and all objects)? " +
                        "Back up first if you're unsure (Server tab). Ctrl+S saves afterwards.", ButtonType.OK, ButtonType.CANCEL).apply { initOwner(stage); headerText = null }
                if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return@setOnAction
                importRegions(cacheDir!!, keysDir!!, sel)
                lbl.text = "Importing ${sel.size} region(s)... see the message bar in the editor."
            }
        }
        val btnGo = Button("Go there").apply {
            setOnAction { list.selectionModel.selectedItem?.let { goTo(it.x + 32, it.y + 32) } }
        }
        val btnCopy = Button("Copy ids").apply {
            setOnAction {
                val ids = list.selectionModel.selectedItems.ifEmpty { filtered }.joinToString(", ") { it.regionId.toString() }
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(ids) })
                lbl.text = "Copied: $ids"
            }
        }
        val root = VBox(8.0,
            HBox(6.0, Label("Other cache"), txtDir, btnDir, btnCompare).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, txtFind, chkNamed, cmbSort).apply { alignment = Pos.CENTER_LEFT },
            list, HBox(6.0, btnImport, btnGo, btnCopy), lbl
        ).apply { padding = Insets(10.0); VBox.setVgrow(list, Priority.ALWAYS) }
        stage.scene = Scene(root, 820.0, 620.0)
        utils.Branding.apply(stage, "find custom areas")
        stage.show()
    }
}
