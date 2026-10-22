package utils

import cache.definitions.ModelDefinition
import controllers.AnimClip

/**
 * Skeletal (2021+, "animaya") animations, ported from the 211 client so the editor poses models the way the game
 * does: keyframe sets (archive 22 in build 241, archive 0 in rev 211; group = id >> 16, file = id & 0xFFFF, the same
 * bytes in both, see [Keyframes]), the bone hierarchy at the end of the skeleton (index 1), curves sampled at
 * whole client cycles, and vertices skinned with their per-vertex bone weights. Floats are kept as Float and the sums
 * in the client's order, so the result matches the game's pose.
 */
object SkeletalAnims {
    private val EPS = Math.ulp(1.0f)
    private val EPS2 = 2 * Math.ulp(1.0f)

    // ---------------- matrices (row-major, row vectors: p' = p * M, translation in 12..14) ----------------

    class Mat4 {
        val m = FloatArray(16)
        init { identity() }
        fun identity(): Mat4 { java.util.Arrays.fill(m, 0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f; return this }
        fun zero(): Mat4 { java.util.Arrays.fill(m, 0f); return this }
        fun set(o: Mat4): Mat4 { System.arraycopy(o.m, 0, m, 0, 16); return this }
        fun copy(): Mat4 = Mat4().set(this)
        fun add(o: Mat4): Mat4 { for (i in 0 until 16) m[i] += o.m[i]; return this }

        /** this = this * o, summed in the client's order. */
        fun mul(o: Mat4): Mat4 {
            val t = m; val p = o.m
            val n0 = p[0] * t[0] + p[4] * t[1] + p[8] * t[2] + p[12] * t[3]
            val n1 = t[3] * p[13] + t[1] * p[5] + t[0] * p[1] + p[9] * t[2]
            val n2 = t[3] * p[14] + p[2] * t[0] + p[6] * t[1] + t[2] * p[10]
            val n3 = t[3] * p[15] + t[2] * p[11] + t[0] * p[3] + p[7] * t[1]
            val n4 = t[7] * p[12] + t[6] * p[8] + p[0] * t[4] + t[5] * p[4]
            val n5 = t[5] * p[5] + t[4] * p[1] + p[9] * t[6] + t[7] * p[13]
            val n6 = p[14] * t[7] + p[10] * t[6] + p[6] * t[5] + p[2] * t[4]
            val n7 = p[11] * t[6] + t[5] * p[7] + t[4] * p[3] + t[7] * p[15]
            val n8 = t[9] * p[4] + p[0] * t[8] + t[10] * p[8] + t[11] * p[12]
            val n9 = p[13] * t[11] + t[10] * p[9] + p[5] * t[9] + p[1] * t[8]
            val n10 = t[11] * p[14] + p[6] * t[9] + p[2] * t[8] + t[10] * p[10]
            val n11 = p[15] * t[11] + p[3] * t[8] + t[9] * p[7] + t[10] * p[11]
            val n12 = t[14] * p[8] + t[12] * p[0] + t[13] * p[4] + p[12] * t[15]
            val n13 = p[13] * t[15] + t[12] * p[1] + t[13] * p[5] + p[9] * t[14]
            val n14 = t[15] * p[14] + p[2] * t[12] + p[6] * t[13] + t[14] * p[10]
            val n15 = t[15] * p[15] + t[14] * p[11] + t[13] * p[7] + t[12] * p[3]
            t[0] = n0; t[1] = n1; t[2] = n2; t[3] = n3; t[4] = n4; t[5] = n5; t[6] = n6; t[7] = n7
            t[8] = n8; t[9] = n9; t[10] = n10; t[11] = n11; t[12] = n12; t[13] = n13; t[14] = n14; t[15] = n15
            return this
        }

        private fun det(): Float {
            val f = m
            return f[8] * f[5] * f[3] * f[14] + f[13] * f[10] * f[3] * f[4] + (f[8] * f[1] * f[6] * f[15] + f[14] * f[1] * f[4] * f[11] +
                    (f[14] * f[9] * f[0] * f[7] + f[11] * f[6] * f[0] * f[13] + (f[15] * f[10] * f[5] * f[0] - f[11] * f[5] * f[0] * f[14] -
                    f[15] * f[9] * f[0] * f[6]) - f[0] * f[7] * f[10] * f[13] - f[1] * f[4] * f[10] * f[15]) - f[12] * f[6] * f[1] * f[11] -
                    f[8] * f[1] * f[7] * f[14] + f[12] * f[10] * f[7] * f[1] + f[2] * f[4] * f[9] * f[15] - f[13] * f[11] * f[4] * f[2] -
                    f[2] * f[5] * f[8] * f[15] + f[12] * f[11] * f[5] * f[2] + f[13] * f[2] * f[7] * f[8] - f[7] * f[2] * f[9] * f[12] -
                    f[14] * f[4] * f[3] * f[9]) - f[3] * f[5] * f[10] * f[12] - f[13] * f[3] * f[6] * f[8] + f[9] * f[6] * f[3] * f[12]
        }

        /** The client's 4x4 inverse (class417.method7922), term for term. */
        fun invert(): Mat4 {
            val f = m
            val v1 = 1.0f / det()
            val v2 = v1 * (f[13] * f[6] * f[11] + (f[10] * f[5] * f[15] - f[14] * f[11] * f[5] - f[6] * f[9] * f[15]) + f[9] * f[7] * f[14] - f[13] * f[10] * f[7])
            val v3 = v1 * (f[13] * f[10] * f[3] + (f[10] * -f[1] * f[15] + f[14] * f[11] * f[1] + f[9] * f[2] * f[15] - f[13] * f[11] * f[2] - f[3] * f[9] * f[14]))
            val v4 = (f[15] * f[1] * f[6] - f[14] * f[7] * f[1] - f[2] * f[5] * f[15] + f[2] * f[7] * f[13] + f[5] * f[3] * f[14] - f[13] * f[6] * f[3]) * v1
            val v5 = (f[9] * f[6] * f[3] + (f[2] * f[5] * f[11] + f[1] * f[7] * f[10] + f[11] * f[6] * -f[1] - f[9] * f[7] * f[2] - f[3] * f[5] * f[10])) * v1
            val v6 = (f[6] * f[8] * f[15] + f[15] * -f[4] * f[10] + f[14] * f[4] * f[11] - f[12] * f[11] * f[6] - f[7] * f[8] * f[14] + f[12] * f[10] * f[7]) * v1
            val v7 = v1 * (f[8] * f[3] * f[14] + f[2] * f[11] * f[12] + (f[10] * f[0] * f[15] - f[0] * f[11] * f[14] - f[15] * f[8] * f[2]) - f[3] * f[10] * f[12])
            val v8 = v1 * (f[2] * f[4] * f[15] + f[15] * f[6] * -f[0] + f[7] * f[0] * f[14] - f[12] * f[7] * f[2] - f[14] * f[3] * f[4] + f[3] * f[6] * f[12])
            val v9 = (f[4] * f[3] * f[10] + f[11] * f[0] * f[6] - f[10] * f[7] * f[0] - f[11] * f[4] * f[2] + f[8] * f[2] * f[7] - f[8] * f[3] * f[6]) * v1
            val v10 = (f[13] * f[7] * f[8] + f[11] * f[5] * f[12] + (f[9] * f[4] * f[15] - f[4] * f[11] * f[13] - f[15] * f[5] * f[8]) - f[12] * f[7] * f[9]) * v1
            val v11 = (f[3] * f[9] * f[12] + (f[8] * f[1] * f[15] + f[9] * -f[0] * f[15] + f[11] * f[0] * f[13] - f[1] * f[11] * f[12] - f[13] * f[3] * f[8])) * v1
            val v12 = (f[15] * f[5] * f[0] - f[0] * f[7] * f[13] - f[1] * f[4] * f[15] + f[1] * f[7] * f[12] + f[13] * f[4] * f[3] - f[12] * f[5] * f[3]) * v1
            val v13 = v1 * (f[3] * f[5] * f[8] + (f[11] * f[4] * f[1] + -f[0] * f[5] * f[11] + f[7] * f[0] * f[9] - f[1] * f[7] * f[8] - f[3] * f[4] * f[9]))
            val v14 = v1 * (f[14] * f[9] * -f[4] + f[13] * f[4] * f[10] + f[8] * f[5] * f[14] - f[12] * f[10] * f[5] - f[8] * f[6] * f[13] + f[12] * f[9] * f[6])
            val v15 = (f[14] * f[0] * f[9] - f[13] * f[0] * f[10] - f[14] * f[8] * f[1] + f[12] * f[1] * f[10] + f[2] * f[8] * f[13] - f[12] * f[2] * f[9]) * v1
            val v16 = v1 * (f[14] * f[1] * f[4] + f[5] * -f[0] * f[14] + f[6] * f[0] * f[13] - f[12] * f[6] * f[1] - f[4] * f[2] * f[13] + f[12] * f[2] * f[5])
            val v17 = (f[9] * f[2] * f[4] + f[1] * f[6] * f[8] + (f[5] * f[0] * f[10] - f[0] * f[6] * f[9] - f[4] * f[1] * f[10]) - f[2] * f[5] * f[8]) * v1
            f[0] = v2; f[1] = v3; f[2] = v4; f[3] = v5; f[4] = v6; f[5] = v7; f[6] = v8; f[7] = v9
            f[8] = v10; f[9] = v11; f[10] = v12; f[11] = v13; f[12] = v14; f[13] = v15; f[14] = v16; f[15] = v17
            return this
        }

        /** Rotation from a quaternion (writes the 3x3 part of an identity, like class417.method7919). */
        fun rotation(x: Float, y: Float, z: Float, w: Float): Mat4 {
            identity()
            val ww = w * w; val xw = x * w; val yw = w * y; val zw = z * w; val xx = x * x; val yx = y * x; val xz = x * z; val yy = y * y; val zy = z * y; val zz = z * z
            m[0] = ww + xx - zz - yy
            m[1] = zw + zw + yx + yx
            m[2] = xz - yw - yw + xz
            m[4] = yx + (yx - zw - zw)
            m[5] = ww + yy - xx - zz
            m[6] = zy + xw + zy + xw
            m[8] = yw + xz + xz + yw
            m[9] = zy - xw - xw + zy
            m[10] = ww + zz - yy - xx
            return this
        }

        fun scale(sx: Float, sy: Float, sz: Float): Mat4 { identity(); m[0] = sx; m[5] = sy; m[10] = sz; return this }

        fun euler(): FloatArray {
            val e0 = (-Math.asin(m[6].toDouble())).toFloat()
            val c = Math.cos(e0.toDouble())
            return if (Math.abs(c) > 0.005) floatArrayOf(e0, Math.atan2(m[2].toDouble(), m[10].toDouble()).toFloat(), Math.atan2(m[4].toDouble(), m[5].toDouble()).toFloat())
            else floatArrayOf(e0, if (m[6] < 0) Math.atan2(m[1].toDouble(), m[0].toDouble()).toFloat() else (-Math.atan2(m[1].toDouble(), m[0].toDouble())).toFloat(), 0f)
        }

        fun scales(): FloatArray {
            fun len(a: Float, b: Float, c: Float) = Math.sqrt((a * a + b * b + c * c).toDouble()).toFloat()
            return floatArrayOf(len(m[0], m[1], m[2]), len(m[4], m[5], m[6]), len(m[8], m[9], m[10]))
        }
    }

