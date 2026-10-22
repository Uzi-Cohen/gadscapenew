package controllers

import cache.CachePaths
import cache.loaders.SpriteLoader
import com.displee.cache.CacheLibrary
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.image.WritableImage
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.stage.Stage
import utils.GadTools
import java.io.File

/**
 * View the game's interfaces (cache index 3) and edit text, colours, sprites, position and size.
 * Edits are patched into the original bytes in place, so nothing else in a component changes.
 * A component is only editable when the editor could read every byte of it (otherwise view only).
 */
typealias Comp = InterfaceCodec.Comp

/** otherCache: look at another cache's interfaces (read-only, nothing can be saved into it). */
class InterfaceEditorWindow(private var spriteLoader: SpriteLoader, private val log: (String) -> Unit,
                            private val otherCache: java.io.File? = null) {
    /** The open cache's format (211 or 241): build 241 keeps model ids in 4 bytes (see [InterfaceCodec.decode]). */
    private var rev = cache.CacheIO.REV_211
    private fun decode(g: Int, id: Int, d: ByteArray) = InterfaceCodec.decode(g, id, d, rev)


    // ---------------- patching ----------------

    private fun putShort(c: Comp, off: Int, v: Int) { if (off >= 0) { c.data[off] = (v shr 8).toByte(); c.data[off + 1] = v.toByte() } }
    private fun putInt(c: Comp, off: Int, v: Int) { if (off >= 0) for (i in 0..3) c.data[off + i] = (v shr (24 - 8 * i)).toByte() }

    private fun patchText(c: Comp, newText: String) {
        if (c.offText < 0) return
        val bytes = newText.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val shift = bytes.size - c.textLen
        c.data = c.data.copyOfRange(0, c.offText) + bytes + c.data.copyOfRange(c.offText + c.textLen, c.data.size)
        if (c.offColor > c.offText) c.offColor += shift
        if (c.offSprite > c.offText) c.offSprite += shift
        c.textLen = bytes.size
        c.text = newText
    }

    // ---------------- window ----------------

    private var lib: CacheLibrary? = null
    private var groupId = -1
    private var comps = ArrayList<Comp>()
    private var selected: Comp? = null
    private val dirty = HashSet<Int>()
    private val canvas = Canvas(765.0, 503.0)
    private val compList = ListView<Comp>()
    private val showHidden = CheckBox("Show hidden parts")
    private val spriteCache = HashMap<Int, WritableImage?>()

    private val txtX = TextField(); private val txtY = TextField(); private val txtW = TextField(); private val txtH = TextField()
    private val txtText = TextField(); private val txtSprite = TextField()
    private val colorPick = ColorPicker()
    private val lblInfo = Label().apply { isWrapText = true; styleClass.add("subtle") }

    /** Old-style (317) cache: its interfaces, read with their own format. */
    private var old: cache.Interface317? = null

    fun show() {
        val ids: List<Int>
        if (otherCache != null && cache.Cache317Maps.isOldStyle(otherCache)) {
            old = try { cache.Interface317(cache.Cache317Maps(otherCache)) } catch (e: Exception) { log("Can't open that cache: ${e.message}"); return }
            ids = old!!.screens()
        } else {
            lib = try { CacheLibrary(otherCache?.path ?: CachePaths.cacheDir) } catch (e: Exception) { log("Can't open the cache: ${e.message}"); return }
            rev = try { cache.CacheIO.revision(lib!!) } catch (e: Exception) { cache.CacheIO.REV_211 }
            if (otherCache != null) try { spriteLoader = SpriteLoader(lib!!) } catch (e: Exception) { log("That cache's sprites couldn't be read: ${e.message}") }
            ids = try { lib!!.index(3).archiveIds().sorted() } catch (e: Exception) { emptyList<Int>() }
        }
        val l = lib

        val search = TextField().apply { promptText = "Interface id" }
        val list = ListView<Int>()
        // find text anywhere in the game's interfaces
        val findText = TextField().apply { promptText = "Find text (e.g. an old server name)" }
        val findResults = ListView<String>().apply { prefHeight = 160.0 }
        val btnFind = Button("Find").apply {
            setOnAction {
                val q = findText.text.trim().toLowerCase()
                if (q.isEmpty()) return@setOnAction
                val hits = ArrayList<String>()
                old?.let { o ->
                    // old-style: search every part, report the screen it sits in
                    val parentOf = HashMap<Int, Int>()
                    o.all.values.forEach { f -> f.children.forEach { ch -> parentOf[ch.id] = f.id } }
                    fun rootOf(id: Int): Int { var c = id; var n = 0; while (parentOf[c] != null && n++ < 50) c = parentOf[c]!!; return c }
                    o.all.values.filter { it.text?.toLowerCase()?.contains(q) == true }.take(200).forEach { f ->
                        hits.add("${rootOf(f.id)}:${f.id}  ${f.text!!.replace(Regex("<[^>]*>"), "").take(60)}")
                    }
                    findResults.items.setAll(if (hits.isEmpty()) listOf("Not found in the interfaces this viewer could read.") else hits)
                    return@setOnAction
                }
                if (l == null) return@setOnAction
                for (g in ids) {
                    try {
                        val a = l.index(3).archive(g) ?: continue
                        for (f in a.fileIds()) {
                            val d = l.data(3, g, f) ?: continue
                            val c = decode(g, f, d)
                            val t = c.text ?: continue
                            if (t.toLowerCase().contains(q)) hits.add("$g:$f  ${t.replace(Regex("<[^>]*>"), "").take(60)}")
                        }
                    } catch (e: Exception) {}
                    if (hits.size >= 200) break
                }
                findResults.items.setAll(if (hits.isEmpty()) listOf("Not in any interface text. It's probably set by the server.") else hits)
            }
        }
        findText.setOnAction { btnFind.fire() }
        findResults.selectionModel.selectedItemProperty().addListener { _, _, v ->
            val g = v?.substringBefore(":")?.toIntOrNull() ?: return@addListener
            val f = v.substringAfter(":").substringBefore(" ").toIntOrNull() ?: return@addListener
            list.items.indexOf(g).takeIf { it >= 0 }?.let { idx -> list.selectionModel.clearAndSelect(idx); (list as ListView<*>).scrollTo(idx) }
            openGroup(g)
            comps.firstOrNull { it.id == f }?.let { compList.selectionModel.select(it); compList.scrollTo(it) }
        }
        list.items.setAll(ids)
        search.textProperty().addListener { _, _, q ->
            list.items.setAll(if (q.isBlank()) ids else ids.filter { it.toString().startsWith(q.trim()) })
        }
        list.selectionModel.selectedItemProperty().addListener { _, _, id -> if (id != null) openGroup(id) }

        compList.selectionModel.selectedItemProperty().addListener { _, _, c -> select(c) }
        showHidden.selectedProperty().addListener { _, _, _ -> draw() }
        canvas.setOnMouseClicked { e ->
            val hit = comps.filter { visible(it) && e.x >= it.ax && e.y >= it.ay && e.x < it.ax + it.aw && e.y < it.ay + it.ah }
                .minByOrNull { it.aw * it.ah }
            if (hit != null) compList.selectionModel.select(hit)
        }

        val btnApply = Button("Apply").apply { styleClass.add("accent"); setOnAction { applyEdits() } }
        val btnSave = Button("Save interface to map").apply { setOnAction { save() }; isDisable = otherCache != null }
        val grid = GridPane().apply {
            hgap = 6.0; vgap = 6.0
            addRow(0, Label("X"), txtX, Label("Y"), txtY)
            addRow(1, Label("Width"), txtW, Label("Height"), txtH)
            add(Label("Text"), 0, 2); add(txtText, 1, 2, 3, 1)
            add(Label("Colour"), 0, 3); add(colorPick, 1, 3, 3, 1)
            add(Label("Sprite id"), 0, 4); add(txtSprite, 1, 4)
            listOf(txtX, txtY, txtW, txtH, txtSprite).forEach { it.prefColumnCount = 5 }
        }
        val right = VBox(8.0, Label("Parts"), compList, showHidden, Separator(), grid, HBox(6.0, btnApply, btnSave), lblInfo).apply {
            padding = Insets(8.0); prefWidth = 330.0
            VBox.setVgrow(compList, Priority.ALWAYS)
        }
        val left = VBox(6.0, Label("Find text"), HBox(4.0, findText, btnFind), findResults, Label("Interfaces"), search, list).apply {
            padding = Insets(8.0); prefWidth = 260.0
            VBox.setVgrow(list, Priority.ALWAYS)
        }
        val center = ScrollPane(StackPane(canvas).apply { style = "-fx-background-color: #0c0a09;"; alignment = Pos.TOP_LEFT })
        val root = BorderPane(center, null, right, null, left)
        val stage = Stage()
        stage.scene = Scene(root, 1300.0, 700.0)
        utils.Branding.apply(stage, if (otherCache != null) "interfaces in ${otherCache.parentFile?.name ?: otherCache.name} (view only)" else "interfaces")
        stage.setOnHidden { try { lib?.close() } catch (e: Exception) {} }
        stage.show()
        lblInfo.text = "Pick an interface on the left. Click a part (or pick it in the list) to edit it. " +
                "Apply changes it here, Save writes it into your saved map (cache-out). Parts marked view only can't be changed safely."
    }

    private fun openGroup(id: Int) {
        groupId = id
        comps.clear()
        selected = null
        old?.let { o -> openOld(o, id); return }
        val l = lib ?: return
        try {
            val archive = l.index(3).archive(id)
            val fileIds = archive?.fileIds() ?: intArrayOf()
            for (f in fileIds) {
                val data = l.data(3, id, f) ?: continue
                comps.add(decode(id, f, data))
            }
        } catch (e: Exception) {
            log("Interface $id: ${e.message}")
        }
        val byId = comps.associateBy { it.id }
        comps.forEach { it.children.clear() }
        comps.forEach { c -> if (c.parent >= 0) byId[c.parent and 0xFFFF]?.children?.add(c) }
        compList.items.setAll(comps)
        val editable = comps.count { it.editable }
        lblInfo.text = "Interface $id: ${comps.size} parts, $editable editable."
        draw()
    }

    /** Build an old-style screen: walk its children (positions are relative to the parent). */
    private fun openOld(o: cache.Interface317, rootId: Int) {
        var next = 0
        fun add(id: Int, parent: Int, x: Int, y: Int, depth: Int) {
            val f = o.all[id] ?: return
            if (depth > 30) return
            val c = Comp(rootId, next++, ByteArray(0))
            c.type = f.type; c.x = x; c.y = y; c.w = f.width; c.h = f.height
            c.parent = parent; c.text = f.text; c.color = f.color; c.filled = f.filled
            c.name = "#$id" + (f.sprite?.let { "  sprite $it" } ?: "") + (f.tooltip?.let { "  \"$it\"" } ?: "")
            c.image = f.sprite?.let { o.sprite(it) }
            c.editable = false
            comps.add(c)
            val me = c.id
            f.children.forEach { ch -> add(ch.id, me, ch.x, ch.y, depth + 1) }
        }
        add(rootId, -1, 0, 0, 0)
        val byId = comps.associateBy { it.id }
        comps.forEach { it.children.clear() }
        comps.forEach { c -> if (c.parent >= 0) byId[c.parent]?.children?.add(c) }
        compList.items.setAll(comps)
        lblInfo.text = "Screen $rootId: ${comps.size} parts (view only)." + (o.problem?.let { "  $it" } ?: "")
        draw()
    }

    private fun visible(c: Comp) = showHidden.isSelected || !c.hidden

    private fun layout(c: Comp, px: Int, py: Int, pw: Int, ph: Int) {
        c.aw = when (c.wMode) { 1 -> pw - c.w; 2 -> c.w * pw shr 14; else -> c.w }
        c.ah = when (c.hMode) { 1 -> ph - c.h; 2 -> c.h * ph shr 14; else -> c.h }
        val rx = when (c.xMode) { 1 -> (pw - c.aw) / 2 + c.x; 2 -> pw - c.aw - c.x; 3 -> c.x * pw shr 14; 4 -> (pw - c.aw) / 2 + (c.x * pw shr 14); 5 -> pw - c.aw - (c.x * pw shr 14); else -> c.x }
        val ry = when (c.yMode) { 1 -> (ph - c.ah) / 2 + c.y; 2 -> ph - c.ah - c.y; 3 -> c.y * ph shr 14; 4 -> (ph - c.ah) / 2 + (c.y * ph shr 14); 5 -> ph - c.ah - (c.y * ph shr 14); else -> c.y }
        c.ax = px + rx; c.ay = py + ry
        for (ch in c.children) layout(ch, c.ax, c.ay, c.aw, c.ah)
    }

    private fun spriteImage(id: Int): WritableImage? = spriteCache.getOrPut(id) {
        try {
            val s = spriteLoader.get(id) ?: return@getOrPut null
            if (s.width <= 0 || s.height <= 0) return@getOrPut null
            val img = WritableImage(s.width, s.height)
            for (y in 0 until s.height) for (x in 0 until s.width) {
                val p = s.pixels[x + y * s.width]
                img.pixelWriter.setArgb(x, y, if (p == 0) 0 else (0xFF shl 24) or p)
            }
            img
        } catch (e: Exception) { null }
    }

    private fun rgb(c: Int) = Color.rgb(c shr 16 and 255, c shr 8 and 255, c and 255)

    private fun draw() {
        val g = canvas.graphicsContext2D
        g.fill = Color.web("#1a1612"); g.fillRect(0.0, 0.0, canvas.width, canvas.height)
        comps.filter { it.parent < 0 || comps.none { p -> p.id == (it.parent and 0xFFFF) } }
            .forEach { layout(it, 0, 0, canvas.width.toInt(), canvas.height.toInt()) }
        fun paint(c: Comp) {
            if (!visible(c)) return
            val x = c.ax.toDouble(); val y = c.ay.toDouble(); val w = c.aw.toDouble(); val h = c.ah.toDouble()
            when (c.type) {
                3 -> if (c.filled) { g.fill = rgb(c.color); g.fillRect(x, y, w, h) } else { g.stroke = rgb(c.color); g.strokeRect(x + .5, y + .5, w - 1, h - 1) }
                4 -> { g.fill = rgb(c.color); g.font = Font.font("Verdana", 11.0); g.fillText(c.text?.replace(Regex("<[^>]*>"), "") ?: "", x + 1, y + 12, maxOf(w, 1.0)) }
                5 -> (c.image ?: spriteImage(c.sprite))?.let { g.drawImage(it, x, y, if (w > 0) w else it.width, if (h > 0) h else it.height) }
                6 -> { g.stroke = Color.web("#5a4d3b"); g.strokeRect(x, y, w, h); g.fill = Color.web("#5a4d3b"); g.fillText(if (c.model >= 0) "model ${c.model}" else "model", x + 2, y + 12) }
                9 -> { g.stroke = rgb(c.color); g.strokeLine(x, y, x + w, y + h) }
            }
            c.children.forEach { paint(it) }
        }
        comps.filter { it.parent < 0 || comps.none { p -> p.id == (it.parent and 0xFFFF) } }.forEach { paint(it) }
        selected?.let { s ->
            g.stroke = Color.web("#ffff00"); g.lineWidth = 2.0
            g.strokeRect(s.ax.toDouble(), s.ay.toDouble(), s.aw.toDouble(), s.ah.toDouble())
            g.lineWidth = 1.0
        }
    }

    private fun select(c: Comp?) {
        selected = c
        if (c != null) {
            txtX.text = c.x.toString(); txtY.text = c.y.toString(); txtW.text = c.w.toString(); txtH.text = c.h.toString()
            txtText.text = c.text ?: ""; txtText.isDisable = !c.editable || c.offText < 0
            colorPick.value = rgb(c.color); colorPick.isDisable = !c.editable || c.offColor < 0
            txtSprite.text = if (c.sprite >= 0) c.sprite.toString() else ""; txtSprite.isDisable = !c.editable || c.offSprite < 0
            // position/size: always safe (fixed spot at the start of every part)
            listOf(txtX, txtY, txtW, txtH).forEach { it.isDisable = c.offX < 0 }
        }
        draw()
    }

    private fun applyEdits() {
        val c = selected ?: return
        try {
            txtX.text.trim().toIntOrNull()?.let { c.x = it; putShort(c, c.offX, it) }
            txtY.text.trim().toIntOrNull()?.let { c.y = it; putShort(c, c.offY, it) }
            txtW.text.trim().toIntOrNull()?.let { c.w = it; putShort(c, c.offW, it) }
            txtH.text.trim().toIntOrNull()?.let { c.h = it; putShort(c, c.offH, it) }
            if (c.editable && c.offText >= 0 && txtText.text != c.text) patchText(c, txtText.text)
            if (c.editable && c.offColor >= 0) {
                val col = colorPick.value
                val v = ((col.red * 255).toInt() shl 16) or ((col.green * 255).toInt() shl 8) or (col.blue * 255).toInt()
                c.color = v; putInt(c, c.offColor, v)
            }
            if (c.editable && c.offSprite >= 0) txtSprite.text.trim().toIntOrNull()?.let { c.sprite = it; putInt(c, c.offSprite, it) }
            dirty.add(c.id)
            compList.refresh()
            draw()
            lblInfo.text = "Changed part ${c.id}. Save to keep it (${dirty.size} part(s) changed)."
        } catch (e: Exception) {
            lblInfo.text = "Couldn't apply: ${e.message}"
        }
    }

    private fun save() {
        if (dirty.isEmpty() || groupId < 0) { lblInfo.text = "Nothing changed yet."; return }
        val out = GadTools.ensureExportCache()
        try {
            val outLib = CacheLibrary(out.path)
            // the parts were read (and patched in place) in the open cache's format; the saved map must be the same one
            val outRev = cache.CacheIO.revision(outLib)
            if (cache.CacheIO.is241(outLib) != (rev >= cache.CacheIO.REV_241)) {
                outLib.close()
                lblInfo.text = "Not saved: the saved map (${out.path}) is a ${if (outRev >= cache.CacheIO.REV_241) "build 241" else "rev 211"} cache " +
                        "but these interfaces were read from a ${if (rev >= cache.CacheIO.REV_241) "build 241" else "rev 211"} one."
                return
            }
            for (c in comps.filter { it.id in dirty }) outLib.put(3, groupId, c.id, c.data)
            outLib.index(3).update()
            outLib.close()
            lblInfo.text = "Saved ${dirty.size} part(s) of interface $groupId into ${out.path}. Deploy to put it on the server."
            log("Interface $groupId: saved ${dirty.size} part(s)")
            dirty.clear()
        } catch (e: Exception) {
            lblInfo.text = "Save failed: ${e.message}"
        }
    }
}

