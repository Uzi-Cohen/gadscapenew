package utils

import com.displee.cache.CacheLibrary
import utils.gambling.If3
import utils.gambling.If3.FONT_BOLD
import utils.gambling.If3.FONT_REGULAR
import utils.gambling.If3.FONT_SMALL
import utils.gambling.Sprites
import javax.imageio.ImageIO

/**
 * Adds the Premade Kits side tab to a build 241 cache: interface 5250 (the music tab's slot shows it; the server opens
 * it there instead of the music list when the cache has it, PremadeKits.available) and the kit tab icon, sprite 21500,
 * which replaces the music note (sprite 910) on the tab buttons of the toplevel interfaces.
 *
 * Idempotent: everything is compared with what the cache holds and only what differs is written. Close the server,
 * client and editors that use the cache first.
 *
 *     java -cp <editor fat jar> utils.KitsPatch <cache folder>
 *
 * Layout (side panel 190x261): title, 2 x 3 kit tiles (88x70, nine-slice button frame 1141-1149, selected frame
 * 1150-1158 hidden until the server shows it), each with name, combat level, three item models the server fills
 * (helm, body, weapon) and "Spawn"; the sixth tile re-spawns the last kit. Footer line under the tiles.
 * The server's copy of the ids is server/core/.../kits/KitsUi.kt; [Ids] is the same list and [layout] checks it.
 */
object KitsPatch {
    private const val INTERFACES = 3
    private const val SPRITES = 8

    const val INTERFACE_ID = 5250
    const val TAB_ICON = 21500
    /** The music tab's note (build 241) on the toplevel tab buttons. */
    private const val MUSIC_ICON = 910
    /** Toplevel interfaces with tab buttons: fixed, resizable classic / modern, mobile. */
    private val TOPLEVELS = intArrayOf(548, 161, 164, 165, 601)

    private const val ORANGE = 0xFF981F
    private const val YELLOW = 0xFFFF00
    private const val GREEN = 0x00FF00
    private const val GREY = 0x9F9F9F
    private const val FOOTER_GREY = 0xC8C8C8

    private const val BACKGROUND_SPRITE = 297
    private const val FRAME_SPRITE = 1141
    private const val FRAME_SELECTED_SPRITE = 1150
    private const val CORNER = 6

    private const val TILE_W = 88
    private const val TILE_H = 70

    /** The kits the tab lists, in tile order: name, combat level; the sixth tile is "Last kit". */
    private val KITS = listOf("Main" to 126, "Zerker" to 99, "Pure" to 83, "Hybrid" to 126, "Tribrid" to 126)

    /** Component ids, as the server's KitsUi has them. */
    object Ids {
        const val TITLE = 2
        const val TILES = 6
        const val TILE_FIRST = 3
        const val TILE_STRIDE = 27
        const val TILE = 0
        const val FRAME = 1
        const val FRAME_SELECTED = 11
        const val NAME = 21
        const val LEVEL = 22
        const val ITEM = 23
        const val SPAWN = 26
        const val FOOTER = TILE_FIRST + TILES * TILE_STRIDE
    }

    fun apply(lib: CacheLibrary): String? {
        val done = ArrayList<String>()
        val touched = HashSet<Int>()

        val icon = tabIcon()
        if (put(lib, SPRITES, TAB_ICON, 0, icon)) { done.add("tab icon"); touched.add(SPRITES) }

        val b = layout()
        val comps = b.encode()
        val existing = existing(lib, INTERFACES, INTERFACE_ID)
        if (existing == null || existing.size != comps.size || !comps.indices.all { existing[it]?.contentEquals(comps[it]) == true }) {
            if (existing != null) lib.remove(INTERFACES, INTERFACE_ID)
            for ((i, c) in comps.withIndex()) lib.put(INTERFACES, INTERFACE_ID, i, c)
            done.add("kits tab (interface $INTERFACE_ID)")
            touched.add(INTERFACES)
        }

        var buttons = 0
        for (top in TOPLEVELS) {
            val files = existing(lib, INTERFACES, top) ?: continue
            for ((file, data) in files) {
                if (data == null || !isGraphic(data) || sprite(data) != MUSIC_ICON) continue
                val copy = data.copyOf()
                setSprite(copy, TAB_ICON)
                lib.put(INTERFACES, top, file, copy)
                buttons++
            }
        }
        if (buttons > 0) { done.add("$buttons tab buttons"); touched.add(INTERFACES) }

        if (done.isEmpty()) return null
        for (index in touched) lib.index(index).update()
        return "Premade kits: added or updated " + done.joinToString() + "."
    }