    // ---------------- bones ----------------

    class Bone(val parentIndex: Int, val bindLocal: Array<Mat4>) {
        var parent: Bone? = null
        val defaultEuler: Array<FloatArray>
        val defaultTrans: Array<FloatArray>
        val defaultScale: Array<FloatArray>
        private val bindWorld = arrayOfNulls<Mat4>(bindLocal.size)
        private val invBindWorld = arrayOfNulls<Mat4>(bindLocal.size)
        private val animLocal = Mat4()
        private val animWorld = Mat4()
        private val skin = Mat4()
        private var dirtyWorld = true
        private var dirtySkin = true

        init {
            defaultEuler = Array(bindLocal.size) { bindLocal[it].copy().invert().euler() }
            defaultTrans = Array(bindLocal.size) { floatArrayOf(bindLocal[it].m[12], bindLocal[it].m[13], bindLocal[it].m[14]) }
            defaultScale = Array(bindLocal.size) { bindLocal[it].scales() }
        }

        fun bindWorld(p: Int): Mat4 = bindWorld[p] ?: bindLocal[p].copy().also { w -> parent?.let { w.mul(it.bindWorld(p)) }; bindWorld[p] = w }
        fun invBindWorld(p: Int): Mat4 = invBindWorld[p] ?: bindWorld(p).copy().invert().also { invBindWorld[p] = it }
        fun setLocal(mat: Mat4) { animLocal.set(mat); dirtyWorld = true; dirtySkin = true }
        fun animWorld(): Mat4 {
            if (dirtyWorld) { animWorld.set(animLocal); parent?.let { animWorld.mul(it.animWorld()) }; dirtyWorld = false }
            return animWorld
        }
        fun skin(p: Int): Mat4 {
            if (dirtySkin) { skin.set(invBindWorld(p)).mul(animWorld()); dirtySkin = false }
            return skin
        }
    }

