package controllers

import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.Configuration
import models.GadToolsModel
import models.NpcEdit
import models.TerrainTool
import utils.KotlinSpawnFormat
import utils.NpcDef
import utils.NpcDefs
import utils.NpcSpawn
import utils.NpcSpawns
import java.io.File

/**
 * NPCs: find one by name or id, then click the map to place it (NPC tool). Click an NPC on the map to
 * pick it: drag to move, R to turn, Delete to remove. Its right-click options can be renamed and reordered here.
 */
class NpcWindow(private val gadToolsModel: GadToolsModel, private val configuration: Configuration, private val log: (String) -> Unit) {
    companion object {
        private var open: NpcWindow? = null
        fun show(gadToolsModel: GadToolsModel, configuration: Configuration, log: (String) -> Unit) {
            (open ?: NpcWindow(gadToolsModel, configuration, log).also { open = it }).show()
        }
        val DIRS = controllers.worldRenderer.NpcModels.DIRECTIONS

        /** The server folder: picked before, or found in the usual places (see [utils.ServerSource]). */
        fun serverDir(configuration: Configuration): File? = utils.ServerSource.dir(configuration)
    }

    private val stage = Stage()
    private val lblStatus = Label().apply { isWrapText = true }
    private val results = FXCollections.observableArrayList<NpcDef>()
    private val list = ListView(results).apply { prefHeight = 220.0 }
    private val txtSearch = TextField().apply { promptText = "Name or id (e.g. banker, 3106)" }
    private val txtRadius = TextField(gadToolsModel.npcPlaceRadius.get().toString()).apply { prefColumnCount = 3 }

    // selected spawn
    private val lblSel = Label("Nothing picked. Choose the NPC tool and click an NPC on the map.").apply { isWrapText = true }
    private val cmbDir = ComboBox(FXCollections.observableArrayList(DIRS))
    private val txtSelRadius = TextField().apply { prefColumnCount = 3 }
    private var shownSel: NpcSpawn? = null
    private var shownVersion = -1

    // options of one NPC definition
    private var optDef: NpcDef? = null
    private val lblOpt = Label("Pick an NPC to see its options.").apply { isWrapText = true }
    private val optFields = Array(5) { TextField().apply { promptText = "(empty)"; HBox.setHgrow(this, Priority.ALWAYS) } }

    private fun status(s: String) { lblStatus.text = s }

    private fun search() {
        val defs = NpcDefs.cachedOrNull() ?: run { status("Reading NPC definitions..."); return }
        val q = txtSearch.text.trim().toLowerCase()
        val found = if (q.isEmpty()) defs.values.filter { it.name != "null" && it.models.isNotEmpty() }.sortedBy { it.id }.take(300)
        else q.toIntOrNull()?.let { id -> listOfNotNull(defs[id]) + defs.values.filter { it.id != id && it.id.toString().startsWith(q) }.sortedBy { it.id }.take(50) }
            ?: defs.values.filter { it.name.toLowerCase().contains(q) }.sortedWith(compareBy({ !it.name.toLowerCase().startsWith(q) }, { it.id })).take(300)
        results.setAll(found)
        if (found.isEmpty()) status("No NPC matches \"$q\".")
    }

    private fun showOptions(def: NpcDef?) {
        optDef = def
        if (def == null) { lblOpt.text = "Pick an NPC to see its options."; optFields.forEach { it.text = ""; it.isDisable = true }; return }
        lblOpt.text = "Right-click options of ${def.name} (${def.id}), top to bottom as the game shows them." +
                if (!def.readOk) "\nThis NPC uses data the editor can't rewrite, so its options can't be changed." else ""
        for (i in 0 until 5) { optFields[i].text = def.options[i] ?: ""; optFields[i].isDisable = !def.readOk }
    }

    private fun refreshSelected(force: Boolean) {
        val s = gadToolsModel.selectedNpc
        if (!force && s === shownSel && NpcSpawns.version == shownVersion) return
        shownSel = s; shownVersion = NpcSpawns.version
        if (s == null || !NpcSpawns.all.contains(s)) {
            lblSel.text = "Nothing picked. Choose the NPC tool and click an NPC on the map."
            cmbDir.isDisable = true; txtSelRadius.isDisable = true
            return
        }
        val d = NpcDefs.cachedOrNull()?.get(s.id)
        lblSel.text = "${d?.name ?: "NPC"} (${s.id}) at ${s.x}, ${s.y}, floor ${s.z}" + (s.file?.let { "\n${it.name}" } ?: "")
        cmbDir.isDisable = false; txtSelRadius.isDisable = false
        cmbDir.value = s.direction.toUpperCase().takeIf { it in DIRS } ?: "SOUTH"
        txtSelRadius.text = s.radius.toString()
        if (d != null && optDef?.id != d.id) showOptions(d)
    }

