package controllers

import cache.loaders.ModelLoader
import cache.loaders.TextureLoader
import com.displee.cache.CacheLibrary
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import utils.*
import models.Configuration as EditorConfiguration
import java.io.File

/**
 * The Items tab: make new items and change existing ones (name, options, price, inventory picture, worn models,
 * equip slot, recolours, params, noted version), with the inventory picture drawn the way the game draws it.
 */
class ItemsTab(
    private val stage: Stage,
    private val lib: () -> CacheLibrary?,
    private val models: () -> ModelLoader?,
    private val anims: () -> AnimCache?,
    private val modelsTabId: () -> Int,
    private val say: (String) -> Unit,
    private val reopen: () -> Unit
) {
    private var textures: TextureLoader? = null
    private val all = FXCollections.observableArrayList<ItemFull>()
    private val filtered = FilteredList(all)
    private val list = ListView(filtered).apply { prefWidth = 230.0 }
    private val search = TextField().apply { promptText = "name or id" }
    private val icons = HashMap<Int, Image?>()
    private val bigIcon = ImageView().apply { fitWidth = 144.0; fitHeight = 128.0; isSmooth = false }
    private val view = ModelView3D(520.0, 420.0)
    private val viewMode = ComboBox(FXCollections.observableArrayList(
        "Inventory model", "Worn (male)", "Worn (female)", "On a player (male)", "On a player (female)")).apply { selectionModel.select(0) }
    private val info = Label("Pick an item on the left, or make a new one.").apply { isWrapText = true }
    private val playback = AnimPlayback(view)
    private val playInfo = Label("")

    /** The item as it is in the cache, and the copy being edited. */
    private var original: ItemFull? = null
    private var work: ItemFull? = null
    private var filling = false

    // ---------- fields ----------
    private fun num(w: Int = 6) = TextField().apply { prefColumnCount = w }
    private val fName = TextField()
    private val fCost = num(9); private val fWeight = num(6); private val fCategory = num(5); private val fTeam = num(4)
    private val fStack = CheckBox("Stackable"); private val fMembers = CheckBox("Members"); private val fTrade = CheckBox("Tradeable")
    private val fInv = Array(5) { TextField().apply { prefColumnCount = 8 } }
    private val fGround = Array(5) { TextField().apply { prefColumnCount = 8 } }
    private val fModel = num(); private val fZoom = num(); private val fXan = num(); private val fYan = num(); private val fZan = num()
    private val fOffX = num(); private val fOffY = num()
    private val fRx = num(4); private val fRy = num(4); private val fRz = num(4); private val fAmb = num(4); private val fCon = num(4)
    private val fSlot = ComboBox(FXCollections.observableArrayList(ItemFull.SLOTS.entries.map { "${it.value}" }))
    private val fHide1 = ComboBox(FXCollections.observableArrayList(ItemFull.HIDES.entries.map { it.value }))
    private val fHide2 = ComboBox(FXCollections.observableArrayList(ItemFull.HIDES.entries.map { it.value }))
    private val fM0 = num(5); private val fM1 = num(5); private val fM2 = num(5); private val fMOff = num(3)
    private val fF0 = num(5); private val fF1 = num(5); private val fF2 = num(5); private val fFOff = num(3)
    private val fMH = num(); private val fMH2 = num(); private val fFH = num(); private val fFH2 = num()
    private val recolBox = VBox(3.0)
    private val params = FXCollections.observableArrayList<Pair<Int, Any>>()
    private val paramList = ListView(params).apply { prefHeight = 130.0 }
    private val pKey = num(6); private val pVal = TextField().apply { prefColumnCount = 10 }
    private val noteInfo = Label("")

    // ---------- server stats (data/items/ItemDefinitions.json, special-attacks.json, enum 906) ----------
    private val configuration = EditorConfiguration()
    private var stats: ServerItemStats? = null
    private val statsInfo = Label("").apply { isWrapText = true }
    private val statsFields = ServerStatsForm({ specialNames() }) { playOnPlayer(it) }

    /** "Try a model on the player", under the 3D view. */
    private val tryModel = TryModelPanel({ stage }, lib, models, anims, view, playback, { work },
        { key -> statsFields.anim(key) }, { specialAnimation() }, modelsTabId,
        { id, target, place -> useTriedModel(id, target, place) }, { lastViewKey = "playing" }, { say(it) })

    /** The special attack's animation, or why there's no single one. */
    private fun specialAnimation(): Pair<Int?, String> {
        val name = statsFields.specialName ?: return null to "This item has no special attack."
        val anim = server()?.let { ServerItems.specialAnimations(it)[name] }
            ?: return null to "The $name special sets its animation in code (it changes with the situation), so there's no single one to play."
        return anim to ""
    }

    /** Puts a model tried on the player into the item's fields (saved with the item; Reset takes it back). */
    private fun useTriedModel(id: Int, target: TryModelPanel.Target, place: ItemOnPlayer.Place): String {
        work ?: return "Pick an item first."
        val s = id.toString()
        when (target) {
            TryModelPanel.Target.WORN -> { fM0.text = s; fM1.text = ""; fM2.text = ""; fF0.text = s; fF1.text = ""; fF2.text = "" }
            TryModelPanel.Target.MALE -> { fM0.text = s; fM1.text = ""; fM2.text = "" }
            TryModelPanel.Target.FEMALE -> { fF0.text = s; fF1.text = ""; fF2.text = "" }
            TryModelPanel.Target.INVENTORY -> fModel.text = s
        }
        var moved = ""
        val slot = place.slot
        if (target != TryModelPanel.Target.INVENTORY && slot != null) {
            fSlot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(slot).coerceAtLeast(0))
            fHide1.selectionModel.select(ItemFull.HIDES.keys.indexOf(place.hides).coerceAtLeast(0))
            fHide2.selectionModel.select(ItemFull.HIDES.keys.indexOf(place.hides2).coerceAtLeast(0))
            moved = " It's now worn ${place.label.lowercase()}."
        }
        apply(); fillRecolours()
        val pending = if (PendingModels.isPending(id)) " The model goes into the cache when you Save." else ""
        return "Model $id is now ${target.label.removePrefix("As ").lowercase()}.$moved Save to keep it; Reset takes it back.$pending"
    }

    private fun int(t: TextField, def: Int) = t.text.trim().toIntOrNull() ?: def
    private fun idOrNone(t: TextField) = t.text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: -1
    private fun showId(v: Int) = if (v < 0) "" else v.toString()

    private fun load(id: Int) {
        val l = lib() ?: return
        val d = try { ItemStore.read(l, id) } catch (e: Exception) { null }
        original = d
        if (d == null) { work = null; info.text = "Item $id isn't in the cache."; return }
        if (!d.readOk) { work = null; info.text = "Item $id (${d.name}) can't be edited: ${d.problem}."; bigIcon.image = null; view.show(null); return }
        work = d.copy(d.id)
        fill()
        loadStats(id)
        info.text = "Item $id: ${d.name}" + (if (d.isNoted) " - this is the noted version of item ${d.noteLinked}; change the real item instead." else "")
    }

    /** Item -> fields. */
    private fun fill() {
        val d = work ?: return
        filling = true
        fName.text = d.name; fCost.text = d.cost.toString(); fWeight.text = d.weight?.let { String.format("%.3f", it / 1000.0) } ?: ""
        fCategory.text = showId(d.category); fTeam.text = if (d.team == 0) "" else d.team.toString()
        fStack.isSelected = d.stackable; fMembers.isSelected = d.members; fTrade.isSelected = d.tradeable
        for (i in 0 until 5) { fInv[i].text = d.inventoryOptions[i] ?: ""; fGround[i].text = d.groundOptions[i] ?: "" }
        fModel.text = d.inventoryModel.toString(); fZoom.text = d.zoom2d.toString(); fXan.text = d.xan2d.toString(); fYan.text = d.yan2d.toString(); fZan.text = d.zan2d.toString()
        fOffX.text = d.offsetX2d.toString(); fOffY.text = d.offsetY2d.toString()
        fRx.text = d.resizeX.toString(); fRy.text = d.resizeY.toString(); fRz.text = d.resizeZ.toString(); fAmb.text = d.ambient.toString(); fCon.text = d.contrast.toString()
        fSlot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(d.wearPos1).coerceAtLeast(0))
        fHide1.selectionModel.select(ItemFull.HIDES.keys.indexOf(d.wearPos2).coerceAtLeast(0))
        fHide2.selectionModel.select(ItemFull.HIDES.keys.indexOf(d.wearPos3).coerceAtLeast(0))
        fM0.text = showId(d.maleModel0); fM1.text = showId(d.maleModel1); fM2.text = showId(d.maleModel2); fMOff.text = d.maleOffset.toString()
        fF0.text = showId(d.femaleModel0); fF1.text = showId(d.femaleModel1); fF2.text = showId(d.femaleModel2); fFOff.text = d.femaleOffset.toString()
        fMH.text = showId(d.maleHead); fMH2.text = showId(d.maleHead2); fFH.text = showId(d.femaleHead); fFH2.text = showId(d.femaleHead2)
        params.setAll(d.params.entries.map { it.key to it.value })
        noteInfo.text = when {
            d.isNoted -> "This is a noted item (of ${d.noteLinked})."
            d.noteLinked >= 0 -> "Noted version: item ${d.noteLinked}."
            else -> "No noted version."
        }
        filling = false
        fillRecolours()
        refresh(true)
    }

    /** Fields -> item. */
    private fun apply() {
        if (filling) return
        val d = work ?: return
        d.name = fName.text.ifBlank { "null" }
        d.cost = int(fCost, d.cost)
        d.weight = fWeight.text.trim().toDoubleOrNull()?.let { Math.round(it * 1000).toInt().coerceIn(-32768, 32767) }
        d.category = idOrNone(fCategory); d.team = int(fTeam, 0).coerceIn(0, 255)
        d.stackable = fStack.isSelected; d.members = fMembers.isSelected; d.tradeable = fTrade.isSelected
        for (i in 0 until 5) { d.inventoryOptions[i] = fInv[i].text.trim().ifEmpty { null }; d.groundOptions[i] = fGround[i].text.trim().ifEmpty { null } }
        d.inventoryModel = int(fModel, d.inventoryModel).coerceIn(0, 65535)
        d.zoom2d = int(fZoom, d.zoom2d).coerceIn(1, 65535); d.xan2d = int(fXan, d.xan2d) and 2047; d.yan2d = int(fYan, d.yan2d) and 2047; d.zan2d = int(fZan, d.zan2d) and 2047
        d.offsetX2d = int(fOffX, d.offsetX2d).coerceIn(-32768, 32767); d.offsetY2d = int(fOffY, d.offsetY2d).coerceIn(-32768, 32767)
        d.resizeX = int(fRx, 128).coerceIn(1, 65535); d.resizeY = int(fRy, 128).coerceIn(1, 65535); d.resizeZ = int(fRz, 128).coerceIn(1, 65535)
        d.ambient = int(fAmb, 0) and 255; d.contrast = int(fCon, 0) and 255
        d.wearPos1 = ItemFull.SLOTS.keys.elementAtOrElse(fSlot.selectionModel.selectedIndex) { -1 }
        d.wearPos2 = ItemFull.HIDES.keys.elementAtOrElse(fHide1.selectionModel.selectedIndex) { -1 }
        d.wearPos3 = ItemFull.HIDES.keys.elementAtOrElse(fHide2.selectionModel.selectedIndex) { -1 }
        d.maleModel0 = idOrNone(fM0); d.maleModel1 = idOrNone(fM1); d.maleModel2 = idOrNone(fM2); d.maleOffset = int(fMOff, 0) and 255
        d.femaleModel0 = idOrNone(fF0); d.femaleModel1 = idOrNone(fF1); d.femaleModel2 = idOrNone(fF2); d.femaleOffset = int(fFOff, 0) and 255
        d.maleHead = idOrNone(fMH); d.maleHead2 = idOrNone(fMH2); d.femaleHead = idOrNone(fFH); d.femaleHead2 = idOrNone(fFH2)
        d.params.clear(); for ((k, v) in params) d.params[k] = v
        refresh(false)
    }

    private var lastViewKey = ""

    /** Redraw the inventory picture, and the 3D view when what it shows changed. */
    private fun refresh(force: Boolean) {
        val d = work ?: return
        val m = models() ?: return
        bigIcon.image = try { ItemPreview.icon(d, m, textures, 4) } catch (e: Exception) { null }
        val mode = viewMode.selectionModel.selectedIndex
        val key = "$mode ${d.inventoryModel} ${d.maleModel0} ${d.maleModel1} ${d.maleModel2} ${d.femaleModel0} ${d.femaleModel1} ${d.femaleModel2} " +
                "${d.maleOffset} ${d.femaleOffset} ${d.wearPos1} ${d.wearPos2} ${d.wearPos3} ${d.resizeX} ${d.resizeY} ${d.resizeZ} " +
                d.recolorFrom.joinToString() + d.recolorTo.joinToString()
        if (!force && key == lastViewKey) return
        lastViewKey = key
        playback.stop(); playInfo.text = ""
        val md = try {
            when (mode) {
                1 -> ItemPreview.wornModel(d, false, m)
                2 -> ItemPreview.wornModel(d, true, m)
                3 -> lib()?.let { ItemPreview.onPlayer(d, false, it, m) }
                4 -> lib()?.let { ItemPreview.onPlayer(d, true, it, m) }
                else -> ItemPreview.inventoryModel(d, m)
            }
        } catch (e: Exception) { null }
        if (!view.show(md) && mode in 1..2) say("This item has no ${if (mode == 1) "male" else "female"} worn models.")
    }

    // ---------- recolours ----------
    private fun swatch(hsl: Int) = Rectangle(18.0, 18.0, ModelCodec.palette[hsl and 0xFFFF].let { Color.rgb(it shr 16 and 255, it shr 8 and 255, it and 255) }).apply { stroke = Color.BLACK }

    /** Colours used by the item's models (inventory and worn), most used first. */
    private fun modelColours(): List<Int> {
        val d = work ?: return emptyList()
        val m = models() ?: return emptyList()
        val counts = HashMap<Int, Int>()
        for (id in listOf(d.inventoryModel, d.maleModel0, d.maleModel1, d.maleModel2, d.femaleModel0, d.femaleModel1, d.femaleModel2)) {
            if (id < 0) continue
            val md = PendingModels.model(m, id) ?: continue
            val fc = md.faceColors ?: continue
            for (i in fc.indices) { val c = fc[i].toInt() and 0xFFFF; if (c != 65535 && (md.faceTextures?.get(i)?.toInt() ?: -1) == -1) counts[c] = (counts[c] ?: 0) + 1 }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun fillRecolours() {
        val d = work ?: return
        recolBox.children.clear()
        for (k in d.recolorFrom.indices) {
            val from = d.recolorFrom[k].toInt() and 0xFFFF
            val to = d.recolorTo[k].toInt() and 0xFFFF
            val rgb = ModelCodec.palette[to]
            val picker = ColorPicker(Color.rgb(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255)).apply { prefWidth = 90.0 }
            picker.setOnAction {
                val c = picker.value
                d.recolorTo[k] = ModelCodec.nearestHsl(((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()).toShort()
                refresh(false)
            }
            val remove = Button("x").apply { setOnAction {
                val keep = d.recolorFrom.indices.filter { it != k }
                d.recolorFrom = ShortArray(keep.size) { d.recolorFrom[keep[it]] }; d.recolorTo = ShortArray(keep.size) { d.recolorTo[keep[it]] }
                fillRecolours(); refresh(false)
            } }
            recolBox.children.add(HBox(6.0, swatch(from), Label("$from  ->"), picker, remove).apply { alignment = Pos.CENTER_LEFT })
        }
        val used = d.recolorFrom.map { it.toInt() and 0xFFFF }.toSet()
        val free = modelColours().filter { it !in used }
        if (free.isNotEmpty()) {
            val pick = ComboBox(FXCollections.observableArrayList(free)).apply {
                promptText = "add a colour of the model"
                setCellFactory { object : ListCell<Int>() { override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty); text = if (empty || item == null) null else "$item"; graphic = if (empty || item == null) null else swatch(item) } } }
            }
            pick.setOnAction {
                val c = pick.value ?: return@setOnAction
                if (d.recolorFrom.size >= 255) return@setOnAction say("An item can have at most 255 recolours.")
                d.recolorFrom = d.recolorFrom + c.toShort(); d.recolorTo = d.recolorTo + c.toShort()
                fillRecolours(); refresh(false)
                say("Added colour $c. Pick what it becomes with its colour picker.")
            }
            recolBox.children.add(pick)
        }
    }

    // ---------- params ----------
    private val skills = listOf("Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching", "Fishing",
        "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecraft", "Hunter", "Construction")
    private fun paramName(k: Int, v: Any): String = when (k) {
        434, 435, 191, 613 -> "skill needed" + ((v as? Int)?.let { skills.getOrNull(it) }?.let { " ($it)" } ?: "")
        436, 437, 192, 614 -> "level needed"
        1564 -> "equip slot"
        in 451..458 -> "extra option text"
        else -> ""
    }

    // ---------- server stats ----------
    private fun server(): File? = ServerSource.dir(configuration)

    private fun loadStats(id: Int) {
        val s = server()
        if (s == null) { stats = null; statsInfo.text = "Pick the server folder (Tools > NPCs or Shops) to edit bonuses, attack speed and specials."; fillStats(); return }
        if (!ServerItems.definitionsFile(s).isFile) { stats = null; statsInfo.text = "${ServerItems.definitionsFile(s).path} isn't there. Get it from the live server, or copy the server's data folder next to its code."; fillStats(); return }
        val st = try { ServerItems.read(s, id) } catch (e: Exception) { stats = null; statsInfo.text = "Couldn't read ${ServerItems.definitionsFile(s).path}: ${e.message}"; fillStats(); return }
        st.special = try { ServerItems.readSpecials(s)[id] } catch (e: Exception) { null }
            ?: specialNames().entries.firstOrNull { id in it.value }?.key
        st.specialEnergy = lib()?.let { ServerItems.specialEnergy(it, id) }
        stats = st
        statsInfo.text = when {
            ItemStats.osrs(s, id) != null -> "Item $id is an OSRS item: the server also uses its OSRS wiki stats (the higher bonus wins; attack speed and requirements come from OSRS), so lowering a stat here may not change it in game."
            ServerItems.has(s, id) -> "From ${ServerItems.definitionsFile(s).path}."
            else -> "The server has no stats for item $id yet, so it can't be worn in game. Copy them from a similar item, change them, and Save stats."
        }
        fillStats()
    }

    private var specialCache: Pair<File, LinkedHashMap<String, List<Int>>>? = null
    private fun specialNames(): LinkedHashMap<String, List<Int>> {
        val s = server() ?: return LinkedHashMap()
        specialCache?.let { if (it.first == s) return it.second }
        val n = try { ServerItems.specialNames(s) } catch (e: Exception) { LinkedHashMap<String, List<Int>>() }
        specialCache = s to n
        return n
    }

    private fun fillStats() = statsFields.fill(stats)

    /** Fields -> stats. */
    private fun readStats(): ServerItemStats? = statsFields.read(stats)

    /**
     * Saves the stats into the server's data files (and the special energy into the exported cache). [newUndoStep] false
     * when the caller just made the undo step (a new item), so one Undo takes both back.
     */
    fun saveStats(st: ServerItemStats, newUndoStep: Boolean = true): String {
        val s = server() ?: return "Pick the server folder first (Tools > NPCs or Shops)."
        if (!ServerItems.definitionsFile(s).isFile) return "${ServerItems.definitionsFile(s).path} isn't there; get it from the live server first."
        ServerItems.write(s, st)
        val specials = ServerItems.readSpecials(s)
        val built = specialNames().entries.firstOrNull { st.id in it.value }?.key
        val wanted = st.special
        // only weapons the server code doesn't already give a special go in the file
        if (wanted == null || wanted == built) specials.remove(st.id) else specials[st.id] = wanted
        if (specials != ServerItems.readSpecials(s)) ServerItems.writeSpecials(s, specials)
        val oldEnergy = lib()?.let { ServerItems.specialEnergy(it, st.id) }
        val energy = if (wanted == null) null else st.specialEnergy
        var cache = false
        if (energy != oldEnergy) { ServerItems.setSpecialEnergy(st.id, energy, backup = newUndoStep); cache = true; reopen() }
        if (wanted == null && built != null) return "Saved. Item ${st.id} keeps the ${built} special: the server code gives it that one."
        return "Saved the server stats of item ${st.id}" + (if (cache) " and its special energy (in the exported cache)" else "") +
                ". Send to live server puts them live" + (if (cache) " (tick 'with the cache' for the energy)" else "") + "."
    }

    private fun saveStatsClicked() {
        statsFields.problem()?.let { return say(it) }
        val st = readStats() ?: return say("There are no server stats to save here (see the note above them).")
        try { say(saveStats(st)); stats = st } catch (e: Exception) { say("Couldn't save the stats: ${e.message}") }
    }

    private fun copyStatsFrom() {
        val other = TextInputDialog("").apply { title = "Copy stats"; headerText = null; contentText = "Item id to copy bonuses, speed, animations and special from"; initOwner(stage) }
            .showAndWait().orElse(null)?.trim()?.toIntOrNull() ?: return
        val s0 = server() ?: return say("Pick the server folder first.")
        val cur = stats ?: return
        // what the other item really has in game (OSRS items included)
        val src = ServerItems.read(s0, other)
        cur.weight = src.weight; cur.slot = src.slot; cur.equipmentType = src.equipmentType; cur.bonuses = src.bonuses.clone()
        cur.requirements.clear(); cur.requirements.putAll(src.requirements); cur.weapon = src.copy().weapon
        val s = server()
        cur.special = (if (s != null) ServerItems.readSpecials(s)[other] else null) ?: specialNames().entries.firstOrNull { other in it.value }?.key
        cur.specialEnergy = lib()?.let { ServerItems.specialEnergy(it, other) }
        fillStats()
        say("Copied the stats of item $other. Save stats to keep them.")
    }

    private fun fetchStats() {
        val s = server() ?: return say("Pick the server folder first (Tools > NPCs or Shops).")
        say("Getting ItemDefinitions.json from the live server...")
        Thread {
            val d = Deployer { m -> javafx.application.Platform.runLater { say(m) } }
            val ok = d.fetchServerData("items/ItemDefinitions.json", ServerItems.definitionsFile(s))
            d.fetchServerData("items/special-attacks.json", ServerItems.specialsFile(s))
            javafx.application.Platform.runLater {
                work?.let { loadStats(it.id) }
                say(if (ok) "Got the live ItemDefinitions.json." else "Couldn't get it (check Tools > Deploy > Test connection).")
            }
        }.start()
    }

    private fun sendStats() {
        val s = server() ?: return say("Pick the server folder first.")
        val pending = ServerItems.pending() + ItemStats.pending().sorted().map { "stats of item $it" }
        val withCache = Alert(Alert.AlertType.CONFIRMATION, "Send ${if (pending.isEmpty()) "nothing from the server files" else pending.joinToString(", ")} to the live server and restart it?\n\n" +
                "Yes: also send the exported cache (needed for new items, models and special energy).\nNo: only the server files.",
            ButtonType.YES, ButtonType.NO, ButtonType.CANCEL).apply { initOwner(stage); headerText = null }.showAndWait().orElse(ButtonType.CANCEL)
        if (withCache == ButtonType.CANCEL) return
        say("Sending...")
        Thread {
            val ok = Deployer { m -> javafx.application.Platform.runLater { say(m) } }.deployItemStats(s, withCache == ButtonType.YES)
            javafx.application.Platform.runLater { say(if (ok) "Item stats are live." else "Not sent; see the messages above (Tools > Deploy shows the full log).") }
        }.start()
    }

    /** Shows the item on a plain player (the view's male/female choice) and plays [seq] on both. */
    fun playOnPlayer(seq: Int?): String {
        val d = work ?: return "Pick an item first.".also { say(it) }
        val l = lib() ?: return ""; val m = models() ?: return ""; val a = anims() ?: return ""
        val female = viewMode.selectionModel.selectedIndex == 2 || viewMode.selectionModel.selectedIndex == 4
        val r = ItemOnPlayer.play(d, female, seq, l, m, a, view, playback)
        if (r.model != null) { lastViewKey = "playing"; posed = r.model }
        playInfo.text = r.message
        say(r.message)
        return r.message
    }

    /** The player model being animated, for saving a pose. */
    private var posed: cache.definitions.ModelDefinition? = null

    /** The pose on screen as a model (a copy with the animated vertex positions). */
    private fun currentPose(): cache.definitions.ModelDefinition? {
        val md = posed ?: return null
        val c = playback.current ?: return null
        val (x, y, z) = c.pose(playback.frame)
        return cache.definitions.ModelDefinition.merge(listOf(md)).also { cp ->
            for (v in 0 until cp.vertexCount) { cp.vertexPositionsX[v] = x[v]; cp.vertexPositionsY[v] = y[v]; cp.vertexPositionsZ[v] = z[v] }
            cp.priority = md.priority
        }
    }

    private fun exportPose() {
        val md = currentPose() ?: return say("Play an animation first (the pose on screen is what gets saved).")
        val f = javafx.stage.FileChooser().apply { initialFileName = "pose_${work?.id ?: 0}_${playback.frame}.obj"; extensionFilters.add(javafx.stage.FileChooser.ExtensionFilter("OBJ", "*.obj")) }.showSaveDialog(stage) ?: return
        f.writeText(ModelCodec.toObj(md, "pose frame ${playback.frame}"))
        say("Saved the pose (frame ${playback.frame + 1}) as ${f.name}.")
    }

    private fun savePoseAsModel() {
        val md = currentPose() ?: return say("Play an animation first (the pose on screen is what gets saved).")
        try {
            val enc = ModelCodec.encodeOld(md)
            val id = CacheEdits.putModel(enc.data, null)
            reopen()
            say("Saved the pose as new model $id in the exported cache. " + enc.warnings.joinToString(" "))
        } catch (e: Exception) { say("Couldn't save the pose: ${e.message}") }
    }

    private fun statsForm(): VBox = statsFields.node(
        VBox(7.0, Label("Server stats (the server's data files, not the cache)").apply { styleClass.add("section") }, statsInfo),
        Button("Save stats").apply { styleClass.add("accent"); setOnAction { saveStatsClicked() } },
        Button("Copy from item...").apply { setOnAction { copyStatsFrom() } },
        Button("Get from live server").apply { setOnAction { fetchStats() } },
        Button("Send to live server...").apply { setOnAction { sendStats() } })

    // ---------- saving ----------
    private fun save(asNew: Boolean) {
        val d = work ?: return say("Pick an item first.")
        val l = lib() ?: return
        apply()
        try {
            val out = ArrayList<ItemFull>()
            val item = if (asNew) d.copy(ItemStore.nextFreeId(l)).also {
                it.noteLinked = -1; it.placeholderLink = -1; it.placeholderTemplate = -1; it.boughtLink = -1; it.boughtTemplate = -1
            } else d
            out.add(item)
            ItemStore.save(out, "item-${item.id}")
            afterWrite(item.id)
            say((if (asNew) "Saved as new item ${item.id}" else "Saved item ${item.id}") + " (${item.name}) in the exported cache. Deploy sends it to the server." +
                    if (asNew && d.noteLinked >= 0) " The new item has no noted version yet; use Make noted version." else "")
        } catch (e: Exception) { say("Couldn't save: ${e.message}") }
    }

    private fun makeNoted() {
        val d = work ?: return say("Pick an item first.")
        val l = lib() ?: return
        if (d.isNoted) return say("This is already a noted item.")
        if (d.stackable) return say("Stackable items don't need a noted version.")
        apply()
        try {
            val nid = if (d.noteLinked >= 0 && l.data(2, 10, d.noteLinked) != null) return say("It already has a noted version (item ${d.noteLinked}).")
                else ItemStore.nextFreeId(l)
            d.noteLinked = nid
            ItemStore.save(listOf(d, ItemStore.notedFor(d, nid, ItemStore.noteTemplate(l))), "item-${d.id}-noted")
            afterWrite(d.id)
            say("Made item $nid the noted version of ${d.id} and saved both.")
        } catch (e: Exception) { say("Couldn't make it: ${e.message}") }
    }

    private fun newBlank() {
        val l = lib() ?: return
        val d = ItemFull(ItemStore.nextFreeId(l)).apply {
            name = "New item"; inventoryModel = modelsTabId().takeIf { it >= 0 } ?: 2429; zoom2d = 1200; xan2d = 300; yan2d = 0
            inventoryOptions[4] = "Drop"; groundOptions[2] = "Take"
        }
        original = null; work = d
        fill()
        loadStats(d.id)
        info.text = "New item ${d.id} (not saved yet). Set it up, then Save."
    }

    /** Opens the step-by-step new item maker, starting from the item open now. */
    private fun openMaker() {
        val l = lib() ?: return say("Open a cache first.")
        val m = models() ?: return say("Open a cache first.")
        try {
            NewItemWizard(stage, l, m, anims(), textures, all.toList(), server(), { specialNames() }, original, modelsTabId) { plan -> createItem(plan) }.open()
        } catch (e: Exception) { say("Couldn't open the item maker: ${e.message}") }
    }

    /** Writes what the item maker made: the item (and noted version) into the exported cache, its stats into the server's files. */
    private fun createItem(plan: NewItemWizard.Plan): String? {
        val l = lib() ?: return "the cache isn't open"
        val item = plan.item
        if (l.data(2, 10, item.id) != null) return "item ${item.id} was used by something else in the meantime. Cancel and start again."
        val out = arrayListOf(item)
        if (plan.noted) {
            val nid = (item.id + 1).takeIf { l.data(2, 10, it) == null } ?: maxOf(ItemStore.nextFreeId(l), item.id + 1)
            item.noteLinked = nid
            out.add(ItemStore.notedFor(item, nid, ItemStore.noteTemplate(l)))
        } else item.noteLinked = -1
        ItemStore.save(out, "new-item-${item.id}")
        var msg = "Made item ${item.id} (${item.name})" + (if (plan.noted) " and its noted version ${item.noteLinked}" else "") + " in the exported cache."
        val st = plan.stats
        if (st != null) {
            st.slot = item.wearPos1
            msg += " " + try { saveStats(st, newUndoStep = false) } catch (e: Exception) { "Its server stats weren't saved: ${e.message}" }
        }
        afterWrite(item.id)
        say(msg)
        return null
    }

    private fun copyFromOther() {
        val dir = javafx.stage.DirectoryChooser().apply { title = "Folder with the other cache (main_file_cache.dat2)" }.showDialog(stage) ?: return
        val dat = dir.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.equals("main_file_cache.dat2", true) }
            ?: return say("No 211-style cache (main_file_cache.dat2) in ${dir.path}.")
        val ids = TextInputDialog("").apply { title = "Copy items"; headerText = null; contentText = "Item ids to copy (e.g. 22325, 27275-27280)"; initOwner(stage) }.showAndWait().orElse(null) ?: return
        val list = ids.split(',', ' ', ';').filter { it.isNotBlank() }.flatMap { p ->
            val r = p.trim().split('-')
            if (r.size == 2) { val a = r[0].toIntOrNull(); val b = r[1].toIntOrNull(); if (a != null && b != null && b >= a && b - a < 2000) (a..b).toList() else emptyList() }
            else listOfNotNull(p.trim().toIntOrNull())
        }
        if (list.isEmpty()) return say("No ids given.")
        val keep = Alert(Alert.AlertType.CONFIRMATION, "Keep the same item ids where they're free here?\n\nYes: item 22325 stays 22325 if that id is free (so the server's item names match).\nNo: always add them after the last item.",
            ButtonType.YES, ButtonType.NO).apply { initOwner(stage); headerText = null }.showAndWait().orElse(ButtonType.NO) == ButtonType.YES
        try {
            val other = CacheLibrary(dat.parentFile.path)
            val r = try { ItemCopy.fromOtherCache(other, list, keep) } finally { other.close() }
            afterWrite(r.newIds.firstOrNull())
            say(r.message + " Undo last change puts the cache back.")
        } catch (e: Exception) { say("Couldn't copy: ${e.message}") }
    }

    /** The item open now, with its noted version and models, as a zip to import into another cache. */
    private fun exportBundle() {
        val d = original ?: return say("Pick a saved item first.")
        val l = lib() ?: return
        val safe = d.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').toLowerCase()
        val f = javafx.stage.FileChooser().apply { initialFileName = "item_${d.id}_$safe.zip"; extensionFilters.add(javafx.stage.FileChooser.ExtensionFilter("Item bundle", "*.zip")) }.showSaveDialog(stage) ?: return
        say(ItemCopy.exportBundle(l, listOf(d.id), f) + " Saved ${f.name}.")
    }

    private fun importBundle() {
        val f = javafx.stage.FileChooser().apply { title = "Item bundle (.zip)"; extensionFilters.add(javafx.stage.FileChooser.ExtensionFilter("Item bundle", "*.zip")) }.showOpenDialog(stage) ?: return
        val keep = Alert(Alert.AlertType.CONFIRMATION, "Keep the item ids from the bundle where they're free here?\n\nNo: add them after the last item.",
            ButtonType.YES, ButtonType.NO).apply { initOwner(stage); headerText = null }.showAndWait().orElse(ButtonType.NO) == ButtonType.YES
        try {
            val r = ItemCopy.importBundle(f, keep)
            afterWrite(r.newIds.firstOrNull())
            say(r.message + " Undo last change puts the cache back.")
        } catch (e: Exception) { say("Couldn't import: ${e.message}") }
    }

    /**
     * Search the newest Old School cache and copy items from it into this cache: models, noted version and server
     * stats included (see [LiveOsrs]).
     */
    private fun importFromLiveOsrs() {
        val win = Stage().apply { initOwner(this@ItemsTab.stage); title = "Import from live OSRS" }
        val status = Label("Loading the newest Old School item list...").apply { isWrapText = true }
        val query = TextField().apply { promptText = "item name or id (e.g. avernic treads, soulreaper)"; isDisable = true }
        val results = ListView<LiveOsrs.Entry>().apply { prefHeight = 360.0; selectionModel.selectionMode = SelectionMode.MULTIPLE }
        val keepIds = CheckBox("Keep the OSRS item ids where they're free here").apply { isSelected = true }
        val go = Button("Import").apply { isDisable = true; isDefaultButton = true }
        var cat: LiveOsrs.Catalog? = null
        fun mark(e: LiveOsrs.Entry): String {
            val here = try { lib()?.let { ItemStore.read(it, e.id) } } catch (x: Exception) { null }
            return if (here != null && here.name == e.name) "$e  - already here" else e.toString()
        }
        results.setCellFactory { object : ListCell<LiveOsrs.Entry>() {
            override fun updateItem(item: LiveOsrs.Entry?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) null else mark(item) }
        } }
        query.textProperty().addListener { _, _, q ->
            val c = cat ?: return@addListener
            val found = c.search(q)
            results.items.setAll(found.take(200))
            status.text = if (q.isBlank()) "Build ${c.build}: ${c.entries.size} items. Type a name." else "${found.size} match(es)" + (if (found.size > 200) ", showing 200" else "") + ". Pick one or more, then Import."
        }
        results.selectionModel.selectedItems.addListener(javafx.collections.ListChangeListener { go.isDisable = results.selectionModel.selectedItems.isEmpty() })
        go.setOnAction {
            val c = cat ?: return@setOnAction
            val picked = results.selectionModel.selectedItems.toList()
            if (picked.isEmpty()) return@setOnAction
            go.isDisable = true
            status.text = "Getting ${picked.size} item(s) and their models..."
            val server = server()
            val keep = keepIds.isSelected
            Thread {
                val src = LiveOsrs.Source(c, server)
                // models are downloaded here (not on the window's thread); textures this cache doesn't have are named
                val missingTex = sortedSetOf<Int>()
                val problems = ArrayList<String>()
                try {
                    val l = lib()
                    val haveTex = l?.index(9)?.archive(0)?.fileIds()?.toSet() ?: emptySet()
                    for (e in picked) {
                        val it = try { src.item(e.id) } catch (x: Exception) { problems.add("${e.name}: ${x.message}"); null } ?: continue
                        for (m in listOf(it.inventoryModel, it.maleModel0, it.maleModel1, it.maleModel2, it.femaleModel0, it.femaleModel1, it.femaleModel2,
                                it.maleHead, it.maleHead2, it.femaleHead, it.femaleHead2).filter { m -> m >= 0 }) {
                            val b = src.model(m) ?: continue
                            if (l != null) try {
                                ModelLoader(l).decode(b, m).faceTextures?.forEach { t -> if (t >= 0 && t.toInt() !in haveTex) missingTex.add(t.toInt()) }
                            } catch (x: Exception) {}
                        }
                    }
                } catch (x: Exception) { problems.add(x.message ?: x.toString()) }
                javafx.application.Platform.runLater {
                    try {
                        val ok = picked.filter { e -> problems.none { it.startsWith(e.name + ":") } }
                        if (ok.isEmpty()) { status.text = "Couldn't import: " + problems.joinToString("; "); go.isDisable = false; return@runLater }
                        val r = ItemCopy.copy(src, ok.map { it.id }, keep)
                        afterWrite(r.newIds.firstOrNull())
                        val warn = (if (missingTex.isNotEmpty()) " Its models use texture(s) ${missingTex.joinToString(", ")}, which this cache doesn't have: those parts may look wrong in game." else "") +
                                (if (problems.isNotEmpty()) " Not imported: " + problems.joinToString("; ") + "." else "")
                        status.text = r.message + warn
                        say(r.message + warn + " Undo last change puts the cache back.")
                        results.refresh()
                    } catch (x: Exception) { status.text = "Couldn't import: ${x.message}" }
                    go.isDisable = false
                }
            }.apply { isDaemon = true; name = "live-osrs-import" }.start()
        }
        win.scene = javafx.scene.Scene(VBox(8.0, query, results, keepIds, HBox(8.0, go), status).apply { padding = Insets(12.0); prefWidth = 520.0 })
        stage.scene?.stylesheets?.let { win.scene.stylesheets.setAll(it) }
        win.show()
        Thread {
            try {
                val c = LiveOsrs.catalog { m -> javafx.application.Platform.runLater { status.text = m } }
                javafx.application.Platform.runLater {
                    cat = c
                    query.isDisable = false; query.requestFocus()
                    status.text = "Build ${c.build}: ${c.entries.size} items. Type a name."
                }
            } catch (x: Exception) {
                javafx.application.Platform.runLater { status.text = "Couldn't get the OSRS item list: ${x.message}" }
            }
        }.apply { isDaemon = true; name = "live-osrs-list" }.start()
    }

    private fun afterWrite(select: Int?) {
        reopen()
        textures = try { lib()?.let { TextureLoader(it) } } catch (e: Exception) { null }
        icons.clear()
        refreshList()
        if (select != null) {
            load(select)
            filtered.indexOfFirst { it.id == select }.takeIf { it >= 0 }?.let { list.selectionModel.clearAndSelect(it); list.scrollTo(it) }
        }
    }

    fun refreshList() {
        val l = lib() ?: return
        if (textures == null) textures = try { TextureLoader(l) } catch (e: Exception) { null }
        val items = ArrayList<ItemFull>()
        for (id in try { ItemStore.ids(l) } catch (e: Exception) { emptyList<Int>() }) {
            val d = try { ItemStore.read(l, id) } catch (e: Exception) { null } ?: continue
            items.add(d)
        }
        all.setAll(items)
    }

    /** After an undo: the cache changed under us. */
    fun reloaded() { icons.clear(); refreshList(); work?.let { w -> if (original != null) load(w.id) } }

    private fun icon(d: ItemFull): Image? = icons.getOrPut(d.id) {
        val m = models() ?: return@getOrPut null
        val shown = if (d.isNoted) all.firstOrNull { it.id == d.noteLinked } ?: d else d
        try { ItemPreview.icon(shown, m, textures, 1) } catch (e: Exception) { null }
    }

    private fun row(label: String, vararg nodes: javafx.scene.Node) = HBox(5.0, Label(label).apply { minWidth = 92.0 }, *nodes).apply { alignment = Pos.CENTER_LEFT }

    fun tab(): Tab {
        search.textProperty().addListener { _, _, q ->
            val t = q.trim().toLowerCase()
            filtered.setPredicate { t.isEmpty() || it.id.toString().startsWith(t) || it.name.toLowerCase().contains(t) }
        }
        list.setCellFactory {
            object : ListCell<ItemFull>() {
                override fun updateItem(item: ItemFull?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.toString()
                    graphic = if (empty || item == null) null else ImageView(icon(item)).apply { fitWidth = 30.0; fitHeight = 27.0; isPreserveRatio = true }
                }
            }
        }
        list.selectionModel.selectedItemProperty().addListener { _, _, d -> if (d != null) load(d.id) }
        viewMode.setOnAction { refresh(true) }
        // every field updates the item and the picture as you type
        val texts = listOf(fName, fCost, fWeight, fCategory, fTeam, fModel, fZoom, fXan, fYan, fZan, fOffX, fOffY, fRx, fRy, fRz, fAmb, fCon,
            fM0, fM1, fM2, fMOff, fF0, fF1, fF2, fFOff, fMH, fMH2, fFH, fFH2) + fInv + fGround
        for (t in texts) t.textProperty().addListener { _, _, _ -> apply() }
        for (c in listOf(fStack, fMembers, fTrade)) c.selectedProperty().addListener { _, _, _ -> apply() }
        for (c in listOf(fSlot, fHide1, fHide2)) c.setOnAction { apply() }
        fModel.focusedProperty().addListener { _, _, f -> if (!f) fillRecolours() }

        paramList.setCellFactory { object : ListCell<Pair<Int, Any>>() { override fun updateItem(item: Pair<Int, Any>?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else "${item.first} = ${if (item.second is String) "\"${item.second}\"" else item.second}   ${paramName(item.first, item.second)}"
        } } }
        paramList.selectionModel.selectedItemProperty().addListener { _, _, p -> if (p != null) { pKey.text = p.first.toString(); pVal.text = p.second.toString() } }

        fun turnButtons(field: TextField, label: String) = HBox(2.0,
            Button("-").apply { setOnAction { field.text = ((field.text.toIntOrNull() ?: 0) - 64 and 2047).toString() } },
            Button("+").apply { setOnAction { field.text = ((field.text.toIntOrNull() ?: 0) + 64 and 2047).toString() } }).also { it.id = label }

        val form = VBox(7.0,
            info,
            Label("General").apply { styleClass.add("section") },
            row("Name", fName),
            row("Price", fCost, Label("Weight kg"), fWeight),
            HBox(10.0, fStack, fMembers, fTrade),
            row("Category", fCategory, Label("Team (capes)"), fTeam),
            Separator(),
            Label("Inventory options (empty = none; the game puts Drop or Examine on its own)").apply { isWrapText = true },
            HBox(4.0, *fInv),
            Label("Ground options (empty = the default; 3rd is Take)"),
            HBox(4.0, *fGround),
            Separator(),
            Label("Inventory picture"),
            row("Model", fModel, Button("Use the model open in Models").apply { setOnAction {
                val id = modelsTabId(); if (id >= 0) { fModel.text = id.toString(); fillRecolours() } else say("Open a model in the Models tab first.") } }),
            row("Zoom", fZoom, Button("closer").apply { setOnAction { fZoom.text = ((fZoom.text.toIntOrNull() ?: 2000) * 9 / 10).coerceAtLeast(1).toString() } },
                Button("further").apply { setOnAction { fZoom.text = ((fZoom.text.toIntOrNull() ?: 2000) * 11 / 10).toString() } }),
            row("Turn X / Y / Z", fXan, turnButtons(fXan, "x"), fYan, turnButtons(fYan, "y"), fZan, turnButtons(fZan, "z")),
            row("Move X / Y", fOffX, fOffY),
            row("Size % x128", fRx, fRy, fRz),
            row("Light / shade", fAmb, fCon),
            Separator(),
            Label("Worn"),
            row("Equip slot", fSlot),
            row("Also hides", fHide1, fHide2),
            row("Male models", fM0, fM1, fM2, Label("raise"), fMOff),
            row("Female models", fF0, fF1, fF2, Label("raise"), fFOff),
            row("Heads (chat)", Label("M"), fMH, fMH2, Label("F"), fFH, fFH2),
            Button("Female = male").apply { setOnAction { fF0.text = fM0.text; fF1.text = fM1.text; fF2.text = fM2.text; fFOff.text = fMOff.text; fFH.text = fMH.text; fFH2.text = fMH2.text } },
            Separator(),
            statsForm(),
            Separator(),
            Label("Recolours (a colour of the model -> a new colour)"),
            recolBox,
            Separator(),
            Label("Params (requirements, extra option texts...)"),
            paramList,
            HBox(4.0, Label("key"), pKey, Label("value"), pVal,
                Button("Set").apply { setOnAction {
                    val k = pKey.text.trim().toIntOrNull() ?: return@setOnAction say("The key is a number.")
                    val v: Any = pVal.text.trim().toIntOrNull() ?: pVal.text
                    val i = params.indexOfFirst { it.first == k }
                    if (i >= 0) params[i] = k to v else params.add(k to v)
                    apply()
                } },
                Button("Remove").apply { setOnAction { val k = pKey.text.trim().toIntOrNull(); params.removeIf { it.first == k }; apply() } }),
            Label("Skill needed: key 434 = skill (0 Attack, 1 Defence, 2 Strength, 4 Ranged, 6 Magic...), 436 = level; 435 / 437 for a second skill.").apply { isWrapText = true },
            Separator(),
            HBox(8.0, noteInfo, Button("Make noted version").apply { setOnAction { makeNoted() } }).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, Button("Save").apply { styleClass.add("accent"); setOnAction { save(false) } },
                Button("Save as new item").apply { setOnAction { save(true) } },
                Button("Reset").apply { setOnAction { val o = original; if (o != null) load(o.id) else if (work != null) newBlank() } })
        ).apply { padding = Insets(8.0) }
        val right = ScrollPane(form).apply { isFitToWidth = true; prefWidth = 520.0; minWidth = 480.0 }
        val left = VBox(6.0,
            Button("Make a new item...").apply { maxWidth = Double.MAX_VALUE; styleClass.add("accent"); style = "-fx-font-size: 14px; -fx-padding: 8;"
                tooltip = Tooltip("Step by step: start from an item, name it, change its look, see it on a player, set its stats, then create it.")
                setOnAction { openMaker() } },
            search, list,
            Button("Blank item (no guide)").apply { maxWidth = Double.MAX_VALUE; setOnAction { newBlank() } },
            Button("Copy from another cache...").apply { maxWidth = Double.MAX_VALUE; setOnAction { copyFromOther() } },
            HBox(4.0, Button("Export bundle...").apply { maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS); setOnAction { exportBundle() } },
                Button("Import bundle...").apply { maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS); setOnAction { importBundle() } }),
            Button("Import from live OSRS...").apply { maxWidth = Double.MAX_VALUE; setOnAction { importFromLiveOsrs() } }
        ).apply { padding = Insets(8.0); VBox.setVgrow(list, Priority.ALWAYS) }
        val iconBox = StackPane(bigIcon).apply { style = "-fx-background-color: #3e3529; -fx-border-color: #5a4d3b;"; prefWidth = 160.0; prefHeight = 140.0; maxWidth = 160.0; maxHeight = 140.0 }
        val center = VBox(8.0, HBox(12.0, iconBox, VBox(6.0, Label("Inventory picture (as in game, 4x)"), Label("3D view"), viewMode)),
            javafx.scene.layout.FlowPane(6.0, 6.0,
                Button("Play stand").apply { setOnAction { playOnPlayer(statsFields.anim("stand")) } },
                Button("Play attack").apply { setOnAction { playOnPlayer(statsFields.anim("accurate")) } },
                Button("Play special").apply { setOnAction {
                    val (anim, why) = specialAnimation()
                    if (anim == null) say(why) else playOnPlayer(anim)
                } },
                Button("Stop").apply { setOnAction { playback.stop(); refresh(true) } },
                Button("Pause").apply { setOnAction { playback.playing = !playback.playing; text = if (playback.playing) "Pause" else "Resume" } },
                Button("<").apply { setOnAction { playback.playing = false; playback.show(playback.frame - 1) } },
                Button(">").apply { setOnAction { playback.playing = false; playback.show(playback.frame + 1) } },
                MenuButton("Save pose").apply { items.addAll(
                    MenuItem("As OBJ...").apply { setOnAction { exportPose() } },
                    MenuItem("As a new model").apply { setOnAction { savePoseAsModel() } }) }).apply {
                    // a FlowPane otherwise claims a minimum of one button per row, which pushed the try-on panel off the window
                    minHeight = Region.USE_PREF_SIZE },
            playInfo.apply { isWrapText = true }, tryModel.under(view)).apply {
            padding = Insets(8.0); VBox.setVgrow(children.last(), Priority.ALWAYS)
        }
        return Tab("Items", BorderPane(center, null, right, null, left)).apply { isClosable = false }
    }
}