    class Hierarchy(val bones: Array<Bone>)

    /** The legacy groups and (2021+) bones of a skeleton (index 1). */
    class SkeletonData(raw: ByteArray) {
        val count: Int
        val types: IntArray
        val labels: Array<IntArray>
        val hierarchy: Hierarchy?
        init {
            val b = Buf(raw)
            count = b.u8()
            types = IntArray(count) { b.u8() }
            val sizes = IntArray(count) { b.u8() }
            labels = Array(count) { i -> IntArray(sizes[i]) { b.u8() } }
            var h: Hierarchy? = null
            if (b.p < raw.size) {
                val boneCount = b.u16()
                if (boneCount > 0) {
                    val poses = b.u8()
                    val bones = Array(boneCount) {
                        val parent = b.s16()
                        val mats = Array(poses) { Mat4().also { mt -> for (k in 0 until 16) mt.m[k] = b.f32(); b.f32(); b.f32(); b.f32() } }
                        Bone(parent, mats)
                    }
                    for (bone in bones) if (bone.parentIndex >= 0 && bone.parentIndex < bones.size) bone.parent = bones[bone.parentIndex]
                    h = Hierarchy(bones)
                }
            }
            hierarchy = h
        }
    }

    // ---------------- curves ----------------

    private class Key(val frame: Int, val value: Float, val inTanX: Float, val inTanY: Float, val outTanX: Float, val outTanY: Float) { var next: Key? = null }

    private const val CONSTANT = 0; private const val LINEAR = 1; private const val CYCLE = 2; private const val CYCLE_OFFSET = 3; private const val OSCILLATE = 4

