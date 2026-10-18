package cache.loaders

import cache.ConfigType
import cache.IndexType
import cache.definitions.OverlayDefinition
import cache.utils.read24BitInt
import cache.utils.readUnsignedByte
import com.displee.cache.CacheLibrary
import com.google.inject.Inject
import java.nio.ByteBuffer

class OverlayLoader @Inject constructor(
    cacheLibrary: CacheLibrary,
    private val overlayDefinitionCache: HashMap<Int, OverlayDefinition> = HashMap()
) {
    @Synchronized fun get(id: Int): OverlayDefinition? {
        return overlayDefinitionCache[id]
    }

    fun ids(): List<Int> = overlayDefinitionCache.keys.sorted()

    @Synchronized fun load(id: Int, b: ByteArray?): OverlayDefinition {
        val def = OverlayDefinition(id)
        val inputStream = ByteBuffer.wrap(b)
        while (true) {
            val opcode: Int = inputStream.readUnsignedByte()
            if (opcode == 0) {
                break
            }
            if (opcode == 1) {
                val color: Int = inputStream.read24BitInt()
                def.rgbColor = color
            } else if (opcode == 2) {
                val texture: Int = inputStream.readUnsignedByte()
                def.texture = texture
            } else if (opcode == 5) {
                def.hideUnderlay = false
            } else if (opcode == 7) {
                val secondaryColor: Int = inputStream.read24BitInt()
                def.secondaryRgbColor = secondaryColor
            }
        }
        def.calculateHsl()
        return def
    }

    /** A floor added after start (a twin made for View > 117 HD floors, utils.FloorTwins). */
    @Synchronized fun add(id: Int, b: ByteArray) {
        overlayDefinitionCache[id] = load(id, b)
    }

    init {
        val index = cacheLibrary.index(IndexType.CONFIGS.id)
        val archive = index.archive(ConfigType.OVERLAY.id)
        for (file in archive!!.fileIds()) {
            val data = cacheLibrary.data(IndexType.CONFIGS.id, ConfigType.OVERLAY.id, file)
            val def = load(file, data)
            overlayDefinitionCache[def.id] = def
        }
        // floors the editor added (HD twins) live in the exported cache only
        try {
            val out = utils.GadTools.exportCacheDir
            if (java.io.File(out, "main_file_cache.dat2").exists()) {
                val lib = CacheLibrary(out.path)
                try {
                    for (file in lib.index(IndexType.CONFIGS.id).archive(ConfigType.OVERLAY.id)?.fileIds() ?: intArrayOf()) {
                        if (overlayDefinitionCache.containsKey(file)) continue
                        val data = lib.data(IndexType.CONFIGS.id, ConfigType.OVERLAY.id, file) ?: continue
                        overlayDefinitionCache[file] = load(file, data)
                    }
                } finally { lib.close() }
            }
        } catch (e: Throwable) { println("Exported floors not read: ${e.message}") }
    }
}