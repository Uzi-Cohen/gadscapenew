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
        val rev = ConfigRev.of(lib)
        val archive = lib.index(2).archive(10) ?: return out
        for (id in archive.fileIds()) {
            val data = lib.data(2, 10, id) ?: continue
            out[id] = decode(id, data, rev)
        }
        // noted items have no name or picture of their own: borrow the real item's
        for (d in out.values) if (d.noted) out[d.link]?.let { real ->
            d.name = real.name + " (noted)"
            if (d.model < 0 || d.recolorFrom == null) { d.model = real.model; d.recolorFrom = real.recolorFrom; d.recolorTo = real.recolorTo }
            d.cost = real.cost
        }
        return out
    }

    /** Reads the few fields the shop builder needs, in the item format of a [rev] cache (see [ItemFull] for both). */
    fun decode(id: Int, b: ByteArray, rev: Int): ItemDef {
        val d = ItemDef(id)
        val r241 = ConfigRev.is241(rev)
        val i = CfgIn(b)
        try {
            while (i.p < b.size) {
                val op = i.u8()
                when {
                    op == 0 -> return d
                    op == 2 -> d.name = i.str()
                    op == 3 || op == 9 || op in 30..39 -> i.skipStr()
                    op in 4..8 || op == 75 || op == 94 || op == 95 || op in 110..112 || op == 139 || op == 140 || op == 148 || op == 149 -> i.skip(2)
                    op == 11 || op == 16 || op == 65 -> {}
                    op == 12 -> d.cost = i.i32()
                    op == 13 || op == 14 || op == 27 || op == 42 || op in 113..115 -> i.skip(1)
                    op == 40 -> { val n = i.u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n)
                        for (k in 0 until n) { d.recolorFrom!![k] = i.u16().toShort(); d.recolorTo!![k] = i.u16().toShort() } }
                    op == 41 -> i.skip(4 * i.u8())
                    op == 43 -> { i.u8(); while (i.u8() != 0) i.skipStr() }
                    op == 97 -> d.link = i.u16()
                    op == 98 -> { i.u16(); d.noted = true }
                    op in 100..109 -> i.skip(4)
                    op == 249 -> CfgParams.read(i, rev)
                    // model ids: 2 bytes in rev 211 (1, 23-26, 78, 79, 90-93), ints in build 241 (44-54)
                    !r241 && op == 1 -> d.model = i.u16()
                    !r241 && (op == 23 || op == 25) -> i.skip(3)
                    !r241 && (op == 24 || op == 26 || op == 78 || op == 79 || op in 90..93) -> i.skip(2)
                    r241 && op == 44 -> d.model = i.i32()
                    r241 && (op == 45 || op == 48) -> i.skip(5)
                    r241 && op in 46..54 -> i.skip(4)
                    // build 241's newer settings
                    r241 && (op == 15 || op == 160 || op == 251) -> {}
                    r241 && op == 99 -> i.skip(2)
                    r241 && op == 161 -> i.skip(2 * i.u16())
                    r241 && op == 200 -> { i.skip(2); i.skipStr() }
                    r241 && op == 201 -> { i.skip(13); i.skipStr() }
                    r241 && op == 202 -> { i.skip(15); i.skipStr() }
                    else -> return d // unknown opcode: keep what we have (the name comes early)
                }
            }
        } catch (e: Exception) { }
        return d
    }
}