    /** One float channel, sampled at every whole frame when it's read (like class125.method3063). */
    class Curve internal constructor(keysIn: List<Any>, private val pre: Int, private val post: Int, private val weighted: Boolean) {
        @Suppress("UNCHECKED_CAST")
        private val keys = keysIn as List<Key>
        val first: Int = keys[0].frame
        val last: Int = keys[keys.size - 1].frame
        private val samples: FloatArray
        private val before: Float
        private val after: Float
        // segment coefficients
        private var start = 0f; private var end = 0f; private var linearX = false
        private var a0 = 0f; private var a1 = 0f; private var a2 = 0f; private var a3 = 0f
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f; private var b3 = 0f

        init {
            samples = FloatArray(last - first + 1)
            for (f in first..last) samples[f - first] = raw(f.toFloat())
            before = raw((first - 1).toFloat())
            after = raw((last + 1).toFloat())
        }

        fun value(frame: Int): Float = if (frame < first) before else if (frame > last) after else samples[frame - first]

        private fun segment(t: Float): Key? {
            var k: Key? = null
            for (key in keys) { if (key.frame.toFloat() <= t) k = key else break }
            return k
        }

        private fun raw(t: Float): Float {
            if (keys.isEmpty()) return 0f
            if (t < keys[0].frame.toFloat()) return if (pre == CONSTANT) keys[0].value else extrapolate(t, true)
            if (t > keys[keys.size - 1].frame.toFloat()) return if (post == CONSTANT) keys[keys.size - 1].value else extrapolate(t, false)
            val k = segment(t) ?: return 0f
            if (0.0 == k.outTanX.toDouble() && 0.0 == k.outTanY.toDouble()) return k.value
            if (k.outTanX == Float.MAX_VALUE && k.outTanY == Float.MAX_VALUE) return if (k.frame.toFloat() != t && k.next != null) k.next!!.value else k.value
            val n = k.next ?: return k.value
            val x0 = k.frame.toFloat(); val y0 = k.value
            val x1 = x0 + 0.33333334f * k.outTanX; val y1 = k.outTanY * 0.33333334f + y0
            val x3 = n.frame.toFloat(); val y3 = n.value
            val x2 = x3 - 0.33333334f * n.inTanX; val y2 = y3 - n.inTanY * 0.33333334f
            if (weighted) { bezierSetup(x0, x1, x2, x3, y0, y1, y2, y3); return bezier(t) }
            hermiteSetup(x0, x1, x2, x3, y0, y1, y2, y3)
            val d = t - start
            return d * (a1 + (a3 * d + a2) * d) + a0
        }

        private fun hermiteSetup(v1: Float, v2: Float, v3: Float, v4: Float, v5: Float, v6: Float, v7: Float, v8: Float) {
            start = v1
            val v9 = v4 - v1; val v10 = v8 - v5
            var v11 = v2 - v1
            var v12 = 0f; var v13 = 0f
            if (0.0 != v11.toDouble()) v12 = (v6 - v5) / v11
            v11 = v4 - v3
            if (0.0 != v11.toDouble()) v13 = (v8 - v7) / v11
            val v14 = 1.0f / (v9 * v9)
            val v15 = v12 * v9; val v16 = v9 * v13
            a3 = (v16 + v15 - v10 - v10) * v14 / v9
            a2 = (v10 + v10 + v10 - v15 - v15 - v16) * v14
            a1 = v12
            a0 = v5
        }

        private fun bezierSetup(x0: Float, x1: Float, x2: Float, x3: Float, y0: Float, y1: Float, y2: Float, y3: Float) {
            var yc1 = y1; var yc2 = y2
            val span = x3 - x0
            if (0.0 == span.toDouble()) return
            val u = floatArrayOf((x1 - x0) / span, (x2 - x0) / span)
            linearX = u[0] == 0.33333334f && u[1] == 0.6666667f
            val u0 = u[0]; val u1 = u[1]
            if (u[0].toDouble() < 0.0) u[0] = 0f
            if (u[1].toDouble() > 1.0) u[1] = 1f
            if (u[0].toDouble() > 1.0 || u[1] < -1.0f) {
                u[1] = 1.0f - u[1]
                if (u[0] < 0f) u[0] = 0f
                if (u[1] < 0f) u[1] = 0f
                if (u[0] > 1f || u[1] > 1f) {
                    val d = ((u[1].toDouble() - 2.0) * u[1].toDouble() + ((u[1] + (u[0] - 2.0f)) * u[0]).toDouble() + 1.0).toFloat()
                    if (d + EPS > 0f) {
                        if (EPS + u[0] < 1.3333334f) {
                            val p = u[0] - 2.0f; val q = u[0] - 1.0f
                            val r = Math.sqrt((p * p - 4.0f * q * q).toDouble()).toFloat()
                            val hi = 0.5f * (-p + r)
                            if (u[1] + EPS > hi) u[1] = hi - EPS
                            else { val lo = (-p - r) * 0.5f; if (u[1] < lo + EPS) u[1] = EPS + lo }
                        } else { u[0] = 1.3333334f - EPS; u[1] = 0.33333334f - EPS }
                    }
                }
                u[1] = 1.0f - u[1]
            }
            if (u0 != u[0] && u0.toDouble() != 0.0) yc1 = y0 + (y1 - y0) * u[0] / u0
            if (u1 != u[1] && u1.toDouble() != 1.0) yc2 = (y3.toDouble() - (1.0 - u[1].toDouble()) * (y3 - y2).toDouble() / (1.0 - u1.toDouble())).toFloat()
            start = x0; end = x3
            // x polynomial (GrandExchangeOfferOwnWorldComparator.method1202) and y polynomial (FriendSystem.method1909)
            run { val e1 = u[0] - 0f; val e2 = u[1] - u[0]; val e3 = 1.0f - u[1]; val f = e2 - e1; a3 = e3 - e2 - f; a2 = f + f + f; a1 = e1 + e1 + e1; a0 = 0f }
            run { val e1 = yc1 - y0; val e2 = yc2 - yc1; val e3 = y3 - yc2; val f = e2 - e1; b3 = e3 - e2 - f; b2 = f + f + f; b1 = e1 + e1 + e1; b0 = y0 }
        }

