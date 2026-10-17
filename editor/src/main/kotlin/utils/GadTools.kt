package utils

import cache.CachePaths
import cache.XteaManager
import com.displee.cache.CacheLibrary
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import controllers.worldRenderer.Camera
import controllers.worldRenderer.Constants
import models.scene.REGION_SIZE
import models.scene.Scene
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

data class Bookmark(val name: String, val x: Int, val y: Int, val z: Int)

data class ToolTemplates(
    val tele: String? = "::tele {x} {y} {z}",
    val npcSpawn: String? = "{\"id\": {id}, \"x\": {x}, \"y\": {y}, \"z\": {z}, \"direction\": \"{dir}\", \"radius\": {radius}}",
    val objectSpawn: String? = "{\"id\": {id}, \"type\": {type}, \"rotation\": {rot}, \"x\": {x}, \"y\": {y}, \"z\": {z}}",
    val deployHost: String? = "gadscape.duckdns.org",
    val deployPort: Int? = 22,
    val deployUser: String? = "root",
    val deployKey: String? = "",
    val deployRemoteCache: String? = "/opt/gadscape/cache/data/cache",
    val deployService: String? = "gadscape",
    /** Path of the server jar on the server; empty = read it from the systemd service. */
    val deployJar: String? = "",
    /** The server's data folder (ItemDefinitions.json and other server data). */
    val deployRemoteData: String? = "/opt/gadscape/data"
)

object GadTools {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Folder picked in the launcher (the one holding cache\ and xteas.json). */
    val editorDir: File get() = File(CachePaths.cacheDir).absoluteFile.parentFile
    val exportCacheDir: File get() = File(editorDir, "cache-out")
    val backupsDir: File get() = File(editorDir, "region-backups")
    private val bookmarksFile: File get() = File(editorDir, "gadscape-bookmarks.json")
    private val templatesFile: File get() = File(editorDir, "gadscape-tools.json")

    fun regionOf(x: Int, y: Int): Int = ((x shr 6) shl 8) or (y shr 6)

    /** A folder with an empty xteas.json, for caches whose maps aren't encrypted. */
    fun noKeysDir(): File = File(System.getProperty("java.io.tmpdir"), "ume-no-keys").apply {
        mkdirs(); File(this, "xteas.json").let { if (!it.isFile) it.writeText("[]") }
    }

    /** Scene coords -> world coords, or null if outside the loaded area. */
    fun sceneToWorld(scene: Scene, sx: Int, sy: Int): Pair<Int, Int>? {
        if (!scene.isLoaded() || sx < 0 || sy < 0) return null
        val gx = sx / REGION_SIZE
        val gy = sy / REGION_SIZE
        if (gx >= scene.radius || gy >= scene.radius) return null
        val start = scene.startRegionId
        val baseX = ((start shr 8) + gx) shl 6
        val baseY = ((start and 0xFF) + gy) shl 6
        return Pair(baseX + sx % REGION_SIZE, baseY + sy % REGION_SIZE)
    }

    /** World coords -> scene coords, or null if outside the loaded area. */
    fun worldToScene(scene: Scene, x: Int, y: Int): Pair<Int, Int>? {
        if (!scene.isLoaded()) return null
        val start = scene.startRegionId
        val sx = x - ((start shr 8) shl 6)
        val sy = y - ((start and 0xFF) shl 6)
        val size = scene.radius * REGION_SIZE
        if (sx < 0 || sy < 0 || sx >= size || sy >= size) return null
        return Pair(sx, sy)
    }

    /** Put the camera so it looks at the given scene tile. */
    fun aimCamera(camera: Camera, sx: Int, sy: Int) {
        val tx = sx * Constants.LOCAL_TILE_SIZE + Constants.LOCAL_HALF_TILE_SIZE
        val ty = sy * Constants.LOCAL_TILE_SIZE + Constants.LOCAL_HALF_TILE_SIZE
        camera.cameraZ = -2500
        val sin = camera.pitchSin / 65536.0
        val cos = camera.pitchCos / 65536.0
        val back = if (sin > 0.2) 2500 * cos / sin else 0.0
        val fx = -camera.yawSin / 65536.0
        val fy = camera.yawCos / 65536.0
        camera.cameraX = (tx - fx * back).toInt()
        camera.cameraY = (ty - fy * back).toInt()
    }