/** Reads interface parts (cache index 3) and remembers where the editable fields sit in the bytes. */
object InterfaceCodec {
    // ---------------- component data ----------------

    class Comp(val group: Int, val id: Int, var data: ByteArray) {
        var if3 = false
        var type = 0
        var x = 0; var y = 0; var w = 0; var h = 0
        var xMode = 0; var yMode = 0; var wMode = 0; var hMode = 0
        var parent = -1
        var hidden = false
        var text: String? = null
        var color = 0
        var filled = false
        var sprite = -1
        /** Model components (type 6): the model id, or -1. */
        var model = -1
        var name: String = ""
        /** Picture for old-style caches (their sprites are found by name). */
        var image: javafx.scene.image.WritableImage? = null
        var editable = false   // whole component understood -> safe to patch
        // byte offsets of the fields we can change
        var offX = -1; var offY = -1; var offW = -1; var offH = -1
        var offText = -1; var textLen = 0
        var offColor = -1; var offSprite = -1
        var children = ArrayList<Comp>()
        // absolute position on the canvas (worked out when drawing)
        var ax = 0; var ay = 0; var aw = 0; var ah = 0

        override fun toString(): String {
            val kind = when (type) { 0 -> "box"; 3 -> "rect"; 4 -> "text"; 5 -> "sprite"; 6 -> "model"; 9 -> "line"; else -> "type $type" }
            val label = text?.takeIf { it.isNotBlank() }?.let { "\"${it.take(24)}\"" } ?: name.takeIf { it.isNotBlank() }
                ?: (if (type == 6 && model >= 0) "model $model" else "")
            return "$id  $kind  $label" + (if (!editable) "  (view only)" else "")
        }
    }