        private fun bezier(t: Float): Float {
            val s = if (t == start) 0f else if (t == end) 1f else (t - start) / (end - start)
            val p = if (linearX) s else {
                val roots = FloatArray(5)
                val n = solve(floatArrayOf(a0 - s, a1, a2, a3), 3, 0f, true, 1f, true, roots)
                if (n == 1) roots[0] else 0f
            }
            return b0 + p * (b1 + (b3 * p + b2) * p)
        }

        private fun extrapolate(t: Float, isPre: Boolean): Float {
            val x0 = keys[0].frame.toFloat(); val x1 = keys[keys.size - 1].frame.toFloat()
            val span = x1 - x0
            if (span.toDouble() == 0.0) return keys[0].value
            val r = if (t > x1) (t - x1) / span else (t - x0) / span
            var n = r.toInt().toDouble()
            var frac = Math.abs((r.toDouble() - n).toFloat())
            var local = frac * span
            n = Math.abs(1.0 + n)
            val h = n / 2.0
            frac = (h - h.toInt().toDouble()).toFloat()
            val mode = if (isPre) pre else post
            if (isPre) {
                when (mode) {
                    OSCILLATE -> local = if (frac.toDouble() != 0.0) local + x0 else x1 - local
                    CYCLE, CYCLE_OFFSET -> local = x1 - local
                    LINEAR -> { val d = x0 - t; var v = keys[0].value; if (0.0 != keys[0].inTanX.toDouble()) v -= keys[0].inTanY * d / keys[0].inTanX; return v }
                }
            } else {
                when (mode) {
                    OSCILLATE -> local = if (0.0 != frac.toDouble()) x1 - local else local + x0
                    CYCLE, CYCLE_OFFSET -> local += x0
                    LINEAR -> { val d = t - x1; val k = keys[keys.size - 1]; var v = k.value; if (0.0 != k.outTanX.toDouble()) v += k.outTanY * d / k.outTanX; return v }
                }
            }
            var v = raw(local)
            val delta = keys[keys.size - 1].value - keys[0].value
            if (isPre && pre == CYCLE_OFFSET) v = (v.toDouble() - n * delta.toDouble()).toFloat()
            else if (!isPre && post == CYCLE_OFFSET) v = (v.toDouble() + n * delta.toDouble()).toFloat()
            return v
        }

        companion object {
            internal fun read(b: Buf): Curve? {
                val count = b.u16()
                b.u8() // read and ignored by the client
                val pre = b.u8().let { if (it in 0..4) it else 0 }
                val post = b.u8().let { if (it in 0..4) it else 0 }
                val weighted = b.u8() != 0
                val keys = ArrayList<Key>(count)
                for (i in 0 until count) {
                    val k = Key(b.s16(), b.f32(), b.f32(), b.f32(), b.f32(), b.f32())
                    keys.lastOrNull()?.next = k
                    keys.add(k)
                }
                return if (keys.isEmpty()) null else Curve(keys, pre, post, weighted)
            }
        }
    }

    /** Horner evaluation (AbstractUserComparator.method7640). */
    private fun horner(c: FloatArray, n: Int, x: Float): Float { var r = c[n]; for (i in n - 1 downTo 0) r = r * x + c[i]; return r }

