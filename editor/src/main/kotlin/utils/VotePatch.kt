package utils

import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary
import utils.gambling.If3
import utils.gambling.If3.FONT_BOLD
import utils.gambling.If3.FONT_REGULAR
import utils.gambling.If3.FONT_SMALL

/**
 * GadScape voting, written into a build 241 cache (server: com.gadscape.game.model.ui.vote.VoteUi has the ids below,
 * word for word; the item ids are com.gadscape.game.item.CustomItemId's):
 *
 * - 5260 GadScape Vote (::vote, 470x500): Refresh at the top left; six site rows (name, points and status, a
 *   Vote / Claim / Voted button the server switches); the perk panels in a scrolling box (2 columns, 208x58 each:
 *   item, title, two lines, status line); the footer with the player's vote total. Only vanilla graphics and scripts:
 *   the steel border is script 227 (as the Party Drop Chest uses it), the boxes are the 921-928 frame, the scrollbar is
 *   script 63 (proc 31) with the steel scrollbar graphics script 648 gives it.
 * - Vote ticket (50020, stackable, tradeable): the Castle wars ticket (4067) recoloured: faces of saturation >= 4 gold
 *   (hue of 0xe8c020, saturation 6, lightness x 1.2 + 10), the rest blue (hue of 0x1f5fd8, saturation 6, lightness x 0.9).
 * - Vote mystery box (50021, tradeable, Open): the Mystery box (6199) recoloured by hue (its faces are all saturation 7):
 *   hue <= 2 (the box) blue, the rest (the ribbon) gold, with the same blue and gold as the ticket.
 *
 * Idempotent: an interface or item is written only when the cache holds something else, so running it again changes
 * nothing. Close the server, client and editor that use the cache first.
 *
 *     java -cp <editor fat jar> utils.VotePatch <cache folder>
 *     java -cp <editor fat jar> utils.VotePatch --server-ids
 */
object VotePatch {
    private const val CONFIGS = 2
    private const val ITEMS = 10
    private const val INTERFACES = 3
    private const val MODELS = 7

    const val VOTE_SCREEN = 5260

    const val VOTE_TICKET = 50020
    const val VOTE_MYSTERY_BOX = 50021
    private const val CASTLE_WARS_TICKET = 4067
    private const val MYSTERY_BOX = 6199

    private const val GOLD = 0xe8c020
    private const val BLUE = 0x1f5fd8

    const val W = 470
    const val H = 500

    private const val ORANGE = 0xFF981F
    private const val WHITE = 0xFFFFFF
    private const val GREEN = 0x00FF00
    private const val OUTER = 0x0E0E0C
    private const val INNER = 0x474745
    /** Script 2067's button fill (dark, see-through), as PartyGiveawayPatch's buttons use it. */
    private const val BOX_FILL = 0x30201C
    private const val BOX_FILL_OPACITY = 200

    /** Vanilla script 227: steel border, title, close button. */
    private const val STEELBORDER = 227
    /** Vanilla script 63: (scrollbar, content, 6 graphics) -> proc 31, the vertical scrollbar. */
    private const val SCROLLBAR = 63
    /** The steel scrollbar graphics (script 648 hands them to proc 31). */
    private val SCROLLBAR_GRAPHICS = listOf(792, 789, 790, 791, 773, 788)
    /** 921-928: corners top left, top right, bottom left, bottom right, then the left, top, right and bottom edges. */
    private const val BORDER = 921

    /** The sites in row order (the server's VoteSite order) and their default points. */
    private val SITES = listOf("RuneLocus" to 5, "RSPS-List" to 4, "TopG" to 2, "Top100Arena" to 2, "RuneList" to 2, "MoparScape" to 2)

