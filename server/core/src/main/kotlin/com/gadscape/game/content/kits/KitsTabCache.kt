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

/**
 * Puts the Kits panel (in place of the music player, like Roat Pkz) into the game cache when the server
 * starts, so clients download it with the rest of the cache and nothing has to be repacked by hand.
 * Running it again changes nothing once the cache already has it.
 */
object KitsTabCache {

    private val log = LoggerFactory.getLogger(KitsTabCache::class.java)

    const val INTERFACE_ID = 5110
    const val ROWS = 10

    // Component ids, used by KitsTab on the server side.
    const val TITLE = 3
    const val SAVE_BUTTON = 6
    const val FIRST_ROW = 8
    const val PREV = FIRST_ROW + ROWS
    const val PAGE = PREV + 1
    const val NEXT = PAGE + 1
    const val COUNT = NEXT + 1
    const val DUMMY = COUNT + 1

    private const val ORANGE = 0xFF981F
    private const val GOLD = 0xE3B104
    private const val WHITE = 0xFFFFFF
    private const val PANEL = 0x2E2820
    private const val BORDER = 0x5B5245
    private const val BUTTON = 0x463D32

    /** Sprite groups of the music tab icon (modern, 2005 and unused styles) that become the kits icon. */
    private val MUSIC_ICONS = intArrayOf(910, 787, 893)
    private const val COMBAT_ICON = 168
    private val TOPLEVELS = intArrayOf(161, 164, 165, 548, 601)

    @JvmStatic
    fun ensure(path: String) {
        val cache = try {
            Cache.openCache(path, false)
        } catch (e: Exception) {
            log.warn("Couldn't open the cache to add the Kits tab: {}", e.message)
            return
        } ?: return
        try {
            CacheManager.loadCache(cache)
            packInterface(cache)
            renameTabStone(cache)
            packIcon(cache)
            log.info("Kits tab is in the cache (interface {}).", INTERFACE_ID)
        } catch (e: Exception) {
            log.error("Couldn't add the Kits tab to the cache", e)
        } finally {
            cache.close()
        }
    }

    private fun component(id: Int, type: Int, build: ComponentDefinitions.() -> Unit) = ComponentDefinitions().apply {
        interfaceId = INTERFACE_ID
        componentId = id
        isIf3 = true
        this.type = type
        parentId = if (id == 0) -1 else 0
        build()
    }

    private fun ComponentDefinitions.fillWidth(margin: Int) {
        widthMode = 1
        width = margin * 2
        x = margin
    }

    private fun text(id: Int, value: String, font: Int, colour: Int, build: ComponentDefinitions.() -> Unit) =
        component(id, 4) {
            this.font = font
            text = value
            color = colour
            textShadowed = true
            xAllignment = 1
            yAllignment = 1
            build()
        }

    private fun ComponentDefinitions.ops(vararg names: String) {
        actions = arrayOf(*names)
        var mask = 0
        for (i in names.indices) mask = mask or (1 shl (i + 1))
        accessMask = mask
    }

    private fun build(): List<ComponentDefinitions> {
        val list = ArrayList<ComponentDefinitions>()
        list += component(0, 0) { widthMode = 1; heightMode = 1 }
        list += component(1, 3) { fillWidth(3); y = 3; heightMode = 1; height = 6; color = PANEL; filled = true }
        list += component(2, 3) { fillWidth(3); y = 3; heightMode = 1; height = 6; color = BORDER }
        list += text(TITLE, "Your Custom Kits", 496, ORANGE) { fillWidth(6); y = 7; height = 16 }
        list += component(4, 3) { fillWidth(10); y = 27; height = 22; color = BUTTON; filled = true }
        list += component(5, 3) { fillWidth(10); y = 27; height = 22; color = BORDER }
        list += text(SAVE_BUTTON, "Save current gear as a kit", 495, WHITE) {
            fillWidth(10); y = 27; height = 22
            ops("Save")
            opBase = "<col=ff9040>current gear</col>"
        }
        list += component(7, 3) { fillWidth(8); y = 55; height = 1; color = BORDER; filled = true }
        for (row in 0 until ROWS) {
            list += text(FIRST_ROW + row, "", 495, GOLD) {
                fillWidth(10); y = 60 + row * 17; height = 17
                xAllignment = 0
                ops("Load", "Overwrite", "Rename", "Delete", "Set default")
                opBase = "<col=ff9040>kit</col>"
            }
        }
        list += text(PREV, "< Prev", 495, ORANGE) {
            x = 10; width = 50; yMode = 2; y = 24; height = 18; xAllignment = 0
            ops("Previous page")
        }
        list += text(PAGE, "1/1", 494, WHITE) { fillWidth(60); yMode = 2; y = 24; height = 18 }
        list += text(NEXT, "Next >", 495, ORANGE) {
            xMode = 2; x = 10; width = 50; yMode = 2; y = 24; height = 18; xAllignment = 2
            ops("Next page")
        }
        list += text(COUNT, "", 494, WHITE) { fillWidth(10); yMode = 2; y = 6; height = 16 }
        list += text(DUMMY, "", 494, WHITE) { hidden = true; width = 1; height = 1 }
        return list
    }

