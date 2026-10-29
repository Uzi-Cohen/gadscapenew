package utils

import com.displee.cache.CacheLibrary
import utils.gambling.If3
import utils.gambling.If3.FONT_BOLD
import utils.gambling.If3.FONT_REGULAR
import utils.gambling.If3.FONT_SMALL
import java.io.ByteArrayOutputStream

/**
 * The staff drop party chest and the YouTuber giveaway screens (server: com.gadscape.game.content.giveaway.PartyGiveawayUi
 * has the ids below, word for word):
 *
 * - 5240 the staff copy of the Party Drop Chest (interface 265, build 241): 265's components 0-18 as they are (its
 *   scripts get 5240's components instead of 265's), the 1 / 5 / X / All buttons moved up and a red Party button (19-29)
 *   under All. ::dropparty.
 * - 5241 YouTuber Giveaway: approved prizes (15 a page), the winner, the prize and Give prize, the last 3 giveaways. ::giveaway.
 * - 5242 Confirm giveaway: who gets what, Confirm / Cancel.
 *
 * Only vanilla graphics and scripts: the steel border is the client's own (script 227, as 265 uses it), buttons are the
 * 1141-1149 / 1150-1158 and 921-928 frames, hover graphics none. No sprites are added.
 *
 * Idempotent like GamblingPatch: an interface is written only when what the cache has differs.
 *
 *     java -cp <editor fat jar> utils.PartyGiveawayPatch <cache folder>
 */
object PartyGiveawayPatch {
    private const val INTERFACES = 3

    const val PARTY_CHEST = 265
    const val DROP_PARTY_CHEST = 5240
    const val GIVEAWAY = 5241
    const val GIVEAWAY_CONFIRM = 5242

    /** Build 241's interface 265 has components 0..18. */
    private const val CHEST_COMPONENTS = 19
    /** The 1 / 5 / X / All buttons (265 components 7-10) and their new y (centred in component 6): room for Party under All. */
    private val BUTTON_Y = mapOf(7 to -84, 8 to -44, 9 to -4, 10 to 36)
    const val PARTY_Y = 76

    private const val ORANGE = 0xFF981F
    private const val WHITE = 0xFFFFFF
    private const val GREY = 0x9F9F9F
    private const val LIGHT = 0xC1C1C1
    private const val YELLOW = 0xFFFF00
    private const val RED = 0xFF0000
    private const val GREEN = 0x00FF00
    private const val BOX = 0x5A5245
    /** Script 2067's button fill (dark, see-through) as the Party Drop Chest's Confirm uses it. */
    private const val BUTTON_FILL = 0x30201C
    private const val BUTTON_FILL_OPACITY = 200

    /** Vanilla script 227: steel border, title, close button (script 228 draws it: 297 background, 310-315 / 172 / 173, 535 / 536). */
    private const val STEELBORDER = 227

    /** Sprites of the vanilla nine-part button frames: top left, top, top right, left, middle, right, bottom left, bottom, bottom right. */
    val BUTTON = (1141..1149).toList()
    val BUTTON_SELECTED = (1150..1158).toList()
    /** 921-928: corners top left, top right, bottom left, bottom right, then the left, top, right and bottom edges. */
    private const val BORDER = 921

