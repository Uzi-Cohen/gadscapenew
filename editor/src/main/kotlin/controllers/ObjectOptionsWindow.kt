package controllers

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import models.Configuration
import models.GadToolsModel
import models.MultiEdit
import models.ObjectEdit
import utils.ObjDef
import utils.ObjDefs
import java.io.File

/**
 * An object's right-click options (rename, reorder, add, remove), and "works like": make it do what another
 * object does on the server (a booth that opens the Grand Exchange, a barrier you can enter).
 */
class ObjectOptionsWindow(
    private val gadToolsModel: GadToolsModel,
    private val configuration: Configuration,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val locationsLoader: cache.loaders.LocationsLoader,
    private val log: (String) -> Unit
) {
    companion object {
        private var open: ObjectOptionsWindow? = null
        fun show(gadToolsModel: GadToolsModel, configuration: Configuration, objectLoader: cache.loaders.ObjectLoader,
                 locationsLoader: cache.loaders.LocationsLoader, log: (String) -> Unit) {
            val w = open ?: ObjectOptionsWindow(gadToolsModel, configuration, objectLoader, locationsLoader, log).also { open = it }
            w.show()
            gadToolsModel.selectedObject?.let { w.load(it.loc.objId) }
        }
        private fun skip(f: File) = f.invariantSeparatorsPath.let { it.contains("/build/") || it.contains("/.gradle/") || it.contains("/.git/") }
    }

    /** A place in the server's code that mentions the other object. */
    class Ref(val file: File, val line: Int, val kind: String, val token: String, val text: String) {
        override fun toString() = "$kind  |  ${file.name}:${line + 1}  |  ${text.trim().take(110)}"
    }

    private val stage = Stage()
    private var def: ObjDef? = null
    private val txtId = TextField().apply { prefColumnCount = 6; promptText = "object id" }
    private val txtName = TextField().apply { HBox.setHgrow(this, Priority.ALWAYS) }
    private val optFields = Array(5) { TextField().apply { promptText = "(empty)"; HBox.setHgrow(this, Priority.ALWAYS) } }
    private val lblInfo = Label("Pick an object in the 3D view (click it with the Inspect tool), or type its id.").apply { isWrapText = true }
    private val txtLike = TextField().apply { prefColumnCount = 6; promptText = "e.g. 10061" }
    private val refs = FXCollections.observableArrayList<Ref>()
    private val refList = ListView(refs).apply { prefHeight = 200.0 }
    private val lblStatus = Label().apply { isWrapText = true }

    fun load(id: Int) {
        val d = try { ObjDefs.read(id) } catch (e: Exception) { null }
        def = d
        txtId.text = id.toString()
        if (d == null) { lblInfo.text = "Your cache has no object $id."; return }
        txtName.text = d.name
        for (i in 0 until 5) optFields[i].text = d.options[i] ?: ""
        val placed = gadToolsModel.selectedObject?.takeIf { it.loc.objId == id }
        lblInfo.text = "Object $id: ${d.name}" + (placed?.let { " (picked at ${it.worldX}, ${it.worldY})" } ?: "") +
                if (!d.readOk) "\nThis object has settings the editor doesn't know (made by another tool). Saving adds your name and options after them, everything else stays as it is." +
                    (if (utils.ConfigRev.is241(d.rev)) " In a build 241 cache options can only be replaced this way, not removed (options set after the unknown settings aren't shown and stay)." else "")
                else ""
    }

    private fun currentOptions() = Array(5) { optFields[it].text.trim().takeIf { s -> s.isNotEmpty() } }

    private fun swap(a: Int, b: Int) { if (b in 0..4) { val t = optFields[a].text; optFields[a].text = optFields[b].text; optFields[b].text = t } }

    private fun saveAll() {
        val d = def ?: return
        try {
            val saved = ObjDefs.save(d, txtName.text.trim().ifEmpty { "null" }, currentOptions())
            def = saved
            objectLoader.getAll()[saved.id] = objectLoader.load(saved.id, saved.raw)
            lblStatus.text = "Saved object ${d.id} (every ${d.name} with this id, everywhere). Deploy the map to send it."
            log("Object ${d.id}: name \"${saved.name}\", options ${saved.options.joinToString { it ?: "-" }}")
        } catch (e: Exception) { lblStatus.text = "Couldn't save: ${e.message}" }
    }

    /** Copy the definition to a new id and put the picked object on it, so only that one changes. */
    private fun saveAsNew() {
        val d = def ?: return
        val sel = gadToolsModel.selectedObject?.takeIf { it.loc.objId == d.id }
            ?: run { lblStatus.text = "Pick the placed object in the 3D view first (Inspect tool, click it)."; return }
        try {
            val copy = ObjDefs.cloneAsNew(d, txtName.text.trim().ifEmpty { "null" }, currentOptions())
            objectLoader.getAll()[copy.id] = objectLoader.load(copy.id, copy.raw)
            gadToolsModel.actions.add {
                val locs = locationsLoader.get(sel.regionId)?.locations
                if (locs == null || !locs.contains(sel.loc)) { gadToolsModel.message = "The picked object isn't there any more."; null }
                else {
                    val now = sel.loc.copy(objId = copy.id)
                    locs.remove(sel.loc); locs.add(now)
                    gadToolsModel.selectedObject = models.SelectedObject(sel.regionId, now)
                    gadToolsModel.message = "The picked object is now object ${copy.id} (a copy of ${d.id}). Ctrl+Z puts the old one back."
                    MultiEdit(listOf(ObjectEdit(sel.regionId, sel.loc, false), ObjectEdit(sel.regionId, now, true)))
                }
            }
            def = copy; txtId.text = copy.id.toString()
            lblStatus.text = "Made object ${copy.id} (copy of ${d.id}) for just the picked one. Save the map and deploy to send it. " +
                    "To make it work on the server, use \"Works like\" below with ${d.id}."
            txtLike.text = d.id.toString()
        } catch (e: Exception) { lblStatus.text = "Couldn't make the copy: ${e.message}" }
    }

    /** Where the server's code handles object [otherId]: by id, by its ObjectId constant, or by its name. */
    private fun findRefs(otherId: Int) {
        val dir = NpcWindow.serverDir(configuration) ?: run { lblStatus.text = "Set the server folder first (NPCs window or Shops)."; return }
        val other = ObjDefs.read(otherId)
        lblStatus.text = "Looking through the server for object $otherId (${other?.name ?: "?"})..."
        Thread({
            val files = dir.walkTopDown().onEnter { !skip(it) }.filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
            // ObjectId constants for that id
            val constants = files.filter { it.nameWithoutExtension == "ObjectId" }.flatMap { f ->
                f.readLines().mapNotNull { Regex("""\b([A-Z][A-Z0-9_]*)\s*(?::\s*Int\s*)?=\s*$otherId\b""").find(it)?.groupValues?.get(1) }
            }.toSet()
            val idRe = Regex("""\b$otherId\b""")
            val nameRe = other?.name?.takeIf { it != "null" }?.let { Regex("\"" + Regex.escape(it) + "\"", RegexOption.IGNORE_CASE) }
            val constRe = if (constants.isEmpty()) null else Regex("""\b(${constants.joinToString("|")})\b""")
            val out = ArrayList<Ref>()
            for (f in files) {
                if (f.nameWithoutExtension == "ObjectId") continue
                val objectish = f.invariantSeparatorsPath.toLowerCase().let { it.contains("object") || it.contains("/area/") || it.contains("plugin") }
                val ls = try { f.readLines() } catch (e: Exception) { continue }
                ls.forEachIndexed { i, l ->
                    val t = l.trim()
                    if (t.startsWith("//") || t.startsWith("import ")) return@forEachIndexed
                    constRe?.find(l)?.let { out.add(Ref(f, i, "by constant ${it.value}", it.value, l)); return@forEachIndexed }
                    if (objectish) idRe.find(l)?.let { out.add(Ref(f, i, "by id", otherId.toString(), l)); return@forEachIndexed }
                    nameRe?.find(l)?.let { out.add(Ref(f, i, "by name", it.value, l)) }
                }
            }
            Platform.runLater {
                refs.setAll(out.sortedBy { if (it.kind.startsWith("by name")) 1 else 0 })
                lblStatus.text = if (out.isEmpty()) "Nothing in the server's code mentions object $otherId. Its options may be handled by name or option text elsewhere; try another object of the same kind."
                else "Found ${out.size} place(s). \"by id\" / \"by constant\": select one and click Add my object there. " +
                        "\"by name\": the server matches the object's name, so giving yours the same name (and its options) is enough."
            }
        }, "object-refs").apply { isDaemon = true }.start()
    }

    /** Put our object's id next to the other one in that line of code (handler lists like getObjects()). */
    private fun addToRef(r: Ref) {
        val myId = txtId.text.trim().toIntOrNull() ?: run { lblStatus.text = "No object loaded."; return }
        if (r.kind.startsWith("by name")) {
            txtName.text = r.token.trim('"')
            lblStatus.text = "Name set to ${txtName.text}. Copy its options too if needed, then Save."
            return
        }
        val ls = r.file.readLines().toMutableList()
        val l = ls[r.line]
        if (Regex("""\b$myId\b""").containsMatchIn(l)) { lblStatus.text = "That line already has $myId."; return }
        val m = Regex("""\b${Regex.escape(r.token)}\b""").find(l) ?: run { lblStatus.text = "That line changed, find again."; return }
        ls[r.line] = l.substring(0, m.range.last + 1) + ", $myId" + l.substring(m.range.last + 1)
        r.file.writeText(ls.joinToString("\n") + "\n")
        utils.Deployer.markShopEdited(r.file.absolutePath)
        log("Object $myId now handled like ${r.token} in ${r.file.name}:${r.line + 1}")
        lblStatus.text = "Added $myId to ${r.file.name} line ${r.line + 1}:\n${ls[r.line].trim()}\nDeploy with Server > Deploy shops + NPC spawns (it sends every edited server file)."
    }

    fun show() {
        if (stage.scene != null) { stage.show(); stage.toFront(); return }
        val btnLoad = Button("Open").apply { setOnAction { txtId.text.trim().toIntOrNull()?.let { load(it) } } }
        txtId.setOnAction { btnLoad.fire() }
        val btnPicked = Button("Use picked object").apply { setOnAction {
            val s = gadToolsModel.selectedObject ?: run { lblStatus.text = "Click an object in the 3D view first (Inspect tool)."; return@setOnAction }
            load(s.loc.objId)
        } }
        val optRows = VBox(4.0)
        for (i in 0 until 5) {
            val up = Button("▲").apply { isDisable = i == 0; setOnAction { swap(i, i - 1) } }
            val down = Button("▼").apply { isDisable = i == 4; setOnAction { swap(i, i + 1) } }
            val clear = Button("✕").apply { setOnAction { optFields[i].text = "" } }
            optRows.children.add(HBox(4.0, Label("${i + 1}."), optFields[i], up, down, clear).apply { alignment = Pos.CENTER_LEFT })
        }
        val btnSave = Button("Save for every object with this id").apply { styleClass.add("accent"); setOnAction { saveAll() } }
        val btnNew = Button("Save as a new object, just the picked one").apply { setOnAction { saveAsNew() } }

        val btnFind = Button("Find in server code").apply { styleClass.add("accent"); setOnAction {
            txtLike.text.trim().toIntOrNull()?.let { findRefs(it) } ?: run { lblStatus.text = "Type the id of the object it should work like." }
        } }
        val btnCopyOpts = Button("Copy its name + options").apply { setOnAction {
            val o = txtLike.text.trim().toIntOrNull()?.let { ObjDefs.read(it) } ?: run { lblStatus.text = "No such object."; return@setOnAction }
            txtName.text = o.name
            for (i in 0 until 5) optFields[i].text = o.options[i] ?: ""
            lblStatus.text = "Copied ${o.name}'s name and options. Save to keep them."
        } }
        val btnAdd = Button("Add my object there").apply { setOnAction {
            refList.selectionModel.selectedItem?.let { addToRef(it) } ?: run { lblStatus.text = "Select a line in the list." }
        } }

        fun header(t: String) = Label(t).apply { styleClass.add("section-header") }
        val root = VBox(8.0,
            header("Object"),
            HBox(6.0, Label("id"), txtId, btnLoad, btnPicked).apply { alignment = Pos.CENTER_LEFT },
            lblInfo,
            HBox(6.0, Label("Name"), txtName).apply { alignment = Pos.CENTER_LEFT },
            header("Right-click options"), optRows,
            HBox(6.0, btnSave, btnNew).apply { alignment = Pos.CENTER_LEFT },
            Separator(),
            header("Works like another object"),
            Label("The menu is in the cache, what a click does is in the server. Give the id of an object that already does it " +
                    "(Grand Exchange booth 10061, a bank booth, a barrier...)").apply { isWrapText = true; styleClass.add("subtle") },
            HBox(6.0, Label("like object"), txtLike, btnFind, btnCopyOpts).apply { alignment = Pos.CENTER_LEFT },
            refList, btnAdd,
            Separator(), lblStatus
        ).apply { padding = Insets(10.0); VBox.setVgrow(refList, Priority.ALWAYS) }
        stage.scene = Scene(ScrollPane(root).apply { isFitToWidth = true }, 640.0, 820.0)
        utils.Branding.apply(stage, "object options")
        stage.show()
    }
}
