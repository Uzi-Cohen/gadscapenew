package utils.gambling

import java.io.ByteArrayOutputStream

/**
 * Interface components (IF3) in the build 241 layout, the layout the cache converter round-trips over every
 * component of the 241 cache (server cache-convert IfComp): header, the type's own fields, then click mask, name, ops,
 * drag settings, target verb, the 18 hook lists and the 3 trigger lists. Only the types these screens use.
 *
 * An interface is built top-down with [If3.Builder]: each call adds the next component id, children name their
 * parent by id. Hooks call vanilla clientscripts only (see [GRAPHIC_SWAPPER]).
 */
object If3 {
    const val LAYER = 0
    const val RECT = 3
    const val TEXT = 4
    const val GRAPHIC = 5
    const val MODEL = 6

    /** Vanilla clientscript 44 (build 241): (component, graphic) -> if_setgraphic(graphic, component). */
    const val GRAPHIC_SWAPPER = 44
    /** Hook argument the client replaces with the component the event fired on. */
    const val EVENT_COMPONENT = -2147483645

    /** Fonts (build 241 cache): p11, p12, b12, q8. */
    const val FONT_SMALL = 494
    const val FONT_REGULAR = 495
    const val FONT_BOLD = 496
    const val FONT_FANCY = 497

    private val HOOKS = listOf("onLoad", "onMouseOver", "onMouseLeave", "onTargetLeave", "onTargetEnter", "onVarTransmit",
        "onInvTransmit", "onStatTransmit", "onTimer", "onOp", "onMouseRepeat", "onClick", "onClickRepeat", "onRelease", "onHold",
        "onDrag", "onDragComplete", "onScrollWheel")

    class Comp(val id: Int, val type: Int, val parent: Int) {
        var x = 0
        var y = 0
        var width = 0
        var height = 0
        var widthMode = 0
        var heightMode = 0
        var xMode = 0
        var yMode = 0
        var hidden = false
        // layer
        var noClickThrough = false
        // graphic
        var sprite = -1
        var tiling = false
        // graphic / rect: 0 opaque .. 255 invisible
        var opacity = 0
        // text / rect / graphic shadow
        var font = FONT_REGULAR
        var text = ""
        var xAlign = 0
        var yAlign = 0
        var shadow = true
        var color = 0
        var filled = true
        // model
        var zoom = 0
        var rotX = 0
        var rotY = 0
        val ops = ArrayList<String>()
        var opBase = ""
        val hooks = HashMap<String, List<Any>>()

        fun encode(): ByteArray {
            val o = Out()
            o.u8(255).u8(type).u16(0).u16(x).u16(y).u16(width).u16(height)
                .u8(widthMode).u8(heightMode).u8(xMode).u8(yMode)
                .u16(if (parent < 0) 65535 else parent).u8(if (hidden) 1 else 0)
            when (type) {
                LAYER -> o.u16(0).u16(0).u8(if (noClickThrough) 1 else 0)
                GRAPHIC -> o.i32(sprite).u16(0).u8(if (tiling) 1 else 0).u8(opacity).u8(0).i32(0).u8(0).u8(0)
                MODEL -> {
                    o.i32(-1).u16(0).u16(0).u16(rotX).u16(0).u16(rotY).u16(zoom).u16(65535).u8(0).u16(0)
                    if (widthMode != 0) o.u16(0)
                    if (heightMode != 0) o.u16(0)
                }
                TEXT -> o.u16(font).str(text).u8(0).u8(xAlign).u8(yAlign).u8(if (shadow) 1 else 0).i32(color)
                RECT -> o.i32(color).u8(if (filled) 1 else 0).u8(opacity)
                else -> error("component type $type")
            }
            // click mask: bit n transmits op n
            var mask = 0
            for (i in ops.indices) mask = mask or (1 shl (i + 1))
            o.u24(mask).str(opBase).u8(ops.size)
            for (op in ops) o.str(op)
            o.u8(0).u8(0).u8(0).str("")
            for (h in HOOKS) {
                val args = hooks[h]
                if (args == null) {
                    o.u8(0)
                    continue
                }
                o.u8(args.size)
                for (a in args) {
                    if (a is Int) o.u8(0).i32(a) else o.u8(1).str(a as String)
                }
            }
            repeat(3) { o.u8(0) }
            return o.bytes()
        }
    }

