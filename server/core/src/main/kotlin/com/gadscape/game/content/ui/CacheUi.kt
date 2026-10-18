package com.gadscape.game.content.ui

import com.gadscape.game.content.gamble.ui.GambleCache
import com.gadscape.game.content.kits.KitsTabCache
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
 * GadScape's own interfaces, sprites, models and object definitions, written into the game cache when the
 * server starts (before the cache is loaded), so clients download them with the rest of the cache.
 * Every pack must be safe to run on every start: writing the same data again changes nothing.
 *
 * Id ranges, so features never collide:
 *  - interfaces: kits/side panel 5110-5119, gambling 5120-5139, donator 5140-5159, slayer 5160-5169, tabs 5170-5189
 *  - sprites: side panel 7100-7199, gambling 7200-7599, donator 7600-7799, slayer 7800-7899, tabs 7900-8199
 *  - models: gamble zone 65000-65199; object definitions: gamble zone 60000-60199
 *  - client scripts: 9500-9599 (ClientScriptsCache)
 */
interface CachePack {
    val name: String
    fun pack(cache: Cache)
}

object CacheUi {

    private val log = LoggerFactory.getLogger(CacheUi::class.java)

    /** Add new packs here. */
    @JvmField
    val PACKS: List<CachePack> = listOf(
        KitsTabCache,
        ClientScriptsCache,
        GambleCache,
    )

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
            for (pack in PACKS) {
                try {
                    pack.pack(cache)
                    log.info("Cache pack '{}' is in the cache.", pack.name)
                } catch (e: Exception) {
                    log.error("Cache pack '{}' failed", pack.name, e)
                }
            }
        } finally {
            cache.close()
        }
    }

    // ---- interfaces ----------------------------------------------------------------------------------------

    /** Writes an interface group; components past the end of [components] are removed. */
    @JvmStatic
    fun packInterface(cache: Cache, groupId: Int, components: List<ComponentDefinitions>) {
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
        for (id in components.size until group.highestFileId) group.findFileByID(id)?.let { group.removeFile(it) }
    }

    // ---- sprites -------------------------------------------------------------------------------------------

    /**
     * Packs PNGs from the classpath folder [resourceDir] as sprite groups [base], [base]+1, ... in the order of
     * [names] (file names without .png). Magenta (255,0,255) counts as transparent, like 317 sprites.
     */
    @JvmStatic
    fun packSprites(cache: Cache, resourceDir: String, base: Int, names: List<String>) {
        val archive = cache.getArchive(ArchiveType.SPRITES)
        for ((i, name) in names.withIndex()) {
            val image = CacheUi::class.java.getResourceAsStream("$resourceDir/$name.png")?.use { ImageIO.read(it) }
                ?: error("missing sprite $resourceDir/$name.png")
            packSprite(archive, base + i, image)
        }
    }

    @JvmStatic
    fun packSprite(archive: mgi.tools.jagcached.cache.Archive, id: Int, image: BufferedImage) {
        val argb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) for (y in 0 until image.height) {
            val p = image.getRGB(x, y)
            val rgb = p and 0xFFFFFF
            argb.setRGB(x, y, when {
                p ushr 24 < 128 || rgb == 0xFF00FF -> 0
                rgb == 0 -> (0xFF shl 24) or 1 // black would read as transparent
                else -> p or (0xFF shl 24)
            })
        }
        val def = SpriteGroupDefinitions(id, image.width, image.height)
        def.setImage(0, argb)
        val data = def.encode()
        val group = archive.findGroupByID(id)
        if (group == null) archive.addGroup(Group(id, File(data))) else group.findFileByID(0).setData(data)
    }

    fun ByteBuffer.trimmed() = ByteBuffer(toArray(0, position))
}

/**
 * Builds the components of one interface in order (component id = position in the list).
 * Use [CacheUi.packInterface] with [list] to write it.
 */
class UiBuilder(val interfaceId: Int) {
    val list = ArrayList<ComponentDefinitions>()
    val next get() = list.size

    fun add(id: Int, type: Int, parent: Int, build: ComponentDefinitions.() -> Unit): ComponentDefinitions {
        check(id == list.size) { "component $id out of order (next is ${list.size})" }
        val c = ComponentDefinitions().apply {
            this.interfaceId = this@UiBuilder.interfaceId
            componentId = id
            isIf3 = true
            this.type = type
            parentId = parent
            build()
        }
        list += c
        return c
    }

    /** type 0: a container. Set scrollHeight for scrolling content (see [scrollbar]). */
    fun layer(id: Int, parent: Int, build: ComponentDefinitions.() -> Unit) = add(id, 0, parent, build)
    fun rect(id: Int, parent: Int, colour: Int, fill: Boolean, build: ComponentDefinitions.() -> Unit) =
        add(id, 3, parent) { color = colour; filled = fill; build() }
    fun sprite(id: Int, parent: Int, spriteId: Int, build: ComponentDefinitions.() -> Unit) =
        add(id, 5, parent) { this.spriteId = spriteId; build() }
    /** An item slot: a sprite component the server fills with IF_SETOBJECT (sendComponentItem). */
    fun item(id: Int, parent: Int, build: ComponentDefinitions.() -> Unit) = add(id, 5, parent) { spriteId = -1; build() }
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

    /**
     * The OSRS vertical scrollbar for [layer] (which needs scrollHeight > height), drawn in the 16px wide
     * [bar] component next to it: client script 30 ([clientscript,scrollbar_vertical]) runs on load and also wires the mouse wheel.
     */
    fun scrollbar(id: Int, parent: Int, layer: Int, build: ComponentDefinitions.() -> Unit) = layer(id, parent) {
        build()
        onLoadListener = arrayOf<Any>(30, interfaceId shl 16 or id, interfaceId shl 16 or layer)
    }

    companion object {
        const val SMALL = 494
        const val REGULAR = 495
        const val BOLD = 496
        const val QUILL = 497
        const val CLOSE_SPRITE = 535
        const val CLOSE_SPRITE_HOVER = 536
    }
}

fun ComponentDefinitions.at(x: Int, y: Int, w: Int, h: Int) {
    this.x = x; this.y = y; width = w; height = h
}

/** Right-click options; also sets the access mask so the client sends them to the server. */
fun ComponentDefinitions.ops(vararg names: String) {
    actions = arrayOf(*names)
    var mask = 0
    for (i in names.indices) mask = mask or (1 shl (i + 1))
    accessMask = mask
}