    private val mapLoader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "map-loader").apply { isDaemon = true } }

    /** True while a map is loading in the background (the old one stays on screen; map edits wait). */
    val mapLoading: Boolean get() = queuedLoads.get() > 0
    private val queuedLoads = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Loads [radius] x [radius] regions around [centerRegion] in the background, so the window stays usable (a 7 x 7
     * map takes a while). [done] runs on the JavaFX thread afterwards, with the error if it failed.
     */
    /** "Loading ..." while a map loads (shown over the 3D view), else empty. JavaFX thread only. */
    val loadingText = javafx.beans.property.SimpleStringProperty("")
    private var pendingLoads = 0

    private val newestLoad = java.util.concurrent.atomic.AtomicLong()

    fun loadAsync(scene: Scene, centerRegion: Int, radius: Int, done: (Throwable?) -> Unit = {}) {
        queuedLoads.incrementAndGet()
        val ticket = newestLoad.incrementAndGet()
        javafx.application.Platform.runLater { pendingLoads++; loadingText.set("Loading $radius x $radius regions around region $centerRegion...") }
        mapLoader.execute {
            var err: Throwable? = null
            // a newer request (another map size or place picked meanwhile) wins: this one is skipped
            if (ticket != newestLoad.get()) err = java.util.concurrent.CancellationException("a newer map load replaced it")
            else try { scene.load(centerRegion, radius) } catch (e: Throwable) { err = e; e.printStackTrace() }
            queuedLoads.decrementAndGet()
            javafx.application.Platform.runLater {
                if (--pendingLoads <= 0) { pendingLoads = 0; loadingText.set("") }
                done(err)
            }
        }
    }

    /** [goTo] in the background. */
    fun goToAsync(scene: Scene, camera: Camera, x: Int, y: Int, radius: Int, done: (Throwable?) -> Unit = {}) =
        loadAsync(scene, regionOf(x, y), radius) { e ->
            if (e == null) worldToScene(scene, x, y)?.let { aimCamera(camera, it.first, it.second) }
            done(e)
        }

    /** Load the area around a world tile and point the camera at it. */
    fun goTo(scene: Scene, camera: Camera, x: Int, y: Int, radius: Int) {
        scene.load(regionOf(x, y), radius)
        val s = worldToScene(scene, x, y) ?: return
        aimCamera(camera, s.first, s.second)
    }

    /** Shift the loaded area by whole regions, keeping the camera on the same world spot. */
    fun shiftRegions(scene: Scene, camera: Camera, dx: Int, dy: Int) {
        if (!scene.isLoaded()) return
        val oldX = camera.cameraX
        val oldY = camera.cameraY
        val oldZ = camera.cameraZ
        loadAsync(scene, scene.centerRegionId + dx * 256 + dy, scene.radius) { e ->
            if (e != null) return@loadAsync
            camera.cameraX = oldX - dx * REGION_SIZE * Constants.LOCAL_TILE_SIZE
            camera.cameraY = oldY - dy * REGION_SIZE * Constants.LOCAL_TILE_SIZE
            camera.cameraZ = oldZ
        }
    }

    // ---------- templates ----------

    fun templates(): ToolTemplates {
        val f = templatesFile
        val d = ToolTemplates()
        val loaded: ToolTemplates? = if (f.exists()) try {
            gson.fromJson(f.readText(), ToolTemplates::class.java)
        } catch (e: Exception) {
            println("gadscape-tools.json is invalid, using defaults: ${e.message}")
            return d
        } else null
        val t = loaded ?: d
        val merged = ToolTemplates(
            t.tele ?: d.tele, t.npcSpawn ?: d.npcSpawn, t.objectSpawn ?: d.objectSpawn,
            t.deployHost ?: d.deployHost, t.deployPort ?: d.deployPort, t.deployUser ?: d.deployUser,
            t.deployKey ?: d.deployKey, t.deployRemoteCache ?: d.deployRemoteCache, t.deployService ?: d.deployService,
            t.deployJar ?: d.deployJar, t.deployRemoteData ?: d.deployRemoteData
        )
        // write back so new settings show up in the file
        if (merged != loaded) try { f.writeText(gson.toJson(merged)) } catch (e: Exception) {}
        return merged
    }

    val templatesPath: File get() = templatesFile

    /** Regions edited this session. Kept so edits survive moving the view away before exporting. */
    val dirtyRegions: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** Write every loaded region, plus any region edited this session, into cache-out (auto-backup first). Returns regions written. */
    fun exportLoaded(scene: Scene, locationsLoader: cache.loaders.LocationsLoader,
                     regionLoader: cache.loaders.RegionLoader, xtea: XteaManager): Int {
        val loaded = loadedRegionIds(scene)
        val ids = LinkedHashSet(loaded)
        synchronized(dirtyRegions) { ids.addAll(dirtyRegions) }
        try {
            backupRegions(ids, xtea, "auto-export")
        } catch (e: Exception) {
            println("Auto-backup before export failed: ${e.message}")
        }
        val out = CacheLibrary(ensureExportCache().path)
        var count = 0
        try {
            val done = HashSet<Int>()
            for (col in scene.getRegions()) for (sr in col) {
                if (sr == null) continue
                locationsLoader.writeLocations(out, sr.locationsDefinition)
                regionLoader.writeRegion(out, sr.regionDefinition)
                done.add(sr.regionDefinition.regionId)
                count++
            }
            // edited regions that are no longer in view still live in the loaders' caches
            for (id in ids) {
                if (id in done) continue
                val region = regionLoader.get(id) ?: continue
                val locs = try { locationsLoader.get(id) } catch (e: Exception) { null }
                if (locs != null) locationsLoader.writeLocations(out, locs)
                regionLoader.writeRegion(out, region)
                count++
            }
        } finally {
            out.close()
        }
        return count
    }

    fun fill(template: String, values: Map<String, Any>): String {
        var out = template
        values.forEach { (k, v) -> out = out.replace("{$k}", v.toString()) }
        return out
    }

    // ---------- bookmarks ----------

    private val defaultBookmarks = listOf(
        Bookmark("Lumbridge", 3222, 3218, 0),
        Bookmark("Varrock square", 3212, 3424, 0),
        Bookmark("Edgeville", 3094, 3494, 0),
        Bookmark("Grand Exchange", 3164, 3487, 0),
        Bookmark("Falador", 2964, 3378, 0)
    )

    fun loadBookmarks(): MutableList<Bookmark> {
        val f = bookmarksFile
        if (!f.exists()) {
            saveBookmarks(defaultBookmarks)
            return defaultBookmarks.toMutableList()
        }
        return try {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            gson.fromJson<List<Bookmark>>(f.readText(), type).toMutableList()
        } catch (e: Exception) {
            println("gadscape-bookmarks.json is invalid: ${e.message}")
            mutableListOf()
        }
    }

    fun saveBookmarks(list: List<Bookmark>) {
        try { bookmarksFile.writeText(gson.toJson(list)) } catch (e: Exception) { println("Could not save bookmarks: ${e.message}") }
    }

    // ---------- export cache + region backups ----------

    /** Make sure cache-out exists (a copy of the editor cache) and return it. */
    fun ensureExportCache(): File {
        val out = exportCacheDir
        if (!out.exists() || out.listFiles()?.none { it.name.startsWith("main_file_cache") } != false) {
            File(CachePaths.cacheDir).absoluteFile.copyRecursively(out, true)
        }
        return out
    }

    private fun readLandscape(lib: CacheLibrary, name: String, keys: IntArray?): Pair<ByteArray, Boolean>? {
        val withKey = try { lib.data(5, name, keys) } catch (e: Exception) { null }
        if (withKey != null) return Pair(withKey, keys != null)
        try { lib.index(5).unCache() } catch (e: Exception) {}
        val plain = try { lib.data(5, name) } catch (e: Exception) { null } ?: return null
        return Pair(plain, false)
    }

    /** Snapshot the terrain + objects of the given regions from cache-out. Returns the backup folder. */
    fun backupRegions(regionIds: Collection<Int>, xtea: XteaManager, label: String = "manual"): File? {
        if (regionIds.isEmpty()) return null
        val cacheDir = ensureExportCache()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val dir = File(backupsDir, "$stamp-$label")
        dir.mkdirs()
        val lib = CacheLibrary(cacheDir.path)
        try {
            for (id in regionIds) {
                val rx = (id shr 8) and 0xFF
                val ry = id and 0xFF
                val m = try { lib.data(5, "m${rx}_${ry}") } catch (e: Exception) { null }
                if (m != null) File(dir, "m${rx}_${ry}.dat").writeBytes(m)
                val l = readLandscape(lib, "l${rx}_${ry}", xtea.getKeys(id))
                if (l != null) {
                    File(dir, "l${rx}_${ry}.dat").writeBytes(l.first)
                    if (l.second) File(dir, "l${rx}_${ry}.enc").writeText("encrypted")
                }
            }
        } finally {
            lib.close()
        }
        File(dir, "regions.txt").writeText(regionIds.joinToString(","))
        return dir
    }

    fun listBackups(): List<File> =
        backupsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    /** Write a backup's files back into cache-out. Returns number of files restored. */
    fun restoreBackup(dir: File, xtea: XteaManager): Int {
        val lib = CacheLibrary(ensureExportCache().path)
        var count = 0
        try {
            dir.listFiles()?.filter { it.name.endsWith(".dat") }?.forEach { f ->
                val name = f.nameWithoutExtension
                val bytes = f.readBytes()
                if (name.startsWith("l") && File(dir, "$name.enc").exists()) {
                    val parts = name.substring(1).split("_")
                    val id = (parts[0].toInt() shl 8) or parts[1].toInt()
                    lib.put(5, name, bytes, xtea.getKeys(id))
                } else {
                    lib.put(5, name, bytes)
                }
                count++
            }
            lib.index(5).update()
        } finally {
            lib.close()
        }
        return count
    }

    fun loadedRegionIds(scene: Scene): List<Int> {
        if (!scene.isLoaded()) return emptyList()
        val start = scene.startRegionId
        val ids = ArrayList<Int>()
        for (gx in 0 until scene.radius) for (gy in 0 until scene.radius) ids.add(start + gx * 256 + gy)
        return ids
    }
}