    const val PRIZE_SLOTS = 15
    private const val PRIZE_COLUMNS = 5
    /** Each prize tile: its layer, the 9 frame parts, the item, the amount. */
    const val TILE_STRIDE = 12

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 1 && args[0] == "--server-ids") {
            println(serverIds())
            return
        }
        if (args.size != 1) {
            println("usage: java -cp <editor jar> utils.PartyGiveawayPatch <build 241 cache folder>")
            kotlin.system.exitProcess(2)
        }
        val lib = CacheLibrary.create(args[0])
        try {
            println(apply(lib) ?: "Nothing to add: the cache already has the drop party and giveaway screens.")
        } finally {
            lib.close()
        }
    }

    /** Adds or updates the three interfaces in [lib] (a build 241 cache). What was done, or null when nothing was. */
    fun apply(lib: CacheLibrary): String? {
        val done = ArrayList<String>()
        val notes = ArrayList<String>()
        val chest = dropPartyChest(lib)
        if (chest == null) notes.add("the staff chest wasn't added: interface $PARTY_CHEST isn't build 241's (19 components)")
        else if (write(lib, DROP_PARTY_CHEST, chest)) done.add("staff drop party chest ($DROP_PARTY_CHEST)")
        for (b in listOf(giveaway(), confirm())) {
            if (write(lib, b.interfaceId, b.encode())) done.add(if (b.interfaceId == GIVEAWAY) "giveaway ($GIVEAWAY)" else "giveaway confirm ($GIVEAWAY_CONFIRM)")
        }
        if (done.isNotEmpty()) lib.index(INTERFACES).update()
        if (done.isEmpty() && notes.isEmpty()) return null
        val sb = StringBuilder()
        if (done.isNotEmpty()) sb.append("Party / giveaway: added or updated ").append(done.joinToString()).append('.')
        if (notes.isNotEmpty()) sb.append(if (sb.isEmpty()) "" else " ").append("Party / giveaway: ").append(notes.joinToString()).append('.')
        return sb.toString()
    }

    /** Writes [comps] as interface [id] unless the cache already has exactly them; true when it wrote. */
    private fun write(lib: CacheLibrary, id: Int, comps: List<ByteArray>): Boolean {
        val archive = try { lib.index(INTERFACES).archive(id) } catch (e: Exception) { null }
        val existing = archive?.let { a -> a.fileIds().associateWith { a.file(it)?.data } }
        if (existing != null && existing.size == comps.size && comps.indices.all { existing[it]?.contentEquals(comps[it]) == true }) return false
        if (existing != null) lib.remove(INTERFACES, id)
        for ((i, c) in comps.withIndex()) lib.put(INTERFACES, id, i, c)
        return true
    }

    // ------------------------------------------------------------------------------------------------ staff chest

    /** Component ids of 5240 (the server names them in PartyGiveawayUi.DropPartyChest). */
    object Chest {
        const val ONE = 7
        const val FIVE = 8
        const val X = 9
        const val ALL = 10
        const val CHEST = 11
        const val OFFER = 16
        const val CONFIRM = 17
        const val PARTY = 19
    }

    /** 265 copied as 5240 with the Party button, or null when 265 isn't the layout this was made for. */
    private fun dropPartyChest(lib: CacheLibrary): List<ByteArray>? {
        val archive = try { lib.index(INTERFACES).archive(PARTY_CHEST) } catch (e: Exception) { null } ?: return null
        val ids = archive.fileIds().sorted()
        if (ids != (0 until CHEST_COMPONENTS).toList()) return null
        val out = ArrayList<ByteArray>()
        for (id in ids) {
            val raw = archive.file(id)?.data?.let { RawComp.parse(it) } ?: return null
            raw.remap(PARTY_CHEST, DROP_PARTY_CHEST)
            if (id == 1) raw.replaceString("Party Drop Chest", "Staff Party Chest")
            BUTTON_Y[id]?.let { raw.setY(it) }
            out.add(raw.encode())
        }
        // the Party button: a layer in the buttons' column (component 6) like 1 / 5 / X / All, drawn with the same frame
        val b = If3.Builder(DROP_PARTY_CHEST)
        repeat(CHEST_COMPONENTS) { b.comps.add(If3.Comp(it, If3.LAYER, -1)) } // placeholders: ids line up with 265's
        val party = b.layer(6, 0, PARTY_Y, 0, 30, "party") { widthMode = 1; xMode = 1; yMode = 1; ops.add("Party") }
        b.nine(party, 30, 30, BUTTON)
        b.text(party, 0, 0, 30, 30, "Party", FONT_SMALL, RED, xAlign = 1, yAlign = 1)
        check(party == Chest.PARTY)
        for (c in b.comps.drop(CHEST_COMPONENTS)) out.add(c.encode())
        return out
    }

    /**
     * An IF3 component as bytes up to its hooks, its 18 hooks, then the rest (triggers): enough to point its script
     * arguments at another interface and move it.
     */
    private class RawComp(val head: ByteArray, val hooks: MutableList<MutableList<Any>?>, val tail: ByteArray) {
        fun remap(from: Int, to: Int) {
            for (h in hooks) h?.replaceAll { a -> if (a is Int && a ushr 16 == from) (to shl 16) or (a and 0xFFFF) else a }
        }

        fun replaceString(from: String, to: String) {
            for (h in hooks) h?.replaceAll { a -> if (a == from) to else a }
        }

        fun setY(y: Int) {
            head[6] = (y shr 8).toByte()
            head[7] = y.toByte()
        }

        fun encode(): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(head)
            for (h in hooks) {
                if (h == null) { o.write(0); continue }
                o.write(h.size)
                for (a in h) {
                    if (a is Int) { o.write(0); o.write(a ushr 24); o.write(a shr 16); o.write(a shr 8); o.write(a) }
                    else { o.write(1); o.write((a as String).toByteArray(Charsets.ISO_8859_1)); o.write(0) }
                }
            }
            o.write(tail)
            return o.toByteArray()
        }

        companion object {
            private const val HOOKS = 18

            /** Null for an IF1 component or a type this doesn't know. */
            fun parse(b: ByteArray): RawComp? {
                if (b.isEmpty() || b[0].toInt() and 0xFF != 255) return null
                var p = 19 // 255, type, content type, x, y, width, height, the 4 modes, parent, hidden
                fun u8() = b[p++].toInt() and 0xFF
                fun str(): String {
                    val start = p
                    while (b[p].toInt() != 0) p++
                    return String(b, start, p++ - start, Charsets.ISO_8859_1)
                }
                val type = b[1].toInt() and 0xFF
                val wm = b[12].toInt()
                val hm = b[13].toInt()
                when (type) {
                    0 -> p += 5
                    3, 9 -> p += 6
                    4 -> { p += 2; str(); p += 8 }
                    5 -> p += 15
                    6 -> { p += 21; if (wm != 0) p += 2; if (hm != 0) p += 2 }
                    else -> return null
                }
                p += 3 // click mask
                str() // op base
                repeat(u8()) { str() }
                p += 3 // drag dead zone, dead time, render
                str() // target verb
                val head = b.copyOfRange(0, p)
                val hooks = ArrayList<MutableList<Any>?>()
                repeat(HOOKS) {
                    val n = u8()
                    if (n == 0) { hooks.add(null); return@repeat }
                    val args = ArrayList<Any>(n)
                    repeat(n) {
                        if (u8() == 0) {
                            args.add(((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF))
                            p += 4
                        } else args.add(str())
                    }
                    hooks.add(args)
                }
                return RawComp(head, hooks, b.copyOfRange(p, b.size))
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ shared parts

    /** A graphic of any vanilla sprite (If3.Builder.graphic takes the gambling sprites only). */
    private fun If3.Builder.sprite(parent: Int, x: Int, y: Int, w: Int, h: Int, id: Int, tiled: Boolean = false): Int {
        val c = If3.Comp(comps.size, If3.GRAPHIC, parent)
        c.x = x; c.y = y; c.width = w; c.height = h; c.sprite = id; c.tiling = tiled
        comps.add(c)
        return c.id
    }

    /** A nine-part frame filling a [w] x [h] parent: 6 px corners and edges ([parts] in the 1141-1149 order). */
    private fun If3.Builder.nine(parent: Int, w: Int, h: Int, parts: List<Int>) {
        sprite(parent, 0, 0, 6, 6, parts[0])
        sprite(parent, 6, 0, w - 12, 6, parts[1], true)
        sprite(parent, w - 6, 0, 6, 6, parts[2])
        sprite(parent, 0, 6, 6, h - 12, parts[3], true)
        sprite(parent, 6, 6, w - 12, h - 12, parts[4], true)
        sprite(parent, w - 6, 6, 6, h - 12, parts[5], true)
        sprite(parent, 0, h - 6, 6, 6, parts[6])
        sprite(parent, 6, h - 6, w - 12, 6, parts[7], true)
        sprite(parent, w - 6, h - 6, 6, 6, parts[8])
    }

    /** A big button (script 2067's look): dark see-through fill inset 2, the 921-928 border, the label. Returns its layer. */
    private fun If3.Builder.bigButton(parent: Int, x: Int, y: Int, w: Int, h: Int, label: String, color: Int, op: String, name: String): Int {
        val layer = layer(parent, x, y, w, h, name) { ops.add(op) }
        rect(layer, 2, 2, w - 4, h - 4, BUTTON_FILL, BUTTON_FILL_OPACITY)
        sprite(layer, 0, 0, 9, 9, BORDER)
        sprite(layer, w - 9, 0, 9, 9, BORDER + 1)
        sprite(layer, 0, h - 9, 9, 9, BORDER + 2)
        sprite(layer, w - 9, h - 9, 9, 9, BORDER + 3)
        sprite(layer, 0, 9, 9, h - 18, BORDER + 4, true)
        sprite(layer, 9, 0, w - 18, 9, BORDER + 5, true)
        sprite(layer, w - 9, 9, 9, h - 18, BORDER + 6, true)
        sprite(layer, 9, h - 9, w - 18, 9, BORDER + 7, true)
        text(layer, 0, 0, w, h, label, FONT_BOLD, color, xAlign = 1, yAlign = 1)
        return layer
    }

    /** A main screen's root (centred) and its steel border with [title]. */
    private fun If3.Builder.frame(w: Int, h: Int, title: String): Int {
        val root = layer(-1, 0, 0, w, h, "root") { xMode = 1; yMode = 1 }
        layer(root, 0, 0, 0, 0, "frame") {
            widthMode = 1; heightMode = 1
            hooks["onLoad"] = listOf(STEELBORDER, If3.EVENT_COMPONENT, title)
        }
        return root
    }

    private fun If3.Builder.outline(parent: Int, x: Int, y: Int, w: Int, h: Int) = rect(parent, x, y, w, h, BOX, 0) { filled = false }

    // ------------------------------------------------------------------------------------------------ giveaway

    const val GIVEAWAY_W = 488
    const val GIVEAWAY_H = 300

    /**
     * 5241. Left: the approved prizes, [PRIZE_SLOTS] tiles a page (hidden until the server fills them; Select). Right:
     * the winner (Set winner asks for a name), online / offline, the prize and Give prize. Bottom: the last 3 giveaways.
     */
    fun giveaway() = If3.Builder(GIVEAWAY).apply {
        val root = frame(GIVEAWAY_W, GIVEAWAY_H, "YouTuber Giveaway")
        // dividers: the steel edge sprites, as 265 draws its own (edge strip 15 px into the 36 px frame)
        sprite(root, 232, 30, 36, 166, 172, true)
        sprite(root, 6, 196, GIVEAWAY_W - 12, 36, 314, true)
        text(root, 12, 34, 230, 18, "Approved prizes", FONT_BOLD, ORANGE, xAlign = 1)
        text(root, 256, 34, 220, 18, "Give to winner", FONT_BOLD, ORANGE, xAlign = 1)
        for (i in 0 until PRIZE_SLOTS) {
            val col = i % PRIZE_COLUMNS
            val row = i / PRIZE_COLUMNS
            val tile = layer(root, 12 + col * 46, 54 + row * 44, 44, 42, "tile$i") { hidden = true; ops.add("Select") }
            nine(tile, 44, 42, BUTTON)
            model(tile, 4, 5, 36, 32)
            text(tile, 3, 2, 40, 12, "", FONT_SMALL, YELLOW, yAlign = 0)
        }
        text(root, 12, 188, 50, 16, "< Prev", FONT_SMALL, ORANGE, name = "prev") { ops.add("Previous page") }
        text(root, 62, 188, 128, 16, "", FONT_SMALL, ORANGE, xAlign = 1, name = "page")
        text(root, 190, 188, 50, 16, "Next >", FONT_SMALL, ORANGE, xAlign = 2, name = "next") { ops.add("Next page") }

        text(root, 262, 56, 52, 20, "Winner:", FONT_REGULAR, ORANGE)
        val box = layer(root, 314, 56, 158, 20, "winner_box") { ops.add("Set winner") }
        rect(box, 0, 0, 158, 20, 0, 120)
        outline(box, 0, 0, 158, 20)
        text(box, 4, 0, 150, 20, "Click to enter a name", FONT_REGULAR, GREY, name = "winner_name")
        text(root, 262, 80, 210, 14, "", FONT_SMALL, GREY, name = "winner_status")
        text(root, 262, 98, 60, 16, "Prize:", FONT_REGULAR, ORANGE)
        outline(root, 262, 116, 44, 40)
        model(root, 266, 120, 36, 32, "prize_item")
        text(root, 312, 116, 160, 18, "No prize selected", FONT_REGULAR, WHITE, name = "prize_name")
        text(root, 312, 134, 160, 24, "", FONT_SMALL, ORANGE, name = "prize_info")
        bigButton(root, 274, 162, 186, 32, "Give prize", ORANGE, "Give prize", "give")

        text(root, 14, 218, 460, 16, "Recent giveaways", FONT_BOLD, ORANGE)
        for (i in 0 until 3) text(root, 16, 236 + i * 16, 456, 16, "", FONT_REGULAR, LIGHT, name = "recent$i")
    }

    // ------------------------------------------------------------------------------------------------ confirm

    const val CONFIRM_W = 300
    const val CONFIRM_H = 194

    /** 5242: "Give this prize to <name>?", the item and its name, the warning, Confirm / Cancel. */
    fun confirm() = If3.Builder(GIVEAWAY_CONFIRM).apply {
        val root = frame(CONFIRM_W, CONFIRM_H, "Confirm giveaway")
        text(root, 10, 36, CONFIRM_W - 20, 18, "", FONT_REGULAR, ORANGE, xAlign = 1, name = "question")
        outline(root, (CONFIRM_W - 44) / 2, 58, 44, 40)
        model(root, (CONFIRM_W - 36) / 2, 62, 36, 32, "item")
        text(root, 10, 102, CONFIRM_W - 20, 18, "", FONT_REGULAR, WHITE, xAlign = 1, name = "item_name")
        text(root, 10, 120, CONFIRM_W - 20, 28, "This cannot be undone. Staff can see every giveaway.", FONT_SMALL, GREY, xAlign = 1)
        bigButton(root, 40, 152, 100, 30, "Confirm", GREEN, "Confirm", "confirm")
        bigButton(root, 160, 152, 100, 30, "Cancel", RED, "Cancel", "cancel")
    }

    /** The named component ids, for the server's PartyGiveawayUi. */
    fun serverIds(): String {
        val sb = StringBuilder()
        sb.append("DropPartyChest ID=$DROP_PARTY_CHEST ONE=${Chest.ONE} FIVE=${Chest.FIVE} X=${Chest.X} ALL=${Chest.ALL} CHEST=${Chest.CHEST} ")
        sb.append("OFFER=${Chest.OFFER} CONFIRM=${Chest.CONFIRM} PARTY=${Chest.PARTY}\n")
        for (b in listOf(giveaway(), confirm())) {
            sb.append(if (b.interfaceId == GIVEAWAY) "Giveaway" else "GiveawayConfirm").append(" ID=").append(b.interfaceId)
            for ((name, id) in b.names) sb.append(' ').append(name.uppercase()).append('=').append(id)
            sb.append('\n')
        }
        return sb.toString()
    }
}