    /** Change the picked spawn through the 3D view's undo list. */
    private fun editSelected(what: String, change: (NpcSpawn.State) -> NpcSpawn.State) {
        val s = gadToolsModel.selectedNpc ?: run { status("Pick an NPC on the map first."); return }
        gadToolsModel.actions.add {
            val before = s.state()
            val after = change(before)
            if (after == before) null else {
                NpcSpawns.apply(s, after)
                gadToolsModel.npcVersion.set(gadToolsModel.npcVersion.get() + 1)
                gadToolsModel.message = "$what. Ctrl+Z undoes it."
                NpcEdit(s, before, after)
            }
        }
    }

    private fun readSpawns(dir: File) {
        status("Reading spawn files in ${dir.path}...")
        Thread({
            val msg = try {
                val fmt = KotlinSpawnFormat(dir)
                if (fmt.constantsFile == null) "Couldn't find NpcId.java / NpcId.kt in ${dir.path}, so spawn lines can't be matched to NPCs."
                else NpcSpawns.load(dir, fmt) + " NPC names from ${fmt.constantsFile!!.name}."
            } catch (e: Exception) { e.printStackTrace(); "Couldn't read the spawns: ${e.message}" }
            log(msg)
            Platform.runLater { status(msg); refreshSelected(true) }
        }, "npc-spawns").apply { isDaemon = true }.start()
    }

    fun show() {
        if (stage.scene != null) { stage.show(); stage.toFront(); return }
        NpcDefs.loadInBackground()

        // ---- server folder + spawn files ----
        val txtDir = TextField(serverDir(configuration)?.path ?: "").apply { promptText = "Server folder (the folder with gradlew and plugins/)"; HBox.setHgrow(this, Priority.ALWAYS) }
        val btnBrowse = Button("Browse...").apply { setOnAction {
            DirectoryChooser().apply { title = "Server folder" }.showDialog(stage)?.let { txtDir.text = it.path }
        } }
        val btnRead = Button("Read spawns").apply { styleClass.add("accent"); setOnAction {
            val (d, note) = utils.ServerSource.fromPicked(configuration, File(txtDir.text.trim()))
            note?.let { log(it) }
            if (d == null) { status(note ?: "Pick the server folder first."); return@setOnAction }
            txtDir.text = d.path
            if (NpcSpawns.dirty && !confirm("Read the spawn files again?", "Your NPC changes that aren't saved yet would be lost.")) return@setOnAction
            readSpawns(d)
        } }
        val btnSave = Button("Save spawns").apply { setOnAction {
            status(try { NpcSpawns.save().also { log(it) } + " Deploy them with Server > Deploy shops (spawn files go with the shops)." }
                   catch (e: Exception) { e.printStackTrace(); "Saving failed: ${e.message}" })
        } }

        // ---- find an NPC ----
        txtSearch.textProperty().addListener { _, _, _ -> search() }
        list.setOnMouseClicked { e -> if (e.clickCount >= 2) useForPlacing() }
        list.selectionModel.selectedItemProperty().addListener { _, _, d -> if (d != null) showOptions(d) }
        val btnUse = Button("Place this NPC").apply { styleClass.add("accent"); setOnAction { useForPlacing() } }
        txtRadius.textProperty().addListener { _, _, v -> v.trim().toIntOrNull()?.let { gadToolsModel.npcPlaceRadius.set(it.coerceIn(0, 64)) } }
        val btnSwap = Button("Change picked NPC to this").apply { setOnAction {
            val d = list.selectionModel.selectedItem ?: run { status("Choose an NPC in the list first."); return@setOnAction }
            editSelected("Changed to ${d.name} (${d.id})") { it.copy(id = d.id) }
        } }

        // ---- picked spawn ----
        cmbDir.setOnAction { val v = cmbDir.value ?: return@setOnAction; if (shownSel?.direction != v) editSelected("Turned to face ${v.toLowerCase().replace('_', '-')}") { it.copy(direction = v) } }
        txtSelRadius.setOnAction { txtSelRadius.text.trim().toIntOrNull()?.let { r -> editSelected("Walk range set to $r") { it.copy(radius = r.coerceIn(0, 64)) } } }
        val btnRadius = Button("Set").apply { setOnAction { txtSelRadius.fireEvent(javafx.event.ActionEvent()) } }
        val btnTurnL = Button("↺").apply { tooltip = Tooltip("Turn left (Shift+R)"); setOnAction { turn(-1) } }
        val btnTurnR = Button("↻").apply { tooltip = Tooltip("Turn right (R)"); setOnAction { turn(1) } }
        val btnRemove = Button("Remove").apply { setOnAction {
            val s = gadToolsModel.selectedNpc ?: run { status("Pick an NPC on the map first."); return@setOnAction }
            gadToolsModel.actions.add {
                val before = s.state()
                NpcSpawns.remove(s)
                gadToolsModel.selectedNpc = null
                gadToolsModel.npcVersion.set(gadToolsModel.npcVersion.get() + 1)
                gadToolsModel.message = "Removed NPC ${s.id}. Ctrl+Z puts it back."
                NpcEdit(s, before, null)
            }
        } }

        // ---- options ----
        val optRows = VBox(4.0)
        for (i in 0 until 5) {
            val up = Button("▲").apply { tooltip = Tooltip("Move up"); setOnAction { swap(i, i - 1) } }
            val down = Button("▼").apply { tooltip = Tooltip("Move down"); setOnAction { swap(i, i + 1) } }
            val clear = Button("✕").apply { tooltip = Tooltip("Remove this option"); setOnAction { optFields[i].text = "" } }
            if (i == 0) up.isDisable = true
            if (i == 4) down.isDisable = true
            optRows.children.add(HBox(4.0, Label("${i + 1}."), optFields[i], up, down, clear).apply { alignment = Pos.CENTER_LEFT })
        }
        val btnSaveOpts = Button("Save options").apply { styleClass.add("accent"); setOnAction { saveOptions() } }
        val btnResetOpts = Button("Undo changes").apply { setOnAction { showOptions(optDef) } }
        showOptions(null)

        val selGrid = GridPane().apply { hgap = 6.0; vgap = 6.0 }
        selGrid.addRow(0, Label("Facing"), cmbDir, btnTurnL, btnTurnR)
        selGrid.addRow(1, Label("Walk range"), txtSelRadius, btnRadius, btnRemove)

        fun header(t: String) = Label(t).apply { styleClass.add("section-header") }
        val root = VBox(8.0,
            header("Server spawn files"),
            HBox(6.0, txtDir, btnBrowse).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, btnRead, btnSave).apply { alignment = Pos.CENTER_LEFT },
            Separator(),
            header("Find an NPC"),
            txtSearch, list,
            HBox(6.0, btnUse, Label("walk range"), txtRadius, btnSwap).apply { alignment = Pos.CENTER_LEFT },
            Separator(),
            header("Picked on the map"),
            lblSel, selGrid,
            Separator(),
            header("Right-click options"),
            lblOpt, optRows,
            HBox(6.0, btnSaveOpts, btnResetOpts).apply { alignment = Pos.CENTER_LEFT },
            Separator(),
            lblStatus,
            Label("NPC tool: click an empty tile to place, click an NPC to pick it, drag to move it, R / Shift+R to turn, Delete to remove. " +
                    "Ctrl+Z undoes. The numbers above NPCs are their ids (green = picked).").apply { isWrapText = true; styleClass.add("subtle") }
        ).apply { padding = Insets(10.0) }
        stage.scene = Scene(ScrollPane(root).apply { isFitToWidth = true }, 470.0, 860.0)
        utils.Branding.apply(stage, "NPCs")

