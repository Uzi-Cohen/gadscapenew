package cache.definitions.converters

import cache.definitions.ModelDefinition
import cache.definitions.ObjectDefinition
import cache.loaders.ModelLoader
import com.google.inject.Inject

class ObjectToModelConverter @Inject constructor(
    private val modelLoader: ModelLoader,
    private val litModelCache: HashMap<Long, ModelDefinition> = HashMap()
) {
    fun toModelTypesMap(objectDefinition: ObjectDefinition): HashMap<Int, ModelDefinition?> {
        val modelDefMap: HashMap<Int, ModelDefinition?> = HashMap()
        if (objectDefinition.modelTypes == null) {
            // interactable is the default
            modelDefMap[10] = toModel(objectDefinition, 10, 0)
        } else {
            for (typ in objectDefinition.modelTypes!!) {
                modelDefMap[typ] = toModel(objectDefinition, typ, 0)
            }
        }

        return modelDefMap
    }

    @Synchronized fun toModel(objectDefinition: ObjectDefinition, type: Int, orientation: Int): ModelDefinition? {
        val modelTag: Long = if (objectDefinition.modelTypes == null) {
            orientation + (10 shl 3) + (objectDefinition.id shl 10).toLong()
        } else {
            orientation + (type shl 3) + (objectDefinition.id shl 10).toLong()
        }
        var litModel: ModelDefinition? = litModelCache[modelTag]
        if (litModel == null) {
            litModel = objectDefinition.getModelDefinition(type, orientation)
            if (litModel == null) {
                return null
            }

            litModel.tag = modelTag
            litModelCache[modelTag] = litModel
        }
        return litModel
    }

    private fun ObjectDefinition.getModelDefinition(type: Int, orientation: Int): ModelDefinition? {
        var orientation = orientation
        var modelDefinition: ModelDefinition? = null
        if (modelTypes == null) {
            if (type != 10 || modelIds == null) {
                return null
            }
            val modelLen = modelIds!!.size
            val parts = ArrayList<ModelDefinition>()
            for (i in 0 until modelLen) {
                var modelId = modelIds!![i]
                if (isRotated) {
                    modelId += 65536 // the mirrored version (the loader mirrors it once)
                }
                parts.add(modelLoader.get(modelId) ?: return null)
            }
            // objects made of several models: join them (it used to be an empty model, so they didn't show)
            modelDefinition = if (parts.size == 1) parts[0] else ModelDefinition.merge(parts)
        } else {
            var modelIdx = -1
            for (i in modelTypes!!.indices) {
                if (modelTypes!![i] == type) {
                    modelIdx = i
                    break
                }
            }
            if (modelIdx == -1) {
                return null
            }
            var modelId = modelIds!![modelIdx]
            val isRotated = isRotated xor (orientation > 3)
            if (isRotated) {
                modelId += 65536
            }
            modelDefinition = modelLoader.get(modelId) // mirrored ids are mirrored by the loader
            if (modelDefinition == null) {
                return null
            }
        }
        if (modelDefinition == null) {
            return null
        }

        val copy = ModelDefinition(
            modelDefinition,
            orientation == 0,
            recolorToFind == null,
            retextureToFind == null
        )

        val diagonalDecoration = type == 4 && orientation > 3
        orientation = orientation and 0x3
        when (orientation) {
            1 -> {
                copy.rotateY90Ccw()
            }
            2 -> {
                copy.rotateY180()
            }
            3 -> {
                copy.rotateY270Ccw()
            }
        }
        if (diagonalDecoration) {
            // wall decorations on a diagonal wall: the game turns them 45 degrees and moves them onto the wall
            copy.rotateY(256)
            copy.offsetBy(45, 0, -45)
        }
        if (recolorToFind != null) {
            for (i in recolorToFind!!.indices) {
                copy.recolor(recolorToFind!![i], recolorToReplace[i])
            }
        }
        if (retextureToFind != null) {
            for (i in retextureToFind!!.indices) {
                copy.retexture(retextureToFind!![i], textureToReplace!![i])
            }
        }
        return copy
    }

    /**
     * A small purple box for objects the editor has no model for (multilocs whose default state has none,
     * models it can't read, invisible blockers). They're real in game, so they have to be visible and
     * removable here.
     */
    fun standIn(objectDefinition: ObjectDefinition): ModelDefinition {
        val tag = (objectDefinition.id.toLong() shl 10) or 0x3FF
        litModelCache[tag]?.let { return it }
        val half = 40
        val top = -80
        val xs = intArrayOf(-half, half, half, -half, -half, half, half, -half)
        val ys = intArrayOf(0, 0, 0, 0, top, top, top, top)
        val zs = intArrayOf(-half, -half, half, half, -half, -half, half, half)
        val faces = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 3), // bottom
            intArrayOf(4, 6, 5), intArrayOf(4, 7, 6), // top
            intArrayOf(0, 4, 5), intArrayOf(0, 5, 1),
            intArrayOf(1, 5, 6), intArrayOf(1, 6, 2),
            intArrayOf(2, 6, 7), intArrayOf(2, 7, 3),
            intArrayOf(3, 7, 4), intArrayOf(3, 4, 0),
        )
        val m = ModelDefinition()
        m.vertexCount = 8
        m.vertexPositionsX = xs
        m.vertexPositionsY = ys
        m.vertexPositionsZ = zs
        m.faceCount = faces.size
        m.faceVertexIndices1 = IntArray(faces.size) { faces[it][0] }
        m.faceVertexIndices2 = IntArray(faces.size) { faces[it][1] }
        m.faceVertexIndices3 = IntArray(faces.size) { faces[it][2] }
        m.faceColors = ShortArray(faces.size) { STAND_IN_HSL }
        m.tag = tag
        litModelCache[tag] = m
        return m
    }

    /**
     * Why an object placed as [type] shows as a purple box, or null when it has a model. The type is mapped the way
     * the scene does it (wall decorations use their type-4 model, diagonal objects their type-10 one).
     */
    fun whyNoModel(objectDefinition: ObjectDefinition, type: Int, orientation: Int): String? {
        val modelType = when (type) { in 4..8 -> 4; 11 -> 10; else -> type }
        try {
            if (toModel(objectDefinition, modelType, orientation) != null) return null
        } catch (e: Exception) { }
        val ids = objectDefinition.modelIds
            ?: return "it has no model at all. In game it's invisible too, and it still blocks if it's solid."
        val types = objectDefinition.modelTypes
        if (types != null && modelType !in types || types == null && modelType != 10)
            return "it has no model for how it's placed (type $type; its models are for type ${types?.joinToString("/") ?: "10"}). " +
                    "In game it's invisible there but can still block. Place it as one of those types, or remove it."
        for (id in ids) {
            val ok = try { modelLoader.get(id) != null } catch (e: Exception) { false }
            if (!ok) return "its model $id isn't in your cache (or can't be read). In game it's invisible but still blocks. " +
                    "Import model $id (Models window) or remove the object."
        }
        return "the editor couldn't build its model."
    }

    companion object {
        /** Purple in the game's 16-bit HSL. */
        const val STAND_IN_HSL: Short = ((51 shl 10) or (6 shl 7) or 60).toShort()
    }
}