    private fun packInterface(cache: Cache) {
        val archive = cache.getArchive(ArchiveType.INTERFACES)
        var group = archive.findGroupByID(INTERFACE_ID)
        if (group == null) {
            group = Group(INTERFACE_ID)
            for (c in build()) group.addFile(File(c.componentId, c.encode().trimmed()))
            archive.addGroup(group)
            return
        }
        for (c in build()) {
            val file = group.findFileByID(c.componentId)
            if (file == null) group.addFile(File(c.componentId, c.encode().trimmed())) else file.setData(c.encode().trimmed())
        }
    }

    /** The tab stone's tooltip and right-click option say Kits instead of Music Player. */
    private fun renameTabStone(cache: Cache) {
        val archive = cache.getArchive(ArchiveType.INTERFACES)
        for (top in TOPLEVELS) {
            val group = archive.findGroupByID(top) ?: continue
            for (componentId in 0 until group.highestFileId) {
                val file = group.findFileByID(componentId) ?: continue
                val data = file.data ?: continue
                data.position = 0
                val c = ComponentDefinitions(top shl 16 or componentId, data)
                val actions = c.actions ?: continue
                if (actions.none { it == "Music Player" }) continue
                c.actions = actions.map { if (it == "Music Player") "Kits" else it }.toTypedArray()
                file.setData(c.encode().trimmed())
            }
        }
    }

    /** The music note icon becomes the crossed swords, in gold. */
    private fun packIcon(cache: Cache) {
        val sprites = cache.getArchive(ArchiveType.SPRITES)
        val source = sprites.findGroupByID(COMBAT_ICON)?.findFileByID(0)?.data ?: return
        source.position = 0
        val swords = SpriteGroupDefinitions.decode(COMBAT_ICON, source).getImage(0)
        for (id in MUSIC_ICONS) {
            val file = sprites.findGroupByID(id)?.findFileByID(0) ?: continue
            val data = file.data ?: continue
            data.position = 0
            val old = SpriteGroupDefinitions.decode(id, data)
            val w = old.width
            val h = old.height
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val dx = (w - swords.width) / 2
            val dy = (h - swords.height) / 2
            for (x in 0 until swords.width) for (y in 0 until swords.height) {
                val tx = x + dx
                val ty = y + dy
                if (tx !in 0 until w || ty !in 0 until h) continue
                val argb = swords.getRGB(x, y)
                if (argb ushr 24 == 0) continue
                image.setRGB(tx, ty, gold(argb))
            }
            val icon = SpriteGroupDefinitions(id, w, h)
            icon.setImage(0, image)
            file.setData(icon.encode())
        }
    }

    private fun ByteBuffer.trimmed() = ByteBuffer(toArray(0, position))

    private fun gold(argb: Int): Int {
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        val lum = (r * 3 + g * 6 + b) / 10
        val nr = (lum * 1.15).toInt().coerceIn(1, 255)
        val ng = (lum * 0.85).toInt().coerceIn(1, 255)
        val nb = (lum * 0.25).toInt().coerceIn(1, 255)
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}