    /** The client's bounded polynomial root finder (SpriteMask.method5838), line for line. */
    fun solve(c: FloatArray, degIn: Int, lo: Float, incLo: Boolean, hi: Float, incHi: Boolean, out: FloatArray): Int {
        var deg = degIn
        var sum = 0f
        for (i in 0 until deg + 1) sum += Math.abs(c[i])
        val tol = (Math.abs(lo) + Math.abs(hi)) * (deg + 1).toFloat() * EPS
        if (sum <= tol) return -1
        val cn = FloatArray(deg + 1)
        for (i in 0 until deg + 1) cn[i] = 1.0f / sum * c[i]
        while (Math.abs(cn[deg]) < tol) deg--
        var found = 0
        if (deg == 0) return found
        if (deg == 1) {
            out[0] = -cn[0] / cn[1]
            val okLo = if (incLo) lo < out[0] + tol else lo < out[0] - tol
            val okHi = if (incHi) hi > out[0] - tol else hi > tol + out[0]
            found = if (okLo && okHi) 1 else 0
            if (found > 0) { if (incLo && out[0] < lo) out[0] = lo else if (incHi && out[0] > hi) out[0] = hi }
            return found
        }
        val poly = cn; val polyDeg = deg
        val d = FloatArray(deg + 1)
        for (i in 1..deg) d[i - 1] = i.toFloat() * cn[i]
        val crit = FloatArray(deg + 1)
        val nc = solve(d, deg - 1, lo, false, hi, false, crit)
        if (nc == -1) return 0
        var skip = false
        var fPrev = 0f; var fA: Float; var xB = 0f
        for (i in 0..nc) {
            if (found > deg) return found
            val xA: Float
            if (i == 0) {
                xA = lo
                fA = horner(cn, deg, lo)
                if (Math.abs(fA) <= tol && incLo) out[found++] = lo
            } else { xA = xB; fA = fPrev }
            if (nc == i) { xB = hi; skip = false } else xB = crit[i]
            fPrev = horner(cn, deg, xB)
            if (skip) skip = false
            else if (Math.abs(fPrev) < tol) {
                if (nc != i || incHi) { out[found++] = xB; skip = true }
            } else if (fA < 0f && fPrev > 0f || fA > 0f && fPrev < 0f) {
                val slot = found++
                var v24 = xA; var v25 = xB
                var v26 = horner(poly, polyDeg, xA)
                val root: Float
                if (Math.abs(v26) < EPS) root = xA
                else {
                    var v27 = horner(poly, polyDeg, xB)
                    if (Math.abs(v27) < EPS) root = xB
                    else {
                        var v28 = 0f; var v29 = 0f; var v30 = 0f; var v35 = 0f
                        var v36 = true; var again: Boolean
                        do {
                            again = false
                            if (v36) { v28 = v24; v35 = v26; v29 = v25 - v24; v30 = v29; v36 = false }
                            if (Math.abs(v35) < Math.abs(v27)) { v24 = v25; v25 = v28; v28 = v24; v26 = v27; v27 = v35; v35 = v26 }
                            val v38 = EPS2 * Math.abs(v25) + 0f
                            val v39 = (v28 - v25) * 0.5f
                            val go = Math.abs(v39) > v38 && 0f != v27
                            if (go) {
                                if (!(Math.abs(v30) < v38) && !(Math.abs(v26) <= Math.abs(v27))) {
                                    var v34 = v27 / v26
                                    var v31: Float; var v32: Float
                                    if (v28 == v24) { v31 = v39 * 2.0f * v34; v32 = 1.0f - v34 }
                                    else {
                                        v32 = v26 / v35
                                        val v33 = v27 / v35
                                        v31 = ((v32 - v33) * v39 * 2.0f * v32 - (v25 - v24) * (v33 - 1.0f)) * v34
                                        v32 = (v34 - 1.0f) * (v33 - 1.0f) * (v32 - 1.0f)
                                    }
                                    if (v31.toDouble() > 0.0) v32 = -v32 else v31 = -v31
                                    v34 = v30
                                    v30 = v29
                                    if (2.0f * v31 < v32 * 3.0f * v39 - Math.abs(v38 * v32) && v31 < Math.abs(v32 * 0.5f * v34)) v29 = v31 / v32
                                    else { v29 = v39; v30 = v39 }
                                } else { v29 = v39; v30 = v39 }
                                v24 = v25; v26 = v27
                                if (Math.abs(v29) > v38) v25 += v29 else if (v39.toDouble() > 0.0) v25 += v38 else v25 -= v38
                                v27 = horner(poly, polyDeg, v25)
                                if ((v27 * (v35 / Math.abs(v35))).toDouble() > 0.0) { v36 = true; again = true } else again = true
                            }
                        } while (again)
                        root = v25
                    }
                }
                out[slot] = root
                if (found > 1 && out[found - 2] >= out[found - 1] - tol) { out[found - 2] = 0.5f * (out[found - 1] + out[found - 2]); found-- }
            }
        }
        return found
    }

    // ---------------- keyframe sets ----------------

    /** A skeletal animation's curves (class133), with its own copy of the skeleton (bone state is kept per set). */
    class KeyframeSet(raw: ByteArray, skeletonRaw: (Int) -> ByteArray?) {
        val skeletonId: Int
        val skeleton: SkeletonData
        val poseIndex: Int
        /** [bone][slot 0-8]: rotation xyz, translation xyz, scale xyz. */
        val boneCurves: Array<Array<Curve?>?>
        /** [legacy group][slot]: transparency (type 4) curves live in slot 0. */
        val groupCurves: Array<Array<Curve?>?>
        var hasAlpha = false
        /** The range the header holds (read and ignored by the client; the sequence's range, see [Keyframes.headerRange]). */
        val headerStart: Int
        val headerEnd: Int

