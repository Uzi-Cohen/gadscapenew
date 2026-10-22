package utils

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary

/**
 * Models imported from a file to try on an item, not in the cache yet. Each gets the model id it will have, so the
 * item's fields can point at it and the previews show it; it's written into the cache only when an item using it is
 * saved ([ItemStore.save]), in that save's undo step. Nothing is written for one that's never used.
 */
object PendingModels {
    class Pending(val id: Int, val name: String, val data: ByteArray, val model: ModelDefinition)

    private val byId = LinkedHashMap<Int, Pending>()

    /** Reserves a free model id in [lib] for [data] (already checked to be a model) and returns it. */
    @Synchronized
    fun add(lib: CacheLibrary, name: String, data: ByteArray, model: ModelDefinition): Pending {
        // the same file again: the same model
        byId.values.firstOrNull { it.data.contentEquals(data) }?.let { return it }
        // the highest free id up to 65534 in a 241 cache (the client fetches models with 16-bit ids), the first run
        // of 50 free ids up to 65534 in a 211 cache
        val id = cache.IdAllocator(lib, cache.IdKind.MODEL, byId.keys).next()
        return Pending(id, name, data, model).also { byId[id] = it }
    }

    @Synchronized fun get(id: Int): Pending? = byId[id]
    @Synchronized fun isPending(id: Int) = id in byId

    /** A cache model or a pending one: null when [id] is neither (or out of range). */
    fun model(models: ModelLoader, id: Int): ModelDefinition? {
        if (id < 0) return null
        get(id)?.let { return it.model }
        return try { models.get(id) } catch (e: Exception) { null }
    }

    /**
     * Makes sure every pending model [ids] mentions is in [lib] (an open exported cache): written when it's missing
     * (also after an undo took it out again), left alone when it's already there. If something else took its id in the
     * meantime, it moves to a free id: the returned map says old id -> new id for those.
     */
    @Synchronized
    fun writeInto(lib: CacheLibrary, ids: Collection<Int>): Map<Int, Int> {
        val moved = HashMap<Int, Int>()
        val todo = ids.filter { it in byId }.distinct()
        if (todo.isEmpty()) return moved
        val used = lib.index(7).archiveIds().toHashSet()
        val free = cache.IdAllocator(lib, cache.IdKind.MODEL, byId.keys)
        var wrote = false
        for (id in todo) {
            val p = byId[id] ?: continue
            val there = if (id in used) lib.data(7, id, 0) else null
            if (there != null && there.contentEquals(p.data)) continue
            val target = if (id !in used) id else free.next()
            lib.put(7, target, 0, p.data)
            used.add(target); wrote = true
            if (target != id) {
                moved[id] = target
                byId.remove(id); byId[target] = Pending(target, p.name, p.data, p.model)
            }
        }
        if (wrote) lib.index(7).update()
        return moved
    }
}