    private class Reader(val b: ByteArray) {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun s8() = b[p++].toInt()
        fun u16() = (u8() shl 8) or u8()
        fun s16() = u16().toShort().toInt()
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
        fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
        fun listener() { val n = u8(); repeat(n) { if (u8() == 0) i32() else str() } }
        fun triggers() { val n = u8(); repeat(n) { i32() } }
        fun done() = p == b.size
    }

    /**
     * Reads one part. [rev] is the cache's format: from rev 237 on (build 241) model ids are 4 bytes, in IF3 model
     * parts (type 6) and in old-style (IF1) ones; in rev 211 they are 2 bytes (65535 = none). Nothing else in the
     * part formats changed between 211 and 241 (checked on every interface of the stock 241 and GadScape caches).
     */
    fun decode(group: Int, id: Int, data: ByteArray, rev: Int = cache.CacheIO.REV_211): Comp {
        val c = Comp(group, id, data)
        val r = Reader(data)
        val intModels = rev >= 237
        try {
            if (data.isNotEmpty() && data[0].toInt() == -1) {
                c.if3 = true
                r.u8()
                c.type = r.u8(); r.u16() // content type
                c.offX = r.p; c.x = r.s16()
                c.offY = r.p; c.y = r.s16()
                c.offW = r.p; c.w = r.u16()
                c.offH = r.p; c.h = if (c.type == 9) r.s16() else r.u16()
                c.wMode = r.s8(); c.hMode = r.s8(); c.xMode = r.s8(); c.yMode = r.s8()
                val parent = r.u16(); c.parent = if (parent == 0xFFFF) -1 else parent and 0xFFFF
                c.hidden = r.u8() == 1
                when (c.type) {
                    0 -> { r.u16(); r.u16(); r.u8() }
                    5 -> { c.offSprite = r.p; c.sprite = r.i32(); r.u16(); r.u8(); r.u8(); r.u8(); r.i32(); r.u8(); r.u8() }
                    6 -> {
                        c.model = if (intModels) r.i32() else r.u16().let { if (it == 0xFFFF) -1 else it }
                        r.s16(); r.s16(); r.u16(); r.u16(); r.u16(); r.u16(); r.u16(); r.u8(); r.s16()
                        // the client reads the width override when the width mode is set and a short when the height mode is
                        if (c.wMode != 0) r.u16()
                        if (c.hMode != 0) r.s16()
                    }
                    4 -> {
                        r.u16() // font
                        c.offText = r.p; c.text = r.str(); c.textLen = r.p - c.offText
                        r.u8(); r.u8(); r.u8(); r.u8()
                        c.offColor = r.p; c.color = r.i32()
                    }
                    3 -> { c.offColor = r.p; c.color = r.i32(); c.filled = r.u8() == 1; r.u8() }
                    9 -> { r.u8(); c.offColor = r.p; c.color = r.i32(); r.u8() }
                    else -> throw IllegalStateException("unknown type")
                }
                r.u24()
                c.name = r.str()
                val ops = r.u8(); repeat(ops) { r.str() }
                r.u8(); r.u8(); r.u8()
                r.str()
                repeat(18) { r.listener() }
                repeat(3) { r.triggers() }
                // model parts with only one of the width/height modes set: the cache tool wrote both overrides, the
                // client reads one, so they end 2 bytes early (the server's ComponentDefinitions accepts this too)
                val quirk = c.type == 6 && (c.wMode != 0) != (c.hMode != 0) && r.b.size - r.p == 2
                c.editable = r.done() || quirk
            } else {
                // old (if1) format: only the position/size header is understood
                c.type = r.u8(); r.u8(); r.u16()
                c.offX = r.p; c.x = r.s16()
                c.offY = r.p; c.y = r.s16()
                c.offW = r.p; c.w = r.u16()
                c.offH = r.p; c.h = r.u16()
                r.u8()
                val parent = r.u16(); c.parent = if (parent == 0xFFFF) -1 else parent and 0xFFFF
                c.editable = false
            }
        } catch (e: Exception) {
            c.editable = false
        }
        return c
    }

}