        init {
            val b = Buf(raw)
            b.u8() // version
            skeletonId = b.u16()
            skeleton = SkeletonData(skeletonRaw(skeletonId) ?: error("skeleton $skeletonId is missing"))
            headerStart = b.u16(); headerEnd = b.u16()
            poseIndex = b.u8()
            val count = b.u16()
            val bones = skeleton.hierarchy?.bones?.size ?: 0
            boneCurves = arrayOfNulls(bones)
            groupCurves = arrayOfNulls(skeleton.count)
            for (i in 0 until count) {
                val type = b.u8().let { if (it in 0..5) it else 0 }
                val index = b.shortSmart()
                val channel = b.u8().let { if (it in 0..16) it else 0 }
                val curve = Curve.read(b)
                val slots = when (type) { 1 -> 9; 2 -> 3; 3 -> 6; 4 -> 1; 5 -> 3; else -> 0 }
                val slot = when (channel) { 0 -> -1; in 1..9 -> channel - 1; in 10..15 -> channel - 10; else -> 0 }
                val arr = if (type == 1) boneCurves else groupCurves
                if (index < 0 || index >= arr.size) continue
                if (arr[index] == null) arr[index] = arrayOfNulls(slots)
                if (type == 4) hasAlpha = true
                // invalid slots throw in the client and are skipped; same here
                val target = arr[index]!!
                if (curve != null && slot >= 0 && slot < target.size) target[slot] = curve
            }
        }

        /** Sets every bone's local matrix for frame [f] (class133.method3097 via class217.method4443). */
        fun pose(f: Int, masks: BooleanArray? = null, maskValue: Boolean = false) {
            val bones = skeleton.hierarchy?.bones ?: return
            val m = Mat4(); val r = Mat4(); val s = Mat4()
            for (i in bones.indices) {
                if (masks != null && masks[i] != maskValue) continue
                val bone = bones[i]
                val c = boneCurves.getOrNull(i)
                val p = poseIndex
                m.identity()
                var rx = bone.defaultEuler[p][0]; var ry = bone.defaultEuler[p][1]; var rz = bone.defaultEuler[p][2]
                if (c != null) { c[0]?.let { rx = it.value(f) }; c[1]?.let { ry = it.value(f) }; c[2]?.let { rz = it.value(f) } }
                // q = qy * qx * qz, built the client's way (identity, then q.mul(qz), q.mul(qx), q.mul(qy))
                var qx = 0f; var qy = 0f; var qz = 0f; var qw = 1f
                fun mulLeft(bx: Float, by: Float, bz: Float, bw: Float) {
                    val nx = by * qz + qw * bx + bw * qx - qy * bz
                    val ny = qx * bz + qy * bw - bx * qz + by * qw
                    val nz = bw * qz + bx * qy - qx * by + bz * qw
                    val nw = bw * qw - qx * bx - by * qy - bz * qz
                    qx = nx; qy = ny; qz = nz; qw = nw
                }
                fun axis(ax: Float, ay: Float, az: Float, a: Float) {
                    val sn = Math.sin((a * 0.5f).toDouble()).toFloat(); val cs = Math.cos((0.5f * a).toDouble()).toFloat()
                    mulLeft(ax * sn, ay * sn, sn * az, cs)
                }
                axis(0f, 0f, 1f, rz); axis(1f, 0f, 0f, rx); axis(0f, 1f, 0f, ry)
                m.mul(r.rotation(qx, qy, qz, qw))
                var sx = bone.defaultScale[p][0]; var sy = bone.defaultScale[p][1]; var sz = bone.defaultScale[p][2]
                if (c != null) { c.getOrNull(6)?.let { sx = it.value(f) }; c.getOrNull(7)?.let { sy = it.value(f) }; c.getOrNull(8)?.let { sz = it.value(f) } }
                m.mul(s.scale(sx, sy, sz))
                var tx = bone.defaultTrans[p][0]; var ty = bone.defaultTrans[p][1]; var tz = bone.defaultTrans[p][2]
                if (c != null) { c.getOrNull(3)?.let { tx = it.value(f) }; c.getOrNull(4)?.let { ty = it.value(f) }; c.getOrNull(5)?.let { tz = it.value(f) } }
                m.m[12] = tx; m.m[13] = ty; m.m[14] = tz
                bone.setLocal(m)
            }
        }

