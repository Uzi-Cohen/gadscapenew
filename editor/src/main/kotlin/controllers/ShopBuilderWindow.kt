package controllers

import cache.CachePaths
import cache.loaders.ModelLoader
import cache.loaders.TextureLoader
import com.displee.cache.CacheLibrary
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.*
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import models.Configuration
import utils.ItemDef
import utils.ItemDefs
import java.io.File

/**
 * Shop editor for the server's shop scripts.
 * Reads each file line by line: shop headers ("Name"(...) {), item lines (UnivShopItem(...), ShopItem(...), ...)
 * and keeps every other line exactly as it was, so saving never loses anything it didn't understand.
 */
class ShopBuilderWindow(
    private val configuration: Configuration,
    private val modelLoader: ModelLoader,
    private val textureLoader: TextureLoader,
    private val log: (String) -> Unit
) {
    // ---------------- model ----------------

    /** One line inside a shop: an item we understand, or a raw line kept as-is. */
    class Entry(var raw: String?) {
        var indent = "            "
        var call = "UnivShopItem"
        var item = ""
        val args = LinkedHashMap<String, String>()
        val positional = ArrayList<String>()    // positional args after the item (kept in order)
        var suffix = ".invoke()"
        var original: String? = null   // the line as it was in the file; written back unchanged unless edited
        var direct = false             // "2364(stock, sell, buy, ...) // name" style instead of UnivShopItem(...)
        var inTable = false            // inside a home-shop table: one item per line, comma after all but the last
        var comment = ""
        var dirty = false
        val isItem get() = raw == null
        var buyPrice: String get() = if (direct) positional.getOrNull(2) ?: "" else args["buyPrice"] ?: args["price"] ?: positional.getOrNull(0) ?: ""
            set(v) { if (v == buyPrice) return; dirty = true
                if (direct) { while (positional.size < 3) positional.add("0"); positional[2] = v; return }
                when { args.containsKey("price") -> args["price"] = v; positional.isNotEmpty() && !args.containsKey("buyPrice") -> positional[0] = v; v.isBlank() -> args.remove("buyPrice"); else -> args["buyPrice"] = v } }
        var ironman: Boolean get() = args["ironmanRestricted"] == "true"
            set(v) { if (v == ironman) return; dirty = true; if (v) args["ironmanRestricted"] = "true" else args.remove("ironmanRestricted") }
        var stock: String get() = if (direct) positional.getOrNull(0) ?: "" else args["amount"] ?: args["stock"] ?: ""
            set(v) { if (v == stock || v.isBlank()) return; dirty = true
                if (direct) { while (positional.size < 3) positional.add("0"); positional[0] = v } else args[if (args.containsKey("stock")) "stock" else "amount"] = v }
        var sellPrice: String get() = if (direct) positional.getOrNull(1) ?: "" else args["sellPrice"] ?: ""
            set(v) { if (v == sellPrice || v.isBlank()) return; dirty = true
                if (direct) { while (positional.size < 3) positional.add("0"); positional[1] = v } else args["sellPrice"] = v }
        fun others() = if (direct) (positional.drop(3) + args.filterKeys { it != "ironmanRestricted" }.map { "${it.key} = ${it.value}" }).joinToString(", ") else (positional.drop(if (args.containsKey("buyPrice") || args.containsKey("price")) 0 else 1) +
                args.filterKeys { it != "buyPrice" && it != "price" && it != "ironmanRestricted" }.map { "${it.key}=${it.value}" }).joinToString(", ")
        /** For table items: the line without its trailing comma (render adds commas by position). */
        fun tableCode(): String = (original?.takeIf { !dirty } ?: (indent + call + "(" + (listOf(item) + positional + args.map { "${it.key}=${it.value}" }).joinToString(", ") + ")"))
            .trimEnd().removeSuffix(",").trimEnd()
        fun code(): String = raw ?: original?.takeIf { !dirty } ?: if (direct)
            (indent + item + "(" + (positional + args.map { "${it.key} = ${it.value}" }).joinToString(", ") + ")" + (if (comment.isNotBlank()) " $comment" else ""))
        else (indent + call + "(" + (listOf(item) + positional + args.map { "${it.key}=${it.value}" }).joinToString(", ") + ")" + suffix)
    }

    class Shop(var name: String) {
        var isTable = false
        var tableCurrency = ""
        var indent = "        "
        val headerArgs = ArrayList<String>()   // everything inside "Name"( ... )
        var idIdx = 0; var curIdx = -1; var polIdx = -1
        val entries = FXCollections.observableArrayList<Entry>()
        var startLine = -1; var endLine = -1   // lines in the file this shop occupies (inclusive)
        var id: String get() = if (idIdx >= 0) headerArgs.getOrElse(idIdx) { "" } else ""
            set(v) { if (idIdx >= 0) setArg(idIdx, v) }
        var currency: String get() = if (isTable) tableCurrency else if (curIdx >= 0) headerArgs[curIdx] else ""
            set(v) { if (isTable) return; if (curIdx < 0) { headerArgs.add(v); curIdx = headerArgs.size - 1 } else headerArgs[curIdx] = v }
        var policy: String get() = if (polIdx >= 0) headerArgs[polIdx] else ""
            set(v) { if (isTable) return; if (polIdx < 0) { headerArgs.add(v); polIdx = headerArgs.size - 1 } else headerArgs[polIdx] = v }
        private fun setArg(i: Int, v: String) { while (headerArgs.size <= i) headerArgs.add(""); headerArgs[i] = v }
        var originalHeader: String? = null; var originalName = ""; var originalArgs: List<String> = emptyList()
        fun headerLine() = originalHeader?.takeIf { isTable || (name == originalName && headerArgs == originalArgs) }
            ?: "$indent\"${name.replace("\"", "\\\"")}\"(${headerArgs.joinToString(", ")}) {"
        override fun toString() = (if (isTable) "Home: $name" else name) + (if (id.isNotBlank()) "  (id $id)" else "") + "  - ${entries.count { it.isItem }} items"
    }

    class ShopFile(val file: File, val lines: MutableList<String>) {
        val shops = ArrayList<Shop>()
        var isNew = false
        override fun toString() = (if (shops.any { it.isTable }) "\u2605 HOME SHOP tabs (what players see at home)" else file.nameWithoutExtension) +
                "  (${shops.size} ${if (shops.any { it.isTable }) "tab" else "shop"}${if (shops.size == 1) "" else "s"})"
    }

    private fun splitArgs(s: String): MutableList<String> {
        val out = ArrayList<String>(); var depth = 0; var cur = StringBuilder(); var inStr = false
        for (ch in s) {
            when {
                ch == '"' -> { inStr = !inStr; cur.append(ch) }
                !inStr && (ch == '(' || ch == '[' || ch == '{') -> { depth++; cur.append(ch) }
                !inStr && (ch == ')' || ch == ']' || ch == '}') -> { depth--; cur.append(ch) }
                !inStr && ch == ',' && depth == 0 -> { out.add(cur.toString().trim()); cur = StringBuilder() }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private val headerRe = Regex("""^(\s*)"([^"]{0,200})"\s*\((.{0,400})\)\s*\{\s*$""")
    private val itemRe = Regex("""^(\s*)([A-Za-z_]\w{0,40}Item)\s*\((.{0,600}?)\)((?:\.\w{1,30}\(\))?)\s*$""")
    // 2364(250, 0, 15000, ironmanRestricted = true) // rune bar
    private val directRe = Regex("""^(\s*)(\d{1,6}|ItemId\.\w{1,80}|[A-Z][A-Z0-9_]{1,80})\s*\((.{0,400}?)\)\s*(//.{0,300})?$""")
    private val currencies = LinkedHashSet<String>()
    private val policies = LinkedHashSet<String>()

    private fun braces(line: String): Int {
        var d = 0; var inStr = false
        for (c in line) { if (c == '"') inStr = !inStr else if (!inStr) { if (c == '{') d++ else if (c == '}') d-- } }
        return d
    }

    private fun parse(f: File): ShopFile {
        val sf = ShopFile(f, f.readLines().toMutableList())
        var i = 0
        while (i < sf.lines.size) {
            val t = tableRe.find(sf.lines[i])
            if (t != null) { i = parseTable(sf, i, t); continue }
            val h = headerRe.find(sf.lines[i])
            if (h == null) { i++; continue }
            val shop = Shop(h.groupValues[2])
            shop.indent = h.groupValues[1]
            shop.headerArgs.addAll(splitArgs(h.groupValues[3]))
            // which header arg is which: currency mentions "Currency" or is a known currency name; the id is a number
            shop.curIdx = shop.headerArgs.indexOfFirst { it.contains("Currency", true) || it.contains("POINTS", true) || it in currencies }
            shop.idIdx = shop.headerArgs.indexOfFirst { it.trim().toIntOrNull() != null }
            shop.polIdx = shop.headerArgs.indices.lastOrNull { it != shop.curIdx && it != shop.idIdx && shop.headerArgs[it].trim().toIntOrNull() == null && shop.headerArgs[it].matches(Regex("""[\w.]+""")) } ?: -1
            if (shop.curIdx >= 0) currencies.add(shop.headerArgs[shop.curIdx])
            if (shop.polIdx >= 0) policies.add(shop.headerArgs[shop.polIdx])
            shop.startLine = i
            shop.originalHeader = sf.lines[i]; shop.originalName = shop.name; shop.originalArgs = ArrayList(shop.headerArgs)
            var depth = braces(sf.lines[i])
            var j = i + 1
            while (j < sf.lines.size && depth > 0) {
                val line = sf.lines[j]
                val d = braces(line)
                if (depth + d <= 0) break // closing line of the shop
                val dm = directRe.find(line)
                val m = itemRe.find(line)
                if (dm != null && depth == 1) {
                    val e = Entry(null)
                    e.original = line; e.direct = true
                    e.indent = dm.groupValues[1]; e.item = dm.groupValues[2]; e.comment = dm.groupValues[4].trim()
                    for (a in splitArgs(dm.groupValues[3])) {
                        val eq = a.indexOf('=')
                        if (eq > 0 && a.substring(0, eq).trim().matches(Regex("\\w+"))) e.args[a.substring(0, eq).trim()] = a.substring(eq + 1).trim()
                        else e.positional.add(a)
                    }
                    shop.entries.add(e)
                } else if (m != null && depth == 1) {
                    val e = Entry(null)
                    e.original = line
                    e.indent = m.groupValues[1]; e.call = m.groupValues[2]; e.suffix = m.groupValues[4]
                    val parts = splitArgs(m.groupValues[3])
                    e.item = parts.firstOrNull() ?: ""
                    for (a in parts.drop(1)) {
                        val eq = a.indexOf('=')
                        if (eq > 0 && a.substring(0, eq).trim().matches(Regex("\\w+"))) e.args[a.substring(0, eq).trim()] = a.substring(eq + 1).trim()
                        else e.positional.add(a)
                    }
                    shop.entries.add(e)
                } else shop.entries.add(Entry(line))
                depth += d
                j++
            }
            shop.endLine = j
            sf.shops.add(shop)
            i = j + 1
        }
        return sf
    }

    private val tableRe = Regex("""^(\s*)val\s+(\w+)\s*=\s*UnivShopTable\(\s*$""")
    private val tableItemRe = Regex("""^(\s*)(UnivShopItem)\s*\((.{0,600}?)\)\s*,?\s*(//.*)?$""")
    private val categoryCurrency = HashMap<String, String>()

    private fun parens(line: String): Int {
        var d = 0; var inStr = false
        for (c in line.substringBefore("//")) { if (c == '"') inStr = !inStr else if (!inStr) { if (c == '(') d++ else if (c == ')') d-- } }
        return d
    }

    /**
     * val Vote = UnivShopTable( category = ..., items = arrayOf( UnivShopItem(...), ... ) )
     * A tab ends right before the next "val X = UnivShopTable(" (or the companion's closing brace for the
     * last one), so a stray bracket in one tab can't swallow the others.
     */
    private fun parseTable(sf: ShopFile, start: Int, t: MatchResult): Int {
        val shop = Shop(t.groupValues[2])
        shop.isTable = true; shop.indent = t.groupValues[1]; shop.idIdx = -1
        shop.startLine = start; shop.originalHeader = sf.lines[start]; shop.originalName = shop.name
        var boundary = sf.lines.size
        for (k in start + 1 until sf.lines.size) {
            val l = sf.lines[k]
            if (tableRe.find(l) != null || Regex("""^\s*}\s*$""").matches(l)) { boundary = k; break }
        }
        // the tab's closing ")" = last non-blank line before the boundary
        var end = boundary - 1
        while (end > start && sf.lines[end].isBlank()) end--
        for (j in start + 1 until end) {
            val line = sf.lines[j]
            Regex("""category\s*=\s*UnivShopCategory\.(\w+)""").find(line)?.let { c ->
                shop.tableCurrency = categoryCurrency[c.groupValues[1]] ?: "ShopCurrency.COINS"
            }
            val m = tableItemRe.find(line)
            if (m != null) {
                val e = Entry(null)
                e.original = line; e.inTable = true; e.suffix = ""
                e.indent = m.groupValues[1]; e.call = m.groupValues[2]
                val parts = splitArgs(m.groupValues[3])
                e.item = parts.firstOrNull() ?: ""
                for (a in parts.drop(1)) {
                    val eq = a.indexOf('=')
                    if (eq > 0 && a.substring(0, eq).trim().matches(Regex("\\w+"))) e.args[a.substring(0, eq).trim()] = a.substring(eq + 1).trim()
                    else e.positional.add(a)
                }
                shop.entries.add(e)
            } else shop.entries.add(Entry(line))
        }
        shop.endLine = end
        sf.shops.add(shop)
        return end + 1
    }

    /** The file with every shop block regenerated and everything else untouched. */
    private fun render(sf: ShopFile): String {
        if (sf.isNew) return buildString {
            append(defaultHeader())
            val cls = sf.file.nameWithoutExtension
            appendln("class $cls : ShopScript() {")
            appendln("    init {")
            for (s in sf.shops) { appendln(s.headerLine()); s.entries.forEach { appendln(it.code()) }; appendln("${s.indent}}") }
            appendln("    }")
            appendln("}")
        }
        val out = ArrayList<String>()
        var i = 0
        for (s in sf.shops.sortedBy { it.startLine }) {
            while (i < s.startLine) out.add(sf.lines[i++])
            out.add(s.headerLine())
            if (s.isTable) {
                val lastItem = s.entries.indexOfLast { it.isItem }
                s.entries.forEachIndexed { k, e -> out.add(if (e.isItem) e.tableCode() + (if (k < lastItem) "," else "") else e.code()) }
            } else s.entries.forEach { out.add(it.code()) }
            out.add(sf.lines.getOrElse(s.endLine) { "${s.indent}}" })
            i = s.endLine + 1
        }
        while (i < sf.lines.size) out.add(sf.lines[i++])
        return out.joinToString("\n") + "\n"
    }

    private fun defaultHeader() = """
        package com.${utils.ServerSource.pkg(server)}.plugins.shops

        import com.${utils.ServerSource.pkg(server)}.scripts.shops.ShopScript
        import com.zenyte.game.model.shop.*
        import com.zenyte.game.model.shop.ShopPolicy
        import com.zenyte.game.model.shop.ShopPolicy.*
        import com.zenyte.game.model.shop.ShopCurrency
        import com.zenyte.game.model.shop.ShopCurrency.*
        import com.zenyte.game.item.ItemId
        import com.zenyte.game.item.ItemId.*
        import com.${utils.ServerSource.pkg(server)}.game.content.universalshop.*
        import com.${utils.ServerSource.pkg(server)}.game.content.universalshop.UnivShopItem
        import com.${utils.ServerSource.pkg(server)}.game.content.universalshop.UnivShopItem.*
    """.trimIndent() + "\n\n"

    // ---------------- server source lookups ----------------

    private fun guessServerDir(): File? = utils.ServerSource.dir(configuration)

    private fun shopsDir(server: File) = File(server, utils.Deployer.shopsPath(server))
    private val UNIV_DIR get() = utils.ServerSource.pkgPath(server, "core-model/src/main/kotlin", "game/content/universalshop")

    private fun findSource(server: File, vararg names: String): File? = server.walkTopDown().maxDepth(16)
        .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
        .firstOrNull { it.isFile && it.name in names }

    /** Enum constants of a Java/Kotlin enum (every NAME( or NAME, at the start of a line before the first ';'). */
    private fun enumConstants(f: File?): List<String> {
        f ?: return emptyList()
        val text = f.readText()
        val start = Regex("""enum\s+(class\s+)?\w+[^{]*\{""").find(text)?.range?.last ?: return emptyList()
        val body = text.substring(start + 1).substringBefore(";")
        return Regex("""(?m)^\s*([A-Z][A-Z0-9_]+)\s*(?=[(,{]|$)""").findAll(body).map { it.groupValues[1] }.distinct().toList()
    }

    /** ItemId.NAME -> id, from the server's ItemId file. */
    private val itemIdConsts = HashMap<String, Int>()
    private fun loadItemIds(server: File) {
        val f = findSource(server, "ItemId.java", "ItemId.kt") ?: return
        Regex("""(\w+)\s*=\s*(\d+)""").findAll(f.readText()).forEach { itemIdConsts[it.groupValues[1]] = it.groupValues[2].toInt() }
    }

    private val byConstName = HashMap<String, Int>()
    private fun constName(n: String) = n.toUpperCase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
    private fun idOf(expr: String): Int? {
        val e = expr.trim().removePrefix("ItemId.")
        e.toIntOrNull()?.let { return it }
        itemIdConsts[e]?.let { return it }
        if (byConstName.isEmpty()) items.values.sortedBy { it.id }.forEach { d -> if (d.name != "null" && !d.noted) byConstName.putIfAbsent(constName(d.name), d.id) }
        return byConstName[e]
    }
    /** How to write an item in a shop that uses names: the ItemId constant if there is one. */
    private fun constFor(id: Int): String? = itemIdConsts.entries.firstOrNull { it.value == id }?.key
        ?: items[id]?.takeIf { it.name != "null" }?.let { constName(it.name) }?.takeIf { byConstName[it] == id }

    // ---------------- items / icons ----------------

    private var items: Map<Int, ItemDef> = emptyMap()
    private val icons = HashMap<Int, Image?>()

    private fun icon(id: Int?): Image? {
        id ?: return null
        return icons.getOrPut(id) {
            try {
                val d = items[id] ?: return@getOrPut null
                val md = modelLoader.get(d.model) ?: return@getOrPut null
                val orig = md.faceColors
                val from = d.recolorFrom; val to = d.recolorTo
                if (orig != null && from != null && to != null) {
                    val copy = orig.copyOf()
                    for (i in copy.indices) for (j in from.indices) if (copy[i] == from[j]) copy[i] = to[j]
                    md.faceColors = copy
                }
                try { utils.ModelThumbs.render(md, 36.0, textureLoader) } finally { md.faceColors = orig }
            } catch (e: Exception) { null }
        }
    }

    private fun nameOf(expr: String) = idOf(expr)?.let { items[it]?.name } ?: expr.removePrefix("ItemId.")

    // ---------------- window ----------------

    private var server: File? = null
    private var current: ShopFile? = null
    private var currentShop: Shop? = null
    private val lblInfo = Label().apply { isWrapText = true; styleClass.add("subtle") }

    fun show() {
        try { val lib = CacheLibrary(CachePaths.cacheDir); items = ItemDefs.load(lib); lib.close() }
        catch (e: Exception) { log("Shop builder: couldn't read items: ${e.message}") }
        server = guessServerDir()

        val allFiles = FXCollections.observableArrayList<ShopFile>()
        val filteredFiles = FilteredList(allFiles)
        val fileList = ListView<ShopFile>(filteredFiles)
        val fileSearch = TextField().apply { promptText = "Search shops" }
        fileSearch.textProperty().addListener { _, _, q ->
            val t = q.trim().toLowerCase()
            filteredFiles.setPredicate { f -> t.isEmpty() || f.file.name.toLowerCase().contains(t) || f.shops.any { it.name.toLowerCase().contains(t) } }
        }
        val lblServer = Label().apply { isWrapText = true; styleClass.add("subtle") }

        val cmbCurrency = ComboBox<String>().apply { isEditable = true; prefWidth = 260.0 }
        val cmbPolicy = ComboBox<String>().apply { isEditable = true; prefWidth = 200.0 }

        fun reloadFiles() {
            val s = server
            lblServer.text = s?.path ?: "Pick your server folder (the one with gradlew)."
            currencies.clear(); policies.clear(); itemIdConsts.clear()
            if (s == null) { allFiles.clear(); return }
            loadItemIds(s)
            enumConstants(findSource(s, "ShopCurrency.java", "ShopCurrency.kt")).forEach { currencies.add("ShopCurrency.$it") }
            enumConstants(findSource(s, "ShopPolicy.java", "ShopPolicy.kt")).forEach { policies.add(it) }
            val bad = ArrayList<String>()
            categoryCurrency.clear()
            File(s, "$UNIV_DIR/UnivShopCategory.kt").takeIf { it.isFile }?.readLines()?.forEach { line ->
                Regex("""^\s*(\w+)\s*\((.*)\)\s*[,;]?\s*$""").find(line)?.let { m ->
                    val cur = Regex("""currency\s*=\s*([\w.]+)""").find(m.groupValues[2])?.groupValues?.get(1)
                    categoryCurrency[m.groupValues[1]] = cur ?: "ShopCurrency.COINS"
                    if (cur != null) currencies.add(cur)
                }
            }
            val home = File(s, "$UNIV_DIR/UnivShopTable.kt").takeIf { it.isFile }?.let { f ->
                try { parse(f) } catch (e: Throwable) { bad.add(f.name); null }
            }
            val files = listOfNotNull(home) + (shopsDir(s).listFiles { f -> f.extension == "kt" }?.sortedBy { it.name }?.mapNotNull { f ->
                try { parse(f) } catch (e: Throwable) { bad.add(f.name); println("Shop file ${f.name} skipped: $e"); null }
            } ?: emptyList())
            allFiles.setAll(files)
            cmbCurrency.items.setAll(currencies.sorted())
            cmbPolicy.items.setAll(policies.sorted())
            val shops = files.sumBy { it.shops.size }
            val its = files.sumBy { f -> f.shops.sumBy { s -> s.entries.count { it.isItem } } }
            lblInfo.text = "${files.size} files, $shops shops, $its items, ${currencies.size} currencies, ${itemIdConsts.size} item names from ItemId." +
                    if (bad.isNotEmpty()) "  Couldn't read: ${bad.joinToString(", ")}" else ""
        }
        val btnServer = Button("Server folder...").apply {
            setOnAction {
                val d = DirectoryChooser().apply { title = "Pick your server folder"; server?.let { initialDirectory = it } }
                    .showDialog(scene?.window) ?: return@setOnAction
                server = utils.ServerSource.fromPicked(configuration, d).first ?: d; reloadFiles()
            }
        }

        // --- shop header ---
        val shopPick = ComboBox<Shop>()
        val txtName = TextField(); val txtId = TextField().apply { prefColumnCount = 6 }

        // --- items table (with search) ---
        val tableItems = FXCollections.observableArrayList<Entry>()
        val filteredItems = FilteredList(tableItems)
        val table = TableView<Entry>(filteredItems)
        val itemSearch = TextField().apply { promptText = "Search this shop (name or id)" }
        itemSearch.textProperty().addListener { _, _, q ->
            val t = q.trim().toLowerCase()
            filteredItems.setPredicate { e -> t.isEmpty() || e.item.toLowerCase().contains(t) || nameOf(e.item).toLowerCase().contains(t) || idOf(e.item)?.toString() == t }
        }
        table.columns.addAll(
            TableColumn<Entry, Entry>("").apply {
                prefWidth = 44.0
                setCellValueFactory { javafx.beans.property.SimpleObjectProperty(it.value) }
                setCellFactory { object : TableCell<Entry, Entry>() {
                    override fun updateItem(item: Entry?, empty: Boolean) {
                        super.updateItem(item, empty)
                        graphic = if (empty || item == null) null else ImageView(icon(idOf(item.item))).apply { fitWidth = 32.0; fitHeight = 32.0; isPreserveRatio = true }
                    }
                } }
            },
            TableColumn<Entry, String>("Id").apply { prefWidth = 70.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(idOf(it.value.item)?.toString() ?: it.value.item) } },
            TableColumn<Entry, String>("Item").apply { prefWidth = 200.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(nameOf(it.value.item)) } },
            TableColumn<Entry, String>("Stock").apply { prefWidth = 70.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.stock) } },
            TableColumn<Entry, String>("Sell").apply { prefWidth = 70.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.sellPrice) } },
            TableColumn<Entry, String>("Price").apply { prefWidth = 90.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.buyPrice) } },
            TableColumn<Entry, String>("Ironman").apply { prefWidth = 70.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(if (it.value.ironman) "blocked" else "") } },
            TableColumn<Entry, String>("Other").apply { prefWidth = 180.0; setCellValueFactory { javafx.beans.property.SimpleStringProperty(it.value.others()) } }
        )

        // --- edit / add by id ---
        val txtItem = TextField().apply { promptText = "item id"; prefColumnCount = 7 }
        val txtPrice = TextField().apply { promptText = "price"; prefColumnCount = 8 }
        val chkIron = CheckBox("Ironmen can't buy")
        val txtStock = TextField().apply { promptText = "stock"; prefColumnCount = 5 }
        val txtSell = TextField().apply { promptText = "sell"; prefColumnCount = 5 }
        val preview = ImageView().apply { fitWidth = 32.0; fitHeight = 32.0; isPreserveRatio = true }
        val lblPreview = Label("Type an item id")
        txtItem.textProperty().addListener { _, _, v ->
            val id = idOf(v)
            preview.image = icon(id)
            lblPreview.text = when { id == null -> "Type an item id"; items[id] == null -> "No item $id in the cache"; else -> "${items[id]!!.name}  (value ${items[id]!!.cost})" }
        }
        table.selectionModel.selectedItemProperty().addListener { _, _, r ->
            if (r != null) { txtItem.text = idOf(r.item)?.toString() ?: r.item; txtPrice.text = r.buyPrice; chkIron.isSelected = r.ironman
                txtStock.text = r.stock; txtSell.text = r.sellPrice }
        }
        fun addItem(id: Int, price: String) {
            val shop = currentShop ?: run { lblInfo.text = "Pick a shop first."; return }
            val template = shop.entries.lastOrNull { it.isItem }
            val e = Entry(null)
            if (template != null) { e.indent = template.indent; e.call = template.call; e.suffix = template.suffix; e.direct = template.direct; e.inTable = template.inTable }
            if (shop.isTable) { e.inTable = true; e.call = "UnivShopItem"; e.suffix = ""; if (template == null) e.indent = shop.indent + "        " }
            e.item = if (template != null && template.direct && template.item.trim().toIntOrNull() == null && !template.item.startsWith("ItemId."))
                constFor(id) ?: id.toString() else id.toString()
            val p = price.ifBlank { (items[id]?.cost ?: 1).coerceAtLeast(1).toString() }
            if (e.direct) {
                e.positional.addAll(listOf(txtStock.text.trim().ifBlank { template?.stock?.ifBlank { null } ?: "100" }, txtSell.text.trim().ifBlank { "0" }, p))
                if (e.item.toIntOrNull() != null) items[id]?.name?.let { e.comment = "// " + it.toLowerCase() }
            } else e.args["buyPrice"] = p
            if (chkIron.isSelected) e.args["ironmanRestricted"] = "true"
            // new items go after the last item line, before any trailing raw lines
            val at = shop.entries.indexOfLast { it.isItem } + 1
            shop.entries.add(at, e)
            tableItems.setAll(shop.entries.filter { it.isItem })
            table.selectionModel.select(e); table.scrollTo(e)
            shopPick.items.setAll(shopPick.items.toList())
            lblInfo.text = "Added ${items[id]?.name ?: "item"} ($id). Save to keep it."
        }
        val btnAddId = Button("Add by id").apply {
            styleClass.add("accent")
            setOnAction {
                val id = idOf(txtItem.text) ?: run { lblInfo.text = "Type an item id first."; return@setOnAction }
                addItem(id, txtPrice.text.trim())
            }
        }
        val btnUpdate = Button("Update selected").apply {
            setOnAction {
                val r = table.selectionModel.selectedItem ?: run { lblInfo.text = "Select an item in the list first."; return@setOnAction }
                txtItem.text.trim().takeIf { it.isNotEmpty() && it != (idOf(r.item)?.toString() ?: r.item) }?.let { t ->
                    val nameStyle = r.item.trim().toIntOrNull() == null && !r.item.startsWith("ItemId.")
                    r.item = if (nameStyle) t.toIntOrNull()?.let { constFor(it) } ?: t else t
                    r.dirty = true
                }
                r.buyPrice = txtPrice.text.trim()
                r.stock = txtStock.text.trim()
                r.sellPrice = txtSell.text.trim()
                r.ironman = chkIron.isSelected
                if (r.direct && r.dirty && r.item.toIntOrNull() != null) idOf(r.item)?.let { items[it]?.name }?.let { r.comment = "// " + it.toLowerCase() }
                table.refresh()
            }
        }
        val btnRemove = Button("Remove").apply {
            setOnAction {
                val r = table.selectionModel.selectedItem ?: return@setOnAction
                currentShop?.entries?.remove(r); tableItems.remove(r)
            }
        }
        fun move(dir: Int) {
            val shop = currentShop ?: return
            val r = table.selectionModel.selectedItem ?: return
            val itemsOnly = shop.entries.filter { it.isItem }
            val k = itemsOnly.indexOf(r); val other = itemsOnly.getOrNull(k + dir) ?: return
            val a = shop.entries.indexOf(r); val b = shop.entries.indexOf(other)
            shop.entries[a] = other; shop.entries[b] = r
            tableItems.setAll(shop.entries.filter { it.isItem }); table.selectionModel.select(r)
        }
        val btnUp = Button("\u25B2").apply { setOnAction { move(-1) } }
        val btnDown = Button("\u25BC").apply { setOnAction { move(1) } }

        // --- item search (right) ---
        val search = TextField().apply { promptText = "Search all items by name or id" }
        val results = ListView<ItemDef>()
        results.setCellFactory {
            object : ListCell<ItemDef>() {
                override fun updateItem(item: ItemDef?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) { text = null; graphic = null; return }
                    text = "${item.id}  ${item.name}"
                    graphic = ImageView(icon(item.id)).apply { fitWidth = 28.0; fitHeight = 28.0; isPreserveRatio = true }
                }
            }
        }
        val named = items.values.filter { it.name != "null" }.sortedBy { it.id }
        search.textProperty().addListener { _, _, q ->
            val t = q.trim().toLowerCase()
            results.items.setAll(if (t.isEmpty()) emptyList() else named.filter { it.name.toLowerCase().contains(t) || it.id.toString() == t }.take(300))
        }
        results.setOnMouseClicked { if (it.clickCount == 2) results.selectionModel.selectedItem?.let { d -> addItem(d.id, "") } }
        val btnAdd = Button("Add to shop").apply { setOnAction { results.selectionModel.selectedItem?.let { d -> addItem(d.id, "") } } }

        // --- open / header ---
        fun applyHeader() {
            val s = currentShop ?: return
            s.name = txtName.text.trim()
            if (txtId.text.isNotBlank() && s.idIdx >= 0) s.id = txtId.text.trim()
            (cmbCurrency.editor.text.ifBlank { cmbCurrency.value ?: "" }).takeIf { it.isNotBlank() }?.let { s.currency = it }
            (cmbPolicy.editor.text.ifBlank { cmbPolicy.value ?: "" }).takeIf { it.isNotBlank() }?.let { s.policy = it }
        }
        fun showShop(s: Shop?) {
            currentShop = s
            tableItems.setAll(s?.entries?.filter { it.isItem } ?: emptyList())
            itemSearch.clear()
            txtName.text = s?.name ?: ""; txtId.text = s?.id ?: ""; txtId.isDisable = s == null || s.idIdx < 0
            txtId.promptText = if (s != null && s.idIdx < 0) "this shop has no id" else ""
            cmbCurrency.value = s?.currency; cmbCurrency.editor.text = s?.currency ?: ""
            cmbCurrency.isDisable = s?.isTable == true; cmbPolicy.isDisable = s?.isTable == true
            if (s?.isTable == true) lblInfo.text = "Home shop tab \"${s.name}\": paid in ${s.currency.removePrefix("ShopCurrency.")}. This is what players see in the shop at home."
            cmbPolicy.value = s?.policy; cmbPolicy.editor.text = s?.policy ?: ""
        }
        fileList.selectionModel.selectedItemProperty().addListener { _, _, f ->
            current = f
            shopPick.items.setAll(f?.shops ?: emptyList())
            if (f != null && f.shops.isNotEmpty()) shopPick.selectionModel.selectFirst() else showShop(null)
            if (f != null && f.shops.isEmpty()) lblInfo.text = "${f.file.name}: no shop found in this file (it may be set up a different way)."
        }
        shopPick.selectionModel.selectedItemProperty().addListener { _, old, s -> if (old != null && old === currentShop) applyHeader(); if (s != null) showShop(s) }

        val btnNew = Button("New shop").apply {
            setOnAction {
                val dir = server?.let { shopsDir(it) } ?: run { lblInfo.text = "Pick the server folder first."; return@setOnAction }
                val name = TextInputDialog("My Shop").apply { headerText = "Shop name (shown in game)"; title = "New shop" }.showAndWait().orElse(null) ?: return@setOnAction
                val cls = name.replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "Custom" } + "Shop"
                val sf = ShopFile(File(dir, "$cls.kt"), ArrayList()).apply { isNew = true }
                val nextId = (allFiles.flatMap { f -> f.shops.mapNotNull { it.id.trim().toIntOrNull() } }.maxOrNull() ?: 1000) + 1
                sf.shops.add(Shop(name).apply {
                    headerArgs.addAll(listOf(nextId.toString(), "ShopCurrency.COINS", "STOCK_ONLY")); idIdx = 0; curIdx = 1; polIdx = 2
                })
                allFiles.add(sf); fileList.selectionModel.select(sf)
                lblInfo.text = "New shop \"$name\" (id $nextId). Add items, pick the currency, then Save."
            }
        }
        val btnSave = Button("Save shop file").apply {
            styleClass.add("accent")
            setOnAction {
                val sf = current ?: return@setOnAction
                applyHeader()
                try {
                    sf.file.parentFile.mkdirs()
                    if (sf.file.exists()) sf.file.copyTo(File(sf.file.path + ".bak"), true)
                    val text = render(sf)
                    sf.file.writeText(text)
                    utils.Deployer.markShopEdited(sf.file.absolutePath)
                    // re-read so line positions match the new file
                    val fresh = parse(sf.file)
                    val idx = allFiles.indexOf(sf); if (idx >= 0) allFiles[idx] = fresh
                    fileList.selectionModel.select(fresh)
                    lblInfo.text = "Saved ${sf.file.name} (backup: ${sf.file.name}.bak). Rebuild + restart the server to see it in game."
                    log("Shop saved: ${sf.file.path}")
                } catch (e: Exception) { lblInfo.text = "Save failed: ${e.message}" }
            }
        }
        val btnCopy = Button("Copy code").apply {
            setOnAction { current?.let { applyHeader(); Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(render(it)) }); lblInfo.text = "Copied." } }
        }

        // --- layout ---
        val header = GridPane().apply {
            hgap = 6.0; vgap = 6.0
            addRow(0, Label("Shop"), shopPick, Label("Id"), txtId)
            addRow(1, Label("Name"), txtName)
            addRow(2, Label("Currency"), cmbCurrency, Label("Policy"), cmbPolicy)
            GridPane.setColumnSpan(txtName, 3)
        }
        val addRow = HBox(6.0, preview, Label("Item id"), txtItem, Label("Stock"), txtStock, Label("Sell"), txtSell, Label("Price"), txtPrice, chkIron, btnAddId, btnUpdate).apply { alignment = Pos.CENTER_LEFT }
        val center = VBox(8.0, header, itemSearch, table, HBox(6.0, lblPreview).apply { alignment = Pos.CENTER_LEFT }, addRow,
            HBox(6.0, btnUp, btnDown, btnRemove, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, btnCopy, btnSave), lblInfo)
            .apply { padding = Insets(8.0); VBox.setVgrow(table, Priority.ALWAYS) }
        val left = VBox(6.0, btnServer, lblServer, fileSearch, fileList, btnNew).apply { padding = Insets(8.0); prefWidth = 260.0; VBox.setVgrow(fileList, Priority.ALWAYS) }
        val right = VBox(6.0, Label("All items"), search, results, btnAdd).apply { padding = Insets(8.0); prefWidth = 280.0; VBox.setVgrow(results, Priority.ALWAYS) }
        val stage = Stage()
        stage.scene = Scene(BorderPane(center, null, right, null, left), 1400.0, 760.0)
        utils.Branding.apply(stage, "shops")
        stage.show()
        reloadFiles()
    }
}
