package com.gadscape.game.content.gamble.ui

import com.gadscape.game.content.ui.CacheUi
import com.gadscape.game.content.ui.ClientScriptsCache
import com.gadscape.game.content.ui.UiBuilder
import com.gadscape.game.content.ui.at
import com.gadscape.game.content.ui.ops
import mgi.tools.jagcached.ArchiveType
import mgi.tools.jagcached.cache.Cache
import mgi.types.component.ComponentDefinitions
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Roat Pkz's gambling sprites (resources/gadscape/gamble/sprites, listed in index.txt), packed as sprite groups
 * from [BASE] in index order. Names are Roat's sprite names lower-cased with '_' for spaces, e.g.
 * "diceinterface/side/roll_button_0"; [id] also takes Roat's own spelling ("/DiceInterface/side/roll_Button 0").
 */
object RoatSprites {

    const val BASE = 7200
    private const val DIR = "/gadscape/gamble/sprites"

    val names: List<String> by lazy {
        RoatSprites::class.java.getResourceAsStream("$DIR/index.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
    }
    private val index: Map<String, Int> by lazy { names.withIndex().associate { (i, n) -> n to i } }
    private val sizes = HashMap<String, IntArray>()

    fun key(name: String) = name.trim().trimStart('/').lowercase().replace(' ', '_')

    fun id(name: String): Int = BASE + (index[key(name)] ?: error("no gamble sprite '$name'"))

    fun has(name: String) = index.containsKey(key(name))

    fun image(name: String): BufferedImage =
        RoatSprites::class.java.getResourceAsStream("$DIR/${key(name)}.png")?.use { ImageIO.read(it) }
            ?: error("missing sprite ${key(name)}.png")

    /** Width and height of a sprite. */
    fun size(name: String): IntArray = synchronized(sizes) {
        sizes.getOrPut(key(name)) { image(name).let { intArrayOf(it.width, it.height) } }
    }

    fun pack(cache: Cache) {
        val archive = cache.getArchive(ArchiveType.SPRITES)
        for ((i, name) in names.withIndex()) CacheUi.packSprite(archive, BASE + i, SpriteFit.fit(image(name), matte(name)))
    }

    /** Colour Roat's half-transparent pixels are blended with: what the sprite is drawn on. */
    private fun matte(name: String) = when {
        "/side/" in name && !name.endsWith("line_top_1") -> 0x3E3529 // the side panel's stone
        else -> 0x453C32 // the windows' background
    }
}

/**
 * Makes a PNG fit the cache's sprite format: one fully transparent colour and at most 255 others.
 * Half-transparent pixels are blended onto [matte] (or dropped when nearly clear), then a median cut
 * brings the colours down to 255 when there are more.
 */
object SpriteFit {

    private const val MAX_COLOURS = 254 // packSprite may add one for black

    fun fit(src: BufferedImage, matte: Int): BufferedImage {
        val w = src.width
        val h = src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val colours = HashMap<Int, Int>()
        for (x in 0 until w) for (y in 0 until h) {
            val p = src.getRGB(x, y)
            val a = p ushr 24
            if (a < 24) continue
            val rgb = if (a >= 250) p and 0xFFFFFF else blend(p, a, matte)
            out.setRGB(x, y, (0xFF shl 24) or rgb)
            colours.merge(rgb, 1, Int::plus)
        }
        if (colours.size <= MAX_COLOURS) return out
        val palette = medianCut(colours, MAX_COLOURS)
        val nearest = HashMap<Int, Int>()
        for (x in 0 until w) for (y in 0 until h) {
            val p = out.getRGB(x, y)
            if (p ushr 24 == 0) continue
            val rgb = nearest.getOrPut(p and 0xFFFFFF) { closest(p and 0xFFFFFF, palette) }
            out.setRGB(x, y, (0xFF shl 24) or rgb)
        }
        return out
    }