        /** Skins the base positions into x, y, z with the current bone matrices (Model.method4715 / method4714). */
        fun skin(md: ModelDefinition, baseX: IntArray, baseY: IntArray, baseZ: IntArray, x: IntArray, y: IntArray, z: IntArray) {
            val h = skeleton.hierarchy ?: return
            val groups = md.animayaGroups ?: return
            val scales = md.animayaScales ?: return
            val acc = Mat4(); val t = Mat4(); val sc = Mat4()
            for (v in 0 until md.vertexCount) {
                val bones = groups[v]
                if (bones == null || bones.isEmpty()) continue
                val w = scales[v]!!
                acc.zero()
                for (j in bones.indices) {
                    val b = h.bones.getOrNull(bones[j]) ?: continue
                    val s = w[j].toFloat() / 255.0f
                    t.set(b.skin(poseIndex)).mul(sc.scale(s, s, s))
                    acc.add(t)
                }
                val X = baseX[v].toFloat(); val Y = (-baseY[v]).toFloat(); val Z = (-baseZ[v]).toFloat()
                val a = acc.m
                x[v] = (a[0] * X + a[4] * Y + a[8] * Z + a[12] * 1.0f).toInt()
                y[v] = -((a[1] * X + a[5] * Y + a[9] * Z + a[13] * 1.0f).toInt())
                z[v] = -((a[2] * X + a[6] * Y + a[10] * Z + a[14] * 1.0f).toInt())
            }
        }
    }

    // ---------------- reading ----------------

    internal class Buf(val b: ByteArray) {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun s16() = u16().let { if (it > 32767) it - 65536 else it }
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun f32() = java.lang.Float.intBitsToFloat(i32())
        fun shortSmart(): Int = if ((b[p].toInt() and 0xFF) < 128) u8() - 64 else u16() - 0xC000
    }

    /** The keyframe set of a skeletal animation id (group = id >> 16, file = id & 0xFFFF; archive 22 in 241, 0 in 211). */
    fun load(anims: AnimCache, seq: SeqDef): KeyframeSet? {
        val id = seq.skeletalModel
        val raw = anims.keyframeData(id ushr 16 and 0xFFFF, id and 0xFFFF) ?: return null
        return KeyframeSet(raw) { anims.skeletonData(it) }
    }

    /** A playable clip of [seq] on [md], one frame per client cycle. */
    fun clip(md: ModelDefinition, seq: SeqDef, anims: AnimCache): Pair<AnimClip?, String> {
        val set = try { load(anims, seq) } catch (e: Exception) { return null to "Animation ${seq.id}'s skeletal data couldn't be read: ${e.message}" }
            ?: return null to "Animation ${seq.id}'s skeletal data (keyframe set ${seq.skeletalModel ushr 16}:${seq.skeletalModel and 0xFFFF}) isn't in the cache" +
                    (if (seq.is241) " (archive 22)." else ".")
        // the game plays it for end - start cycles, with no fallback: none (no range setting, a 211 -> 241 merge left 289
        // like this) plays nothing. The preview shows the range its keyframes hold then (what the stock record has).
        val range = seq.skeletalEnd - seq.skeletalStart
        val held = set.headerEnd - set.headerStart
        val length = if (range > 0) range else held.coerceAtLeast(1)
        val noLength = when {
            range > 0 -> ""
            held > 0 -> "It has no length (no skeletal range), so the game plays it for 0 cycles; shown here with the $held " +
                    "cycles its keyframes hold. Importing or saving any animation, or Deploy, gives it that length. "
            else -> "It has no length (no skeletal range), so the game plays it for 0 cycles, and its keyframes don't say " +
                    "how long it is (shown here as 1 cycle). It needs its stock record (audit BOSS-X1). "
        }
        // a 241 cache whose keyframe set is only in archive 0 (left there by a 211 -> 241 merge): shown, but the game won't find it
        val stranded = seq.is241 && anims.keyframeArchive(seq.skeletalModel ushr 16 and 0xFFFF, seq.skeletalModel and 0xFFFF) == cache.CacheIO.FRAMES
        val note = noLength + if (stranded) "Its keyframes are only in archive 0, where build 241 doesn't look, so the game won't " +
                "find them until they're copied to archive 22 (importing or saving any animation, or Deploy, does that). " else ""
        if (md.animayaGroups == null) return SkeletalClip(md, set, length) to note + "That model has no skeletal weights, so it can't move with this animation."
        return SkeletalClip(md, set, length) to note.trim()
    }

    class SkeletalClip(private val md: ModelDefinition, val set: KeyframeSet, private val length: Int) : AnimClip {
        private val bx = md.vertexPositionsX.clone(); private val by = md.vertexPositionsY.clone(); private val bz = md.vertexPositionsZ.clone()
        private val x = bx.clone(); private val y = by.clone(); private val z = bz.clone()
        override val frameCount get() = length
        override fun delay(i: Int) = 1
        override val moves get() = md.animayaGroups != null && set.skeleton.hierarchy != null
        override fun pose(i: Int): Triple<IntArray, IntArray, IntArray> {
            System.arraycopy(bx, 0, x, 0, x.size); System.arraycopy(by, 0, y, 0, y.size); System.arraycopy(bz, 0, z, 0, z.size)
            set.pose(i)
            set.skin(md, bx, by, bz, x, y, z)
            return Triple(x, y, z)
        }
    }
}
