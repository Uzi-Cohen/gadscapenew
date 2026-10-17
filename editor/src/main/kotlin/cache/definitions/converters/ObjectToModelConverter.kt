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

    fun toModel(objectDefinition: ObjectDefinition, type: Int, orientation: Int): ModelDefinition? {
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
}