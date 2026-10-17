package controllers.worldRenderer

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import controllers.worldRenderer.entities.Model
import utils.NpcDef

/**
 * NPC models for the 3D view: every body part merged into one model, recoloured, scaled and turned
 * to the direction the NPC faces (idle pose, no animation).
 */
class NpcModels(private val modelLoader: ModelLoader, private val background: Boolean = false) {
    companion object {
        val DIRECTIONS = listOf("SOUTH", "SOUTH_WEST", "WEST", "NORTH_WEST", "NORTH", "NORTH_EAST", "EAST", "SOUTH_EAST")
        /** Game orientation (0-2047) for a direction name; unknown names face south. */
        fun orientation(dir: String): Int = DIRECTIONS.indexOf(dir.toUpperCase()).let { if (it < 0) 0 else it * 256 }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<Long, java.util.Optional<Model>>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    // models take a moment to build: the 3D view asks, the worker builds, the NPC shows up a frame later
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "npc-models").apply { isDaemon = true } } }

    fun model(def: NpcDef, dir: String): Model? {
        val o = orientation(dir)
        val key = (def.id.toLong() shl 12) or o.toLong()
        cache[key]?.let { return it.orElse(null) }
        if (!background) return make(def, o).also { cache[key] = java.util.Optional.ofNullable(it) }
        if (pending.add(key)) worker.execute {
            cache[key] = java.util.Optional.ofNullable(make(def, o))
            pending.remove(key)
        }
        return null
    }

    private fun make(def: NpcDef, o: Int): Model? = try { build(def, o) } catch (e: Throwable) { println("NPC ${def.id} model: $e"); null }

    /** Forget built models (after the definition changed). */
    fun forget(npcId: Int) { cache.keys.removeAll { k -> (k shr 12).toInt() == npcId } }

    private fun build(def: NpcDef, orientation: Int): Model? {
        val parts = def.models.toList().mapNotNull { modelLoader.get(it) }
        if (parts.isEmpty()) return null
        val m = merge(parts)
        def.recolorFrom?.let { f -> val t = def.recolorTo!!; for (i in f.indices) m.recolor(f[i], t[i]) }
        def.retextureFrom?.let { f -> val t = def.retextureTo!!; for (i in f.indices) m.retexture(f[i], t[i]) }
        if (def.widthScale != 128 || def.heightScale != 128) m.resize(def.widthScale, def.heightScale, def.widthScale)
        if (orientation != 0) {
            val a = orientation * Math.PI / 1024.0
            val s = Math.sin(a); val c = Math.cos(a)
            for (i in 0 until m.vertexCount) {
                val x = m.vertexPositionsX[i]; val z = m.vertexPositionsZ[i]
                m.vertexPositionsX[i] = Math.round(z * s + x * c).toInt()
                m.vertexPositionsZ[i] = Math.round(z * c - x * s).toInt()
            }
        }
        m.vertexNormals = null; m.faceNormals = null
        val model = Model(m, 0, 0)
        model.xOff = def.size * 64
        model.yOff = def.size * 64
        return model
    }

    /** One model out of several (the cache's models are shared, so everything is copied). */
    private fun merge(parts: List<ModelDefinition>): ModelDefinition {
        val out = ModelDefinition()
        val vCount = parts.sumBy { it.vertexCount }
        val fCount = parts.sumBy { it.faceCount }
        val tCount = parts.sumBy { it.textureTriangleCount }
        out.vertexCount = vCount; out.faceCount = fCount; out.textureTriangleCount = tCount
        out.vertexPositionsX = IntArray(vCount); out.vertexPositionsY = IntArray(vCount); out.vertexPositionsZ = IntArray(vCount)
        out.faceVertexIndices1 = IntArray(fCount); out.faceVertexIndices2 = IntArray(fCount); out.faceVertexIndices3 = IntArray(fCount)
        out.faceColors = ShortArray(fCount)
        val anyAlpha = parts.any { it.faceAlphas != null }
        val anyPrio = parts.any { it.faceRenderPriorities != null } || parts.map { it.priority }.distinct().size > 1
        val anyType = parts.any { it.faceRenderTypes != null }
        val anyTex = parts.any { it.faceTextures != null }
        val anyTexCoord = parts.any { it.textureCoordinates != null }
        if (anyAlpha) out.faceAlphas = ByteArray(fCount)
        if (anyPrio) out.faceRenderPriorities = ByteArray(fCount)
        if (anyType) out.faceRenderTypes = ByteArray(fCount)
        if (anyTex) out.faceTextures = ShortArray(fCount) { -1 }
        if (anyTexCoord) out.textureCoordinates = ByteArray(fCount) { -1 }
        if (tCount > 0) {
            out.textureTriangleVertexIndices1 = ShortArray(tCount); out.textureTriangleVertexIndices2 = ShortArray(tCount)
            out.textureTriangleVertexIndices3 = ShortArray(tCount); out.textureRenderTypes = ByteArray(tCount)
        }
        out.priority = parts[0].priority
        var vb = 0; var fb = 0; var tb = 0
        for (p in parts) {
            for (i in 0 until p.vertexCount) {
                out.vertexPositionsX[vb + i] = p.vertexPositionsX[i]
                out.vertexPositionsY[vb + i] = p.vertexPositionsY[i]
                out.vertexPositionsZ[vb + i] = p.vertexPositionsZ[i]
            }
            for (i in 0 until p.faceCount) {
                out.faceVertexIndices1!![fb + i] = p.faceVertexIndices1!![i] + vb
                out.faceVertexIndices2!![fb + i] = p.faceVertexIndices2!![i] + vb
                out.faceVertexIndices3!![fb + i] = p.faceVertexIndices3!![i] + vb
                out.faceColors!![fb + i] = p.faceColors?.get(i) ?: 0
                if (anyAlpha) out.faceAlphas!![fb + i] = p.faceAlphas?.get(i) ?: 0
                if (anyPrio) out.faceRenderPriorities!![fb + i] = p.faceRenderPriorities?.get(i) ?: p.priority
                if (anyType) out.faceRenderTypes!![fb + i] = p.faceRenderTypes?.get(i) ?: 0
                if (anyTex) out.faceTextures!![fb + i] = p.faceTextures?.get(i) ?: -1
                if (anyTexCoord) {
                    val c = p.textureCoordinates?.get(i)?.toInt() ?: -1
                    out.textureCoordinates!![fb + i] = if (c == -1) -1 else ((c and 0xFF) + tb).toByte()
                }
            }
            for (i in 0 until p.textureTriangleCount) {
                out.textureTriangleVertexIndices1!![tb + i] = (p.textureTriangleVertexIndices1!![i] + vb).toShort()
                out.textureTriangleVertexIndices2!![tb + i] = (p.textureTriangleVertexIndices2!![i] + vb).toShort()
                out.textureTriangleVertexIndices3!![tb + i] = (p.textureTriangleVertexIndices3!![i] + vb).toShort()
                out.textureRenderTypes!![tb + i] = p.textureRenderTypes?.get(i) ?: 0
            }
            vb += p.vertexCount; fb += p.faceCount; tb += p.textureTriangleCount
        }
        out.computeMaxPriority()
        return out
    }
}
