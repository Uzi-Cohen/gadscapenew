package cache.loaders

import cache.IndexType
import cache.definitions.TextureDefinition
import com.displee.cache.CacheLibrary

class TextureLoader(
    cacheLibrary: CacheLibrary,
    private val textureDefinitionCache: HashMap<Int, TextureDefinition> = HashMap()
) {
    fun getAll(): Array<TextureDefinition?> {
        val maxId = textureDefinitionCache.maxByOrNull { it.key }!!.key
        // Jagex skipped texture id 54 so now we have to do this?
        val texArray = arrayOfNulls<TextureDefinition>(maxId + 1)
        for (tex in textureDefinitionCache) {
            texArray[tex.key] = tex.value
        }
        return texArray
    }

    @Synchronized fun get(id: Int): TextureDefinition? {
        return textureDefinitionCache[id]
    }

    /**
     * One texture, in the cache's format: rev 211 (12+ bytes, any number of sprites) or build 241 (7 bytes, one
     * sprite); see [utils.TextureCodec].
     */
    private fun load(id: Int, b: ByteArray?, rev: Int): TextureDefinition {
        val t = utils.TextureCodec.decode(id, b ?: ByteArray(0), rev)
        val def = TextureDefinition()
        def.id = id
        def.field1777 = t.averageRgb
        def.field1778 = t.opaque
        def.fileIds = t.sprites
        val n = t.sprites.size
        def.field1780 = if (n > 1) t.blendTypes else IntArray(0)
        def.field1781 = if (n > 1) t.blendExtra else IntArray(0)
        def.field1786 = if (t.colourOps.size == n) t.colourOps else IntArray(n)
        def.field1783 = t.animDirection
        def.field1782 = t.animSpeed
        return def
    }

    fun getAverageTextureRGB(id: Int): Int {
        val texture = get(id)
        return texture?.field1777 ?: 0
    }

    init {
        val rev = cache.CacheIO.revision(cacheLibrary)
        val index = cacheLibrary.index(IndexType.TEXTURES.id)
        val archive = index.archive(0)
        for (file in archive!!.fileIds()) {
            val data = cacheLibrary.data(IndexType.TEXTURES.id, 0, file)
            val def = try { load(file, data, rev) } catch (e: Exception) { println("Texture $file can't be read: $e"); continue }
            textureDefinitionCache[def.id] = def
        }
        cacheLibrary.index(IndexType.TEXTURES.id).unCache() // free memory
    }
}