    private fun blend(p: Int, a: Int, matte: Int): Int {
        fun ch(shift: Int) = ((p shr shift and 0xFF) * a + (matte shr shift and 0xFF) * (255 - a)) / 255
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private class Box(val colours: List<IntArray>) { // r, g, b, count
        fun range(c: Int) = colours.maxOf { it[c] } - colours.minOf { it[c] }
        val widest get() = (0..2).maxBy { range(it) }
        val score get() = range(widest).toLong() * colours.sumOf { it[3].toLong() }
        fun average(): Int {
            var n = 0L
            val sum = LongArray(3)
            for (c in colours) {
                for (i in 0..2) sum[i] += c[i].toLong() * c[3]
                n += c[3]
            }
            return ((sum[0] / n).toInt() shl 16) or ((sum[1] / n).toInt() shl 8) or (sum[2] / n).toInt()
        }
    }

    private fun medianCut(colours: Map<Int, Int>, max: Int): IntArray {
        val boxes = mutableListOf(Box(colours.map { (rgb, n) -> intArrayOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF, n) }))
        while (boxes.size < max) {
            val box = boxes.filter { it.colours.size > 1 }.maxByOrNull { it.score } ?: break
            val c = box.widest
            val sorted = box.colours.sortedBy { it[c] }
            val half = sorted.sumOf { it[3].toLong() } / 2
            var acc = 0L
            var cut = 1
            for ((i, col) in sorted.withIndex()) {
                acc += col[3]
                if (acc >= half) { cut = (i + 1).coerceIn(1, sorted.size - 1); break }
            }
            boxes.remove(box)
            boxes += Box(sorted.subList(0, cut))
            boxes += Box(sorted.subList(cut, sorted.size))
        }
        return boxes.map { it.average() }.distinct().toIntArray()
    }

    private fun closest(rgb: Int, palette: IntArray): Int {
        var best = palette[0]
        var bestD = Int.MAX_VALUE
        for (c in palette) {
            val dr = (rgb shr 16 and 0xFF) - (c shr 16 and 0xFF)
            val dg = (rgb shr 8 and 0xFF) - (c shr 8 and 0xFF)
            val db = (rgb and 0xFF) - (c and 0xFF)
            val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }
}

/**
 * Builds a GadScape interface from Roat Pkz's interface code, call for call: each method takes the same
 * arguments as the Roat call it stands for (child position, Roat component id, sprite name, text, colour,
 * centred, shadow, font index), so the screens line up with theirs. Roat component ids map to the new
 * component ids through [c], which the server code uses to address them.
 *
 * - Roat text is drawn with its baseline [y] + font height and, when centred, around x (+ width / 2);
 *   that is a top-aligned text component here.
 * - Buttons with a hover sprite swap graphics on mouse over/leave through [ClientScriptsCache.SET_GRAPHIC];
 *   clickable texts turn white on hover like Roat's.
 * - Roat's @xxx@ colour codes become <col> tags and "\n" becomes <br>; <img> tags (Roat's own icons) are dropped.
 */
class RoatUi(val interfaceId: Int, width: Int, height: Int, side: Boolean) {

    private val builder = UiBuilder(interfaceId)
    private val ids = HashMap<Int, Int>()
    private val heights = HashMap<Int, Int>()

    val components: List<ComponentDefinitions> get() = builder.list

    init {
        if (side) {
            builder.layer(0, -1) { widthMode = 1; heightMode = 1 }
        } else {
            builder.layer(0, -1) { xMode = 1; yMode = 1; at(0, 0, width, height) }
        }
        ids[ROOT] = 0
        heights[0] = height
    }

    /** The component id for a Roat component id. */
    fun c(roatId: Int): Int = ids[roatId] ?: error("interface $interfaceId has no Roat component $roatId")

    fun has(roatId: Int) = ids.containsKey(roatId)

    private val reverse: Map<Int, Int> by lazy { ids.entries.associate { (roat, id) -> id to roat } }

    /** The Roat component id of a component, for routing clicks. */
    fun roatId(component: Int): Int? = reverse[component]

