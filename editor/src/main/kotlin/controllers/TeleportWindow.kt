package controllers

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.Configuration
import models.GadToolsModel
import utils.GadTools
import java.io.File

/**
 * Every place the server's code sends players to: teleport menus, home / respawn, objects that teleport you.
 * Found by reading the server source for coordinates (Location(3222, 3218, 0) and the like). Pick one, see where it
 * goes on the map, change it (type it, or press P on a tile in the 3D view and use the pinned tile), save, deploy.
 */
class TeleportWindow(
    private val gadToolsModel: GadToolsModel,
    private val configuration: Configuration,
    private val goTo: (Int, Int, Int) -> Unit,
    private val log: (String) -> Unit
) {
    companion object {
        private var open: TeleportWindow? = null
        fun show(gadToolsModel: GadToolsModel, configuration: Configuration, goTo: (Int, Int, Int) -> Unit, log: (String) -> Unit) {
            (open ?: TeleportWindow(gadToolsModel, configuration, goTo, log).also { open = it }).show()
        }
        private val CODE = Regex("""(?:Location|ImmutableLocation|WorldTile|Position|Tile|Coordinate|Coords|teleport|tele)\s*\(\s*(\d{3,5})\s*,\s*(\d{3,5})(?:\s*,\s*(\d))?\s*[,)]""", RegexOption.IGNORE_CASE)
        /** Teleport menu entries: "Alchemical Hydra"(4289, 1351, 10248, 0, "wiki link") (icon, x, y, z). */
        private val MENU = Regex(""""[^"]{2,60}"\s*\(\s*-?\d+\s*,\s*(\d{3,5})\s*,\s*(\d{3,5})\s*,\s*([0-3])\b""")
        private fun codeMatches(l: String) = CODE.findAll(l) + MENU.findAll(l)
        private val DATA = Regex("""[\[(]\s*(\d{4,5})\s*,\s*(\d{4,5})\s*(?:,\s*([0-3]))?\s*[\])]""")
        private val STRING = Regex(""""([^"\\]{2,60})"""")
        private fun skip(f: File) = f.invariantSeparatorsPath.let { it.contains("/build/") || it.contains("/.gradle/") || it.contains("/.git/") || it.contains("/node_modules/") }
        fun inWorld(x: Int, y: Int) = x in 1000..13000 && y in 1000..13000
    }

    /** One coordinate in one line of one file. */
    inner class Hit(val file: File, val line: Int, var x: Int, var y: Int, var z: Int, val hadZ: Boolean, val label: String, val category: String) {
        val origX = x; val origY = y; val origZ = z
        val changed get() = x != origX || y != origY || z != origZ
        val path: String get() = root?.let { file.relativeTo(it).invariantSeparatorsPath } ?: file.path
        override fun toString() = (if (changed) "* " else "") + "$category  |  $x, $y, $z  |  $label  |  ${file.name}:${line + 1}"
    }

    private val stage = Stage()
    private var root: File? = null
    private val all = FXCollections.observableArrayList<Hit>()
    private val shown = FilteredList(all)
    private val list = ListView(shown).apply { prefHeight = 340.0 }
    private val txtSearch = TextField().apply { promptText = "Search: home, varrock, ardougne, a file name, an object id, 3087..." }
    private val cmbCat = ComboBox(FXCollections.observableArrayList("Everything", "Teleports", "Home / respawn", "Objects", "NPCs", "Other"))
    private val txtX = TextField().apply { prefColumnCount = 5 }
    private val txtY = TextField().apply { prefColumnCount = 5 }
    private val txtZ = TextField().apply { prefColumnCount = 2 }
    private val txtContext = TextArea().apply { isEditable = false; prefRowCount = 7; isWrapText = false; style = "-fx-font-family: monospace;" }
    private val lblStatus = Label("Pick the server folder and click Find.").apply { isWrapText = true }
    private val fileCache = HashMap<File, MutableList<String>>()

    private fun lines(f: File): MutableList<String> = fileCache.getOrPut(f) { f.readLines().toMutableList() }

    private fun categoryOf(f: File, text: String): String {
        val p = f.invariantSeparatorsPath.toLowerCase(); val t = text.toLowerCase()
        return when {
            p.contains("home") || p.contains("respawn") || t.contains("home") || t.contains("respawn") -> "Home / respawn"
            p.contains("teleport") || t.contains("teleport") -> "Teleports"
            p.contains("/object") || t.contains("objectaction") || t.contains("objectid") -> "Objects"
            p.contains("/npc") -> "NPCs"
            else -> "Other"
        }
    }

    private fun scan(dir: File): List<Hit> {
        val out = ArrayList<Hit>()
        val exts = setOf("kt", "kts", "java", "toml", "json", "yml", "yaml")
        dir.walkTopDown().onEnter { !skip(it) }.filter { it.isFile && it.extension in exts && it.length() < 3_000_000 }.forEach { f ->
            val isData = f.extension !in setOf("kt", "kts", "java")
            // spawn files are the NPC tool's job
            if (f.nameWithoutExtension.endsWith("Spawns") || f.invariantSeparatorsPath.contains("/spawns/")) return@forEach
            val ls = try { f.readLines() } catch (e: Exception) { return@forEach }
            ls.forEachIndexed { i, l ->
                if (l.trimStart().startsWith("//") || l.trimStart().startsWith("import ")) return@forEachIndexed
                val m = (if (isData) DATA.find(l) else codeMatches(l).firstOrNull()) ?: return@forEachIndexed
                val x = m.groupValues[1].toInt(); val y = m.groupValues[2].toInt()
                if (!inWorld(x, y)) return@forEachIndexed
                val z = m.groupValues[3].toIntOrNull() ?: 0
                val near = (maxOf(0, i - 3)..i).reversed().asSequence().mapNotNull { STRING.find(ls[it])?.groupValues?.get(1) }.firstOrNull()
                val label = near ?: f.nameWithoutExtension
                val ctx = (maxOf(0, i - 3)..i).joinToString(" ") { ls[it] }
                out.add(Hit(f, i, x, y, z, m.groups[3] != null, label, categoryOf(f, ctx)))
            }
        }
        return out
    }

    private fun filter() {
        val q = txtSearch.text.trim().toLowerCase()
        val cat = cmbCat.value ?: "Everything"
        shown.setPredicate { h ->
            (cat == "Everything" || h.category == cat) && (q.isEmpty() || h.label.toLowerCase().contains(q) || h.path.toLowerCase().contains(q) ||
                    "${h.x}, ${h.y}".contains(q) || "${h.x},${h.y}".contains(q) || lines(h.file).getOrNull(h.line)?.toLowerCase()?.contains(q) == true)
        }
        lblStatus.text = "${shown.size} of ${all.size} places." + if (all.any { it.changed }) " ${all.count { it.changed }} changed, not saved yet." else ""
    }

    private fun showHit(h: Hit?) {
        if (h == null) { txtContext.text = ""; return }
        txtX.text = h.x.toString(); txtY.text = h.y.toString(); txtZ.text = h.z.toString()
        val ls = lines(h.file)
        txtContext.text = h.path + "\n" + (maxOf(0, h.line - 3)..minOf(ls.size - 1, h.line + 3)).joinToString("\n") { (if (it == h.line) "> " else "  ") + "${it + 1}: " + ls[it] }
    }

    /** Write the new numbers into the line, keeping everything else on it. */
    private fun rewrite(h: Hit): Boolean {
        val ls = lines(h.file)
        val l = ls.getOrNull(h.line) ?: return false
        val isData = h.file.extension !in setOf("kt", "kts", "java")
        val m = (if (isData) DATA.findAll(l) else codeMatches(l)).firstOrNull { it.groupValues[1].toInt() == h.origX && it.groupValues[2].toInt() == h.origY } ?: return false
        val gx = m.groups[1]!!.range; val gy = m.groups[2]!!.range; val gz = m.groups[3]?.range
        val sb = StringBuilder(l)
        // right to left so the earlier ranges stay valid
        if (gz != null) sb.replace(gz.first, gz.last + 1, h.z.toString())
        else if (h.z != 0) sb.insert(gy.last + 1, ", ${h.z}")
        sb.replace(gy.first, gy.last + 1, h.y.toString())
        sb.replace(gx.first, gx.last + 1, h.x.toString())
        ls[h.line] = sb.toString()
        return true
    }

    private fun apply(targets: List<Hit>, x: Int, y: Int, z: Int) {
        for (h in targets) { h.x = x; h.y = y; h.z = z }
        list.refresh(); filter()
        lblStatus.text = "Changed ${targets.size} place(s) to $x, $y, $z. Click Save files to write them."
    }

    private fun save() {
        val changed = all.filter { it.changed }
        if (changed.isEmpty()) { lblStatus.text = "Nothing changed."; return }
        val bad = ArrayList<Hit>()
        for (h in changed) if (!rewrite(h)) bad.add(h)
        val files = changed.filter { it !in bad }.map { it.file }.distinct()
        val notCode = ArrayList<String>()
        for (f in files) {
            f.writeText(lines(f).joinToString("\n") + "\n")
            if (f.extension in setOf("kt", "kts", "java")) utils.Deployer.markShopEdited(f.absolutePath) else notCode.add(f.name)
        }
        log("Teleports: saved ${changed.size - bad.size} change(s) in ${files.size} file(s)")
        lblStatus.text = "Saved ${changed.size - bad.size} change(s) in ${files.size} file(s). Deploy with Server > Deploy shops + NPC spawns (it sends every edited server file)." +
                (if (bad.isNotEmpty()) "\n${bad.size} couldn't be written (the line changed): ${bad.joinToString { "${it.file.name}:${it.line + 1}" }}" else "") +
                (if (notCode.isNotEmpty()) "\nThese are data files, not code, so they aren't swapped into the live jar: ${notCode.joinToString()}. They need a full server rebuild." else "")
        // the saved values are the new starting point
        val dir = root
        if (dir != null) find(dir)
    }

    private fun find(dir: File) {
        root = dir
        fileCache.clear()
        lblStatus.text = "Reading the server's code for coordinates..."
        Thread({
            val hits = try { scan(dir) } catch (e: Exception) { e.printStackTrace(); emptyList<Hit>() }
            Platform.runLater { all.setAll(hits); filter() }
        }, "teleport-scan").apply { isDaemon = true }.start()
    }

    fun show() {
        if (stage.scene != null) { stage.show(); stage.toFront(); return }
        val txtDir = TextField(NpcWindow.serverDir(configuration)?.path ?: "").apply { HBox.setHgrow(this, Priority.ALWAYS); promptText = "Server folder" }
        val btnBrowse = Button("Browse...").apply { setOnAction { DirectoryChooser().showDialog(stage)?.let { txtDir.text = it.path } } }
        val btnFind = Button("Find").apply { styleClass.add("accent"); setOnAction {
            val (d, note) = utils.ServerSource.fromPicked(configuration, File(txtDir.text.trim()))
            if (d == null) { lblStatus.text = note ?: "Pick the server folder first."; return@setOnAction }
            txtDir.text = d.path
            note?.let { log(it) }
            find(d)
        } }
        cmbCat.value = "Everything"
        cmbCat.setOnAction { filter() }
        txtSearch.textProperty().addListener { _, _, _ -> filter() }
        list.selectionModel.selectedItemProperty().addListener { _, _, h -> showHit(h) }

        fun typed(): Triple<Int, Int, Int>? {
            val x = txtX.text.trim().toIntOrNull(); val y = txtY.text.trim().toIntOrNull(); val z = txtZ.text.trim().toIntOrNull() ?: 0
            if (x == null || y == null || !inWorld(x, y) || z !in 0..3) { lblStatus.text = "Type a tile: x and y (e.g. 3212, 3424) and floor 0-3."; return null }
            return Triple(x, y, z)
        }
        val btnGo = Button("Show on map").apply { setOnAction {
            val h = list.selectionModel.selectedItem ?: return@setOnAction
            goTo(h.x, h.y, h.z)
        } }
        val btnPinned = Button("Use pinned tile (P)").apply {
            tooltip = Tooltip("In the 3D view, hover the tile you want and press P, then click this.")
            setOnAction {
                val p = gadToolsModel.pinned.get() ?: run { lblStatus.text = "Hover a tile in the 3D view and press P first."; return@setOnAction }
                txtX.text = p.x.toString(); txtY.text = p.y.toString(); txtZ.text = p.z.toString()
            }
        }
        val btnApply = Button("Change this one").apply { styleClass.add("accent"); setOnAction {
            val h = list.selectionModel.selectedItem ?: run { lblStatus.text = "Pick a place in the list."; return@setOnAction }
            val (x, y, z) = typed() ?: return@setOnAction
            apply(listOf(h), x, y, z)
        } }
        val btnAll = Button("Change every place with the same tile").apply {
            tooltip = Tooltip("Moving home? Every spot in the code that uses the old home tile (teleport, respawn, ...) moves together.")
            setOnAction {
                val h = list.selectionModel.selectedItem ?: run { lblStatus.text = "Pick a place in the list."; return@setOnAction }
                val (x, y, z) = typed() ?: return@setOnAction
                val same = all.filter { it.origX == h.origX && it.origY == h.origY && it.origZ == h.origZ }
                apply(same, x, y, z)
            }
        }
        val btnRevert = Button("Undo unsaved").apply { setOnAction {
            all.forEach { it.x = it.origX; it.y = it.origY; it.z = it.origZ }; list.refresh(); filter(); showHit(list.selectionModel.selectedItem)
        } }
        val btnSave = Button("Save files").apply { styleClass.add("accent"); setOnAction { save() } }

        // ---- an area was moved on the map: move the code's coordinates inside it the same way ----
        val bx1 = TextField().apply { prefColumnCount = 5; promptText = "x from" }
        val by1 = TextField().apply { prefColumnCount = 5; promptText = "y from" }
        val bx2 = TextField().apply { prefColumnCount = 5; promptText = "x to" }
        val by2 = TextField().apply { prefColumnCount = 5; promptText = "y to" }
        val tdx = TextField().apply { prefColumnCount = 4; promptText = "dx" }
        val tdy = TextField().apply { prefColumnCount = 4; promptText = "dy" }
        val movesFile = File(System.getProperty("user.home"), ".uzis-map-editor/area-moves.txt")
        val cmbMoves = ComboBox<String>().apply { promptText = "Areas you moved with the editor"; prefWidth = 360.0 }
        fun loadMoves() {
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm")
            cmbMoves.items.setAll((if (movesFile.isFile) movesFile.readLines() else emptyList()).mapNotNull { l ->
                val p = l.split(',').mapNotNull { it.trim().toLongOrNull() }
                if (p.size < 7) null else "${fmt.format(java.util.Date(p[6]))}: ${p[0]},${p[1]} - ${p[2]},${p[3]} moved by ${p[4]}, ${p[5]}" +
                        (utils.RegionNames.describe(GadTools.regionOf(p[0].toInt(), p[1].toInt())).let { if (it.isEmpty()) "" else "  ($it)" })
            }.reversed())
        }
        loadMoves()
        cmbMoves.setOnShowing { loadMoves() }
        cmbMoves.setOnAction {
            val v = cmbMoves.value ?: return@setOnAction
            val nums = Regex("""-?\d+""").findAll(v.substringAfter(": ")).map { it.value }.toList()
            if (nums.size >= 6) { bx1.text = nums[0]; by1.text = nums[1]; bx2.text = nums[2]; by2.text = nums[3]; tdx.text = nums[4]; tdy.text = nums[5] }
        }
        val btnShift = Button("Move every place in that box with it").apply { styleClass.add("accent"); setOnAction {
            val v = listOf(bx1, by1, bx2, by2, tdx, tdy).map { it.text.trim().toIntOrNull() }
            if (v.any { it == null }) { lblStatus.text = "Fill in the old box (x from / y from / x to / y to) and how far it moved (dx, dy)."; return@setOnAction }
            val (x1, y1, x2, y2, dx) = v.map { it!! }; val dy = v[5]!!
            val inBox = all.filter { it.origX in minOf(x1, x2)..maxOf(x1, x2) && it.origY in minOf(y1, y2)..maxOf(y1, y2) }
            if (inBox.isEmpty()) { lblStatus.text = "Nothing in the server's code points inside that box."; return@setOnAction }
            for (h in inBox) { h.x = h.origX + dx; h.y = h.origY + dy }
            list.refresh(); filter()
            txtSearch.text = ""
            lblStatus.text = "Moved ${inBox.size} place(s) in the code by $dx, $dy: " + inBox.map { it.file.name }.distinct().joinToString(", ") +
                    ". Check them in the list (marked *), then Save files and deploy."
        } }

        fun header(t: String) = Label(t).apply { styleClass.add("section-header") }
        val root = VBox(8.0,
            header("Server"),
            HBox(6.0, txtDir, btnBrowse, btnFind).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, txtSearch, cmbCat).apply { alignment = Pos.CENTER_LEFT; HBox.setHgrow(txtSearch, Priority.ALWAYS) },
            list, txtContext,
            header("Where it sends you"),
            HBox(6.0, Label("x"), txtX, Label("y"), txtY, Label("floor"), txtZ, btnPinned, btnGo).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, btnApply, btnAll, btnRevert, btnSave).apply { alignment = Pos.CENTER_LEFT },
            header("Moved an area? Move its code with it"),
            HBox(6.0, cmbMoves).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, Label("old box"), bx1, by1, Label("to"), bx2, by2, Label("moved by"), tdx, tdy, btnShift).apply { alignment = Pos.CENTER_LEFT },
            lblStatus,
            Label("Moving home: search \"home\", pick the Edgeville tile, pin the Varrock tile (P in the 3D view), then \"Change every place with the same tile\". " +
                    "An object that teleports wrong: search its object id or the place name. Saved files deploy with Server > Deploy shops + NPC spawns.").apply { isWrapText = true; styleClass.add("subtle") }
        ).apply { padding = Insets(10.0); VBox.setVgrow(list, Priority.ALWAYS) }
        stage.scene = Scene(root, 860.0, 820.0)
        utils.Branding.apply(stage, "teleports")
        stage.show()
        if (txtDir.text.isNotBlank() && File(txtDir.text).isDirectory) find(File(txtDir.text))
    }
}