        object : AnimationTimer() {
            var searched = false
            override fun handle(now: Long) {
                if (!searched && NpcDefs.cachedOrNull() != null) { searched = true; search(); status(NpcSpawns.info) }
                refreshSelected(false)
            }
        }.start()
        stage.show()
        if (NpcSpawns.all.isEmpty() && txtDir.text.isNotBlank() && File(txtDir.text).isDirectory) readSpawns(File(txtDir.text))
    }

    private fun useForPlacing() {
        val d = list.selectionModel.selectedItem ?: run { status("Choose an NPC in the list first."); return }
        gadToolsModel.npcPlaceId.set(d.id)
        gadToolsModel.selectedObject = null
        gadToolsModel.tool.set(TerrainTool.NPC)
        status("Click a tile on the map to place ${d.name} (${d.id}). Click an NPC to pick it instead.")
    }

    private fun turn(step: Int) {
        val s = gadToolsModel.selectedNpc ?: run { status("Pick an NPC on the map first."); return }
        val i = DIRS.indexOf(s.direction.toUpperCase()).coerceAtLeast(0)
        val next = DIRS[(i + step + 8) % 8]
        editSelected("Turned to face ${next.toLowerCase().replace('_', '-')}") { it.copy(direction = next) }
    }

    private fun swap(a: Int, b: Int) {
        if (b !in 0..4) return
        val t = optFields[a].text; optFields[a].text = optFields[b].text; optFields[b].text = t
    }

    private fun saveOptions() {
        val def = optDef ?: run { status("Pick an NPC first."); return }
        val opts = Array(5) { optFields[it].text.trim().takeIf { s -> s.isNotEmpty() } }
        if (opts.contentEquals(def.options)) { status("The options didn't change."); return }
        try {
            val saved = NpcDefs.saveOptions(def, opts)
            showOptions(saved)
            status("Saved the options of ${def.name} (${def.id}) into your map's cache. Deploy the map to send them to the server. " +
                    "The server only reacts to options its code knows (renaming Trade means the server's \"Trade\" handler won't fire).")
            log("NPC ${def.id}: options now ${opts.joinToString(", ") { it ?: "-" }}")
        } catch (e: Exception) {
            status("Couldn't save: ${e.message}")
        }
    }

    private fun confirm(header: String, text: String): Boolean {
        val a = Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL)
        a.title = "NPCs"; a.headerText = header; a.initOwner(stage)
        return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }
}