    /** Packed interface/component id, for client script arguments. */
    fun packed(roatId: Int) = interfaceId shl 16 or c(roatId)

    private fun next(roatId: Int): Int {
        check(!ids.containsKey(roatId)) { "Roat component $roatId added twice" }
        val id = builder.next
        ids[roatId] = id
        return id
    }

    /** addTabInterface(id) used as a child: a layer the size of [w] x [h] (Roat's sub-interfaces have no size). */
    fun container(roatId: Int, parent: Int, x: Int, y: Int, w: Int, h: Int, hide: Boolean = false, scrollHeight: Int = 0) {
        val id = next(roatId)
        builder.layer(id, c(parent)) {
            at(x, y, w, h)
            hidden = hide
            if (scrollHeight > 0) this.scrollHeight = scrollHeight
        }
    }

    /** new InterfaceImage(id, sprite) / addSprite / addHDSprite. */
    fun image(roatId: Int, parent: Int, x: Int, y: Int, sprite: String, hide: Boolean = false) {
        val id = next(roatId)
        val size = RoatSprites.size(sprite)
        builder.sprite(id, c(parent), RoatSprites.id(sprite)) {
            at(x, y, size[0], size[1])
            hidden = hide
        }
    }

    /**
     * new ButtonWithHover(id, base, normal, hover, tooltip) and CloseButton: sprite "[base] [normal]" that shows
     * "[base] [hover]" while the mouse is on it; the tooltip is its right-click option.
     */
    fun button(roatId: Int, parent: Int, x: Int, y: Int, base: String, normal: Int, hover: Int, tooltip: String, hide: Boolean = false) {
        val id = next(roatId)
        val normalSprite = "$base $normal"
        val size = RoatSprites.size(normalSprite)
        val packed = interfaceId shl 16 or id
        builder.sprite(id, c(parent), RoatSprites.id(normalSprite)) {
            at(x, y, size[0], size[1])
            hidden = hide
            ops(tooltip)
            if (hover != normal) {
                onMouseOverListener = arrayOf<Any>(ClientScriptsCache.SET_GRAPHIC, RoatSprites.id("$base $hover"), packed)
                onMouseLeaveListener = arrayOf<Any>(ClientScriptsCache.SET_GRAPHIC, RoatSprites.id(normalSprite), packed)
            }
        }
    }

    /**
     * addText(id, text, colour, centred, shadow, 0, tda, font). Roat centres text on x + [roatWidth] / 2
     * (their texts have width 0 unless the code sets one).
     */
    fun text(
        roatId: Int, parent: Int, x: Int, y: Int, text: String, colour: Int, centred: Boolean, shadow: Boolean, font: Int,
        hide: Boolean = false, roatWidth: Int = 0,
    ) {
        val id = next(roatId)
        val w = 480
        val centre = x + roatWidth / 2
        builder.text(id, c(parent), convert(text), FONTS[font], colour) {
            if (centred) at(centre - w / 2, y, w, lineHeight(font, text)) else at(x, y, w, lineHeight(font, text))
            xAllignment = if (centred) 1 else 0
            yAllignment = 0
            textShadowed = shadow
            hidden = hide
        }
    }

    /** addClickableText(id, text, tooltip, tda, font, colour, centred, shadow, width, height): white on hover. */
    fun clickText(
        roatId: Int, parent: Int, x: Int, y: Int, text: String, tooltip: String, font: Int, colour: Int, centred: Boolean,
        shadow: Boolean, w: Int, h: Int, hide: Boolean = false,
    ) {
        val id = next(roatId)
        val packed = interfaceId shl 16 or id
        builder.text(id, c(parent), convert(text), FONTS[font], colour) {
            at(x, y, w, maxOf(h, lineHeight(font, text)))
            xAllignment = if (centred) 1 else 0
            yAllignment = 0
            textShadowed = shadow
            hidden = hide
            ops(tooltip)
            onMouseOverListener = arrayOf<Any>(ClientScriptsCache.SET_COLOUR, WHITE, packed)
            onMouseLeaveListener = arrayOf<Any>(ClientScriptsCache.SET_COLOUR, colour, packed)
        }
    }

