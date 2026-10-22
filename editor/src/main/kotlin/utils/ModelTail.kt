package utils

/**
 * Models from rev 211-era caches, made ready for a build 241 cache.
 *
 * Build 241 models in the newer encodings (the ones ending in FF FE or FF FD) end their data with a face-depth flag:
 * 0 = none, 1 = one byte per face follows. FF FD models also have a 1-byte field before it (0, or non-zero followed by
 * 10 bytes). Models made before that lack these bytes, and the 241 client then reads past the data (12 of 400 old
 * FF FE and 43 of 400 old FF FD models failed in the audit, A12). [forBuild241] adds them as "none", right before the
 * header, which is what the converter did for GadScape's merged 241 cache (rev241/tools/ModelTail.java; this is a
 * port of it). Old-format and FF FF models are fine in 241 as they are and are never changed.
 */
object ModelTail {
    enum class State {
        /** Already has the 241 tail (or is an FF FE / FF FD model that needs nothing). */
        OK,
        /** An FF FE / FF FD model without the 241 tail: [forBuild241] adds it. */
        NEEDS_TAIL,
        /** Old-format or FF FF model: the same in 211 and 241. */
        OTHER_ENCODING,
        /** An FF FE / FF FD model whose sizes don't add up: left as it is. */
        UNKNOWN,
    }

    class Checked(val state: State, /** Bytes to insert before the header (0 unless [State.NEEDS_TAIL]). */ val insert: Int = 0)

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)

    /**
     * For FF FE and FF FD models: (end of the data blocks the header describes, start of the header, face count).
     * Null for other encodings (or a model too short to have the header).
     */
    fun layout(b: ByteArray): IntArray? {
        val n = b.size
        if (n < 2 || b[n - 2].toInt() != -1) return null
        if (b[n - 1].toInt() == -2 && n >= 23) {
            val h = n - 23
            val vc = u16(b, h); val fc = u16(b, h + 2); val tc = b[h + 4].toInt() and 255; val tex = b[h + 5].toInt() and 255
            val prio = b[h + 6].toInt() and 255; val alpha = b[h + 7].toInt() and 255; val trans = b[h + 8].toInt() and 255
            val x = u16(b, h + 11); val y = u16(b, h + 13); val z = u16(b, h + 15); val fi = u16(b, h + 17); val vg = u16(b, h + 19)
            val d = vc + fc + (if (prio == 255) fc else 0) + (if (trans == 1) fc else 0) + (if (tex == 1) fc else 0) + vg +
                    (if (alpha == 1) fc else 0) + fi + fc * 2 + tc * 6 + x + y + z
            return intArrayOf(d, h, fc)
        }
        if (b[n - 1].toInt() == -3 && n >= 26) {
            val h = n - 26
            val vc = u16(b, h); val fc = u16(b, h + 2); val tc = b[h + 4].toInt() and 255; val f12 = b[h + 5].toInt() and 255
            val prio = b[h + 6].toInt() and 255; val alpha = b[h + 7].toInt() and 255; val trans = b[h + 8].toInt() and 255
            val hasTex = b[h + 9].toInt() and 255
            val x = u16(b, h + 12); val y = u16(b, h + 14); val z = u16(b, h + 16); val fi = u16(b, h + 18)
            val tcoord = u16(b, h + 20); val vg = u16(b, h + 22)
            if (tc > h) return null // not a real FF FD model
            var c0 = 0; var c13 = 0; var c2 = 0
            for (i in 0 until tc) { val t = b[i].toInt(); if (t == 0) c0++; if (t in 1..3) c13++; if (t == 2) c2++ }
            val d = tc + vc + (if (f12 == 1) fc else 0) + fc + (if (prio == 255) fc else 0) + (if (trans == 1) fc else 0) + vg +
                    (if (alpha == 1) fc else 0) + fi + (if (hasTex == 1) fc * 2 else 0) + tcoord + fc * 2 + x + y + z +
                    c0 * 6 + c13 * 6 + c13 * 6 + c13 * 2 + c13 + c13 * 2 + c2 * 2
            return intArrayOf(d, h, fc)
        }
        return null
    }

    /** What a model needs before it goes into a build 241 cache. */
    fun check(b: ByteArray): Checked {
        val l = layout(b) ?: return Checked(State.OTHER_ENCODING)
        val d = l[0]; val h = l[1]; val fc = l[2]; val rem = h - d
        if (rem < 0 || d < 0) return Checked(State.UNKNOWN)
        if (b[b.size - 1].toInt() == -2) {
            return when {
                rem == 0 -> Checked(State.NEEDS_TAIL, 1)
                rem == 1 && b[d].toInt() == 0 -> Checked(State.OK)
                rem == 1 + fc && b[d].toInt() == 1 -> Checked(State.OK)
                else -> Checked(State.UNKNOWN)
            }
        }
        if (rem == 0) return Checked(State.NEEDS_TAIL, 2)
        val base = if (b[d].toInt() == 0) 1 else 11
        return when {
            rem == base -> Checked(State.NEEDS_TAIL, 1)
            rem == base + 1 && b[d + base].toInt() == 0 -> Checked(State.OK)
            rem == base + 1 + fc && b[d + base].toInt() == 1 -> Checked(State.OK)
            else -> Checked(State.UNKNOWN)
        }
    }

    /**
     * The model as a build 241 cache needs it: an FF FE / FF FD model without the 241 tail gets it (as "no face
     * depths"); anything else comes back as it is (the same array). Safe to call on any model, any number of times.
     */
    fun forBuild241(b: ByteArray): ByteArray {
        val c = check(b)
        if (c.state != State.NEEDS_TAIL) return b
        val h = layout(b)!![1]
        val out = ByteArray(b.size + c.insert)
        System.arraycopy(b, 0, out, 0, h)
        System.arraycopy(b, h, out, h + c.insert, b.size - h) // the inserted bytes stay 0
        if (check(out).state != State.OK) throw IllegalStateException("the model still doesn't have the build 241 tail after adding it")
        return out
    }

    /** The model as a cache of format [rev] needs it (see [forBuild241]; rev 211 caches take models as they are). */
    fun forRevision(b: ByteArray, rev: Int): ByteArray = if (ConfigRev.is241(rev)) forBuild241(b) else b
}
