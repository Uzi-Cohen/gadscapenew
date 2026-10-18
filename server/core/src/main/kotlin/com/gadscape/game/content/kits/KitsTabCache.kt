package com.gadscape.game.content.kits

import com.zenyte.CacheManager
import mgi.tools.jagcached.ArchiveType
import mgi.tools.jagcached.cache.Cache
import mgi.tools.jagcached.cache.File
import mgi.tools.jagcached.cache.Group
import mgi.types.component.ComponentDefinitions
import mgi.types.draw.sprite.SpriteGroupDefinitions
import mgi.utilities.ByteBuffer
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Writes GadScape's own interfaces into the game cache when the server starts, so clients download them with
 * the rest of the cache: the Roat Pkz style side panel that replaces the music player (Main and Kits pages)
 * and the PK store window. Running it again changes nothing once the cache already has them.
 */
object KitsTabCache {

    private val log = LoggerFactory.getLogger(KitsTabCache::class.java)

    const val INTERFACE_ID = 5110
    const val STORE_ID = 5111

    // ---- sprites (Roat Pkz panel art) ----------------------------------------------------------------------
    private const val SPRITE_BASE = 7100
    private val SPRITES = listOf(
        "background", "button", "button_hover", "main", "main_on", "kits", "kits_on", "store", "store_on", "line",
    )
    private fun sprite(name: String) = SPRITE_BASE + SPRITES.indexOf(name)

    // ---- side panel component ids ------------------------------------------------------------------------
    const val MAIN_ICON = 3
    const val MAIN_ON = 4
    const val KITS_ICON = 5
    const val KITS_ON = 6
    const val STORE_ICON = 7
    const val MAIN_LAYER = 9
    const val KITS_LAYER = 10
    const val MAIN_LINES = 11
    const val MAIN_LINE_COUNT = 15
    const val TITLE = MAIN_LINES + MAIN_LINE_COUNT // 26
    const val SAVE_SPRITE = TITLE + 1
    const val SAVE_BUTTON = SAVE_SPRITE + 1
    const val FIRST_ROW = SAVE_BUTTON + 1
    const val ROWS = 9
    const val PREV = FIRST_ROW + ROWS
    const val PAGE = PREV + 1
    const val NEXT = PAGE + 1
    const val COUNT = NEXT + 1
    const val DUMMY = COUNT + 1

    // ---- store component ids -----------------------------------------------------------------------------
    const val STORE_POINTS = 4
    const val STORE_CLOSE = 5
    const val STORE_FIRST_CATEGORY = 7
    const val STORE_CATEGORIES = 7
    const val STORE_COLUMNS = 8
    const val STORE_ROWS = 5
    const val STORE_SLOTS = STORE_COLUMNS * STORE_ROWS
    const val STORE_FIRST_ITEM = STORE_FIRST_CATEGORY + STORE_CATEGORIES * 2
    const val STORE_FIRST_PRICE = STORE_FIRST_ITEM + STORE_SLOTS
    const val STORE_PREV = STORE_FIRST_PRICE + STORE_SLOTS
    const val STORE_PAGE = STORE_PREV + 1
    const val STORE_NEXT = STORE_PAGE + 1
    const val STORE_HINT = STORE_NEXT + 1

    const val ORANGE = 0xFF981F
    const val YELLOW = 0xFFF71F
    const val GOLD = 0xE3B104
    const val WHITE = 0xFFFFFF
    const val GREEN = 0x3ED32D
    private const val PANEL = 0x2E2820
    private const val BORDER = 0x5B5245
    private const val BORDER_LIGHT = 0x7A6E5B
    private const val CELL = 0x3A3329

    private const val SMALL = 494
    private const val REGULAR = 495
    private const val BOLD = 496
    private const val CLOSE_SPRITE = 535

