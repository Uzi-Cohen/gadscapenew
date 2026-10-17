package cache

import javafx.scene.image.WritableImage

/**
 * Reads the interfaces of an old-style (317) cache: idx0 archive 3, file "data", plus their sprites
 * from the media archive (idx0 archive 4). Custom clients add their own fields; reading stops at the first
 * interface it can't follow and everything read up to then is kept.
 */
class Interface317(private val cache: Cache317Maps) {
    class Child(val id: Int, val x: Int, val y: Int)
    class Iface(val id: Int, val parent: Int) {
        var type = 0; var width = 0; var height = 0
        var text: String? = null
        var color = 0
        var filled = false
        var sprite: String? = null
        var tooltip: String? = null
        val children = ArrayList<Child>()
    }

    val all = LinkedHashMap<Int, Iface>()
    var stoppedAt = -1
    var problem: String? = null
    private var media: ByteArray? = null
    private val spriteCache = HashMap<String, WritableImage?>()

    private class In(val b: ByteArray) {
        var p = 0
        fun left() = b.size - p
        fun u8() = b[p++].toInt() and 255
        fun u16() = (u8() shl 8) or u8()
        fun s16() = u16().toShort().toInt()
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun str(): String { val s = p; while (b[p].toInt() != 10) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
    }

    init {
        val archive = cache.readFile(0, 3)
        val data = archive?.let { cache.archiveEntry(it, "data") }
        if (data == null) problem = "No interface archive in this cache."
        else parse(data)
        media = cache.readFile(0, 4)
    }

    private fun parse(data: ByteArray) {
        val s = In(data)
        var parentId = -1
        try {
            s.u16() // count of interfaces (max id + 1)
            while (s.left() > 0) {
                var id = s.u16()
                if (id == 65535) { parentId = s.u16(); id = s.u16() }
                stoppedAt = id
                val f = Iface(id, parentId)
                f.type = s.u8()
                val action = s.u8()
                s.u16() // content type
                f.width = s.u16(); f.height = s.u16()
                s.u8() // opacity
                val hover = s.u8(); if (hover != 0) s.u8()
                val cmp = s.u8(); repeat(cmp) { s.u8(); s.u16() }
                val scripts = s.u8(); repeat(scripts) { val n = s.u16(); repeat(n) { s.u16() } }
                if (f.type == 0) {
                    s.u16(); s.u8()
                    val n = s.u16()
                    repeat(n) { f.children.add(Child(s.u16(), s.s16(), s.s16())) }
                }
                if (f.type == 1) { s.u16(); s.u8() }
                if (f.type == 2) {
                    s.u8(); s.u8(); s.u8(); s.u8(); s.u8(); s.u8()
                    repeat(20) { if (s.u8() == 1) { s.s16(); s.s16(); s.str() } }
                    repeat(5) { s.str() }
                }
                if (f.type == 3) f.filled = s.u8() == 1
                if (f.type == 4 || f.type == 1) { s.u8(); s.u8(); s.u8() }
                if (f.type == 4) { f.text = s.str(); s.str() }
                if (f.type == 1 || f.type == 3 || f.type == 4) f.color = s.i32()
                if (f.type == 3 || f.type == 4) { s.i32(); s.i32(); s.i32() }
                if (f.type == 5) { f.sprite = s.str().ifBlank { null }; s.str() }
                if (f.type == 6) {
                    val a = s.u8(); if (a != 0) s.u8()
                    val b = s.u8(); if (b != 0) s.u8()
                    val c = s.u8(); if (c != 0) s.u8()
                    val d = s.u8(); if (d != 0) s.u8()
                    s.u16(); s.u16(); s.u16()
                }
                if (f.type == 7) {
                    s.u8(); s.u8(); s.u8(); s.i32(); s.s16(); s.s16(); s.u8()
                    repeat(5) { s.str() }
                }
                if (action == 2 || f.type == 2) { s.str(); s.str(); s.u16() }
                if (f.type == 8) f.text = s.str()
                if (action == 1 || action == 4 || action == 5 || action == 6) f.tooltip = s.str()
                all[id] = f
            }
            stoppedAt = -1
        } catch (e: Exception) {
            problem = "Read ${all.size} interface parts; stopped at part $stoppedAt (this client uses its own extra fields from there)."
        }
    }

    /** Top-level screens: parts that are their own parent and have children. */
    fun screens(): List<Int> = all.values.filter { it.type == 0 && (it.parent == it.id || it.parent == -1 || all[it.parent] == null) && it.children.isNotEmpty() }
        .map { it.id }.sorted()

    /** "name,index" sprites from the media archive (317 format). */
    fun sprite(spec: String): WritableImage? = spriteCache.getOrPut(spec) {
        try {
            val m = media ?: return@getOrPut null
            val name = spec.substringBefore(",")
            val index = spec.substringAfter(",", "0").trim().toIntOrNull() ?: 0
            val dat = cache.archiveEntry(m, "$name.dat") ?: return@getOrPut null
            val idx = cache.archiveEntry(m, "index.dat") ?: return@getOrPut null
            val d = In(dat); val ix = In(idx)
            ix.p = d.u16()
            ix.u16(); ix.u16()
            val palSize = ix.u8()
            val pal = IntArray(palSize)
            for (k in 0 until palSize - 1) { pal[k + 1] = (ix.u8() shl 16) or (ix.u8() shl 8) or ix.u8(); if (pal[k + 1] == 0) pal[k + 1] = 1 }
            for (l in 0 until index) { ix.p += 2; d.p += ix.u16() * ix.u16(); ix.p++ }
            ix.u8(); ix.u8()
            val w = ix.u16(); val h = ix.u16()
            val type = ix.u8()
            if (w <= 0 || h <= 0 || w > 2048 || h > 2048) return@getOrPut null
            val px = IntArray(w * h)
            if (type == 0) for (i in 0 until w * h) px[i] = pal[d.u8()]
            else for (x in 0 until w) for (y in 0 until h) px[x + y * w] = pal[d.u8()]
            val img = WritableImage(w, h)
            for (y in 0 until h) for (x in 0 until w) { val c = px[x + y * w]; img.pixelWriter.setArgb(x, y, if (c == 0) 0 else (0xFF shl 24) or c) }
            img
        } catch (e: Exception) { null }
    }
}