    /** The perk panels in order (the server fills the item, the status line and may change any text). */
    private val PERKS = listOf(
        Triple("Vote Mystery Box", "A vote mystery box every", "3 votes you claim."),
        Triple("Vote Lottery (weekly)", "Every vote is an entry.", "Drawn every Sunday."),
        Triple("Skilling Boost", "+1% skilling XP per day", "of streak, up to 50%."),
        Triple("Vote Boss: Xamphur", "Spawns every 50 votes.", "Voters of the last 12h fight."),
        Triple("+20% PvM Drop Rate", "20 minutes per vote,", "stacks with every vote."),
        Triple("AFK-Skilling Zone", "6h per vote, +3h per", "donator rank (max 24h)."),
    )

    /** Component ids of 5260, as the server's VoteUi has them; [screen] checks its layout against them. */
    object Ids {
        const val REFRESH = 2
        const val ROWS = 6
        const val ROW_FIRST = 3
        const val ROW_STRIDE = 36
        // offsets in a row
        const val NAME_BOX = 0
        const val NAME = 10
        const val POINTS_BOX = 11
        const val POINTS = 21
        const val STATUS = 22
        const val BUTTON = 23
        const val BUTTON_LABEL = 33
        const val BUTTON_VOTE = 34
        const val BUTTON_CLAIM = 35
        const val PERKS_VIEW = ROW_FIRST + ROWS * ROW_STRIDE // 219
        const val PERKS_SCROLLBAR = PERKS_VIEW + 1
        const val PERKS = 6
        const val PERK_FIRST = PERKS_SCROLLBAR + 1 // 221
        const val PERK_STRIDE = 8
        // offsets in a perk panel
        const val PERK_ITEM = 3
        const val PERK_TITLE = 4
        const val PERK_LINE_1 = 5
        const val PERK_LINE_2 = 6
        const val PERK_STATUS = 7
        const val FOOTER = PERK_FIRST + PERKS * PERK_STRIDE // 269
    }