    @JvmStatic
    fun ensure(path: String) {
        val cache = try {
            Cache.openCache(path, false)
        } catch (e: Exception) {
            log.warn("Couldn't open the cache to add GadScape's interfaces: {}", e.message)
            return
        } ?: return
        try {
            CacheManager.loadCache(cache)
            packSprites(cache)
            packTabIcon(cache)
            packGroup(cache, INTERFACE_ID, sidePanel())
            packGroup(cache, STORE_ID, store())
            log.info("Side panel ({}) and PK store ({}) are in the cache.", INTERFACE_ID, STORE_ID)
        } catch (e: Exception) {
            log.error("Couldn't add GadScape's interfaces to the cache", e)
        } finally {
            cache.close()
        }
    }

    // ---- builders ----------------------------------------------------------------------------------------

    private class Builder(val interfaceId: Int) {
        val list = ArrayList<ComponentDefinitions>()

        fun add(id: Int, type: Int, parent: Int, build: ComponentDefinitions.() -> Unit): ComponentDefinitions {
            check(id == list.size) { "component $id out of order (next is ${list.size})" }
            val c = ComponentDefinitions().apply {
                this.interfaceId = this@Builder.interfaceId
                componentId = id
                isIf3 = true
                this.type = type
                parentId = parent
                build()
            }
            list += c
            return c
        }

        fun layer(id: Int, parent: Int, build: ComponentDefinitions.() -> Unit) = add(id, 0, parent, build)
        fun rect(id: Int, parent: Int, colour: Int, fill: Boolean, build: ComponentDefinitions.() -> Unit) =
            add(id, 3, parent) { color = colour; filled = fill; build() }

        fun sprite(id: Int, parent: Int, spriteId: Int, build: ComponentDefinitions.() -> Unit) =
            add(id, 5, parent) { this.spriteId = spriteId; build() }

        fun text(id: Int, parent: Int, value: String, font: Int, colour: Int, build: ComponentDefinitions.() -> Unit) =
            add(id, 4, parent) {
                this.font = font
                text = value
                color = colour
                textShadowed = true
                xAllignment = 0
                yAllignment = 1
                build()
            }
    }

    private fun ComponentDefinitions.at(x: Int, y: Int, w: Int, h: Int) {
        this.x = x; this.y = y; width = w; height = h
    }

    private fun ComponentDefinitions.ops(vararg names: String) {
        actions = arrayOf(*names)
        var mask = 0
        for (i in names.indices) mask = mask or (1 shl (i + 1))
        accessMask = mask
    }

    /** The music tab replacement, laid out like the Roat Pkz quest tab (184x222 panel under a row of tab icons). */
    private fun sidePanel(): List<ComponentDefinitions> = Builder(INTERFACE_ID).run {
        layer(0, -1) { widthMode = 1; heightMode = 1 }
        text(1, 0, "Gad", BOLD, ORANGE) { at(7, 2, 32, 14) }
        text(2, 0, "Scape", REGULAR, ORANGE) { at(4, 15, 34, 14) }
        sprite(MAIN_ICON, 0, sprite("main")) { at(40, 2, 27, 27); ops("Main tab") }
        sprite(MAIN_ON, 0, sprite("main_on")) { at(40, 2, 27, 27) }
        sprite(KITS_ICON, 0, sprite("kits")) { at(70, 2, 27, 27); ops("Kit tab") }
        sprite(KITS_ON, 0, sprite("kits_on")) { at(70, 2, 27, 27); hidden = true }
        sprite(STORE_ICON, 0, sprite("store")) { at(100, 2, 27, 27); ops("PK Store") }
        sprite(8, 0, sprite("background")) { at(4, 31, 184, 222) }
        layer(MAIN_LAYER, 0) { at(2, 33, 184, 218) }
        layer(KITS_LAYER, 0) { at(2, 33, 184, 218); hidden = true }
        for (i in 0 until MAIN_LINE_COUNT) {
            text(MAIN_LINES + i, MAIN_LAYER, "", SMALL, GOLD) { at(13, 5 + i * 14, 165, 14) }
        }
        text(TITLE, KITS_LAYER, "Your Custom Kits", SMALL, YELLOW) { at(13, 4, 160, 14) }
        sprite(SAVE_SPRITE, KITS_LAYER, sprite("button")) { at(14, 21, 155, 25) }
        text(SAVE_BUTTON, KITS_LAYER, "Save current gear as a kit", SMALL, ORANGE) {
            at(14, 21, 155, 25); xAllignment = 1
            ops("Save")
        }
        for (row in 0 until ROWS) {
            text(FIRST_ROW + row, KITS_LAYER, "", SMALL, GOLD) {
                at(13, 52 + row * 17, 160, 17)
                ops("Load", "Overwrite", "Rename", "Delete", "Set default")
            }
        }
        text(PREV, KITS_LAYER, "<lt> Prev", SMALL, ORANGE) { at(13, 205, 45, 13); ops("Previous page") }
        text(PAGE, KITS_LAYER, "1/1", SMALL, WHITE) { at(60, 205, 64, 13); xAllignment = 1 }
        text(NEXT, KITS_LAYER, "Next <gt>", SMALL, ORANGE) { at(126, 205, 45, 13); xAllignment = 2; ops("Next page") }
        text(COUNT, KITS_LAYER, "", SMALL, WHITE) { at(13, 189, 160, 13); xAllignment = 1 }
        text(DUMMY, KITS_LAYER, "", SMALL, WHITE) { at(0, 0, 1, 1); hidden = true }
        list
    }

