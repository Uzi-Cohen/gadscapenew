package utils

import com.displee.cache.CacheLibrary

/** Just enough of the item definitions (config archive 10) for the shop builder: name, price, model, recolours. */
class ItemDef(val id: Int) {
    var name = "null"
    var cost = 1
    var model = -1
    var noted = false
    var link = -1
    var recolorFrom: ShortArray? = null
    var recolorTo: ShortArray? = null
    override fun toString() = "$id  $name"
}

object ItemDefs {
    fun load(lib: CacheLibrary): Map<Int, ItemDef> {
        val out = HashMap<Int, ItemDef>()
        val archive = lib.index(2).archive(10) ?: return out
        for (id in archive.fileIds()) {
            val data = lib.data(2, 10, id) ?: continue
            out[id] = decode(id, data)
        }
        // noted items have no name or picture of their own: borrow the real item's
        for (d in out.values) if (d.noted) out[d.link]?.let { real ->
            d.name = real.name + " (noted)"
            if (d.model < 0 || d.recolorFrom == null) { d.model = real.model; d.recolorFrom = real.recolorFrom; d.recolorTo = real.recolorTo }
            d.cost = real.cost
        }
        return out
    }

    private fun decode(id: Int, b: ByteArray): ItemDef {
        val d = ItemDef(id)
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
        fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
        try {
            while (p < b.size) {
                when (val op = u8()) {
                    0 -> return d
                    1 -> d.model = u16()
                    2 -> d.name = str()
                    3, 9 -> str()
                    4, 5, 6, 7, 8 -> u16()
                    11, 16, 65 -> {}
                    12 -> d.cost = i32()
                    13, 14, 27, 115 -> u8()
                    23, 25 -> { u16(); u8() }
                    24, 26, 78, 79, 90, 91, 92, 93, 94, 95, 110, 111, 112, 139, 140, 148, 149 -> u16()
                    in 30..39 -> str()
                    40 -> { val n = u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n)
                        for (i in 0 until n) { d.recolorFrom!![i] = u16().toShort(); d.recolorTo!![i] = u16().toShort() } }
                    41 -> { val n = u8(); repeat(n) { u16(); u16() } }
                    42 -> u8()
                    43 -> { val n = u8(); repeat(n) { u8(); str() } } // newer sub-options; harmless if absent
                    75 -> u16()
                    97 -> d.link = u16()
                    98 -> { u16(); d.noted = true }
                    in 100..109 -> { u16(); u16() }
                    113, 114 -> u8()
                    249 -> { val n = u8(); repeat(n) { val isStr = u8() == 1; u24(); if (isStr) str() else i32() } }
                    else -> return d // unknown opcode: keep what we have (the name comes early)
                }
            }
        } catch (e: Exception) { }
        return d
    }
}