    class Builder(val interfaceId: Int) {
        val comps = ArrayList<Comp>()
        /** Component ids by name, for the server's constants (see GamblingPatchTest). */
        val names = LinkedHashMap<String, Int>()

        private fun add(type: Int, parent: Int, name: String?, block: Comp.() -> Unit): Int {
            val c = Comp(comps.size, type, parent)
            c.block()
            comps.add(c)
            if (name != null) {
                require(names.put(name, c.id) == null) { "duplicate component name $name" }
            }
            return c.id
        }

        fun layer(parent: Int, x: Int, y: Int, w: Int, h: Int, name: String? = null, block: Comp.() -> Unit = {}) =
            add(LAYER, parent, name) { this.x = x; this.y = y; width = w; height = h; block() }

        fun graphic(parent: Int, x: Int, y: Int, sprite: Sprites.S, name: String? = null, block: Comp.() -> Unit = {}) =
            add(GRAPHIC, parent, name) { this.x = x; this.y = y; width = sprite.w; height = sprite.h; this.sprite = sprite.id; block() }

        fun text(parent: Int, x: Int, y: Int, w: Int, h: Int, text: String, font: Int = FONT_REGULAR, color: Int = 0xFF981F,
                 xAlign: Int = 0, yAlign: Int = 1, name: String? = null, block: Comp.() -> Unit = {}) =
            add(TEXT, parent, name) {
                this.x = x; this.y = y; width = w; height = h; this.text = text; this.font = font; this.color = color
                this.xAlign = xAlign; this.yAlign = yAlign; block()
            }

        fun rect(parent: Int, x: Int, y: Int, w: Int, h: Int, color: Int, opacity: Int, name: String? = null, block: Comp.() -> Unit = {}) =
            add(RECT, parent, name) { this.x = x; this.y = y; width = w; height = h; this.color = color; this.opacity = opacity; block() }

        fun model(parent: Int, x: Int, y: Int, w: Int, h: Int, name: String? = null, block: Comp.() -> Unit = {}) =
            add(MODEL, parent, name) { this.x = x; this.y = y; width = w; height = h; block() }

        /** A graphic button: [normal], [hover] on mouse-over (vanilla script 44), the given ops. */
        fun button(parent: Int, x: Int, y: Int, normal: Sprites.S, hover: Sprites.S?, vararg ops: String, name: String? = null,
                   block: Comp.() -> Unit = {}) = graphic(parent, x, y, normal, name) {
            this.ops.addAll(ops)
            if (hover != null) hover(normal, hover)
            block()
        }

        operator fun get(name: String) = names[name] ?: error("no component $name in $interfaceId")

        fun encode(): List<ByteArray> = comps.map { it.encode() }
    }

    fun Comp.hover(normal: Sprites.S, hover: Sprites.S) {
        hooks["onMouseOver"] = listOf(GRAPHIC_SWAPPER, EVENT_COMPONENT, hover.id)
        hooks["onMouseLeave"] = listOf(GRAPHIC_SWAPPER, EVENT_COMPONENT, normal.id)
    }

    private class Out {
        private val b = ByteArrayOutputStream(256)
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { b.write((v shr 8) and 0xFF); b.write(v and 0xFF) }
        fun u24(v: Int) = apply { b.write((v shr 16) and 0xFF); u16(v) }
        fun i32(v: Int) = apply { u16(v ushr 16); u16(v) }
        fun str(s: String) = apply { b.write(s.toByteArray(Charsets.ISO_8859_1)); b.write(0) }
        fun bytes(): ByteArray = b.toByteArray()
    }
}
