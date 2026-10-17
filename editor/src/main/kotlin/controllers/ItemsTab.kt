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
import java.io.File

/**
 * The Items tab: make new items and change existing ones (name, options, price, inventory picture, worn models,
 * equip slot, recolours, params, noted version), with the inventory picture drawn the way the game draws it.
 */
class ItemsTab(
    private val stage: Stage,
    private val lib: () -> CacheLibrary?,
    private val models: () -> ModelLoader?,
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

    /** The item as it is in the cache, and the copy being edited. */
    private var original: ItemFull? = null
    private var work: ItemFull? = null
    private var filling = false

    // ---------- server stats (data/items/ItemDefinitions.json on the server) ----------
    private var serverDir: File? = null
    private var stats: com.google.gson.JsonObject? = null
    private val sInfo = Label("").apply { isWrapText = true }
    private val sSlot = ComboBox(FXCollections.observableArrayList(ItemFull.SLOTS.entries.map { it.value }))
    private val sTwoHanded = CheckBox("Two-handed")
    private val sWeight = TextField().apply { prefColumnCount = 6 }
    private val sBonus = Array(14) { TextField().apply { prefColumnCount = 4 } }
    private val sSpeed = TextField().apply { prefColumnCount = 3 }
    private val sVarbit = TextField().apply { prefColumnCount = 4 }
    private val sRange = TextField().apply { prefColumnCount = 3 }
    private val sLongRange = TextField().apply { prefColumnCount = 3 }
    private val sAnims = ItemStats.ANIMS.keys.associateWith { TextField().apply { prefColumnCount = 5 } }
    private val sReq = TextField().apply { promptText = "e.g. Attack 75, Strength 80" }

    private fun server(): File? = serverDir?.takeIf { it.isDirectory } ?: ServerSource.guess()?.also { serverDir = it }

    /** The current item's server stats -> fields. */
    private fun fillStats() {
        val id = work?.id ?: return
        val srv = server()
        // what the server really uses for it (OSRS items also get the wiki stats merged in)
        stats = try { srv?.let { ItemStats.effective(it, id) } } catch (e: Exception) { sInfo.text = "Can't read the server's item stats: ${e.message}"; null }
        val e = stats
        fillStatsFields(e ?: com.google.gson.JsonObject())
        sInfo.text = when {
            srv == null -> "Pick the server folder to see what this item does in game (bonuses, attack speed, animations)."
            srv != null && ItemStats.osrs(srv, id) != null -> "Item $id is an OSRS item: the server also uses its OSRS stats (the higher bonus wins, attack speed and requirements from OSRS), so lowering a stat here may not change it in game."
            e == null -> "The server has no stats for item $id, so it can't be worn or wielded in game. Copy them from a similar item, change them, and Save stats."
            else -> "What item $id does in game (the server's data/items/ItemDefinitions.json)."
        }
    }

    private fun fillStatsFields(e: com.google.gson.JsonObject) {
        sSlot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(ItemStats.int(e, "slot", -1)).coerceAtLeast(0))
        val wd = e.getAsJsonObject("equipmentDefinition")?.getAsJsonObject("weaponDefinition")
        sTwoHanded.isSelected = ItemStats.bool(wd, "twoHanded")
        sWeight.text = if (e.has("weight")) ItemStats.weight(e).toString() else ""
        val b = ItemStats.bonuses(e); for (i in 0 until 14) sBonus[i].text = b[i].toString()
        sSpeed.text = wd?.takeIf { it.has("attackSpeed") }?.let { ItemStats.ticks(ItemStats.int(it, "attackSpeed")).toString() } ?: ""
        sVarbit.text = wd?.let { ItemStats.int(it, "interfaceVarbit").toString() } ?: ""
        sRange.text = wd?.let { ItemStats.int(it, "normalAttackDistance").toString() } ?: ""
        sLongRange.text = wd?.let { ItemStats.int(it, "longAttackDistance").toString() } ?: ""
        for ((k, f) in sAnims) f.text = wd?.get(k)?.asInt?.toString() ?: ""
        sReq.text = ItemStats.requirements(e).entries.joinToString(", ") { (k, v) -> "${ItemStats.SKILLS.getOrElse(k) { k.toString() }} $v" }
    }

    private val reqPart = Regex("""^([A-Za-z]+|\d+)\s*:?\s*(\d+)$""")

    /** Fields -> a stats entry for the current item. */
    private fun statsFromFields(): com.google.gson.JsonObject {
        val id = work?.id ?: error("pick an item first")
        val e = stats?.deepCopy() ?: com.google.gson.JsonObject().apply { addProperty("id", id); addProperty("equipmentType", "DEFAULT") }
        e.addProperty("id", id)
        val slot = ItemFull.SLOTS.keys.toList().getOrElse(sSlot.selectionModel.selectedIndex) { -1 }
        e.addProperty("slot", slot.coerceAtLeast(0))
        ItemStats.setWeight(e, sWeight.text.trim().toDoubleOrNull() ?: 0.0)
        ItemStats.setBonuses(e, IntArray(14) { sBonus[it].text.trim().toIntOrNull() ?: error("${ItemStats.BONUS_NAMES[it]} isn't a number") })
        if (slot == 3 || e.getAsJsonObject("equipmentDefinition")?.has("weaponDefinition") == true) {
            val w = ItemStats.weapon(e)
            w.addProperty("twoHanded", sTwoHanded.isSelected)
            sSpeed.text.trim().toIntOrNull()?.let { w.addProperty("attackSpeed", ItemStats.stored(it)) }
            sVarbit.text.trim().toIntOrNull()?.let { w.addProperty("interfaceVarbit", it) }
            sRange.text.trim().toIntOrNull()?.let { w.addProperty("normalAttackDistance", it) }
            sLongRange.text.trim().toIntOrNull()?.let { w.addProperty("longAttackDistance", it) }
            for ((k, f) in sAnims) f.text.trim().toIntOrNull()?.let { w.addProperty(k, it) }
        }
        val req = LinkedHashMap<Int, Int>()
        for (part in sReq.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = reqPart.find(part) ?: error("requirement '$part' should look like Attack 75")
            val skill = m.groupValues[1].toIntOrNull() ?: ItemStats.SKILLS.indexOfFirst { it.equals(m.groupValues[1], true) }.takeIf { it >= 0 }
                ?: error("${m.groupValues[1]} isn't a skill")
            req[skill] = m.groupValues[2].toInt()
        }
        ItemStats.setRequirements(e, req)
        return e
    }

    private fun saveStats() {
        val srv = server() ?: return say("Pick the server folder first.")
        try {
            val e = statsFromFields()
            ItemStats.put(srv, e)
            say("Saved the stats of item ${e["id"].asInt} in ${ItemStats.file(srv).path}. Send stats to server puts them on the live server.")
            fillStats()
        } catch (ex: Exception) { say("Couldn't save the stats: ${ex.message}") }
    }

    private fun copyStats() {
        val srv = server() ?: return say("Pick the server folder first.")
        val id = work?.id ?: return say("Pick an item first.")
        val from = TextInputDialog("22325").apply { title = "Copy stats"; headerText = null; contentText = "Copy the stats of item id (22325 = Scythe of vitur, 4151 = whip):"; initOwner(stage) }
            .showAndWait().orElse(null)?.trim()?.toIntOrNull() ?: return
        val e = ItemStats.copyFrom(srv, from, id) ?: return say("The server has no stats for item $from.")
        stats = e
        fillStatsFields(e)
        sInfo.text = "Copied from item $from (not saved yet). Change what you want, then Save stats."
    }

    private fun sendStats() {
        val srv = server() ?: return say("Pick the server folder first.")
        val ids = ItemStats.pending()
        if (ids.isEmpty()) return say("No saved stats to send. Save stats first.")
        say("Sending stats for ${ids.size} item(s) to the server (it restarts)...")
        Thread({
            val lines = ArrayList<String>()
            val ok = try { Deployer { lines.add(it); println(it) }.deployItemStats(srv) } catch (ex: Exception) { lines.add("Failed: ${ex.message}"); false }
            javafx.application.Platform.runLater { say((if (ok) "Item stats are on the live server. " else "Sending the stats failed. ") + lines.takeLast(3).joinToString(" ")) }
        }, "send-item-stats").apply { isDaemon = true }.start()
    }

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
        fillStats()
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
            val md = try { m.get(id) } catch (e: Exception) { null } ?: continue
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
        info.text = "New item ${d.id} (not saved yet). Set it up, then Save."
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
            Label("In game (the server's item stats: bonuses, speed, animations)").apply { styleClass.add("section") },
            sInfo,
            HBox(6.0, Button("Server folder...").apply { setOnAction {
                    javafx.stage.DirectoryChooser().apply { title = "Your server folder (with gradlew and data)" }.showDialog(stage)?.let { d ->
                        val s = ServerSource.near(d); if (s == null) say("${d.path} isn't a server folder.") else { serverDir = s; fillStats() } } } },
                Button("Copy stats from item...").apply { setOnAction { copyStats() } }),
            row("Slot", sSlot, sTwoHanded),
            row("Weight kg", sWeight),
            Label("Bonuses"),
            GridPane().apply { hgap = 4.0; vgap = 3.0
                for (i in 0 until 14) { add(Label(ItemStats.BONUS_NAMES[i]).apply { minWidth = Region.USE_PREF_SIZE }, (i % 4) * 2, i / 4); add(sBonus[i], (i % 4) * 2 + 1, i / 4) } },
            row("Attack speed", sSpeed, Label("ticks (4 = whip, 5 = scythe, 6 = slow)")),
            row("Weapon type", sVarbit, Label("(attack styles tab; copy it from a similar weapon)")),
            row("Attack range", sRange, Label("long range"), sLongRange),
            Label("Weapon animations (animation ids; empty = keep as is)"),
            GridPane().apply { hgap = 4.0; vgap = 3.0
                sAnims.entries.forEachIndexed { i, (k, f) -> add(Label(ItemStats.ANIMS[k]).apply { minWidth = Region.USE_PREF_SIZE }, (i % 2) * 2, i / 2); add(f, (i % 2) * 2 + 1, i / 2) } },
            row("Requirements", sReq),
            HBox(6.0, Button("Save stats").apply { styleClass.add("accent"); setOnAction { saveStats() } },
                Button("Send stats to server").apply { setOnAction { sendStats() } }),
            Separator(),
            HBox(8.0, noteInfo, Button("Make noted version").apply { setOnAction { makeNoted() } }).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, Button("Save").apply { styleClass.add("accent"); setOnAction { save(false) } },
                Button("Save as new item").apply { setOnAction { save(true) } },
                Button("Reset").apply { setOnAction { val o = original; if (o != null) load(o.id) else if (work != null) newBlank() } })
        ).apply { padding = Insets(8.0) }
        val right = ScrollPane(form).apply { isFitToWidth = true; prefWidth = 520.0; minWidth = 480.0 }
        val left = VBox(6.0, search, list,
            Button("New item").apply { maxWidth = Double.MAX_VALUE; setOnAction { newBlank() } },
            Button("Copy from another cache...").apply { maxWidth = Double.MAX_VALUE; setOnAction { copyFromOther() } },
            HBox(4.0, Button("Export bundle...").apply { maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS); setOnAction { exportBundle() } },
                Button("Import bundle...").apply { maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS); setOnAction { importBundle() } })
        ).apply { padding = Insets(8.0); VBox.setVgrow(list, Priority.ALWAYS) }
        val iconBox = StackPane(bigIcon).apply { style = "-fx-background-color: #3e3529; -fx-border-color: #5a4d3b;"; prefWidth = 160.0; prefHeight = 140.0; maxWidth = 160.0; maxHeight = 140.0 }
        val center = VBox(8.0, HBox(12.0, iconBox, VBox(6.0, Label("Inventory picture (as in game, 4x)"), Label("3D view"), viewMode)), view).apply {
            padding = Insets(8.0); VBox.setVgrow(view, Priority.ALWAYS)
        }
        return Tab("Items", BorderPane(center, null, right, null, left)).apply { isClosable = false }
    }
}