    /** The OSRS scrollbar for the scrolling container [layer], [h] high. */
    fun scrollbar(roatId: Int, parent: Int, x: Int, y: Int, h: Int, layer: Int) {
        val id = next(roatId)
        builder.scrollbar(id, c(parent), c(layer)) { at(x, y, 16, h) }
    }

    /** A see-through rectangle ([alpha] 0-255 like a PNG's), standing in for Roat's half-transparent overlay sprites. */
    fun shade(roatId: Int, parent: Int, x: Int, y: Int, w: Int, h: Int, colour: Int, alpha: Int, hide: Boolean = false) {
        val id = next(roatId)
        builder.rect(id, c(parent), colour, true) {
            at(x, y, w, h)
            opacity = 255 - alpha
            hidden = hide
        }
    }

    /** A plain clickable area (for sliders and other things Roat draws itself). */
    fun hotspot(roatId: Int, parent: Int, x: Int, y: Int, w: Int, h: Int, tooltip: String, hide: Boolean = false) {
        val id = next(roatId)
        builder.rect(id, c(parent), 0, true) {
            at(x, y, w, h)
            opacity = 255
            hidden = hide
            ops(tooltip)
        }
    }

    companion object {
        /** Parent id meaning the interface's root layer. */
        const val ROOT = -1
        const val WHITE = 0xFFFFFF

        /** Roat's fonts by index: small, regular, bold, quill. */
        val FONTS = intArrayOf(UiBuilder.SMALL, UiBuilder.REGULAR, UiBuilder.BOLD, UiBuilder.QUILL)

        private fun lineHeight(font: Int, text: String) = (if (font == 0) 13 else 15) * (text.split("\\n").size)

        private val CODES = mapOf(
            "mon" to "00ff80", "re1" to "a10c0c", "red" to "ff0000", "lye" to "eae74b", "cre" to "fbd792",
            "pur" to "810a81", "pu2" to "b91dee", "pu3" to "c3acd6", "gro" to "c6b895", "re2" to "750d0d",
            "gre" to "00ff00", "lge" to "94cc95", "blu" to "0000ff", "bl4" to "1d4bff", "bl3" to "1877cb",
            "yel" to "ffff00", "ye2" to "ffff64", "ye3" to "f2f27f", "ye4" to "ffe240", "cya" to "05f1ee",
            "mag" to "ff00ff", "ma2" to "f97329", "whi" to "ffffff", "bla" to "000000", "ki1" to "224cab",
            "ki2" to "006902", "bl2" to "867b65", "lre" to "ff9040", "lr2" to "da6a1a", "lr3" to "dd7c37",
            "dge" to "006b00", "gol" to "ae8e2b", "gra" to "777777", "dre" to "800000", "dr1" to "580101",
            "dbl" to "000080", "db1" to "0b144b", "pin" to "ff76d0", "bro" to "46320a", "pi1" to "e50699",
            "pi2" to "cf9e9b", "pea" to "e0a7a4", "or1" to "ffb000", "or2" to "ff7000", "or3" to "ff3000",
            "or4" to "a85400", "or5" to "ff9900", "or6" to "f69019", "or7" to "fdba44", "or8" to "fcc000",
            "ora" to "ff981f", "gr1" to "c0ff00", "gr2" to "80ff00", "gr3" to "40ff00", "gr4" to "005f00",
            "gr5" to "3ed32d", "due" to "aa8822", "cu1" to "837a73", "cu2" to "a99d94",
        )
        private val CODE = Regex("@([a-z0-9]{3})@")
        private val IMG = Regex("<img=[^>]*> ?")

        /** Roat text markup to OSRS markup. */
        fun convert(text: String): String = text
            .replace(IMG, "")
            .replace(CODE) { m -> CODES[m.groupValues[1]]?.let { "<col=$it>" } ?: m.value }
            .replace("\\n", "<br>")
            .replace("—", "-")
    }
}