    private const val ROW_Y = 40
    private const val ROW_STEP = 34
    private const val PERKS_X = 10
    private const val PERKS_Y = 250
    private const val PERKS_W = 428
    private const val PERKS_H = 214
    private const val PERK_W = 208
    private const val PERK_H = 58
    private const val PERK_STEP_X = 214
    private const val PERK_STEP_Y = 62

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size == 1 && args[0] == "--server-ids") {
            println(serverIds())
            return
        }
        if (args.size != 1) {
            println("usage: java -cp <editor jar> utils.VotePatch <build 241 cache folder>")
            kotlin.system.exitProcess(2)
        }
        val lib = CacheLibrary.create(args[0])
        try {
            println(apply(lib) ?: "Nothing to add: the cache already has the vote screen and vote items.")
        } finally {
            lib.close()
        }
    }

    /** Adds or updates the vote screen and the vote items in [lib] (a build 241 cache). What was done, or null. */
    fun apply(lib: CacheLibrary): String? {
        val done = ArrayList<String>()
        if (write(lib, VOTE_SCREEN, encode(screen()))) {
            lib.index(INTERFACES).update()
            done.add("vote screen ($VOTE_SCREEN)")
        }
        val items = writeItems(lib)
        if (items.isNotEmpty()) done.add("item(s) ${items.joinToString(", ")}")
        if (done.isEmpty()) return null
        return "Vote: added or updated ${done.joinToString()}."
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

    // ------------------------------------------------------------------------------------------------ the screen

    /** 5260: see the class comment. Component order is fixed by [Ids]; the checks below keep it so. */
    fun screen(): If3.Builder {
        val b = If3.Builder(VOTE_SCREEN)
        val root = b.layer(-1, 0, 0, W, H, "root") { xMode = 1; yMode = 1 }
        b.layer(root, 0, 0, 0, 0, "frame") {
            widthMode = 1; heightMode = 1
            hooks["onLoad"] = listOf(STEELBORDER, If3.EVENT_COMPONENT, "GadScape Vote")
        }
        val refresh = b.text(root, 12, 8, 50, 16, "Refresh", FONT_SMALL, ORANGE, name = "refresh") { ops.add("Refresh") }
        check(refresh == Ids.REFRESH)

        for ((k, site) in SITES.withIndex()) {
            val y = ROW_Y + k * ROW_STEP
            val base = Ids.ROW_FIRST + k * Ids.ROW_STRIDE
            val nameBox = b.box(root, 10, y, 140, 30, "name_box$k")
            check(nameBox == base + Ids.NAME_BOX)
            val name = b.text(nameBox, 0, 0, 140, 30, site.first, FONT_BOLD, WHITE, xAlign = 1, yAlign = 1, name = "name$k")
            check(name == base + Ids.NAME)
            val pointsBox = b.box(root, 156, y, 160, 30, "points_box$k")
            check(pointsBox == base + Ids.POINTS_BOX)
            val points = b.text(pointsBox, 0, 2, 160, 14, "${site.second} Vote Points", FONT_REGULAR, ORANGE, xAlign = 1, name = "points$k")
            check(points == base + Ids.POINTS)
            val status = b.text(pointsBox, 0, 16, 160, 12, "", FONT_SMALL, GREEN, xAlign = 1, name = "status$k")
            check(status == base + Ids.STATUS)
            val button = b.box(root, 322, y, 110, 30, "button$k")
            check(button == base + Ids.BUTTON)
            val label = b.text(button, 0, 0, 110, 30, "Vote", FONT_BOLD, GREEN, xAlign = 1, yAlign = 1, name = "label$k")
            check(label == base + Ids.BUTTON_LABEL)
            // the clickable parts: Vote or Claim, the server shows one (or neither: Voted)
            val vote = b.layer(button, 0, 0, 110, 30, "vote$k") { ops.add("Vote") }
            check(vote == base + Ids.BUTTON_VOTE)
            val claim = b.layer(button, 0, 0, 110, 30, "claim$k") { hidden = true; ops.add("Claim") }
            check(claim == base + Ids.BUTTON_CLAIM)
        }

        val view = b.layer(root, PERKS_X, PERKS_Y, PERKS_W, PERKS_H, "perks") {
            hooks["onLoad"] = listOf(SCROLLBAR, (VOTE_SCREEN shl 16) or Ids.PERKS_SCROLLBAR, If3.EVENT_COMPONENT) + SCROLLBAR_GRAPHICS
        }
        check(view == Ids.PERKS_VIEW)
        val bar = b.layer(root, PERKS_X + PERKS_W + 2, PERKS_Y, 16, PERKS_H, "perks_scrollbar")
        check(bar == Ids.PERKS_SCROLLBAR)
        for ((k, perk) in PERKS.withIndex()) {
            val x = (k % 2) * PERK_STEP_X
            val y = (k / 2) * PERK_STEP_Y
            val panel = b.layer(view, x, y, PERK_W, PERK_H, "perk$k")
            check(panel == Ids.PERK_FIRST + k * Ids.PERK_STRIDE)
            b.rect(panel, 0, 0, PERK_W, PERK_H, OUTER, 0) { filled = false }
            b.rect(panel, 1, 1, PERK_W - 2, PERK_H - 2, INNER, 0) { filled = false }
            val item = b.model(panel, 4, 6, 36, 32, "perk_item$k")
            check(item == panel + Ids.PERK_ITEM)
            b.text(panel, 44, 3, 160, 14, perk.first, FONT_REGULAR, ORANGE, name = "perk_title$k")
            b.text(panel, 44, 18, 160, 11, perk.second, FONT_SMALL, WHITE, name = "perk_line1_$k")
            b.text(panel, 44, 29, 160, 11, perk.third, FONT_SMALL, WHITE, name = "perk_line2_$k")
            val status = b.text(panel, 44, 43, 160, 12, "", FONT_SMALL, ORANGE, name = "perk_status$k")
            check(status == panel + Ids.PERK_STATUS)
        }
        val footer = b.text(root, 0, H - 28, W, 16, "", FONT_REGULAR, ORANGE, xAlign = 1, name = "footer")
        check(footer == Ids.FOOTER)
        return b
    }

    /** The perk box's scroll height: the 3 rows of panels, at least the box (then the scrollbar has nothing to move). */
    private val PERKS_SCROLL_HEIGHT = maxOf(PERK_STEP_Y * ((Ids.PERKS + 1) / 2 - 1) + PERK_H, PERKS_H)

    /** [b] as If3 encodes it, with the perk box's scroll height put in (a layer's scroll width and height follow its 19-byte header). */
    private fun encode(b: If3.Builder): List<ByteArray> = b.comps.map { c ->
        val bytes = c.encode()
        if (c.id == Ids.PERKS_VIEW && c.type == If3.LAYER) {
            bytes[21] = (PERKS_SCROLL_HEIGHT shr 8).toByte()
            bytes[22] = PERKS_SCROLL_HEIGHT.toByte()
        }
        bytes
    }

    /** A box: its layer, the dark see-through fill inset 2, the 921-928 frame. Returns the layer. */
    private fun If3.Builder.box(parent: Int, x: Int, y: Int, w: Int, h: Int, name: String): Int {
        val layer = layer(parent, x, y, w, h, name)
        rect(layer, 2, 2, w - 4, h - 4, BOX_FILL, BOX_FILL_OPACITY)
        sprite(layer, 0, 0, 9, 9, BORDER)
        sprite(layer, w - 9, 0, 9, 9, BORDER + 1)
        sprite(layer, 0, h - 9, 9, 9, BORDER + 2)
        sprite(layer, w - 9, h - 9, 9, 9, BORDER + 3)
        sprite(layer, 0, 9, 9, h - 18, BORDER + 4, true)
        sprite(layer, 9, 0, w - 18, 9, BORDER + 5, true)
        sprite(layer, w - 9, 9, 9, h - 18, BORDER + 6, true)
        sprite(layer, 9, h - 9, w - 18, 9, BORDER + 7, true)
        return layer
    }

    /** A graphic of any vanilla sprite (If3.Builder.graphic takes the gambling sprites only). */
    private fun If3.Builder.sprite(parent: Int, x: Int, y: Int, w: Int, h: Int, id: Int, tiled: Boolean = false): Int {
        val c = If3.Comp(comps.size, If3.GRAPHIC, parent)
        c.x = x; c.y = y; c.width = w; c.height = h; c.sprite = id; c.tiling = tiled
        comps.add(c)
        return c.id
    }

    /** The named component ids, for the server's VoteUi. */
    fun serverIds(): String {
        val b = screen()
        val sb = StringBuilder("VoteScreen ID=$VOTE_SCREEN")
        for ((name, id) in b.names) sb.append(' ').append(name.uppercase()).append('=').append(id)
        return sb.toString()
    }

    // ------------------------------------------------------------------------------------------------ the items

    private fun writeItems(lib: CacheLibrary): List<Int> {
        val rev = ConfigRev.of(lib)
        val models = Models(lib)
        fun item(id: Int): ItemFull = ItemStore.read(lib, id)?.takeIf { it.readOk } ?: error("item $id isn't in the cache (or can't be read)")
        val gold = hsl(GOLD)
        val blue = hsl(BLUE)
        val blueGold = { c: Int -> if (sat(c) >= 4) pack(hue(gold), 6, lum(c) * 1.2 + 10) else pack(hue(blue), 6, lum(c) * 0.9) }
        // 6199's faces are all saturation 7, in two hues: 2 the box (blue) and 4 the ribbon (gold)
        val boxBlueGold = { c: Int -> if (hue(c) <= 2) pack(hue(blue), 6, lum(c) * 0.9) else pack(hue(gold), 6, lum(c) * 1.2 + 10) }

        val wanted = ArrayList<ItemFull>()
        wanted += ItemFull(VOTE_TICKET).apply {
            name = "Vote ticket"
            stackable = true
            tradeable = true
            looksLike(item(CASTLE_WARS_TICKET), models, blueGold)
        }
        wanted += ItemFull(VOTE_MYSTERY_BOX).apply {
            name = "Vote mystery box"
            tradeable = true
            inventoryOptions[0] = "Open"
            looksLike(item(MYSTERY_BOX), models, boxBlueGold)
        }

        val written = ArrayList<Int>()
        for (it in wanted) {
            val bytes = ItemStore.checkedBytes(it, rev)
            val current = lib.data(CONFIGS, ITEMS, it.id)
            if (current != null && current.contentEquals(bytes)) continue
            lib.put(CONFIGS, ITEMS, it.id, bytes)
            written.add(it.id)
        }
        if (written.isNotEmpty()) lib.index(CONFIGS).update()
        return written
    }

    /**
     * Gives this item [source]'s picture: its inventory model, camera and size, with every face colour of the model
     * (after [source]'s own recolours) through [recolour].
     */
    private fun ItemFull.looksLike(source: ItemFull, models: Models, recolour: (Int) -> Int) {
        inventoryModel = source.inventoryModel
        zoom2d = source.zoom2d; xan2d = source.xan2d; yan2d = source.yan2d; zan2d = source.zan2d
        offsetX2d = source.offsetX2d; offsetY2d = source.offsetY2d
        resizeX = source.resizeX; resizeY = source.resizeY; resizeZ = source.resizeZ
        ambient = source.ambient; contrast = source.contrast
        val from = ArrayList<Int>(); val to = ArrayList<Int>()
        for (c in models.faceColours(source.inventoryModel)) {
            val shown = source.recolorFrom.indexOfFirst { (it.toInt() and 0xFFFF) == c }
                .let { k -> if (k < 0) c else source.recolorTo[k].toInt() and 0xFFFF }
            val target = recolour(shown)
            if (target != c) { from.add(c); to.add(target) }
        }
        require(from.size <= 255) { "model ${source.inventoryModel} has too many colours for one item" }
        recolorFrom = ShortArray(from.size) { from[it].toShort() }
        recolorTo = ShortArray(to.size) { to[it].toShort() }
        retextureFrom = source.retextureFrom.copyOf(); retextureTo = source.retextureTo.copyOf()
    }

    /** Reads models' face colours from one cache. */
    private class Models(private val lib: CacheLibrary) {
        private val loader = ModelLoader(lib)

        fun faceColours(id: Int): List<Int> {
            val data = lib.data(MODELS, id and 0xFFFF, 0) ?: error("model $id isn't in the cache")
            val md = loader.decode(data, id)
            return (md.faceColors ?: ShortArray(0)).map { it.toInt() and 0xFFFF }.distinct().sorted()
        }
    }

    private fun hue(c: Int) = c shr 10 and 63
    private fun sat(c: Int) = c shr 7 and 7
    private fun lum(c: Int) = c and 127
    private fun pack(hue: Int, sat: Int, lum: Double): Int = (hue and 63 shl 10) or (sat and 7 shl 7) or Math.round(lum).toInt().coerceIn(0, 127)

    /** [rgb] as the game's 16-bit colour (6 bits hue, 3 saturation, 7 lightness). */
    private fun hsl(rgb: Int): Int {
        val r = (rgb shr 16 and 255) / 256.0
        val g = (rgb shr 8 and 255) / 256.0
        val b = (rgb and 255) / 256.0
        val min = minOf(r, g, b)
        val max = maxOf(r, g, b)
        var h = 0.0
        var s = 0.0
        val l = (min + max) / 2.0
        if (min != max) {
            s = if (l < 0.5) (max - min) / (max + min) else (max - min) / (2.0 - max - min)
            h = when (max) {
                r -> (g - b) / (max - min)
                g -> 2.0 + (b - r) / (max - min)
                else -> 4.0 + (r - g) / (max - min)
            }
        }
        h /= 6.0
        if (h < 0) h += 1.0
        val hue = (h * 64.0).toInt() and 63
        val sat = (s * 8.0).toInt().coerceIn(0, 7)
        val lum = (l * 128.0).toInt().coerceIn(0, 127)
        return (hue shl 10) or (sat shl 7) or lum
    }
}
