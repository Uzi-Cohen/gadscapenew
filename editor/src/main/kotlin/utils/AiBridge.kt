package utils

import cache.definitions.Location
import cache.definitions.RegionDefinition
import cache.loaders.ObjectLoader
import cache.loaders.OverlayLoader
import cache.loaders.UnderlayLoader
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import controllers.worldRenderer.Camera
import controllers.worldRenderer.Constants
import models.*
import models.scene.Scene
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The AI bridge: a small local web API (127.0.0.1 only, with a key) that lets Claude (Claude Code on this PC, or a
 * Claude chat linked to it) read the loaded map and change it the same way the tools do. Every change goes through
 * the editor's own undo, so Ctrl+Z takes back whatever the AI did, one request at a time.
 *
 * The port and key are written to ~/.uzis-map-editor/ai-bridge.json while it runs. AI-BRIDGE.md (next to the
 * editor, and served at /api/help) says how to use it.
 */
class AiBridge(
    private val scene: Scene,
    private val camera: Camera,
    private val model: GadToolsModel,
    private val regions: RegionAccess,
    private val objects: ObjectLoader,
    private val underlays: UnderlayLoader,
    private val overlays: OverlayLoader,
    private val textures: cache.loaders.TextureLoader?,
    private val log: (String) -> Unit
) {
    companion object {
        const val DEFAULT_PORT = 47811
        val infoFile = File(System.getProperty("user.home"), ".uzis-map-editor/ai-bridge.json")
        /** Most tiles one request may read or change. */
        const val MAX_TILES = 64 * 64
        const val MAX_ACTIONS = 4000
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val ops = TileOps(regions)
    private val assets = AssetBridge(log)
    private var server: ServerSocket? = null
    @Volatile var running = false; private set
    var port = DEFAULT_PORT; private set
    val key: String = java.util.UUID.randomUUID().toString().replace("-", "")

    // ---------------- server ----------------

    fun start(): String {
        if (running) return "The AI bridge is already on (port $port)."
        var s: ServerSocket? = null
        for (p in DEFAULT_PORT until DEFAULT_PORT + 10) {
            try { s = ServerSocket(p, 20, InetAddress.getByName("127.0.0.1")); port = p; break } catch (e: Exception) {}
        }
        val sock = s ?: return "Couldn't open a port for the AI bridge (47811-47820 are all taken)."
        server = sock; running = true
        infoFile.parentFile.mkdirs()
        infoFile.writeText(gson.toJson(mapOf("url" to "http://127.0.0.1:$port", "key" to key, "header" to "X-Bridge-Key",
            "help" to "http://127.0.0.1:$port/api/help")))
        thread(isDaemon = true, name = "ai-bridge") {
            while (running) {
                val c = try { sock.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { try { handle(c) } catch (e: Exception) {} finally { try { c.close() } catch (e: Exception) {} } }
            }
        }
        log("AI bridge on at http://127.0.0.1:$port (key in ${infoFile.path}).")
        return "AI bridge on: http://127.0.0.1:$port. The key is in ${infoFile.path}."
    }

    fun stop(): String {
        running = false
        try { server?.close() } catch (e: Exception) {}
        server = null
        infoFile.delete()
        return "AI bridge off."
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun handle(c: Socket) {
        c.soTimeout = 60000
        val inp = BufferedInputStream(c.getInputStream())
        fun line(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val ch = inp.read()
                if (ch == -1) return if (b.size() == 0) null else b.toString("UTF-8")
                if (ch == '\n'.toInt()) return b.toString("UTF-8").trimEnd('\r')
                b.write(ch)
                if (b.size() > 16384) return null
            }
        }
        val first = line() ?: return
        val parts = first.split(' ')
        if (parts.size < 2) return
        val headers = HashMap<String, String>()
        while (true) { val h = line() ?: break; if (h.isEmpty()) break; val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).trim().toLowerCase()] = h.substring(i + 1).trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 8_000_000) return respond(c.getOutputStream(), 413, "text/plain", "too big".toByteArray())
        val body = ByteArray(len); var got = 0
        while (got < len) { val n = inp.read(body, got, len - got); if (n < 0) break; got += n }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val req = Req(parts[0].toUpperCase(), path, query, headers, String(body, 0, got, Charsets.UTF_8))
        val out = c.getOutputStream()
        // web pages can't send this header across sites, so a page you visit can't drive the editor
        if (path != "/api/help" && headers["x-bridge-key"] != key && query["key"] != key)
            return json(out, 403, mapOf("error" to "Missing or wrong X-Bridge-Key header. The key is in ${infoFile.path}."))
        try {
            route(req, out)
        } catch (e: BridgeError) {
            json(out, 400, mapOf("error" to e.message))
        } catch (e: AssetBridge.AssetError) {
            json(out, 400, mapOf("error" to e.message))
        } catch (e: Exception) {
            json(out, 500, mapOf("error" to (e.message ?: e.toString())))
        }
    }

    private class BridgeError(msg: String) : Exception(msg)
    private fun fail(msg: String): Nothing = throw BridgeError(msg)

    private fun respond(out: OutputStream, code: Int, type: String, data: ByteArray) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Too Large"; else -> "Error" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(data); out.flush()
    }

    private fun json(out: OutputStream, code: Int, v: Any?) = respond(out, code, "application/json; charset=utf-8", gson.toJson(v).toByteArray(Charsets.UTF_8))

    /** Run on the editor's drawing thread (where the map is changed), and wait for it. */
    private fun <T> onEditor(timeoutS: Long = 20, block: () -> Pair<T, Edit?>): T {
        if (!scene.isLoaded()) fail("No map is loaded in the editor yet.")
        val f = CompletableFuture<T>()
        model.actions.add {
            try { val (r, e) = block(); f.complete(r); e } catch (ex: Throwable) { f.completeExceptionally(ex); null }
        }
        try { return f.get(timeoutS, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        catch (e: java.util.concurrent.TimeoutException) { fail("The editor didn't answer in time (is its window open?).") }
    }

    private fun route(r: Req, out: OutputStream) {
        when ("${r.method} ${r.path}") {
            "GET /api/help" -> respond(out, 200, "text/markdown; charset=utf-8", help().toByteArray())
            "GET /api/state" -> json(out, 200, state())
            "GET /api/area" -> json(out, 200, area(r.query))
            "GET /api/objects" -> json(out, 200, objectSearch(r.query))
            "GET /api/objects/used" -> json(out, 200, objectsUsed(r.query))
            "GET /api/object" -> json(out, 200, objectInfo(r.query["id"]?.toIntOrNull() ?: fail("id needed")))
            "GET /api/floors" -> json(out, 200, floors(r.query))
            "GET /api/screenshot" -> respond(out, 200, "image/png", screenshot(r.query))
            "POST /api/apply" -> json(out, 200, apply(JsonParser().parse(r.body).asJsonObject))
            "POST /api/undo" -> { model.undoRequested.set(true); Thread.sleep(300); json(out, 200, mapOf("ok" to true, "message" to model.message)) }
            "POST /api/redo" -> { model.redoRequested.set(true); Thread.sleep(300); json(out, 200, mapOf("ok" to true, "message" to model.message)) }
            "POST /api/goto" -> json(out, 200, goTo(JsonParser().parse(r.body.ifBlank { "{}" }).asJsonObject))
            "POST /api/view" -> json(out, 200, view(JsonParser().parse(r.body.ifBlank { "{}" }).asJsonObject))
            "POST /api/select" -> json(out, 200, select(JsonParser().parse(r.body.ifBlank { "{}" }).asJsonObject))
            "POST /api/message" -> { val o = JsonParser().parse(r.body).asJsonObject; model.message = o["text"]?.asString ?: ""; json(out, 200, mapOf("ok" to true)) }
            else -> when (val a = assets.route(r.method, r.path, r.query) { JsonParser().parse(r.body.ifBlank { "{}" }).asJsonObject }) {
                is AssetBridge.Reply.Json -> json(out, 200, a.value)
                is AssetBridge.Reply.Png -> respond(out, 200, "image/png", a.bytes)
                is AssetBridge.Reply.Text -> respond(out, 200, "text/markdown; charset=utf-8", a.text.toByteArray())
                null -> json(out, 404, mapOf("error" to "No such call: ${r.method} ${r.path}. GET /api/help lists them."))
            }
        }
    }

    // ---------------- reading ----------------

    private fun loaded(): List<Int> = GadTools.loadedRegionIds(scene)

    private fun isLoaded(x: Int, y: Int) = scene.isLoaded() && GadTools.worldToScene(scene, x, y) != null

    private fun state(): Map<String, Any?> {
        val ids = loaded()
        val sel = model.selection
        val pin = model.pinned.get()
        val so = model.selectedObject
        val area = if (ids.isEmpty()) null else {
            val xs = ids.map { (it shr 8) shl 6 }; val ys = ids.map { (it and 0xFF) shl 6 }
            mapOf("x1" to xs.minOrNull(), "y1" to ys.minOrNull(), "x2" to xs.maxOrNull()!! + 63, "y2" to ys.maxOrNull()!! + 63)
        }
        return linkedMapOf(
            "loaded" to scene.isLoaded(),
            "loadedArea" to area,
            "loadedRegions" to ids,
            "selection" to sel?.let { mapOf("x1" to it.x1, "y1" to it.y1, "x2" to it.x2, "y2" to it.y2, "width" to it.width, "height" to it.height) },
            "pinnedTile" to pin?.let { mapOf("x" to it.x, "y" to it.y, "z" to it.z) },
            "selectedObject" to so?.let { mapOf("x" to it.worldX, "y" to it.worldY, "z" to it.loc.z, "id" to it.loc.objId, "name" to objName(it.loc.objId), "type" to it.loc.type, "rot" to it.loc.orientation) },
            "floor" to model.paintPlane.get(),
            "cameraTile" to cameraTile(),
            "undoSteps" to model.undoCount,
            "unsaved" to model.unsaved.get(),
            "message" to model.message
        )
    }

    private fun cameraTile(): Map<String, Int>? {
        if (!scene.isLoaded()) return null
        val sin = camera.pitchSin / 65536.0; val cos = camera.pitchCos / 65536.0
        val ahead = if (sin > 0.2) -camera.cameraZ * cos / sin else 0.0
        val fx = -camera.yawSin / 65536.0; val fy = camera.yawCos / 65536.0
        val sx = ((camera.cameraX + fx * ahead) / Constants.LOCAL_TILE_SIZE).toInt(); val sy = ((camera.cameraY + fy * ahead) / Constants.LOCAL_TILE_SIZE).toInt()
        val w = GadTools.sceneToWorld(scene, sx, sy) ?: return null
        return mapOf("x" to w.first, "y" to w.second)
    }

    private fun rect(q: Map<String, String>): TileRect {
        val sel = model.selection
        val r = if (q["x1"] != null) TileRect.of(q["x1"]!!.toInt(), q["y1"]!!.toInt(), q["x2"]!!.toInt(), q["y2"]!!.toInt())
            else sel ?: fail("Give x1, y1, x2, y2 or select an area in the editor first.")
        if (r.width * r.height > MAX_TILES) fail("That's ${r.width} x ${r.height} tiles; read at most $MAX_TILES at once (e.g. 64 x 64).")
        return r
    }

    private fun objName(id: Int): String {
        val d = objects.get(id) ?: return "(missing)"
        d.name.takeIf { it != "null" && it.isNotBlank() }?.let { return it }
        val t = (d.modelTypes ?: intArrayOf(10)).toSet()
        return "(no name) " + when {
            1 in t -> "wall corner piece"; 3 in t -> "wall corner pillar"; 2 in t -> "wall corner (L)"; 0 in t -> "wall"
            9 in t -> "diagonal wall"; t.any { it in 4..8 } -> "wall decoration"; t.any { it in 12..21 } -> "roof piece"
            22 in t -> "ground decoration"; else -> "object"
        }
    }

    /** What an object looks like as far as placing goes: its model types and size. */
    private fun objectInfo(id: Int): Map<String, Any?> {
        val d = objects.get(id) ?: fail("There's no object $id.")
        return linkedMapOf("id" to id, "name" to objName(id), "sizeX" to d.sizeX, "sizeY" to d.sizeY,
            "types" to placeTypes(id), "actions" to d.actions.filterNotNull(), "solid" to (d.interactType != 0),
            "walkable" to (d.interactType == 0), "hasModel" to (d.modelIds?.isNotEmpty() == true))
    }

    /** The object types (0-22) an object can be placed as. */
    private fun placeTypes(id: Int): List<Int> {
        val d = objects.get(id) ?: return emptyList()
        val mt = d.modelTypes
        val types = if (mt == null || mt.isEmpty()) (if (d.modelIds?.isNotEmpty() == true) listOf(10, 11) else emptyList()) else mt.toList().distinct()
        val out = types.toMutableSet()
        if (10 in out) out.add(11)
        if (4 in out) out.addAll(listOf(5, 6, 7, 8))
        return out.sorted()
    }

    private fun area(q: Map<String, String>): Map<String, Any?> {
        val r = rect(q)
        val plane = q["z"]?.toIntOrNull() ?: model.paintPlane.get()
        val withObjects = q["objects"] != "0"
        return onEditor {
            // rows from north to south, each tile "h,u,o" (+ ":shape/rot" for a shaped overlay, "!flags" if any)
            val rows = ArrayList<String>()
            val objs = ArrayList<Map<String, Any>>()
            for (y in r.y2 downTo r.y1) {
                val sb = StringBuilder()
                for (x in r.x1..r.x2) {
                    if (x > r.x1) sb.append(' ')
                    val t = regions.region(regionIdOf(x, y))?.tiles?.get(plane)?.get(x and 63)?.get(y and 63)
                    if (t == null || !isLoaded(x, y)) { sb.append('-'); continue }
                    val h = (t.height / -8)
                    sb.append(if (plane == 0) h else (t.cacheHeight ?: -1)).append(',')
                        .append(t.underlayId.toInt() - 1).append(',').append(t.overlayId.toInt() - 1)
                    if (t.overlayId.toInt() != 0 && t.attrOpcode > 1) sb.append(':').append((t.attrOpcode - 2) / 4).append('/').append((t.attrOpcode - 2) and 3)
                    val f = t.settings.toInt() and 0xFF
                    if (f != 0) sb.append('!').append(f)
                }
                rows.add("${y}: $sb")
            }
            if (withObjects) for (id in regionIdsIn(r)) {
                val locs = regions.locations(id)?.locations ?: continue
                val bx = (id shr 8) shl 6; val by = (id and 0xFF) shl 6
                for (l in locs) {
                    val wx = bx + l.x; val wy = by + l.y
                    if (!r.contains(wx, wy)) continue
                    if (q["allFloors"] != "1" && l.z != plane) continue
                    objs.add(linkedMapOf("x" to wx, "y" to wy, "z" to l.z, "id" to l.objId, "name" to objName(l.objId), "type" to l.type, "rot" to l.orientation))
                }
            }
            Pair(linkedMapOf<String, Any?>(
                "area" to mapOf("x1" to r.x1, "y1" to r.y1, "x2" to r.x2, "y2" to r.y2, "z" to plane),
                "tileFormat" to "each row is one y (north first), tiles west to east: height,underlay,overlay[:shape/rot][!flags]; " +
                        "height in steps of 8 game units (bigger = higher, a floor is 30), -1 = none, '-' = not loaded",
                "tiles" to rows,
                "objects" to objs.sortedWith(compareBy({ -(it["y"] as Int) }, { it["x"] as Int }))
            ), null)
        }
    }

    private fun regionIdsIn(r: TileRect): List<Int> {
        val ids = ArrayList<Int>()
        for (x in (r.x1 shr 6)..(r.x2 shr 6)) for (y in (r.y1 shr 6)..(r.y2 shr 6)) ids.add((x shl 8) or y)
        return ids
    }

    private val objectIndex: List<Pair<Int, String>> by lazy {
        objects.getAll().values.filter { it.modelIds?.isNotEmpty() == true }.map { it.id to objName(it.id) }.sortedBy { it.first }
    }

    private fun objectSearch(q: Map<String, String>): List<Map<String, Any?>> {
        val words = (q["q"] ?: fail("q needed, e.g. ?q=dead tree")).toLowerCase().split(' ').filter { it.isNotBlank() }
        val limit = (q["limit"]?.toIntOrNull() ?: 40).coerceIn(1, 300)
        val used = usageCounts()
        return objectIndex.filter { (_, n) -> val ln = n.toLowerCase(); words.all { ln.contains(it) } }
            .sortedByDescending { used[it.first] ?: 0 }
            .take(limit)
            .map { (id, _) -> objectInfo(id) + ("usedNearby" to (used[id] ?: 0)) }
    }

    @Volatile private var usageCache: Pair<Long, Map<Int, Int>>? = null
    private fun usageCounts(): Map<Int, Int> {
        usageCache?.let { if (System.currentTimeMillis() - it.first < 5000) return it.second }
        val m = HashMap<Int, Int>()
        for (id in loaded()) regions.locations(id)?.locations?.toList()?.forEach { m[it.objId] = (m[it.objId] ?: 0) + 1 }
        usageCache = System.currentTimeMillis() to m
        return m
    }

    private fun objectsUsed(q: Map<String, String>): List<Map<String, Any?>> {
        val r = if (q["x1"] != null || model.selection != null) rect(q) else null
        val m = HashMap<Int, Int>()
        val types = HashMap<Int, MutableSet<Int>>()
        for (id in if (r != null) regionIdsIn(r) else loaded()) {
            val bx = (id shr 8) shl 6; val by = (id and 0xFF) shl 6
            regions.locations(id)?.locations?.toList()?.forEach { l ->
                if (r == null || r.contains(bx + l.x, by + l.y)) { m[l.objId] = (m[l.objId] ?: 0) + 1; types.getOrPut(l.objId) { HashSet() }.add(l.type) }
            }
        }
        val limit = (q["limit"]?.toIntOrNull() ?: 150).coerceIn(1, 1000)
        return m.entries.sortedByDescending { it.value }.take(limit).map { (id, n) ->
            val d = objects.get(id)
            linkedMapOf("id" to id, "name" to objName(id), "count" to n, "placedAs" to types[id]!!.sorted(), "sizeX" to (d?.sizeX ?: 1), "sizeY" to (d?.sizeY ?: 1))
        }
    }

    private fun floors(q: Map<String, String>): Map<String, Any?> {
        val usedU = HashMap<Int, Int>(); val usedO = HashMap<Int, Int>()
        for (id in loaded()) {
            val def = regions.region(id) ?: continue
            for (x in 0 until 64) for (y in 0 until 64) {
                val t = def.tiles[0][x][y] ?: continue
                val u = t.underlayId.toInt() - 1; val o = t.overlayId.toInt() - 1
                if (u >= 0) usedU[u] = (usedU[u] ?: 0) + 1
                if (o >= 0) usedO[o] = (usedO[o] ?: 0) + 1
            }
        }
        fun hex(c: Int) = String.format("#%06x", c and 0xFFFFFF)
        return linkedMapOf(
            "note" to "underlay = the ground colour (blends with its neighbours); overlay = paint on top (paths, floors, water), " +
                    "can be shaped (shape 0 = full tile, 1-11 = partial, rot 0-3). usedNearby = tiles using it in the loaded map.",
            "underlays" to underlays.ids().map { id -> linkedMapOf("id" to id, "rgb" to hex(underlays.get(id)?.color ?: 0), "usedNearby" to (usedU[id] ?: 0)) }
                .sortedByDescending { it["usedNearby"] as Int },
            "overlays" to overlays.ids().map { id -> val d = overlays.get(id)
                val tex = d?.texture ?: -1
                val looks = if (tex >= 0) textures?.getAverageTextureRGB(tex)?.let { hex(ModelCodec.palette[it and 0xFFFF]) } else null
                linkedMapOf("id" to id, "rgb" to (looks ?: hex(d?.rgbColor ?: 0)), "texture" to tex, "seeThrough" to ((d?.rgbColor ?: 0) == 0xFF00FF && (d?.texture ?: -1) == -1),
                    "usedNearby" to (usedO[id] ?: 0)) }.sortedByDescending { it["usedNearby"] as Int }
        )
    }

    // ---------------- camera / screenshot ----------------

    private fun goTo(o: JsonObject): Map<String, Any?> {
        val x = o["x"]?.asInt ?: fail("x needed"); val y = o["y"]?.asInt ?: fail("y needed")
        val radius = (o["radius"]?.asInt ?: 3).coerceIn(1, 8)
        val f = CompletableFuture<Unit>()
        javafx.application.Platform.runLater { try { GadTools.goTo(scene, camera, x, y, radius); f.complete(Unit) } catch (e: Exception) { f.completeExceptionally(e) } }
        f.get(60, TimeUnit.SECONDS)
        Thread.sleep(1500)
        return state()
    }

    /** Point the camera: at tile x,y, from [distance] (game units), looking down [pitch] (128 low - 434 straight down), facing [yaw]. */
    private fun view(o: JsonObject): Map<String, Any?> {
        val x = o["x"]?.asInt ?: cameraTile()?.get("x") ?: fail("x needed"); val y = o["y"]?.asInt ?: cameraTile()?.get("y") ?: fail("y needed")
        val s = GadTools.worldToScene(scene, x, y) ?: fail("$x, $y isn't in the loaded map; /api/goto it first.")
        o["pitch"]?.asInt?.let { camera.pitch = it.coerceIn(128, 434) }
        o["yaw"]?.asInt?.let { camera.yaw = it and 2047 }
        val dist = (o["distance"]?.asInt ?: 2500).coerceIn(300, 12000)
        val tx = s.first * Constants.LOCAL_TILE_SIZE + Constants.LOCAL_HALF_TILE_SIZE
        val ty = s.second * Constants.LOCAL_TILE_SIZE + Constants.LOCAL_HALF_TILE_SIZE
        val sin = camera.pitchSin / 65536.0; val cos = camera.pitchCos / 65536.0
        camera.cameraZ = -(dist * sin).toInt() - 240 * model.paintPlane.get()
        val back = dist * cos
        camera.cameraX = (tx - (-camera.yawSin / 65536.0) * back).toInt()
        camera.cameraY = (ty - (camera.yawCos / 65536.0) * back).toInt()
        return mapOf("ok" to true, "pitch" to camera.pitch, "yaw" to camera.yaw, "distance" to dist)
    }

    private fun screenshot(q: Map<String, String>): ByteArray {
        if (q["x"] != null) view(JsonObject().apply {
            addProperty("x", q["x"]!!.toInt()); addProperty("y", q["y"]!!.toInt())
            q["pitch"]?.let { addProperty("pitch", it.toInt()) }; q["yaw"]?.let { addProperty("yaw", it.toInt()) }; q["distance"]?.let { addProperty("distance", it.toInt()) }
        })
        Thread.sleep(250) // a couple of frames so the new view is drawn
        val f = CompletableFuture<java.awt.image.BufferedImage>()
        model.screenshotRequest = f
        val img = try { f.get(10, TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) { model.screenshotRequest = null; fail("The editor didn't draw a frame in 10 seconds (is its window open and not minimised?).") }
            catch (e: java.util.concurrent.ExecutionException) { fail("The editor couldn't read its frame: ${e.cause ?: e}") }
        val max = (q["max"]?.toIntOrNull() ?: 1280).coerceIn(200, 4000)
        val scaled = if (img.width <= max) img else {
            val h = img.height * max / img.width
            java.awt.image.BufferedImage(max, h, java.awt.image.BufferedImage.TYPE_INT_RGB).also { s ->
                val g = s.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, max, h, null); g.dispose()
            }
        }
        val o = ByteArrayOutputStream(); javax.imageio.ImageIO.write(scaled, "png", o); return o.toByteArray()
    }

    private fun select(o: JsonObject): Map<String, Any?> {
        if (o["clear"]?.asBoolean == true) { model.selection = null; return state() }
        model.selection = TileRect.of(o["x1"].asInt, o["y1"].asInt, o["x2"].asInt, o["y2"].asInt)
        return state()
    }

    // ---------------- changing ----------------

    private class Plan(val label: String, val steps: List<() -> Edit?>, val summary: List<String>)

    /** Every action is checked first; if any is wrong nothing is changed. Then all of them become one undo step. */
    private fun apply(body: JsonObject): Map<String, Any?> {
        val label = body["label"]?.asString ?: "AI edit"
        val list = body["actions"]?.asJsonArray ?: fail("actions needed (a list).")
        if (list.size() > MAX_ACTIONS) fail("At most $MAX_ACTIONS actions per request; split it up.")
        val dry = body["dryRun"]?.asBoolean == true
        val errors = ArrayList<String>()
        val steps = ArrayList<() -> Edit?>()
        val counts = LinkedHashMap<String, Int>()
        for ((i, el) in list.withIndex()) {
            try {
                val a = el.asJsonObject
                val op = a["op"]?.asString ?: fail("no op")
                steps.add(plan(op, a))
                counts[op] = (counts[op] ?: 0) + 1
            } catch (e: BridgeError) { errors.add("action $i: ${e.message}") } catch (e: Exception) { errors.add("action $i: ${e.message ?: e}") }
            if (errors.size >= 25) break
        }
        if (errors.isNotEmpty()) return mapOf("ok" to false, "changed" to false, "errors" to errors, "note" to "Nothing was changed. Fix these and send it again.")
        if (dry) return mapOf("ok" to true, "changed" to false, "dryRun" to true, "actions" to counts)
        val result = onEditor(60) {
            val edits = ArrayList<Edit>()
            val problems = ArrayList<String>()
            for ((i, s) in steps.withIndex()) {
                try { s()?.let { edits.add(it) } } catch (e: Exception) { problems.add("action $i: ${e.message}") }
            }
            val edit = when (edits.size) { 0 -> null; 1 -> edits[0]; else -> MultiEdit(edits) }
            model.message = "AI: $label" + (if (edit == null) " (nothing changed)" else ". Ctrl+Z undoes it.")
            Pair(mapOf("ok" to problems.isEmpty(), "changed" to (edit != null), "actions" to counts, "problems" to problems,
                "note" to if (edit != null) "Applied as one undo step: POST /api/undo (or Ctrl+Z) takes it all back." else "Nothing changed."), edit)
        }
        usageCache = null
        log("AI bridge: $label - ${counts.entries.joinToString { "${it.value} ${it.key}" }}")
        return result
    }

    private fun JsonObject.int(k: String): Int = this[k]?.takeIf { !it.isJsonNull }?.asInt ?: fail("$k needed")
    private fun JsonObject.intOr(k: String, d: Int): Int = this[k]?.takeIf { !it.isJsonNull }?.asInt ?: d

    private fun checkLoaded(x: Int, y: Int) { if (!isLoaded(x, y)) fail("$x, $y isn't in the loaded map.") }

    /** The tiles an action covers: a box (x1,y1,x2,y2), one tile (x,y) or a list "tiles": [[x,y], ...]. */
    private fun tilesOf(a: JsonObject): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        when {
            a.has("tiles") -> for (t in a["tiles"].asJsonArray) { val p = t.asJsonArray; out.add(p[0].asInt to p[1].asInt) }
            a.has("x1") -> { val r = TileRect.of(a.int("x1"), a.int("y1"), a.int("x2"), a.int("y2"))
                if (r.width * r.height > MAX_TILES) fail("box too big (${r.width} x ${r.height}), max $MAX_TILES tiles")
                for (x in r.x1..r.x2) for (y in r.y1..r.y2) out.add(x to y) }
            a.has("x") -> out.add(a.int("x") to a.int("y"))
            else -> fail("say which tiles: x,y or x1,y1,x2,y2 or tiles")
        }
        if (out.size > MAX_TILES) fail("too many tiles (${out.size}), max $MAX_TILES")
        out.forEach { checkLoaded(it.first, it.second) }
        return out
    }

    private fun tileChange(a: JsonObject, heightOnly: Boolean, change: (RegionDefinition.Tile, Int, Int) -> Unit): () -> Edit? {
        val tiles = tilesOf(a)
        val z = a.intOr("z", model.paintPlane.get()).also { if (it !in 0..3) fail("z must be 0-3") }
        return {
            val rec = TileRecorder()
            var upper = false
            for ((x, y) in tiles) {
                val id = regionIdOf(x, y)
                val def = regions.region(id) ?: continue
                val t = def.tiles[z][x and 63][y and 63] ?: continue
                rec.touch(id, z, x and 63, y and 63, t)
                change(t, x, y)
                def.syncTile(z, x and 63, y and 63)
                if (def.restackHeights(x and 63, y and 63)) upper = true
            }
            rec.finish(heightOnly && !upper)
        }
    }

    private fun groundH(x: Int, y: Int): Int = ((regions.region(regionIdOf(x, y))?.tiles?.get(0)?.get(x and 63)?.get(y and 63)?.height ?: 0) / -8)

    private fun plan(op: String, a: JsonObject): () -> Edit? = when (op) {
        "place" -> {
            val id = a.int("id"); val x = a.int("x"); val y = a.int("y")
            val z = a.intOr("z", model.paintPlane.get()); val type = a.intOr("type", 10); val rot = a.intOr("rot", 0) and 3
            checkLoaded(x, y)
            if (z !in 0..3) fail("z must be 0-3")
            val def = objects.get(id) ?: fail("there's no object $id")
            if (def.modelIds?.isEmpty() != false) fail("object $id (${objName(id)}) has no model, it would be invisible")
            val types = placeTypes(id)
            if (type !in types) fail("object $id (${objName(id)}) can't be placed as type $type; it can be ${types.joinToString()}")
            ({
                val rid = regionIdOf(x, y)
                val locs = regions.locations(rid)?.locations
                if (locs == null) null else {
                    val group = slot(type)
                    // one object per slot per tile, like the game: a new one replaces what was there
                    val old = locs.filter { it.x == (x and 63) && it.y == (y and 63) && it.z == z && slot(it.type) == group }
                    val parts = ArrayList<Edit>()
                    if (a["replace"]?.asBoolean != false) old.forEach { locs.remove(it); parts.add(ObjectEdit(rid, it, false)) }
                    val loc = Location(id, type, rot, x and 63, y and 63, z)
                    locs.add(loc); parts.add(ObjectEdit(rid, loc, true))
                    if (parts.size == 1) parts[0] else MultiEdit(parts)
                }
            })
        }
        "remove" -> {
            val tiles = tilesOf(a)
            val z = a["z"]?.asInt
            val ids = a["ids"]?.asJsonArray?.map { it.asInt }?.toSet() ?: a["id"]?.asInt?.let { setOf(it) }
            val types = a["types"]?.asJsonArray?.map { it.asInt }?.toSet() ?: a["type"]?.asInt?.let { setOf(it) }
            val set = tiles.map { (it.first.toLong() shl 32) or (it.second.toLong() and 0xFFFFFFFFL) }.toHashSet()
            ({
                val edits = ArrayList<Edit>()
                for (rid in tiles.map { regionIdOf(it.first, it.second) }.distinct()) {
                    val locs = regions.locations(rid)?.locations ?: continue
                    val bx = (rid shr 8) shl 6; val by = (rid and 0xFF) shl 6
                    val gone = locs.filter { l -> ((bx + l.x).toLong() shl 32 or ((by + l.y).toLong() and 0xFFFFFFFFL)) in set &&
                            (z == null || l.z == z) && (ids == null || l.objId in ids) && (types == null || l.type in types) }
                    gone.forEach { locs.remove(it); edits.add(ObjectEdit(rid, it, false)) }
                }
                if (edits.isEmpty()) null else MultiEdit(edits)
            })
        }
        "rotate" -> {
            val x = a.int("x"); val y = a.int("y"); checkLoaded(x, y)
            val z = a.intOr("z", model.paintPlane.get()); val rot = a.int("rot") and 3; val id = a["id"]?.asInt
            ({
                val rid = regionIdOf(x, y)
                val locs = regions.locations(rid)?.locations
                val l = locs?.firstOrNull { it.x == (x and 63) && it.y == (y and 63) && it.z == z && (id == null || it.objId == id) }
                if (locs == null || l == null) null else {
                    val t = l.copy(orientation = rot); locs.remove(l); locs.add(t)
                    MultiEdit(listOf(ObjectEdit(rid, l, false), ObjectEdit(rid, t, true)))
                }
            })
        }
        "height" -> {
            val set = a["set"]?.asInt; val add = a["add"]?.asInt; val smooth = a["smooth"]?.asBoolean == true
            if (set == null && add == null && !smooth) fail("height needs set, add or smooth")
            val tiles = tilesOf(a)
            ({
                // worked out when it runs, after the actions before it in the same request
                val now = HashMap<Pair<Int, Int>, Int>()
                if (smooth) for ((x, y) in tiles) {
                    var s = 0; var n = 0
                    for (dx in -1..1) for (dy in -1..1) if (isLoaded(x + dx, y + dy)) { s += groundH(x + dx, y + dy); n++ }
                    now[x to y] = if (n > 0) Math.round(s.toDouble() / n).toInt() else groundH(x, y)
                }
                val rec = TileRecorder()
                var upper = false
                for ((x, y) in tiles) {
                    val id = regionIdOf(x, y)
                    val def = regions.region(id) ?: continue
                    val t = def.tiles[0][x and 63][y and 63] ?: continue
                    rec.touch(id, 0, x and 63, y and 63, t)
                    val cur = t.height / -8
                    val nh = when { smooth -> now[x to y] ?: cur; set != null -> set; else -> cur + add!! }
                    t.setGroundHeight(-(nh.coerceIn(0, 255)) * 8)
                    if (def.restackHeights(x and 63, y and 63)) upper = true
                }
                rec.finish(!upper)
            })
        }
        "underlay" -> {
            val id = a.int("id")
            if (id >= 0 && underlays.get(id) == null) fail("there's no underlay $id")
            tileChange(a, false) { t, _, _ -> t.underlayId = (id + 1) }
        }
        "overlay" -> {
            val id = a.int("id"); val shape = a.intOr("shape", 0); val rot = a.intOr("rot", 0) and 3
            if (id >= 0 && overlays.get(id) == null) fail("there's no overlay $id")
            if (shape !in 0 until OVERLAY_SHAPES) fail("shape must be 0-${OVERLAY_SHAPES - 1}")
            tileChange(a, false) { t, _, _ ->
                if (id < 0) { t.attrOpcode = 0; t.overlayId = 0; t.overlayPath = 0; t.overlayRotation = 0 }
                else { t.attrOpcode = 2 + shape * 4 + rot; t.overlayId = (id + 1); t.overlayPath = shape.toByte(); t.overlayRotation = rot.toByte() }
            }
        }
        "flags" -> {
            val addM = a.intOr("add", 0) and 0xFF; val remM = a.intOr("remove", 0) and 0xFF
            if (addM == 0 && remM == 0) fail("flags needs add or remove (1 blocked, 2 bridge, 4 remove roof, 8 render below, 16 hide on minimap)")
            tileChange(a, false) { t, _, _ -> t.settings = (((t.settings.toInt() and 0xFF) or addM) and remM.inv()).toByte() }
        }
        "clear" -> {
            val r = TileRect.of(a.int("x1"), a.int("y1"), a.int("x2"), a.int("y2"))
            if (r.width * r.height > MAX_TILES) fail("box too big")
            checkLoaded(r.x1, r.y1); checkLoaded(r.x2, r.y2)
            val parts = TileParts(heights = false, underlays = a["underlays"]?.asBoolean == true, overlays = a["overlays"]?.asBoolean ?: false,
                flags = a["flags"]?.asBoolean ?: false, objects = a["objects"]?.asBoolean ?: true)
            val z = a["z"]?.asInt
            ({ ops.clear(r, parts, if (z != null) z..z else 0..3) })
        }
        else -> fail("unknown op '$op' (place, remove, rotate, height, underlay, overlay, flags, clear)")
    }

    /** Which of a tile's object slots a type uses (walls, wall decorations, ground decorations, the rest). */
    private fun slot(type: Int) = when (type) { in 0..3 -> 0; in 4..8 -> 4; 22 -> 22; else -> 10 }

    private fun help(): String = (try {
        javaClass.getResourceAsStream("/AI-BRIDGE.md")?.readBytes()?.toString(Charsets.UTF_8) ?: "See AI-BRIDGE.md next to the editor."
    } catch (e: Exception) { "See AI-BRIDGE.md next to the editor." })
}