    /** The tab: see the class comment. Component order is fixed by [Ids]; the requires below keep it so. */
    fun layout(): If3.Builder {
        val b = If3.Builder(INTERFACE_ID)
        val root = b.layer(-1, 0, 0, 0, 0, "root") { widthMode = 1; heightMode = 1 }
        b.sprite(root, 0, 0, 0, 0, BACKGROUND_SPRITE, "background") { widthMode = 1; heightMode = 1; tiling = true }
        b.text(root, 6, 2, 160, 16, "Premade Kits", FONT_REGULAR, ORANGE, name = "title")
        require(b["title"] == Ids.TITLE)
        for (k in 0 until Ids.TILES) {
            val kit = KITS.getOrNull(k)
            val x = 5 + (k % 2) * 92
            val y = 21 + (k / 2) * 73
            val tile = b.layer(root, x, y, TILE_W, TILE_H, "tile$k") { ops.add(if (kit != null) "Spawn" else "Re-spawn") }
            require(tile == Ids.TILE_FIRST + k * Ids.TILE_STRIDE + Ids.TILE)
            val frame = b.layer(tile, 0, 0, TILE_W, TILE_H, "frame$k")
            require(frame == tile + Ids.FRAME)
            b.nineSlice(frame, TILE_W, TILE_H, FRAME_SPRITE)
            val selected = b.layer(tile, 0, 0, TILE_W, TILE_H, "frame_selected$k") { hidden = true }
            require(selected == tile + Ids.FRAME_SELECTED)
            b.nineSlice(selected, TILE_W, TILE_H, FRAME_SELECTED_SPRITE)
            val name = b.text(tile, 6, 3, 52, 15, kit?.first ?: "Last kit", FONT_REGULAR, ORANGE, name = "name$k")
            require(name == tile + Ids.NAME)
            b.text(tile, 40, 3, 42, 15, kit?.second?.toString() ?: "", FONT_BOLD, YELLOW, xAlign = 2, name = "level$k")
            for (j in 0 until 3) {
                val m = b.model(tile, 3 + j * 28, 24, 27, 24, "item${k}_$j")
                require(m == tile + Ids.ITEM + j)
            }
            val spawn = if (kit != null) {
                b.text(tile, 0, TILE_H - 17, TILE_W, 14, "Spawn", FONT_SMALL, GREEN, xAlign = 1, name = "spawn$k")
            } else {
                b.text(tile, 0, 26, TILE_W, 28, "Re-spawn<br>your last kit", FONT_SMALL, GREY, xAlign = 1, name = "spawn$k")
            }
            require(spawn == tile + Ids.SPAWN)
        }
        val footer = b.text(root, 6, 241, 180, 14, "Free gear - vanishes on death", FONT_SMALL, FOOTER_GREY, name = "footer")
        require(footer == Ids.FOOTER)
        return b
    }

    /** A graphic of a cache sprite by id, at the given size (the builder's graphic() takes a resource sprite). */
    private fun If3.Builder.sprite(parent: Int, x: Int, y: Int, w: Int, h: Int, sprite: Int, name: String? = null,
                                   block: If3.Comp.() -> Unit = {}): Int {
        val c = If3.Comp(comps.size, If3.GRAPHIC, parent)
        c.x = x; c.y = y; c.width = w; c.height = h; c.sprite = sprite
        c.block()
        comps.add(c)
        if (name != null) require(names.put(name, c.id) == null) { "duplicate component name $name" }
        return c.id
    }

    /** Nine graphics filling a w x h layer: sprites first..first+8 = corner, top, corner, left, middle, right, corner, bottom, corner. */
    private fun If3.Builder.nineSlice(parent: Int, w: Int, h: Int, first: Int) {
        val c = CORNER
        sprite(parent, 0, 0, c, c, first)
        sprite(parent, c, 0, w - 2 * c, c, first + 1) { tiling = true }
        sprite(parent, w - c, 0, c, c, first + 2)
        sprite(parent, 0, c, c, h - 2 * c, first + 3) { tiling = true }
        sprite(parent, c, c, w - 2 * c, h - 2 * c, first + 4) { tiling = true }
        sprite(parent, w - c, c, c, h - 2 * c, first + 5) { tiling = true }
        sprite(parent, 0, h - c, c, c, first + 6)
        sprite(parent, c, h - c, w - 2 * c, c, first + 7) { tiling = true }
        sprite(parent, w - c, h - c, c, c, first + 8)
    }

    /** The kit icon (helm and scimitar, 33x36 like the music note), resource kits/tab_icon.png. */
    private fun tabIcon(): ByteArray {
        val stream = KitsPatch::class.java.getResourceAsStream("/kits/tab_icon.png") ?: error("missing resource kits/tab_icon.png")
        return Sprites.encode(stream.use { ImageIO.read(it) })
    }

    // IF3 component bytes: 255, type, content type (2), x, y, w, h (2 each), 4 modes, parent (2), hidden, then for a
    // graphic (type 5) its sprite id (4).
    private const val SPRITE_OFFSET = 19

    private fun isGraphic(d: ByteArray) = d.size > SPRITE_OFFSET + 4 && d[0] == 0xFF.toByte() && d[1].toInt() == If3.GRAPHIC

    private fun sprite(d: ByteArray): Int = ((d[SPRITE_OFFSET].toInt() and 0xFF) shl 24) or ((d[SPRITE_OFFSET + 1].toInt() and 0xFF) shl 16) or
        ((d[SPRITE_OFFSET + 2].toInt() and 0xFF) shl 8) or (d[SPRITE_OFFSET + 3].toInt() and 0xFF)

    private fun setSprite(d: ByteArray, id: Int) {
        d[SPRITE_OFFSET] = (id ushr 24).toByte()
        d[SPRITE_OFFSET + 1] = (id ushr 16).toByte()
        d[SPRITE_OFFSET + 2] = (id ushr 8).toByte()
        d[SPRITE_OFFSET + 3] = id.toByte()
    }

    private fun existing(lib: CacheLibrary, index: Int, archive: Int): Map<Int, ByteArray?>? {
        val a = try { lib.index(index).archive(archive) } catch (e: Exception) { null } ?: return null
        return a.fileIds().associateWith { a.file(it)?.data }
    }

    private fun put(lib: CacheLibrary, index: Int, archive: Int, file: Int, data: ByteArray): Boolean {
        val current = try { lib.data(index, archive, file) } catch (e: Exception) { null }
        if (current != null && current.contentEquals(data)) return false
        lib.put(index, archive, file, data)
        return true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            println("usage: java -cp <editor jar> utils.KitsPatch <cache folder>")
            kotlin.system.exitProcess(2)
        }
        val lib = CacheLibrary.create(args[0])
        try {
            println(apply(lib) ?: "Nothing to add: the cache already has the premade kits tab.")
        } finally {
            lib.close()
        }
    }
}
