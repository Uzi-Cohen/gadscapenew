package controllers

import cache.loaders.ModelLoader
import cache.loaders.TextureLoader
import com.displee.cache.CacheLibrary
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Modality
import javafx.stage.Stage
import utils.*
import java.io.File

/**
 * "Make a new item": a step-by-step window. Start from an item (or nothing), give it a name and options, change how
 * it looks, see it on a player, set what it does in game, check everything, then Create. Nothing is written until
 * Create; [create] does the writing (the Items tab) and returns an error, or null when it worked.
 */
class NewItemWizard(
    private val owner: Stage,
    private val lib: CacheLibrary,
    private val models: ModelLoader,
    private val anims: AnimCache?,
    private val textures: TextureLoader?,
    items: List<ItemFull>,
    private val server: File?,
    private val specialNames: () -> Map<String, List<Int>>,
    startFrom: ItemFull?,
    private val create: (Plan) -> String?
) {
    /** What Create writes: the item, a noted version or not, and the server stats (null when there's no server folder). */
    class Plan(val item: ItemFull, val noted: Boolean, val stats: ServerItemStats?)

    private val stage = Stage()
    private val newId = ItemStore.nextFreeId(lib)
    private val hasServer = server != null && ServerItems.definitionsFile(server).isFile

    private var step = 0
    private val titles = listOf("Start from", "Name and options", "Look", "On a player", "In game", "Check and create")

    /** The item being made, and what it was made from (null = blank). */
    private var work = blank()
    private var base: ItemFull? = null
    private var builtFrom: Any? = Unit
    private var stats: ServerItemStats? = null

    private fun blank() = ItemFull(newId).apply {
        name = "New item"; inventoryModel = 2429; zoom2d = 1200; xan2d = 300
        inventoryOptions[4] = "Drop"; groundOptions[2] = "Take"
    }

    // ---------- step 1: start from ----------
    private val fromItem = RadioButton("A copy of an item that's already in the game")
    private val fromBlank = RadioButton("Nothing (a blank item)")
    private val all = FXCollections.observableArrayList(items.filter { !it.isNoted && it.readOk && it.name != "null" })
    private val filtered = FilteredList(all)
    private val list = ListView(filtered)
    private val search = TextField().apply { promptText = "Search by name or id, e.g. Abyssal whip" }
    private val icons = HashMap<Int, Image?>()
    private fun icon(d: ItemFull) = icons.getOrPut(d.id) { try { ItemPreview.icon(d, models, textures, 1) } catch (e: Exception) { null } }

    // ---------- step 2: name and options ----------
    private val fName = TextField()
    private val fExamine = TextField()
    private val fSlot = ComboBox(FXCollections.observableArrayList(ItemFull.SLOTS.values.toList()))
    private val fInv = Array(5) { TextField().apply { prefColumnCount = 8 } }
    private val fTrade = CheckBox("Can be traded")
    private val fStack = CheckBox("Stacks in one inventory space (like coins or arrows)")
    private val fMembers = CheckBox("Members only")
    private val fValue = TextField().apply { prefColumnCount = 10 }

    // ---------- step 3: look ----------
    private val bigIcon = ImageView().apply { fitWidth = 144.0; fitHeight = 128.0; isSmooth = false }
    private val lookView = ModelView3D(420.0, 360.0)
    private val lookMode = ComboBox(FXCollections.observableArrayList("Inventory model", "Worn (male)", "Worn (female)")).apply { selectionModel.select(0) }
    private val colourBox = VBox(4.0)
    private val textureBox = VBox(4.0)
    private val fInvModel = TextField().apply { prefColumnCount = 6 }
    private val fMale = Array(3) { TextField().apply { prefColumnCount = 6 } }
    private val fFemale = Array(3) { TextField().apply { prefColumnCount = 6 } }
    private val zoom = Slider(100.0, 8000.0, 2000.0)
    private val turnX = Slider(0.0, 2047.0, 0.0)
    private val turnY = Slider(0.0, 2047.0, 0.0)
    private val turnZ = Slider(0.0, 2047.0, 0.0)
    private val moveX = Slider(-200.0, 200.0, 0.0)
    private val moveY = Slider(-200.0, 200.0, 0.0)
    private val tint = ColorPicker(Color.web("#b02020"))
    private var filling = false

    // ---------- step 4: on a player ----------
    private val playerView = ModelView3D(520.0, 440.0)
    private val playback = AnimPlayback(playerView)
    private val female = CheckBox("Female")
    private val playInfo = Label("").apply { isWrapText = true }

    // ---------- step 5: in game ----------
    private val form = ServerStatsForm(specialNames) { id -> step = 3; show(); playAnim(id) }
    private val statsInfo = Label("").apply { isWrapText = true }

    // ---------- step 6: check ----------
    private val summary = TextArea().apply { isEditable = false; isWrapText = true; prefRowCount = 22 }
    private val makeNoted = CheckBox("Also make a noted version (so it can be noted at the bank and traded in bulk)")

    // ---------- the window ----------
    private val header = Label().apply { style = "-fx-font-size: 17px; -fx-font-weight: bold;" }
    private val crumbs = HBox(6.0)
    private val body = StackPane()
    private val problem = Label("").apply { textFill = Color.web("#e05050"); isWrapText = true }
    private val back = Button("< Back")
    private val next = Button("Next >").apply { styleClass.add("accent"); isDefaultButton = true }
    private val pages: List<Node>

    init {
        val g = ToggleGroup(); fromItem.toggleGroup = g; fromBlank.toggleGroup = g
        fromItem.isSelected = true
        list.disableProperty().bind(fromBlank.selectedProperty()); search.disableProperty().bind(fromBlank.selectedProperty())
        search.textProperty().addListener { _, _, q ->
            val t = q.trim().toLowerCase()
            filtered.setPredicate { t.isEmpty() || it.id.toString() == t || it.name.toLowerCase().contains(t) }
        }
        list.setCellFactory {
            object : ListCell<ItemFull>() {
                override fun updateItem(item: ItemFull?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else "${item.name}   (item ${item.id})"
                    graphic = if (empty || item == null) null else ImageView(icon(item)).apply { fitWidth = 36.0; fitHeight = 32.0; isPreserveRatio = true }
                }
            }
        }
        list.selectionModel.selectedItemProperty().addListener { _, _, _ -> problem.text = "" }
        fromBlank.selectedProperty().addListener { _, _, _ -> problem.text = "" }
        if (startFrom != null && !startFrom.isNoted) all.indexOfFirst { it.id == startFrom.id }.takeIf { it >= 0 }?.let { list.selectionModel.select(it); list.scrollTo(it) }

        pages = listOf(page1(), page2(), page3(), page4(), page5(), page6())
        back.setOnAction { if (step > 0) { step--; show() } }
        next.setOnAction { if (step < pages.size - 1) goNext() else doCreate() }
        val cancel = Button("Cancel").apply { isCancelButton = true; setOnAction { stage.close() } }
        val bottom = HBox(8.0, problem, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, cancel, back, next).apply { padding = Insets(10.0); alignment = Pos.CENTER_RIGHT }
        HBox.setHgrow(problem, Priority.SOMETIMES)
        val top = VBox(6.0, header, crumbs).apply { padding = Insets(10.0, 12.0, 4.0, 12.0) }
        val root = BorderPane(body, top, null, bottom, null)
        stage.scene = Scene(root, 1060.0, 760.0).also { sc -> owner.scene?.stylesheets?.let { sc.stylesheets.addAll(it) } }
        stage.title = "Make a new item"
        stage.initOwner(owner); stage.initModality(Modality.WINDOW_MODAL)
        stage.setOnHidden { playback.stop() }
        show()
    }

    fun open() = stage.show()

    private fun show() {
        header.text = "Step ${step + 1} of ${titles.size}: ${titles[step]}"
        crumbs.children.setAll(titles.mapIndexed { i, t -> Label("${i + 1}. $t").apply {
            style = if (i == step) "-fx-font-weight: bold; -fx-underline: true;" else if (i < step) "-fx-opacity: 0.85;" else "-fx-opacity: 0.5;" } })
        body.children.setAll(pages[step])
        back.isDisable = step == 0
        next.text = if (step == pages.size - 1) "Create" else "Next >"
        problem.text = ""
        if (step != 3) playback.stop()
        when (step) {
            2 -> refreshLook(true)
            3 -> if (playback.current == null) playAnim(form.anim("stand") ?: 808)
            4 -> form.slot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(work.wearPos1).coerceAtLeast(0))
            5 -> fillSummary()
        }
    }

    private fun goNext() {
        val why = check(step)
        if (why != null) { problem.text = why; return }
        leave(step)
        step++
        show()
    }

    /** Why the step's fields can't be used yet, or null. */
    private fun check(s: Int): String? = when (s) {
        0 -> if (fromItem.isSelected && list.selectionModel.selectedItem == null) "Pick the item to start from, or choose \"Nothing\"." else null
        1 -> when {
            fName.text.isBlank() || fName.text.trim() == "null" -> "Give the item a name."
            fValue.text.trim().toIntOrNull()?.takeIf { it >= 0 } == null -> "Value should be a whole number of coins (0 or more)."
            else -> null
        }
        2 -> lookProblem()
        4 -> if (hasServer) form.problem() else null
        else -> null
    }

    /** Takes what the step's fields say into the item / stats. */
    private fun leave(s: Int) {
        when (s) {
            0 -> startFromChoice()
            1 -> readBasics()
            2 -> readLook()
            4 -> if (hasServer) stats = form.read(stats)
        }
    }

    // ---------- step 1 ----------
    private fun page1(): Node {
        val help = Label("Copying gives the new item the same look, worn models, bonuses, attack speed and animations as the item you pick. " +
                "You can change all of it in the next steps. The original item isn't changed.").apply { isWrapText = true }
        return VBox(10.0, Label("What should the new item start from?"), fromItem, VBox(6.0, search, list).apply { padding = Insets(0.0, 0.0, 0.0, 24.0); VBox.setVgrow(list, Priority.ALWAYS) },
            fromBlank, help, Label("It will be item $newId.")).apply { padding = Insets(12.0); VBox.setVgrow(children[2], Priority.ALWAYS) }
    }

    private fun startFromChoice() {
        val pick = if (fromItem.isSelected) list.selectionModel.selectedItem else null
        val key: Any? = pick?.id
        if (key == builtFrom) return
        builtFrom = key
        base = pick?.let { ItemStore.read(lib, it.id) }
        val b = base
        work = if (b == null) blank() else b.copy(newId).also {
            it.noteLinked = -1; it.placeholderLink = -1; it.placeholderTemplate = -1; it.boughtLink = -1; it.boughtTemplate = -1
        }
        stats = if (!hasServer) null else try {
            val from = if (b == null) ServerItemStats(newId) else ServerItems.read(server!!, b.id)
            ServerItemStats(newId).also { x ->
                x.weight = from.weight; x.slot = if (b == null) -1 else from.slot; x.equipmentType = from.equipmentType; x.bonuses = from.bonuses.clone()
                x.requirements.putAll(from.requirements); x.weapon = from.copy().weapon
                if (b != null) {
                    x.special = ServerItems.readSpecials(server!!)[b.id] ?: specialNames().entries.firstOrNull { b.id in it.value }?.key
                    x.specialEnergy = ServerItems.specialEnergy(lib, b.id)
                    x.examine = from.examine
                }
            }
        } catch (e: Exception) { null }
        fillBasics(); fillLook(); form.fill(stats)
        playback.stop(); playerView.show(null)
        statsInfo.text = when {
            !hasServer -> "The server's item file isn't available (set the server folder in Tools > NPCs or Shops, or use Get from live server in the Items tab). " +
                    "The item will still be made; it just can't be worn or used in combat in game until its stats are added in the Items tab."
            b == null -> "A blank item does nothing in game. Tick Weapon for a weapon, set the slot and bonuses, or go back and start from a similar item."
            ItemStats.osrs(server!!, b.id) != null -> "Copied from ${b.name}, including the OSRS stats the server adds for it. Change what you like."
            else -> "Copied from ${b.name}. Change what you like."
        }
    }

    // ---------- step 2 ----------
    private fun page2(): Node {
        fun row(label: String, vararg n: Node) = HBox(6.0, Label(label).apply { minWidth = 110.0 }, *n).apply { alignment = Pos.CENTER_LEFT }
        fSlot.setOnAction {
            if (filling) return@setOnAction
            val slot = ItemFull.SLOTS.keys.elementAt(fSlot.selectionModel.selectedIndex.coerceAtLeast(0))
            // the usual right-click options for that slot
            val opts = usualOptions(slot)
            for (i in 0 until 5) fInv[i].text = opts[i] ?: ""
        }
        fExamine.isDisable = !hasServer
        return VBox(10.0,
            row("Name", fName.apply { prefColumnCount = 28 }),
            row("Examine text", fExamine.apply { prefColumnCount = 40; promptText = if (hasServer) "What players see when they examine it" else "needs the server folder" }),
            row("Worn on", fSlot, Label("(sets the usual right-click options below)")),
            Label("Right-click options in the inventory (empty = none; when the 5th is empty the game shows Drop there)"),
            HBox(4.0, *fInv),
            row("Value (coins)", fValue, Label("shops and high alchemy use this")),
            fTrade, fStack, fMembers
        ).apply { padding = Insets(12.0) }
    }

    private fun usualOptions(slot: Int): Array<String?> = when (slot) {
        -1 -> arrayOf(null, null, null, null, "Drop")
        3, 5 -> arrayOf(null, "Wield", null, null, "Drop")
        else -> arrayOf(null, "Wear", null, null, "Drop")
    }

    private fun fillBasics() {
        filling = true
        val d = work
        fName.text = if (base != null) d.name else "New item"
        fExamine.text = stats?.examine ?: ""
        fSlot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(d.wearPos1).coerceAtLeast(0))
        for (i in 0 until 5) fInv[i].text = d.inventoryOptions[i] ?: ""
        fValue.text = d.cost.toString()
        fTrade.isSelected = d.tradeable; fStack.isSelected = d.stackable; fMembers.isSelected = d.members
        filling = false
    }

    private fun readBasics() {
        val d = work
        d.name = fName.text.trim()
        d.wearPos1 = ItemFull.SLOTS.keys.elementAt(fSlot.selectionModel.selectedIndex.coerceAtLeast(0))
        for (i in 0 until 5) d.inventoryOptions[i] = fInv[i].text.trim().ifEmpty { null }
        d.cost = fValue.text.trim().toInt()
        d.tradeable = fTrade.isSelected; d.stackable = fStack.isSelected; d.members = fMembers.isSelected
        stats?.let { it.examine = fExamine.text.trim(); it.slot = d.wearPos1 }
    }

    // ---------- step 3 ----------
    private fun page3(): Node {
        fun row(label: String, vararg n: Node) = HBox(6.0, Label(label).apply { minWidth = 110.0 }, *n).apply { alignment = Pos.CENTER_LEFT }
        for (s in listOf(zoom, turnX, turnY, turnZ, moveX, moveY)) { s.prefWidth = 260.0; s.valueProperty().addListener { _, _, _ -> if (!filling) { readIcon(); refreshLook(false) } } }
        for (f in listOf(fInvModel) + fMale + fFemale) {
            f.setOnAction { modelsChanged() }
            f.focusedProperty().addListener { _, _, on -> if (!on) modelsChanged() }
        }
        lookMode.setOnAction { refreshLook(true) }
        tint.setOnAction { tintAll() }
        val right = VBox(8.0,
            Label("Colours").apply { styleClass.add("section") },
            Label("Each colour of the item's models; pick what it becomes. The picture updates as you go.").apply { isWrapText = true },
            HBox(6.0, Label("Make it all one colour:"), tint, Button("Undo colours").apply { setOnAction { work.recolorFrom = ShortArray(0); work.recolorTo = ShortArray(0); fillColours(); refreshLook(false) } }).apply { alignment = Pos.CENTER_LEFT },
            colourBox,
            Label("Textures").apply { styleClass.add("section") },
            textureBox,
            Separator(),
            Label("Models (game model ids)").apply { styleClass.add("section") },
            row("In the inventory", fInvModel),
            row("Worn, male", *fMale),
            row("Worn, female", *fFemale, Button("Same as male").apply { setOnAction { for (i in 0 until 3) fFemale[i].text = fMale[i].text; modelsChanged() } }),
            Separator(),
            Label("Inventory picture").apply { styleClass.add("section") },
            row("Zoom (less = bigger)", zoom),
            row("Tilt", turnX), row("Turn", turnY), row("Roll", turnZ),
            row("Move sideways", moveX), row("Move up/down", moveY)
        ).apply { padding = Insets(10.0) }
        val iconBox = StackPane(bigIcon).apply { style = "-fx-background-color: #3e3529; -fx-border-color: #5a4d3b;"; prefWidth = 160.0; prefHeight = 140.0; maxWidth = 160.0; maxHeight = 140.0 }
        val left = VBox(8.0, HBox(10.0, iconBox, VBox(6.0, Label("Inventory picture (as in game, 4x)"), Label("3D view (drag to turn)"), lookMode)), lookView).apply { padding = Insets(10.0); VBox.setVgrow(lookView, Priority.ALWAYS) }
        return SplitPane(left, ScrollPane(right).apply { isFitToWidth = true }).apply { setDividerPositions(0.5) }
    }

    private fun idOrNone(t: TextField) = t.text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: -1
    private fun show(v: Int) = if (v < 0) "" else v.toString()

    private fun fillLook() {
        filling = true
        val d = work
        fInvModel.text = d.inventoryModel.toString()
        fMale[0].text = show(d.maleModel0); fMale[1].text = show(d.maleModel1); fMale[2].text = show(d.maleModel2)
        fFemale[0].text = show(d.femaleModel0); fFemale[1].text = show(d.femaleModel1); fFemale[2].text = show(d.femaleModel2)
        zoom.value = d.zoom2d.toDouble().coerceIn(zoom.min, zoom.max); turnX.value = d.xan2d.toDouble(); turnY.value = d.yan2d.toDouble(); turnZ.value = d.zan2d.toDouble()
        moveX.value = d.offsetX2d.toDouble().coerceIn(moveX.min, moveX.max); moveY.value = d.offsetY2d.toDouble().coerceIn(moveY.min, moveY.max)
        filling = false
        fillColours(); fillTextures()
    }

    private fun readIcon() {
        val d = work
        d.zoom2d = zoom.value.toInt().coerceAtLeast(1); d.xan2d = turnX.value.toInt() and 2047; d.yan2d = turnY.value.toInt() and 2047; d.zan2d = turnZ.value.toInt() and 2047
        d.offsetX2d = moveX.value.toInt(); d.offsetY2d = moveY.value.toInt()
    }

    private fun modelOk(id: Int) = id >= 0 && id <= 65535 && try { models.get(id) != null } catch (e: Exception) { false }

    private fun lookProblem(): String? {
        val inv = fInvModel.text.trim().toIntOrNull() ?: return "The inventory model should be a model id."
        if (!modelOk(inv)) return "There's no model $inv in the cache."
        for ((label, fs) in listOf("male" to fMale, "female" to fFemale)) for (f in fs) {
            if (f.text.isBlank()) continue
            val id = f.text.trim().toIntOrNull() ?: return "The worn $label models should be model ids (or empty)."
            if (!modelOk(id)) return "There's no model $id in the cache (worn, $label)."
        }
        if (work.wearPos1 != -1 && fMale.all { it.text.isBlank() } && fFemale.all { it.text.isBlank() })
            return "It's worn (step 2) but has no worn models, so players would look like they have nothing on. Add a worn model, or go back and make it not worn."
        return null
    }

    private fun modelsChanged() {
        if (filling) return
        if (lookProblem()?.startsWith("It's worn") == false) return
        readLook(); fillColours(); fillTextures(); refreshLook(true)
    }

    private fun readLook() {
        val d = work
        fInvModel.text.trim().toIntOrNull()?.takeIf { modelOk(it) }?.let { d.inventoryModel = it }
        d.maleModel0 = idOrNone(fMale[0]); d.maleModel1 = idOrNone(fMale[1]); d.maleModel2 = idOrNone(fMale[2])
        d.femaleModel0 = idOrNone(fFemale[0]); d.femaleModel1 = idOrNone(fFemale[1]); d.femaleModel2 = idOrNone(fFemale[2])
        readIcon()
        // textures: "from -> to" rows; an empty or same id means no change
        val from = ArrayList<Short>(); val to = ArrayList<Short>()
        for (n in textureBox.children) {
            val r = n as? HBox ?: continue
            val f = r.userData as? Int ?: continue
            val t = (r.children.last() as TextField).text.trim().toIntOrNull() ?: continue
            if (t != f) { from.add(f.toShort()); to.add(t.toShort()) }
        }
        d.retextureFrom = from.toShortArray(); d.retextureTo = to.toShortArray()
    }

    private fun swatch(hsl: Int) = Rectangle(20.0, 20.0, rgbOf(hsl)).apply { stroke = Color.BLACK }
    private fun rgbOf(hsl: Int) = ModelCodec.palette[hsl and 0xFFFF].let { Color.rgb(it shr 16 and 255, it shr 8 and 255, it and 255) }
    private fun hslOf(c: Color) = ModelCodec.nearestHsl(((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt())

    /** The colours the item's models use (and ones already recoloured), most used first. */
    private fun colours(): List<Int> {
        val d = work
        val counts = LinkedHashMap<Int, Int>()
        for (id in listOf(d.inventoryModel, d.maleModel0, d.maleModel1, d.maleModel2, d.femaleModel0, d.femaleModel1, d.femaleModel2)) {
            if (id < 0) continue
            val md = try { models.get(id) } catch (e: Exception) { null } ?: continue
            val fc = md.faceColors ?: continue
            for (i in fc.indices) { val c = fc[i].toInt() and 0xFFFF; if (c != 65535 && (md.faceTextures?.get(i)?.toInt() ?: -1) == -1) counts[c] = (counts[c] ?: 0) + 1 }
        }
        val out = counts.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
        for (f in d.recolorFrom) { val c = f.toInt() and 0xFFFF; if (c !in out) out.add(c) }
        return out
    }

    private fun recolouredTo(from: Int): Int? = work.recolorFrom.indexOfFirst { (it.toInt() and 0xFFFF) == from }.takeIf { it >= 0 }?.let { work.recolorTo[it].toInt() and 0xFFFF }

    private fun setRecolour(from: Int, to: Int?) {
        val d = work
        val keep = d.recolorFrom.indices.filter { (d.recolorFrom[it].toInt() and 0xFFFF) != from }
        val f = keep.map { d.recolorFrom[it] }.toMutableList(); val t = keep.map { d.recolorTo[it] }.toMutableList()
        if (to != null && to != from && f.size < 255) { f.add(from.toShort()); t.add(to.toShort()) }
        d.recolorFrom = f.toShortArray(); d.recolorTo = t.toShortArray()
    }

    private fun fillColours() {
        colourBox.children.clear()
        val cs = colours()
        if (cs.isEmpty()) { colourBox.children.add(Label("These models have no colours to change (they're all textures).")); return }
        for (c in cs.take(40)) {
            val picker = ColorPicker(rgbOf(recolouredTo(c) ?: c)).apply { prefWidth = 110.0 }
            picker.setOnAction { setRecolour(c, hslOf(picker.value)); refreshLook(false) }
            colourBox.children.add(HBox(8.0, swatch(c), Label("becomes"), picker,
                Button("Keep").apply { setOnAction { setRecolour(c, null); picker.value = rgbOf(c); refreshLook(false) } }).apply { alignment = Pos.CENTER_LEFT })
        }
        if (cs.size > 40) colourBox.children.add(Label("(${cs.size - 40} more small colours; \"Make it all one colour\" changes them too.)"))
    }

    /**
     * Every colour takes the picked colour's hue and strength, and its lightness moves halfway to the picked one:
     * the item reads as that colour, and its light and dark parts still differ, so the shading stays.
     */
    private fun tintAll() {
        val target = hslOf(tint.value)
        val hue = target shr 10 and 63; val sat = target shr 7 and 7; val light = target and 127
        for (c in colours()) setRecolour(c, (hue shl 10) or (sat shl 7) or (((c and 127) + light) / 2))
        fillColours(); refreshLook(false)
    }

    private fun fillTextures() {
        textureBox.children.clear()
        val used = LinkedHashSet<Int>()
        for (id in work.modelIds()) {
            val md = try { models.get(id) } catch (e: Exception) { null } ?: continue
            md.faceTextures?.forEach { t -> if (t.toInt() >= 0) used.add(t.toInt()) }
        }
        if (used.isEmpty()) { textureBox.children.add(Label("These models use no textures.")); return }
        for (t in used) {
            val now = work.retextureFrom.indexOfFirst { it.toInt() == t }.takeIf { it >= 0 }?.let { work.retextureTo[it].toInt() } ?: t
            textureBox.children.add(HBox(8.0, Label("Texture $t becomes texture"), TextField(now.toString()).apply {
                prefColumnCount = 5
                setOnAction { readLook(); refreshLook(true) }
                focusedProperty().addListener { _, _, on -> if (!on) { readLook(); refreshLook(true) } }
            }).apply { alignment = Pos.CENTER_LEFT; userData = t })
        }
    }

    private var lastLook = ""

    private fun refreshLook(force: Boolean) {
        val d = work
        bigIcon.image = try { ItemPreview.icon(d, models, textures, 4) } catch (e: Exception) { null }
        val mode = lookMode.selectionModel.selectedIndex
        val key = "$mode ${d.modelIds()} ${d.recolorFrom.joinToString()} ${d.recolorTo.joinToString()} ${d.retextureTo.joinToString()}"
        if (!force && key == lastLook) return
        lastLook = key
        val md = try {
            when (mode) { 1 -> ItemPreview.wornModel(d, false, models); 2 -> ItemPreview.wornModel(d, true, models); else -> ItemPreview.inventoryModel(d, models) }
        } catch (e: Exception) { null }
        lookView.show(md)
    }

    // ---------- step 4 ----------
    private fun page4(): Node {
        fun anim(label: String, key: String, default: Int) = Button(label).apply { setOnAction { playAnim(form.anim(key) ?: default) } }
        female.setOnAction { playAnim(lastPlayed ?: 808) }
        val buttons = FlowPane(6.0, 6.0,
            anim("Stand", "stand", 808), anim("Walk", "walk", 819), anim("Run", "run", 824), anim("Attack", "accurate", 422), anim("Block", "block", 424),
            Button("Special attack").apply { setOnAction {
                val name = form.specialName ?: return@setOnAction run { playInfo.text = "It has no special attack (step 5)." }
                val a = server?.let { ServerItems.specialAnimations(it)[name] }
                    ?: return@setOnAction run { playInfo.text = "The $name special picks its animation in the server code, so there's no single one to show." }
                playAnim(a)
            } },
            female,
            Button("Pause").apply { setOnAction { playback.playing = !playback.playing; text = if (playback.playing) "Pause" else "Play" } },
            Button("<").apply { setOnAction { playback.playing = false; playback.show(playback.frame - 1) } },
            Button(">").apply { setOnAction { playback.playing = false; playback.show(playback.frame + 1) } })
        val note = Label("The animations are the ones it will use in game (set in step 5). Drag to turn the player, scroll to zoom.").apply { isWrapText = true }
        return VBox(8.0, buttons, note, playInfo, playerView).apply { padding = Insets(10.0); VBox.setVgrow(playerView, Priority.ALWAYS) }
    }

    private var lastPlayed: Int? = null

    private fun playAnim(seq: Int?) {
        val a = anims ?: run { playInfo.text = "Animations aren't loaded."; return }
        lastPlayed = seq
        val r = ItemOnPlayer.play(work, female.isSelected, seq, lib, models, a, playerView, playback)
        playInfo.text = (if (work.wearPos1 == -1) "It isn't worn (step 2), so the player has nothing on. " else "") + r.message
    }

    // ---------- step 5 ----------
    private fun page5(): Node {
        val copy = Button("Copy stats from another item...").apply { setOnAction {
            val srv = server ?: return@setOnAction
            val other = TextInputDialog("").apply { title = "Copy stats"; headerText = null; contentText = "Item id to copy bonuses, speed, animations and special from"; initOwner(stage) }
                .showAndWait().orElse(null)?.trim()?.toIntOrNull() ?: return@setOnAction
            val st = stats ?: return@setOnAction
            val src = try { ServerItems.read(srv, other) } catch (e: Exception) { problem.text = "Couldn't read item $other: ${e.message}"; return@setOnAction }
            st.weight = src.weight; st.equipmentType = src.equipmentType; st.bonuses = src.bonuses.clone()
            st.requirements.clear(); st.requirements.putAll(src.requirements); st.weapon = src.copy().weapon
            st.special = ServerItems.readSpecials(srv)[other] ?: specialNames().entries.firstOrNull { other in it.value }?.key
            st.specialEnergy = ServerItems.specialEnergy(lib, other)
            form.fill(st)
            form.slot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(work.wearPos1).coerceAtLeast(0))
            statsInfo.text = "Copied the stats of item $other."
        } }
        copy.disableProperty().set(!hasServer)
        val n = form.node(VBox(6.0, Label("What it does in game: bonuses, how fast it attacks, its animations and special attack. These go in the server's item file.").apply { isWrapText = true },
            statsInfo), copy)
        return ScrollPane(n.apply { padding = Insets(12.0) }).apply { isFitToWidth = true }
    }

    // ---------- step 6 ----------
    private fun page6(): Node {
        makeNoted.selectedProperty().addListener { _, _, _ -> if (step == 5) fillSummary() }
        return VBox(8.0, Label("Check it over. Nothing has been written yet; Create saves it."), summary, makeNoted).apply { padding = Insets(12.0); VBox.setVgrow(summary, Priority.ALWAYS) }
    }

    private var notedDefaultSet = false

    private fun fillSummary() {
        val d = work
        if (!notedDefaultSet) { makeNoted.isSelected = d.tradeable && !d.stackable; notedDefaultSet = true }
        makeNoted.isDisable = d.stackable
        if (d.stackable) makeNoted.isSelected = false
        val st = stats
        val b = base
        val sb = StringBuilder()
        sb.appendLine("New item $newId: ${d.name}" + (if (b != null) "   (made from ${b.name}, item ${b.id})" else "   (made from nothing)"))
        if (makeNoted.isSelected) sb.appendLine("Noted version: item ${newId + 1}")
        sb.appendLine()
        sb.appendLine("Worn on: ${ItemFull.SLOTS[d.wearPos1]}")
        sb.appendLine("Right-click options: ${(d.inventoryOptions.take(4) + (d.inventoryOptions[4] ?: "Drop")).filterNotNull().joinToString(", ")}")
        sb.appendLine("Value: ${d.cost} coins.  ${if (d.tradeable) "Can be traded" else "Can't be traded"}.  ${if (d.stackable) "Stacks" else "Doesn't stack"}.  ${if (d.members) "Members only" else "Free to play"}.")
        if (st?.examine?.isNotBlank() == true) sb.appendLine("Examine: ${st.examine}")
        sb.appendLine()
        sb.appendLine("Inventory model ${d.inventoryModel}" + (if (d.maleModel0 >= 0 || d.femaleModel0 >= 0) ", worn models male ${listOf(d.maleModel0, d.maleModel1, d.maleModel2).filter { it >= 0 }.joinToString("/")}, female ${listOf(d.femaleModel0, d.femaleModel1, d.femaleModel2).filter { it >= 0 }.joinToString("/")}" else ""))
        val changed = d.recolorFrom.size - (b?.recolorFrom?.size ?: 0)
        sb.appendLine("Colours changed: ${d.recolorFrom.size}" + (if (b != null && b.recolorFrom.isNotEmpty()) " (${b.name} already had ${b.recolorFrom.size}; ${if (changed >= 0) "+$changed" else "$changed"})" else ""))
        if (d.retextureFrom.isNotEmpty()) sb.appendLine("Textures changed: ${d.retextureFrom.indices.joinToString { "${d.retextureFrom[it]} -> ${d.retextureTo[it]}" }}")
        sb.appendLine()
        if (st == null) sb.appendLine("In game: no server stats (the server's item file isn't available here). It can't be worn in game until they're added in the Items tab.")
        else {
            val w = st.weapon
            if (w != null) {
                sb.appendLine("Weapon: ${ServerItems.WEAPON_CATEGORIES.getOrNull(w.category) ?: w.category}, attacks every ${w.attackSpeed} ticks (${String.format("%.1f", w.attackSpeed * 0.6)} s)" + (if (w.twoHanded) ", two-handed" else ""))
                sb.appendLine("Animations: stand ${w.stand}, walk ${w.walk}, run ${w.run}, attack ${w.accurate}, block ${w.block}")
            }
            val bonuses = st.bonuses.indices.filter { st.bonuses[it] != 0 }.joinToString(", ") { "${ServerItems.BONUS_NAMES[it]} ${if (st.bonuses[it] > 0) "+" else ""}${st.bonuses[it]}" }
            sb.appendLine("Bonuses: ${bonuses.ifEmpty { "none" }}")
            if (st.requirements.isNotEmpty()) sb.appendLine("Needs: ${st.requirements.entries.joinToString(", ") { e -> "${ServerItems.SKILLS.getOrElse(e.key) { "skill ${e.key}" }} ${e.value}" }}")
            sb.appendLine("Special attack: ${st.special?.let { "$it, ${st.specialEnergy ?: "?"}% energy" } ?: "none"}")
            sb.appendLine("Weight: ${st.weight} kg")
        }
        sb.appendLine()
        sb.appendLine("Create writes the item${if (makeNoted.isSelected) " and its noted version" else ""} into the exported cache" +
                (if (st != null) " and its stats into ${ServerItems.definitionsFile(server!!).path}" else "") + ". Deploy puts it on the live server; Undo last change takes the cache part back.")
        summary.text = sb.toString()
    }

    private fun doCreate() {
        next.isDisable = true
        val err = try { create(Plan(work, makeNoted.isSelected && !work.stackable, stats)) } catch (e: Exception) { e.message ?: e.toString() }
        next.isDisable = false
        if (err != null) { problem.text = "Couldn't create it: $err"; return }
        stage.close()
    }
}