    /** The PK store window: categories on the left, 8x5 items with prices on the right. */
    private fun store(): List<ComponentDefinitions> = Builder(STORE_ID).run {
        layer(0, -1) { xMode = 1; yMode = 1; at(0, 0, 488, 300) }
        rect(1, 0, PANEL, true) { at(0, 0, 488, 300) }
        rect(2, 0, BORDER_LIGHT, false) { at(0, 0, 488, 300) }
        text(3, 0, "PK Store", BOLD, ORANGE) { at(0, 6, 488, 18); xAllignment = 1 }
        text(STORE_POINTS, 0, "", SMALL, YELLOW) { at(10, 8, 200, 14) }
        sprite(STORE_CLOSE, 0, CLOSE_SPRITE) { at(458, 4, 26, 23); ops("Close") }
        rect(6, 0, BORDER, true) { at(6, 28, 476, 1) }
        for (i in 0 until STORE_CATEGORIES) {
            val y = 36 + i * 30
            rect(STORE_FIRST_CATEGORY + i * 2, 0, CELL, true) { at(8, y, 112, 25) }
            text(STORE_FIRST_CATEGORY + i * 2 + 1, 0, "", REGULAR, ORANGE) {
                at(8, y, 112, 25); xAllignment = 1
                ops("View")
            }
        }
        for (slot in 0 until STORE_SLOTS) {
            val cx = 128 + (slot % STORE_COLUMNS) * 44
            val cy = 34 + (slot / STORE_COLUMNS) * 48
            sprite(STORE_FIRST_ITEM + slot, 0, -1) {
                at(cx + 4, cy, 36, 32)
                ops("Value", "Buy 1", "Buy 5", "Buy 10", "Buy X")
            }
        }
        for (slot in 0 until STORE_SLOTS) {
            val cx = 128 + (slot % STORE_COLUMNS) * 44
            val cy = 34 + (slot / STORE_COLUMNS) * 48
            text(STORE_FIRST_PRICE + slot, 0, "", SMALL, YELLOW) { at(cx, cy + 32, 44, 12); xAllignment = 1 }
        }
        text(STORE_PREV, 0, "<lt> Prev", REGULAR, ORANGE) { at(130, 276, 60, 16); ops("Previous page") }
        text(STORE_PAGE, 0, "1/1", REGULAR, WHITE) { at(250, 276, 100, 16); xAllignment = 1 }
        text(STORE_NEXT, 0, "Next <gt>", REGULAR, ORANGE) { at(410, 276, 66, 16); xAllignment = 2; ops("Next page") }
        text(STORE_HINT, 0, "Right-click an item to buy it.", SMALL, WHITE) { at(8, 276, 118, 16) }
        list
    }

