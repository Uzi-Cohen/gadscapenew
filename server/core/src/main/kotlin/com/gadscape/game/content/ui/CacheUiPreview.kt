package com.gadscape.game.content.ui

import com.zenyte.CacheManager
import mgi.tools.jagcached.ArchiveType
import mgi.tools.jagcached.cache.Cache
import mgi.types.component.ComponentDefinitions
import mgi.types.config.draw.font.Fonts
import mgi.types.config.draw.toBufferedImage
import mgi.types.config.items.ItemDefinitions
import mgi.types.config.items.item
import mgi.types.config.items.loadSpritePixels
import mgi.types.draw.Rasterizer2D
import mgi.types.draw.Rasterizer3D
import mgi.types.draw.font.Font
import mgi.types.draw.model.TextureProvider
import mgi.types.draw.sprite.SpriteGroupDefinitions
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Dev tool: draws an interface from a cache to a PNG (2x scale) with the game's own fonts, sprites and item
 * icons, so a layout can be checked without running the client. Not used by the server.
 *
 *     CacheUiPreview.open("/path/to/cache/")      // runs CacheUi.ensure on that cache first
 *     CacheUiPreview.render(5110, 190, 261, 0x3E3529, texts = mapOf(12 to "Hello"), out = "x.png")
 *
 * texts: component -> text; hidden/shown override the cache's hidden flag (hidden parents hide children);
 * items: component -> (itemId, amount). Scroll layers are drawn at scroll 0 without clipping.
 */
object CacheUiPreview {

    lateinit var cache: Cache
    private val fonts = HashMap<Int, Font>()

    @JvmStatic
    fun open(path: String) {
        CacheUi.ensure(path)
        cache = Cache.openCache(path, true)
        CacheManager.loadCache(cache)
        ItemDefinitions().load(cache)
        val tp = TextureProvider(cache, 20, 0.6, 128)
        Rasterizer3D.Rasterizer3D_setTextureLoader(tp)
        Rasterizer3D.Rasterizer3D_setBrightness(0.6)
    }

    fun components(group: Int): List<ComponentDefinitions> {
        val g = cache.getArchive(ArchiveType.INTERFACES).findGroupByID(group)
        return (0 until g.highestFileId).map { i ->
            val d = g.findFileByID(i).data
            d.position = 0
            ComponentDefinitions(group shl 16 or i, d)
        }
    }

    fun spriteImage(id: Int): BufferedImage? {
        val f = cache.getArchive(ArchiveType.SPRITES).findGroupByID(id)?.findFileByID(0) ?: return null
        val d = f.data
        d.position = 0
        return SpriteGroupDefinitions.decode(id, d).getImage(0)
    }

    @JvmStatic
    fun render(
        group: Int, w: Int, h: Int, bg: Int,
        texts: Map<Int, String> = emptyMap(),
        hidden: Set<Int> = emptySet(),
        shown: Set<Int> = emptySet(),
        items: Map<Int, Pair<Int, Int>> = emptyMap(),
        out: String,
    ) {
        val pixels = IntArray(w * h) { bg }
        Rasterizer2D.Rasterizer2D_replace(pixels, w, h)
        val list = components(group)
        val pos = HashMap<Int, IntArray>()
        fun parentOf(c: ComponentDefinitions) = if (c.parentId == -1) -1 else c.parentId and 0xFFFF
        for (c in list) {
            val p = parentOf(c)
            val parent = if (p == -1) intArrayOf(0, 0, w, h) else pos[p]!!
            val (px, py, pw, ph) = parent.toList()
            val cw = if (c.widthMode == 1) pw - c.width else c.width
            val ch = if (c.heightMode == 1) ph - c.height else c.height
            val cx = px + when (c.xMode) { 1 -> (pw - cw) / 2 + c.x; 2 -> pw - cw - c.x; else -> c.x }
            val cy = py + when (c.yMode) { 1 -> (ph - ch) / 2 + c.y; 2 -> ph - ch - c.y; else -> c.y }
            pos[c.componentId] = intArrayOf(cx, cy, cw, ch)
        }
        fun isHidden(c: ComponentDefinitions): Boolean {
            val id = c.componentId
            val self = if (id in shown) false else if (id in hidden) true else c.hidden
            if (self) return true
            val p = parentOf(c)
            return p != -1 && isHidden(list[p])
        }
        fun plot(x: Int, y: Int, rgb: Int) {
            if (x in 0 until w && y in 0 until h) pixels[y * w + x] = rgb
        }
        for (c in list) {
            if (isHidden(c)) continue
            val (x, y, cw, ch) = pos[c.componentId]!!.toList()
            when (c.type) {
                3 -> if (c.filled) Rasterizer2D.Rasterizer2D_fillRectangle(x, y, cw, ch, c.color) else {
                    for (xx in x until x + cw) { plot(xx, y, c.color); plot(xx, y + ch - 1, c.color) }
                    for (yy in y until y + ch) { plot(x, yy, c.color); plot(x + cw - 1, yy, c.color) }
                }
                5 -> {
                    val item = items[c.componentId]
                    val img = if (item != null) cache.item(item.first).loadSpritePixels(cache, amount = item.second).toBufferedImage()
                    else if (c.spriteId >= 0) spriteImage(c.spriteId) else null
                    if (img != null) {
                        val tile = c.spriteTiling && item == null
                        val sx = if (tile) cw else img.width
                        val sy = if (tile) ch else img.height
                        for (ix in 0 until sx) for (iy in 0 until sy) {
                            val a = img.getRGB(ix % img.width, iy % img.height)
                            if (a ushr 24 == 0) continue
                            plot(x + ix, y + iy, a and 0xFFFFFF)
                        }
                    }
                }
                4 -> {
                    val t = texts[c.componentId] ?: c.text
                    if (t.isEmpty()) continue
                    val f = fonts.getOrPut(c.font) { Fonts.load(cache, c.font, 0) }
                    val base = when (c.yAllignment) {
                        0 -> y + f.ascent
                        2 -> y + ch - 2
                        else -> y + (ch + f.ascent) / 2 - 1
                    }
                    when (c.xAllignment) {
                        1 -> f.drawCentered(t, x + cw / 2, base, c.color, if (c.textShadowed) 0 else -1)
                        2 -> f.drawRightAligned(t, x + cw, base, c.color, if (c.textShadowed) 0 else -1)
                        else -> f.draw(t, x, base, c.color, if (c.textShadowed) 0 else -1)
                    }
                }
            }
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        val big = BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB)
        big.createGraphics().apply { drawImage(img, 0, 0, w * 2, h * 2, null); dispose() }
        ImageIO.write(big, "png", File(out))
    }
}