    // ---- packing -----------------------------------------------------------------------------------------

    private fun packGroup(cache: Cache, groupId: Int, components: List<ComponentDefinitions>) {
        val archive = cache.getArchive(ArchiveType.INTERFACES)
        var group = archive.findGroupByID(groupId)
        if (group == null) {
            group = Group(groupId)
            for (c in components) group.addFile(File(c.componentId, c.encode().trimmed()))
            archive.addGroup(group)
            return
        }
        for (c in components) {
            val file = group.findFileByID(c.componentId)
            if (file == null) group.addFile(File(c.componentId, c.encode().trimmed())) else file.setData(c.encode().trimmed())
        }
        // components left over from an older layout
        for (id in components.size until group.highestFileId) group.findFileByID(id)?.let { group.removeFile(it) }
    }

    private fun packSprites(cache: Cache) {
        val archive = cache.getArchive(ArchiveType.SPRITES)
        for ((i, name) in SPRITES.withIndex()) {
            val image = KitsTabCache::class.java.getResourceAsStream("/gadscape/panel/$name.png")?.use { ImageIO.read(it) }
                ?: error("missing sprite $name")
            val argb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until image.width) for (y in 0 until image.height) {
                val p = image.getRGB(x, y)
                // the cache palette has no partial transparency
                argb.setRGB(x, y, if (p ushr 24 < 128) 0 else (p or (0xFF shl 24)).let { if (it and 0xFFFFFF == 0) it or 1 else it })
            }
            val def = SpriteGroupDefinitions(SPRITE_BASE + i, image.width, image.height)
            def.setImage(0, argb)
            val data = def.encode()
            val group = archive.findGroupByID(SPRITE_BASE + i)
            if (group == null) archive.addGroup(Group(SPRITE_BASE + i, File(data)))
            else group.findFileByID(0).setData(data)
        }
    }

    /** Sprite groups of the music tab icon (modern, 2005 and unused styles); they get the gold crossed swords. */
    private val MUSIC_ICONS = intArrayOf(910, 787, 893)
    private const val COMBAT_ICON = 168

    private fun packTabIcon(cache: Cache) {
        val sprites = cache.getArchive(ArchiveType.SPRITES)
        val source = sprites.findGroupByID(COMBAT_ICON)?.findFileByID(0)?.data ?: return
        source.position = 0
        val swords = SpriteGroupDefinitions.decode(COMBAT_ICON, source).getImage(0)
        for (id in MUSIC_ICONS) {
            val file = sprites.findGroupByID(id)?.findFileByID(0) ?: continue
            val data = file.data ?: continue
            data.position = 0
            val old = SpriteGroupDefinitions.decode(id, data)
            val image = BufferedImage(old.width, old.height, BufferedImage.TYPE_INT_ARGB)
            val dx = (old.width - swords.width) / 2
            val dy = (old.height - swords.height) / 2
            for (x in 0 until swords.width) for (y in 0 until swords.height) {
                val tx = x + dx
                val ty = y + dy
                if (tx !in 0 until old.width || ty !in 0 until old.height) continue
                val argb = swords.getRGB(x, y)
                if (argb ushr 24 == 0) continue
                image.setRGB(tx, ty, gold(argb))
            }
            val icon = SpriteGroupDefinitions(id, old.width, old.height)
            icon.setImage(0, image)
            file.setData(icon.encode())
        }
    }

    private fun gold(argb: Int): Int {
        val lum = ((argb shr 16 and 0xFF) * 3 + (argb shr 8 and 0xFF) * 6 + (argb and 0xFF)) / 10
        val r = (lum * 1.15).toInt().coerceIn(1, 255)
        val g = (lum * 0.85).toInt().coerceIn(1, 255)
        val b = (lum * 0.25).toInt().coerceIn(1, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun ByteBuffer.trimmed() = ByteBuffer(toArray(0, position))
}
