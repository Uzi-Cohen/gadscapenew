package controllers.worldRenderer

import controllers.worldRenderer.helpers.GLC
import controllers.worldRenderer.helpers.Gl
import cache.LocationType
import cache.definitions.Location
import cache.definitions.ModelDefinition
import cache.definitions.ObjectDefinition
import cache.definitions.converters.ObjectToModelConverter
import com.google.inject.Inject
import controllers.worldRenderer.components.Hoverable
import controllers.worldRenderer.components.Selectable
import controllers.worldRenderer.entities.Entity
import controllers.worldRenderer.entities.Model
import controllers.worldRenderer.entities.StaticObject
import controllers.worldRenderer.helpers.AntiAliasingMode
import controllers.worldRenderer.helpers.GLUtil
import controllers.worldRenderer.helpers.GLUtil.glDeleteBuffer
import controllers.worldRenderer.helpers.GLUtil.glDeleteBuffers
import controllers.worldRenderer.helpers.GLUtil.glDeleteFrameBuffer
import controllers.worldRenderer.helpers.GLUtil.glDeleteRenderbuffers
import controllers.worldRenderer.helpers.GLUtil.glDeleteTexture
import controllers.worldRenderer.helpers.GLUtil.glDeleteVertexArrays
import controllers.worldRenderer.helpers.GLUtil.glGenBuffers
import controllers.worldRenderer.helpers.GLUtil.glGenVertexArrays
import controllers.worldRenderer.helpers.GLUtil.glGetInteger
import controllers.worldRenderer.helpers.GpuIntBuffer
import controllers.worldRenderer.helpers.ModelBuffers
import controllers.worldRenderer.shaders.Shader
import controllers.worldRenderer.shaders.ShaderException
import controllers.worldRenderer.shaders.Template
import javafx.scene.Group
import javafx.scene.input.KeyCode
import models.HoverModel
import models.GadToolsModel
import models.TerrainTool
import models.HoverObject
import models.ObjectsModel
import models.scene.*
import models.Edit
import models.ObjectEdit
import models.MultiEdit
import models.TileRecorder
import models.TileRect
import models.TileOps
import models.TileParts
import models.OVERLAY_SHAPES
import models.applyEdit
import models.refreshSets
import models.restackHeights
import models.setGroundHeight
import models.syncTile
import models.isBridge
import cache.definitions.RegionDefinition
import utils.EditorRegions
import utils.GadTools
import java.awt.event.ActionListener
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.*
import kotlin.collections.HashSet
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Renderer @Inject constructor(
    private val camera: Camera,
    private val scene: Scene,
    private val sceneUploader: SceneUploader,
    private val inputHandler: InputHandler,
    private val textureManager: TextureManager,
    private val debugModel: models.DebugModel,
    private val objectsModel: ObjectsModel,
    private val hoverModel: HoverModel,
    private val objectToModelConverter: ObjectToModelConverter,
    private val gadToolsModel: GadToolsModel,
    private val editorRegions: EditorRegions,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val overlayLoader: cache.loaders.OverlayLoader
) : GlListener {
    private val MAX_TEMP_VERTICES: Int = 65535

    private val gl = Gl
    private var glProgram = 0
    private var glComputeProgram = 0
    private var glSmallComputeProgram = 0
    private var glUnorderedComputeProgram = 0

    private var fboMainRenderer = 0
    private var rboDepthMain = 0
    private var texColorMain = 0
    private var texPickerMain = 0
    private val pboIds = IntArray(3)
    private var pboIndex = 0

    private var vaoHandle = 0

    private var fboSceneHandle = 0
    private var colorTexSceneHandle = 0
    private var rboSceneHandle = 0
    private var depthTexSceneHandle = 0

    // scene vertex buffer id
    private var bufferId = 0

    // scene uv buffer id
    private var uvBufferId = 0

    private var tmpBufferId = 0// temporary scene vertex buffer
    private var tmpUvBufferId = 0// temporary scene uv buffer
    private var tmpModelBufferId = 0 // scene model buffer, large
    private var tmpModelBufferSmallId = 0// scene model buffer, small

    private var tmpModelBufferUnorderedId = 0
    private var tmpOutBufferId = 0 // target vertex buffer for compute shaders
    private var tmpOutUvBufferId = 0// target uv buffer for compute shaders
    private var colorPickerBufferId = 0// buffer for unique picker id
    private var animFrameBufferId = 0// which frame the model should display on
    private var selectedIdsBufferId = 0

    private var hoverId = 0

    private var textureArrayId = 0
    private var uniformBufferId = 0
    private val drawBuffers: IntBuffer = GpuIntBuffer.allocateDirect(2).apply { put(GLC.GL_COLOR_ATTACHMENT0).put(GLC.GL_COLOR_ATTACHMENT1); flip() }
    private val uniformBuffer: IntBuffer = GpuIntBuffer.allocateDirect(5 + 3 + 1)
    private val textureOffsets = FloatArray(128)

    /** Per-frame work for things not in the scene: NPCs, the object held for placing (the dynamic chunk's queue). */
    private lateinit var modelBuffers: ModelBuffers

    /**
     * One region's part of the scene on the graphics card: its own vertex data and computed output. No buffer comes
     * near the card's per-buffer limit (128 MB on some cards: one buffer for a 5 x 5 map was over twice that), an
     * edit re-uploads only its region, and regions behind the camera aren't drawn.
     */
    private inner class Chunk(val gx: Int, val gy: Int) {
        val mb = ModelBuffers()
        var vb = 0; var uvb = 0; var out = 0; var outUv = 0; var picker = 0; var anim = 0; var vao = 0
        /** Vertices the output buffers hold. */
        var capacity = 0
        /** Vertices to draw (the dynamic chunk sets this each frame). */
        var count = 0
        /** Goes up with every upload: redraws asked for by tiles and objects of an older upload are dropped. */
        var generation = 0

        fun create() {
            vb = glGenBuffers(gl); uvb = glGenBuffers(gl)
            out = glGenBuffers(gl); outUv = glGenBuffers(gl); picker = glGenBuffers(gl); anim = glGenBuffers(gl)
            for (b in intArrayOf(vb, uvb)) { gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, b); gl.glBufferData(GLC.GL_ARRAY_BUFFER, 16L, null, GLC.GL_STATIC_COPY) }
            reserve(1)
            vao = glGenVertexArrays(gl)
            gl.glBindVertexArray(vao)
            gl.glEnableVertexAttribArray(0)
            gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, out)
            gl.glVertexAttribIPointer(0, 4, GLC.GL_INT, 0, 0)
            gl.glEnableVertexAttribArray(1)
            gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, outUv)
            gl.glVertexAttribPointer(1, 4, GLC.GL_FLOAT, false, 0, 0)
            gl.glEnableVertexAttribArray(2)
            gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, picker)
            gl.glVertexAttribIPointer(2, 1, GLC.GL_INT, 0, 0)
            gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, 0)
            gl.glBindVertexArray(0)
        }

        /** Makes room for [verts] output vertices (plus room for objects placed during a drag). Old output is lost. */
        fun reserve(verts: Int) {
            if (verts <= capacity && capacity > 0) return
            val n = verts + MAX_TEMP_VERTICES
            for ((b, bytes) in listOf(out to 16L, outUv to 16L, picker to 4L, anim to 16L)) {
                gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, b)
                gl.glBufferData(GLC.GL_ARRAY_BUFFER, n * bytes, null, GLC.GL_DYNAMIC_DRAW)
            }
            gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, 0)
            capacity = n
        }

        fun delete() {
            for (b in intArrayOf(vb, uvb, out, outUv, picker, anim)) if (b > 0) glDeleteBuffer(gl, b)
            if (vao > 0) glDeleteVertexArrays(gl, vao)
            vb = 0; uvb = 0; out = 0; outUv = 0; picker = 0; anim = 0; vao = 0; capacity = 0
        }
    }

    private var chunks: Array<Array<Chunk>> = emptyArray()
    private var dynamicChunk: Chunk? = null

    private fun chunkAt(sceneX: Int, sceneY: Int): Chunk? =
        if (sceneX < 0 || sceneY < 0) null else chunks.getOrNull(sceneX / REGION_SIZE)?.getOrNull(sceneY / REGION_SIZE)

    // Uniforms
    private var uniDrawDistance = 0
    private var uniProjectionMatrix = 0
    private var uniBrightness = 0
    private var uniTextures = 0
    private var uniTextureOffsets = 0
    private var uniBlockSmall = 0
    private var uniBlockLarge = 0
    private var uniBlockMain = 0
    private var uniSmoothBanding = 0
    private var uniHoverId = 0
    private var uniMouseCoordsId = 0

    // FIXME: setting these here locks this in as the minimum
    // figure out how to make these small and resize programmatically after load
    var canvasWidth = 800
    var canvasHeight = (canvasWidth / 1.3).toInt()
    private var lastViewportWidth = 0
    private var lastViewportHeight = 0
    private var lastCanvasWidth = 0
    private var lastCanvasHeight = 0
    private var lastStretchedCanvasWidth = 0
    private var lastStretchedCanvasHeight = 0
    private var lastAntiAliasingMode: AntiAliasingMode? = null

    /** The 3D view (LWJGL 3, OpenGL 4.5): a JavaFX node, drawn on its own thread. */
    lateinit var view: GlView

    /** Sets the view's size in JavaFX units. */
    fun resize(width: Double, height: Double) { if (::view.isInitialized) view.resize(width, height) }

    fun requestFocus() { if (::view.isInitialized) view.requestFocus() }

    /** Stops drawing while the map isn't on screen (another tab is open); picks up where it was when shown again. */
    fun setPaused(paused: Boolean) { if (::view.isInitialized) view.paused = paused }

    companion object {
        /** Map sizes offered (regions across; a region is 64 x 64 tiles). */
        val SIZES = listOf(3, 5, 7)
        fun mapSize(): Int = models.Configuration().getProp("map-size").toIntOrNull()?.takeIf { it in SIZES } ?: 5

        private val live = java.util.concurrent.CopyOnWriteArrayList<Renderer>()

        /** Stop every 3D render loop (call before the window closes / the app exits). */
        fun shutdownAll() = live.forEach { it.shutdown() }
    }

    fun shutdown() {
        try { if (::view.isInitialized) view.stop() } catch (e: Exception) { println("Closing 3D view: ${e.message}") }
        live.remove(this)
    }

    fun initCanvas(group: Group) {
        // center camera in viewport
        camera.centerX = canvasWidth / 2
        camera.centerY = canvasHeight / 2
        tmpOutUvBufferId = -1
        tmpOutBufferId = tmpOutUvBufferId
        tmpModelBufferUnorderedId = tmpOutBufferId
        tmpModelBufferSmallId = tmpModelBufferUnorderedId
        tmpModelBufferId = tmpModelBufferSmallId
        tmpUvBufferId = tmpModelBufferId
        tmpBufferId = tmpUvBufferId
        uniformBufferId = tmpBufferId
        uvBufferId = uniformBufferId
        bufferId = uvBufferId
        selectedIdsBufferId = -1
        colorPickerBufferId = selectedIdsBufferId
        modelBuffers = ModelBuffers()

        inputHandler.renderer = this
        view = GlView(this)
        inputHandler.attach(view)
        view.resize(canvasWidth.toDouble(), canvasHeight.toDouble())
        group.children.add(view.node)
        view.start()
        live.add(this)

        lastCanvasHeight = -1
        lastCanvasWidth = lastCanvasHeight
        lastViewportHeight = lastCanvasWidth
        lastViewportWidth = lastViewportHeight
        lastStretchedCanvasHeight = -1
        lastStretchedCanvasWidth = lastStretchedCanvasHeight
        lastAntiAliasingMode = null
        textureArrayId = -1

        gadToolsModel.paintPlane.addListener { _, old, new ->
            requeueAll = true
            camera.cameraZ -= 240 * (new.toInt() - old.toInt())
            isSceneUploadRequired = true
            highlightDirty = true
            lastHovered = null
        }
        gadToolsModel.showAllFloors.addListener { _, _, _ -> requeueAll = true; isSceneUploadRequired = true }

        var loadsSeen = -1
        scene.sceneChangeListeners.add(ActionListener {
            isSceneUploadRequired = true
            // a new map starts with the camera over its middle; a rebuilt region (after an edit) leaves it alone
            if (scene.loads != loadsSeen) {
                loadsSeen = scene.loads
                camera.cameraX = Constants.LOCAL_HALF_TILE_SIZE * scene.radius * REGION_SIZE
                camera.cameraY = Constants.LOCAL_HALF_TILE_SIZE * scene.radius * REGION_SIZE
                camera.cameraZ = -2500
            }
            highlightDirty = true
        })
        bindModels()
    }

    fun loadScene() {
        GadTools.loadAsync(scene, 12850, mapSize())
    }


    // filled from the JavaFX thread (edits), read on the render thread
    // (with the upload they belong to: a rebuilt region's old tiles still fire when they're deselected, and their
    // stale colours would land in the new tiles' places)
    private val redrawList: MutableSet<Pair<SceneTile, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val modelRedrawList: MutableSet<Triple<Entity, Chunk, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var injectedObject: SceneObject? = null
    private var heldBound = false
    private fun bindModels() {
        if (heldBound) return
        heldBound = true
        objectsModel.heldObject.addListener { _, _, newValue ->
            injectedObject = newValue
        }
    }

    /** Listens for changes on the tiles and objects of the given grid cells (all when null), so they're redrawn. */
    private fun bindTiles(cells: Set<Int>?) {
        for (gx in 0 until scene.radius) for (gy in 0 until scene.radius) {
            if (cells != null && (gx * 256 + gy) !in cells) continue
            val region = scene.getRegion(gx, gy) ?: continue
            val chunk = chunks.getOrNull(gx)?.getOrNull(gy) ?: continue
            val gen = chunk.generation
            for (z in region.tiles.indices) for (lx in 0 until REGION_SIZE) for (ly in 0 until REGION_SIZE) {
                val tile = region.tiles[z][lx][ly] ?: continue
                tile.x = gx * REGION_SIZE + lx; tile.y = gy * REGION_SIZE + ly
                tile.floorDecoration?.entity?.addListener { (entity, _) -> modelRedrawList.add(Triple(entity, chunk, gen)) }
                tile.gameObjects.forEach { it.entity?.addListener { (entity, _) -> modelRedrawList.add(Triple(entity, chunk, gen)) } }
                tile.wall?.entity?.addListener { (entity, _) -> modelRedrawList.add(Triple(entity, chunk, gen)) }
                tile.addListener { (t, _) -> redrawList.add(t to gen) }
            }
        }
    }

    private fun redrawTiles() {
        val tiles = redrawList.toList(); redrawList.removeAll(tiles.toSet())
        for ((tile, gen) in tiles) {
            val chunk = chunkAt(tile.x, tile.y) ?: continue
            if (chunk.generation != gen) continue
            val mb = chunk.mb
            sceneUploader.upload(tile, mb.vertexBuffer, mb.uvBuffer)
            tile.tilePaint?.recompute(mb)
            tile.tileModel?.recompute(mb)
        }

        val models = modelRedrawList.toList(); modelRedrawList.removeAll(models.toSet())
        for ((e, chunk, gen) in models) {
            if (chunk.generation != gen) continue
            e.getModel().recompute(chunk.mb, e.height)
        }
    }

    private fun handleHover() {
        val mouseX: Int = inputHandler.mouseX
        val mouseY: Int = inputHandler.mouseY

        // Using 3 PBOs brings this function time to 0.05ms, with only 2 PBOs is it 10ms
        // This will write to pboIndex and read from nextIndex which should have finished drawing to
        // since it will 2 frames behind.
        pboIndex = (pboIndex + 1) % 3
        val nextIndex = (pboIndex + 1) % 3

        // Read from pixel buffer object async to get pixels without blocking render
        gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, fboMainRenderer)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pboIds[pboIndex])
        gl.glReadBuffer(GLC.GL_COLOR_ATTACHMENT1)
        gl.glReadPixels(mouseX, canvasHeight - mouseY, 1, 1, GLC.GL_RED_INTEGER, GLC.GL_INT, 0)
        gl.glReadBuffer(GLC.GL_COLOR_ATTACHMENT0)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, 0)
        gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, 0)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pboIds[nextIndex])
        val srcBuf = gl.glMapBuffer(GLC.GL_PIXEL_PACK_BUFFER, GLC.GL_READ_ONLY)
        val pboBytes = ByteArray(4)
        srcBuf[pboBytes]
        val pickerId: Int =
            pboBytes[3].toInt() and 0xFF shl 24 or (pboBytes[2].toInt() and 0xFF shl 16) or (pboBytes[1].toInt() and 0xFF shl 8) or (pboBytes[0].toInt() and 0xFF)
        gl.glUnmapBuffer(GLC.GL_PIXEL_PACK_BUFFER)
        // left bound, every later glReadPixels (the AI bridge's screenshots) would read into it and fail
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, 0)
        if (pickerId == 1058050193) { // clear sky coor
            return
        }

        // -1 = null hover, ignore
        // -2 = we want to pass through this object
        if (pickerId == -1) {
            hoverId = -1
            return
        }
        if (pickerId != -2) {
            hoverId = pickerId
        }

        val x = hoverId shr 18 and 0x1FFF
        val y = hoverId shr 5 and 0x1FFF
        val type = hoverId and 0x1F
        val tile = planeTile(x, y) ?: return

        var hoverEntity: Hoverable? = null
        if (tile.tilePaint != null) {
            hoverEntity = tile.tilePaint!!
        }

        if (tile.tileModel != null) {
            hoverEntity = tile.tileModel!!
        }

        val isClickSticky = false

        if (hoverEntity != null) {
            hoverEntity.isHovered = true
            if (!isClickSticky || (isClickSticky && !inputHandler.isLeftMouseDown && !inputHandler.isRightMouseDown)) {
                val brush = gadToolsModel.brushSize.get().coerceIn(0, 20)
                if (lastHovered != hoverEntity || brush != lastBrush) {
                    lastBrush = brush
                    hoverList.forEach {
                        val st = it as SceneTile
                        st.tilePaint?.isHovered = false
                        st.tileModel?.isHovered = false
                    }

                    lastHovered?.isHovered = false
                    lastHovered = hoverEntity

                    hoverList = LinkedList()
                    // CIRCLE SELECTION (brush)
                    for (yy in y - brush..y + brush) {
                        val dy = yy - y
                        val dx = floor(sqrt((brush * brush + brush - dy * dy).toDouble().coerceAtLeast(0.0))).toInt()
                        for (xx in x - dx..x + dx) {
                            if (xx == x && yy == y) continue
                            val t = planeTile(xx, yy) ?: continue
                            hoverList.add(t)
                        }
                    }

                    hoverList.add(tile)
                    hoverList.forEach {
                        val st = it as SceneTile
                        st.tilePaint?.isHovered = true
                        st.tileModel?.isHovered = true
                    }
                }
            }
        }

        hoverModel.hovered.set(HoverObject(x, y, LocationType.fromId(type)!!, tile))

        if (injectedObject != null && tile.tilePaint != null) {
            val objectDefinition: ObjectDefinition = injectedObject!!.objectDefinition
            val modelDefinition: ModelDefinition? =
                objectToModelConverter.toModel(objectDefinition, injectedObject!!.type, injectedObject!!.orientation)
                    ?: return
            val model = Model(modelDefinition!!, objectDefinition.ambient, objectDefinition.contrast)
            model.xOff = objectDefinition.sizeX * REGION_SIZE
            model.yOff = objectDefinition.sizeY * REGION_SIZE
            sceneUploader.uploadModel(
                StaticObject(
                    objectDefinition,
                    model,
                    tile.tilePaint!!.nwHeight,
                    injectedObject!!.type,
                    injectedObject!!.orientation
                ),
                modelBuffers.vertexBuffer,
                modelBuffers.uvBuffer
            )
            model.drawDynamic(modelBuffers, x, y, tile.tilePaint!!.nwHeight)
        }
    }

    private var hoverList: LinkedList<Hoverable> = LinkedList()

    private var lastHovered: Hoverable? = null
    private var lastSelected: Selectable? = null

    private var lastBrush = -1
    private var lastClick: Double = 0.0

    // ---- editing: strokes, area tools, undo/redo ----
    private var recorder: TileRecorder? = null
    private var strokeHeightOnly = true
    /** Regions whose tile data changed during the current stroke and need a rebuild to show it. */
    private val strokeRebuild = HashSet<Int>()
    private var lastStrokeRebuild = 0L
    private var noiseSeed = 0
    private var selectAnchor: Pair<Int, Int>? = null
    // dragging a selected area to move it: where the drag started and the box before it moved
    private var areaDragFrom: Pair<Int, Int>? = null
    private var areaDragOrig: TileRect? = null
    private var areaDragCopy = false
    private val areaOps by lazy { TileOps(editorRegions) }
    private val undoStack: ArrayDeque<Edit> = ArrayDeque()
    private val redoStack: ArrayDeque<Edit> = ArrayDeque()

    private fun updateCounts() {
        gadToolsModel.undoCount = undoStack.size
        gadToolsModel.redoCount = redoStack.size
    }

    private fun pushUndo(entry: Edit) {
        gadToolsModel.unsaved.set(true)
        undoStack.addLast(entry)
        while (undoStack.size > 100) undoStack.pollFirst()
        redoStack.clear()
        GadTools.dirtyRegions.addAll(entry.regionIds)
        updateCounts()
    }

    private fun sceneRegionById(regionId: Int): SceneRegion? =
        scene.getRegions().flatten().firstOrNull { it?.regionDefinition?.regionId == regionId }

    /** Show edited region data: full rebuild where tiles or objects changed, cheap height refresh elsewhere. */
    private fun refresh(rebuild: Collection<Int>, heights: Collection<Int> = emptyList()) {
        if (!scene.isLoaded()) return
        for (id in heights) sceneRegionById(id)?.let { scene.recalculateRegion(it) }
        if (rebuild.isEmpty()) return
        scene.reloadRegions(rebuild)
        lastHovered = null
        hoverList = LinkedList()
        highlightDirty = true
    }

    /** Floor being viewed and edited (0 = ground). Floors above it are hidden. */
    private val currentPlane: Int get() = gadToolsModel.paintPlane.get().coerceIn(0, 3)

    /** The tile at (x, y) on the current floor, or the ground tile when that floor has none there. */
    private fun planeTile(x: Int, y: Int): SceneTile? = scene.getTile(currentPlane, x, y) ?: scene.getTile(0, x, y)

    private fun typeGroup(t: Int): Int = when (t) {
        in 0..3 -> 0
        in 4..8 -> 4 // all wall decorations sit in the decoration slot
        22 -> 22
        else -> 10
    }

    // ---- objects: place / remove / rotate, with drag strokes ----
    private var objStroke: ArrayList<Edit>? = null
    private var lastObjKey: Long = Long.MIN_VALUE
    /** While drag-removing: only objects with the same id as the first one removed go, and only next to the last tile. */
    private var strokeObjId = -1
    private var strokeLastX = -1
    private var strokeLastY = -1
    private val objStrokeRegions = HashSet<Int>()
    private fun tileKey(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** The stored object under the mouse at scene (x, y) with hover type, or null. */
    private fun findTarget(x: Int, y: Int, type: Int): Pair<SceneRegion, Location>? {
        val region = scene.getRegionFromSceneCoord(x, y) ?: return null
        val lx = x % REGION_SIZE
        val ly = y % REGION_SIZE
        val wanted = if (type == 30 || type == 31) 22 else typeGroup(type)
        val plane = currentPlane
        val candidates = region.locationsDefinition.locations
            .filter { it.x == lx && it.y == ly && typeGroup(it.type) == wanted }
        val target = (if (plane == 0) candidates.minByOrNull { it.z } else candidates.firstOrNull { it.z == plane }) ?: return null
        return Pair(region, target)
    }

    /** Hide a tile's models right away; the proper rebuild happens when the drag ends. */
    private fun hideTileModels(x: Int, y: Int, group: Int) {
        val tile = planeTile(x, y) ?: return
        val mb = chunkAt(x, y)?.mb ?: return
        when (group) {
            0 -> { tile.wall?.entity?.getModel()?.clearDraw(mb); tile.wall?.entity2?.getModel()?.clearDraw(mb) }
            4 -> { tile.wallDecoration?.entity?.getModel()?.clearDraw(mb); tile.wallDecoration?.entity2?.getModel()?.clearDraw(mb) }
            22 -> tile.floorDecoration?.entity?.getModel()?.clearDraw(mb)
            else -> tile.gameObjects.forEach { it.entity?.getModel()?.clearDraw(mb) }
        }
    }

    private fun removeAt(x: Int, y: Int, type: Int, rebuildNow: Boolean): Edit? {
        val (region, target) = findTarget(x, y, type) ?: run {
            if (rebuildNow) gadToolsModel.message = "Nothing to remove there. Point at the object itself."
            return null
        }
        val id = region.regionDefinition.regionId
        region.locationsDefinition.locations.remove(target)
        if (rebuildNow) refresh(listOf(id)) else {
            try { hideTileModels(x, y, typeGroup(target.type)) } catch (e: Exception) {}
            objStrokeRegions.add(id)
        }
        gadToolsModel.message = "Removed object ${target.objId} at ${region.regionDefinition.baseX + target.x}, ${region.regionDefinition.baseY + target.y}. Ctrl+Z brings it back."
        return ObjectEdit(id, target, false)
    }

    /** Remove the object under the mouse (Delete key). */
    private fun removeHovered() {
        if (hoverId == -1) return
        val e = removeAt(hoverId shr 18 and 0x1FFF, hoverId shr 5 and 0x1FFF, hoverId and 0x1F, true) ?: return
        pushUndo(e)
    }

    /** Turn the object under the mouse a quarter turn (R while not holding anything). */
    private fun rotateHovered() {
        if (hoverId == -1) return
        val (region, target) = findTarget(hoverId shr 18 and 0x1FFF, hoverId shr 5 and 0x1FFF, hoverId and 0x1F) ?: run {
            gadToolsModel.message = "Point at an object to rotate it."
            return
        }
        val turned = target.copy(orientation = (target.orientation + 1) and 3)
        val locs = region.locationsDefinition.locations
        locs.remove(target)
        locs.add(turned)
        val id = region.regionDefinition.regionId
        pushUndo(MultiEdit(listOf(ObjectEdit(id, target, false), ObjectEdit(id, turned, true))))
        refresh(listOf(id))
        gadToolsModel.message = "Rotated object ${target.objId} to ${turned.orientation * 90} degrees."
    }

    /**
     * Hold a copy of the object under the mouse, same type and rotation (C),
     * or pick it up so it can be put down somewhere else (M).
     */
    // ---- selected object (tool 1): click to select, drag to move, Ctrl+C/V, R, Delete, M ----
    private var prevLeftDown = false
    private var objDragging = false
    private var objDragTarget: Pair<Int, Int>? = null

    /** The selection if its object is still on the map (undo can take it away). */
    private fun liveSelection(): Pair<SceneRegion, models.SelectedObject>? {
        val sel = gadToolsModel.selectedObject ?: return null
        val region = sceneRegionById(sel.regionId)
        if (region == null || !region.locationsDefinition.locations.contains(sel.loc)) {
            gadToolsModel.selectedObject = null
            highlightDirty = true
            return null
        }
        return Pair(region, sel)
    }

    private fun objectName(id: Int) = objectLoader.get(id)?.name?.takeIf { it != "null" } ?: "object"

    private fun selectObject(regionId: Int, loc: Location) {
        gadToolsModel.selectedObject = models.SelectedObject(regionId, loc)
        highlightDirty = true
    }

    private fun selectAtHover(): Boolean {
        val x = hoverId shr 18 and 0x1FFF
        val y = hoverId shr 5 and 0x1FFF
        val (region, target) = findTarget(x, y, hoverId and 0x1F) ?: run {
            if (gadToolsModel.selectedObject != null) {
                gadToolsModel.selectedObject = null
                highlightDirty = true
                gadToolsModel.message = "Object deselected."
            }
            return false
        }
        selectObject(region.regionDefinition.regionId, target)
        val sel = gadToolsModel.selectedObject!!
        gadToolsModel.message = "Selected ${objectName(target.objId)} (${target.objId}) at ${sel.worldX}, ${sel.worldY}. " +
                "Drag to move, R rotate, Ctrl+C copy, M pick up, Delete remove, Esc deselect."
        return true
    }

    /** Move the selected object so its corner lands on world tile (tx, ty). */
    private fun moveSelectedTo(tx: Int, ty: Int) {
        val (oldRegion, sel) = liveSelection() ?: return
        val newId = GadTools.regionOf(tx, ty)
        val newRegion = sceneRegionById(newId) ?: run {
            gadToolsModel.message = "That spot isn't loaded."
            return
        }
        val moved = sel.loc.copy(x = tx and 63, y = ty and 63)
        oldRegion.locationsDefinition.locations.remove(sel.loc)
        newRegion.locationsDefinition.locations.add(moved)
        pushUndo(MultiEdit(listOf(ObjectEdit(sel.regionId, sel.loc, false), ObjectEdit(newId, moved, true))))
        refresh(listOf(sel.regionId, newId).distinct())
        selectObject(newId, moved)
        gadToolsModel.message = "Moved ${objectName(moved.objId)} to $tx, $ty. Ctrl+Z moves it back."
    }

    private fun rotateSelected() {
        val (region, sel) = liveSelection() ?: return
        val turned = sel.loc.copy(orientation = (sel.loc.orientation + 1) and 3)
        val locs = region.locationsDefinition.locations
        locs.remove(sel.loc)
        locs.add(turned)
        pushUndo(MultiEdit(listOf(ObjectEdit(sel.regionId, sel.loc, false), ObjectEdit(sel.regionId, turned, true))))
        refresh(listOf(sel.regionId))
        selectObject(sel.regionId, turned)
        gadToolsModel.message = "Rotated ${objectName(turned.objId)} to ${turned.orientation * 90}\u00B0."
    }

    private fun deleteSelected() {
        val (region, sel) = liveSelection() ?: return
        region.locationsDefinition.locations.remove(sel.loc)
        pushUndo(ObjectEdit(sel.regionId, sel.loc, false))
        refresh(listOf(sel.regionId))
        gadToolsModel.selectedObject = null
        highlightDirty = true
        gadToolsModel.message = "Removed ${objectName(sel.loc.objId)}. Ctrl+Z brings it back."
    }

    private fun copySelected() {
        val (_, sel) = liveSelection() ?: run {
            gadToolsModel.message = "Click an object first (tool 1), then Ctrl+C."
            return
        }
        gadToolsModel.objectClipboard = sel.loc.copy()
        utils.Favorites.used(sel.loc.objId)
        gadToolsModel.lastCopyWasObject = true
        gadToolsModel.message = "Copied ${objectName(sel.loc.objId)} (${sel.loc.objId}). Hover where you want it and press Ctrl+V."
    }

    private fun pasteObjectAtHover() {
        val clip = gadToolsModel.objectClipboard ?: return
        val w = GadTools.sceneToWorld(scene, hoverId shr 18 and 0x1FFF, hoverId shr 5 and 0x1FFF) ?: return
        val id = GadTools.regionOf(w.first, w.second)
        val region = sceneRegionById(id) ?: return
        val loc = clip.copy(x = w.first and 63, y = w.second and 63, z = currentPlane)
        utils.Favorites.used(loc.objId)
        region.locationsDefinition.locations.add(loc)
        pushUndo(ObjectEdit(id, loc, true))
        refresh(listOf(id))
        selectObject(id, loc)
        gadToolsModel.message = "Pasted ${objectName(loc.objId)} at ${w.first}, ${w.second}. It's selected: drag, R or Ctrl+V again."
    }

    /** Pick the selected object up: it leaves the map and follows the mouse until you click to place it. */
    private fun pickUpSelected() {
        val (region, sel) = liveSelection() ?: run {
            gadToolsModel.message = "Click an object first (tool 1), then M to pick it up."
            return
        }
        val def = objectLoader.get(sel.loc.objId) ?: return
        region.locationsDefinition.locations.remove(sel.loc)
        pushUndo(ObjectEdit(sel.regionId, sel.loc, false))
        refresh(listOf(sel.regionId))
        gadToolsModel.selectedObject = null
        highlightDirty = true
        val held = SceneObject(def, sel.loc.type, sel.loc.orientation and 3)
        javafx.application.Platform.runLater { objectsModel.heldObject.set(held) }
        gadToolsModel.message = "Picked up ${objectName(def.id)}. Click to put it down, R to rotate, Esc to drop."
    }

    /** Tiles the selected object covers, for the highlight. */
    private fun selectedFootprint(): List<Pair<Int, Int>> {
        val sel = gadToolsModel.selectedObject ?: return emptyList()
        val def = objectLoader.get(sel.loc.objId)
        var sx = 1; var sy = 1
        if (def != null && (sel.loc.type == 10 || sel.loc.type == 11)) {
            sx = def.sizeX; sy = def.sizeY
            if (sel.loc.orientation and 1 == 1) { val t = sx; sx = sy; sy = t }
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (dx in 0 until sx.coerceIn(1, 16)) for (dy in 0 until sy.coerceIn(1, 16)) out.add(Pair(sel.worldX + dx, sel.worldY + dy))
        return out
    }

    /** Place the held object at scene (x, y). */
    private fun placeAt(x: Int, y: Int): Edit? {
        val held = injectedObject ?: return null
        val tile = planeTile(x, y) ?: return null
        val region = scene.getRegionFromSceneCoord(x, y) ?: return null
        val objectDefinition: ObjectDefinition = held.objectDefinition
        val modelDefinition: ModelDefinition = objectToModelConverter.toModel(objectDefinition, held.type, held.orientation) ?: run {
            gadToolsModel.message = "Object ${objectDefinition.id} has no model for that type."
            return null
        }
        val plane = currentPlane
        val onFloor = tile.z == plane
        val baseHeight = tile.tilePaint?.nwHeight ?: tile.cacheTile?.height ?: 0
        val groundHeight = if (onFloor) baseHeight else baseHeight - 240 * plane
        val loc = Location(objectDefinition.id, held.type, held.orientation and 3, x % REGION_SIZE, y % REGION_SIZE, plane)
        region.locationsDefinition.locations.add(loc)
        if (plane > 0) objStrokeRegions.add(region.regionDefinition.regionId) // exact rebuild when the drag ends

        val model = Model(modelDefinition, objectDefinition.ambient, objectDefinition.contrast)
        model.xOff = objectDefinition.sizeX * REGION_SIZE
        model.yOff = objectDefinition.sizeY * REGION_SIZE
        // shown right away in its region's output (room is kept for this); the region is rebuilt when the drag ends
        val chunk = chunkAt(x, y)
        if (chunk != null && chunk.mb.targetBufferOffset + modelDefinition.faceCount * 3 <= chunk.capacity) {
            sceneUploader.uploadModel(
                StaticObject(objectDefinition, model, groundHeight, held.type, held.orientation),
                chunk.mb.vertexBuffer,
                chunk.mb.uvBuffer
            )
            model.drawPersistent(chunk.mb, x, y, groundHeight, held.type)
        }
        gadToolsModel.message = "Placed ${objectDefinition.name} (${objectDefinition.id})."
        return ObjectEdit(region.regionDefinition.regionId, loc, true)
    }

    private fun finishObjectStroke() {
        val st = objStroke ?: return
        if (objStrokeRegions.isNotEmpty()) refresh(objStrokeRegions.toList())
        objStrokeRegions.clear()
        if (st.size == 1) pushUndo(st[0]) else if (st.size > 1) {
            pushUndo(MultiEdit(ArrayList(st)))
            val placed = (st[0] as? ObjectEdit)?.added == true
            gadToolsModel.message = "${st.size} objects ${if (placed) "placed" else "removed"}. Ctrl+Z undoes them together."
        }
        objStroke = null
        lastObjKey = Long.MIN_VALUE
        strokeObjId = -1
        strokeLastX = -1
        strokeLastY = -1
    }

    private var flattenTarget = 0

    /** The plane-0 map tile under a scene tile, with its region. */
    private fun groundTile(t: SceneTile?, plane: Int = 0): Triple<RegionDefinition, RegionDefinition.Tile, Pair<Int, Int>>? {
        t ?: return null
        val def = scene.getRegionFromSceneCoord(t.x, t.y)?.regionDefinition ?: return null
        val lx = t.x % REGION_SIZE
        val ly = t.y % REGION_SIZE
        val ct = def.tiles[plane][lx][ly] ?: return null
        return Triple(def, ct, Pair(lx, ly))
    }

    private fun heightOf(t: SceneTile?): Int? = groundTile(t)?.second?.height

    private fun setHeight(t: SceneTile, h: Int, changed: HashSet<SceneRegion>) {
        val (def, ct, local) = groundTile(t) ?: return
        recorder?.touch(def.regionId, 0, local.first, local.second, ct)
        ct.setGroundHeight(h)
        if (def.restackHeights(local.first, local.second)) {
            // a floor above moved with the ground: rebuild that region when the stroke ends
            strokeRebuild.add(def.regionId)
            strokeHeightOnly = false
        }
        scene.getRegionFromSceneCoord(t.x, t.y)?.let { changed.add(it) }
    }

    /** Nearest underlay (stored value, id + 1) around a tile on the same floor, or null. */
    private fun neighbourUnderlay(def: cache.definitions.RegionDefinition, plane: Int, lx: Int, ly: Int): Int? {
        val wx = def.baseX + lx; val wy = def.baseY + ly
        for (r in 1..3) for (dx in -r..r) for (dy in -r..r) {
            if (maxOf(Math.abs(dx), Math.abs(dy)) != r) continue
            val x = wx + dx; val y = wy + dy
            val d = editorRegions.region(models.regionIdOf(x, y)) ?: continue
            val u = d.tiles[plane][x and 63][y and 63]?.underlayId ?: continue
            if (u > 0) return u
        }
        return null
    }

    /** Underlay / overlay / flag paint on one tile. Shift erases. */
    private fun paintTile(t: SceneTile, tool: TerrainTool, erase: Boolean) {
        val plane = gadToolsModel.paintPlane.get().coerceIn(0, 3)
        val (def, ct, local) = groundTile(t, plane) ?: return
        val before = ct.copy()
        recorder?.touch(def.regionId, plane, local.first, local.second, ct)
        when (tool) {
            TerrainTool.UNDERLAY -> {
                val id = if (erase) -1 else gadToolsModel.underlayId.get()
                ct.underlayId = (id + 1)
            }
            TerrainTool.OVERLAY -> {
                val id = if (erase) -1 else gadToolsModel.overlayId.get()
                if (id < 0) {
                    ct.attrOpcode = 0; ct.overlayId = 0; ct.overlayPath = 0; ct.overlayRotation = 0
                } else {
                    val shape = gadToolsModel.overlayShape.get().coerceIn(0, OVERLAY_SHAPES - 1)
                    val rot = gadToolsModel.overlayRotation.get() and 3
                    ct.attrOpcode = 2 + shape * 4 + rot
                    ct.overlayId = (id + 1)
                    ct.overlayPath = shape.toByte()
                    ct.overlayRotation = rot.toByte()
                    // a shaped overlay shows the underlay in its other half: with no underlay that half is dead, so borrow the one next to it
                    if (shape != 0 && ct.underlayId == 0) neighbourUnderlay(def, plane, local.first, local.second)?.let { ct.underlayId = it }
                }
            }
            TerrainTool.FLAGS -> {
                val m = gadToolsModel.flagMask.get()
                models.applyFlags(ct, plane, plane, m, !erase)
                // the bridge flag goes on floor 1 whatever floor is being painted (where the game reads it)
                if (m and 2 != 0 && plane != 1) {
                    val up = def.tiles[1][local.first][local.second]
                    if (up != null) {
                        val was = up.settings
                        recorder?.touch(def.regionId, 1, local.first, local.second, up)
                        models.applyFlags(up, 1, plane, m, !erase)
                        if (up.settings != was) { def.syncTile(1, local.first, local.second); strokeRebuild.add(def.regionId); highlightDirty = true }
                    }
                }
            }
            else -> return
        }
        if (ct == before) return
        def.syncTile(plane, local.first, local.second)
        strokeRebuild.add(def.regionId)
        if (tool == TerrainTool.UNDERLAY) {
            // underlay colours blend 5 tiles across region borders, so refresh the neighbour too
            val (lx, ly) = local
            val dx = if (lx < 5) -1 else if (lx >= REGION_SIZE - 5) 1 else 0
            val dy = if (ly < 5) -1 else if (ly >= REGION_SIZE - 5) 1 else 0
            if (dx != 0) strokeRebuild.add(def.regionId + dx * 256)
            if (dy != 0) strokeRebuild.add(def.regionId + dy)
            if (dx != 0 && dy != 0) strokeRebuild.add(def.regionId + dx * 256 + dy)
        }
        if (tool == TerrainTool.FLAGS) highlightDirty = true
    }

    /** Smooth value noise in -1..1, stable per world tile for one stroke. */
    private fun noise(x: Double, y: Double): Double {
        fun hash(ix: Int, iy: Int): Double {
            var h = ix * 374761393 + iy * 668265263 + noiseSeed * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFF) / 32767.5 - 1.0
        }
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * sx
        val b = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * sx
        return a + (b - a) * sy
    }

    private fun finishStroke() {
        val rec = recorder ?: return
        recorder = null
        if (strokeRebuild.isNotEmpty()) {
            refresh(strokeRebuild.toList())
            strokeRebuild.clear()
        }
        rec.finish(strokeHeightOnly)?.let { pushUndo(it) }
    }

    private fun applyUndoRedo() {
        val undo = gadToolsModel.undoRequested.getAndSet(false)
        val redo = gadToolsModel.redoRequested.getAndSet(false)
        if (!undo && !redo) return
        try { undoRedo(undo) } finally { gadToolsModel.undoRedoDone.incrementAndGet() }
    }

    private fun undoRedo(undo: Boolean) {
        finishStroke()
        gadToolsModel.unsaved.set(true)
        val from = if (undo) undoStack else redoStack
        val to = if (undo) redoStack else undoStack
        val entry = from.pollLast()
        if (entry == null) {
            gadToolsModel.message = if (undo) "Nothing to undo." else "Nothing to redo."
            return
        }
        val (rebuild, heights) = applyEdit(editorRegions, entry, undo)
        to.addLast(entry)
        GadTools.dirtyRegions.addAll(entry.regionIds)
        refresh(rebuild, heights)
        highlightDirty = true
        updateCounts()
        gadToolsModel.message = if (undo) "Undone." else "Redone."
    }

    /** Mouse let go after dragging a selected area: move (or copy) everything in it to where it was dropped. */
    private fun dropDraggedArea() {
        val from = areaDragFrom ?: return
        val orig = areaDragOrig ?: return
        areaDragFrom = null
        areaDragOrig = null
        val target = gadToolsModel.selection ?: return
        val dx = target.x1 - orig.x1
        val dy = target.y1 - orig.y1
        if (dx == 0 && dy == 0) { gadToolsModel.message = "Area not moved."; return }
        val copyOnly = areaDragCopy
        val floor = gadToolsModel.floorToCopy()           // -1 = all floors, else just that floor
        val planes = if (floor >= 0) floor..floor else 0..3
        val parts = gadToolsModel.pasteParts
        gadToolsModel.actions.add {
            val prefab = areaOps.copy(orig, floor)
            val edits = ArrayList<Edit>()
            if (!copyOnly) {
                // leave the ground's own colour (underlay) and heights behind so no dead tiles are left
                areaOps.clear(orig, TileParts(heights = false, underlays = false, overlays = parts.overlays,
                    flags = parts.flags, objects = parts.objects), planes)?.let { edits.add(it) }
            }
            areaOps.paste(prefab, target.x1, target.y1, parts, if (floor >= 0) floor else -1)?.let { edits.add(it) }
            // remembered so the Teleports tool can move the server's coordinates for this area along with it
            if (!copyOnly && edits.isNotEmpty()) try {
                val f = java.io.File(System.getProperty("user.home"), ".uzis-map-editor/area-moves.txt")
                f.parentFile.mkdirs()
                f.appendText("${orig.x1},${orig.y1},${orig.x2},${orig.y2},$dx,$dy,${System.currentTimeMillis()}\n")
            } catch (e: Exception) {}
            gadToolsModel.message = (if (copyOnly) "Copied" else "Moved") + " ${orig.width} x ${orig.height} by $dx, $dy" +
                    (if (floor >= 0) " (floor $floor)" else "") + ". Ctrl+Z undoes it."
            when (edits.size) { 0 -> null; 1 -> edits[0]; else -> MultiEdit(edits) }
        }
    }

    /** Area tools, copy / paste and find & replace queued from the tools panel or hotkeys. */
    private fun runActions() {
        while (true) {
            val action = gadToolsModel.actions.poll() ?: return
            finishStroke()
            val edit = try {
                action()
            } catch (e: Exception) {
                e.printStackTrace()
                gadToolsModel.message = "That failed: ${e.message}"
                null
            } ?: continue
            pushUndo(edit)
            val (rebuild, heights) = refreshSets(edit)
            refresh(rebuild, heights)
            highlightDirty = true
        }
    }

    // ---- selection + flag view highlights ----
    @Volatile private var highlightDirty = true
    private var shownSelection: TileRect? = null
    private var shownFlags = false
    private var shownMask = 0
    private var shownPlane = 0
    private val selectedTiles = ArrayList<SceneTile>()
    private val markedTiles = ArrayList<SceneTile>()

    private var shownObjSel: models.SelectedObject? = null

    private fun updateHighlights() {
        if (!scene.isLoaded()) return
        val sel = gadToolsModel.selection
        val flags = gadToolsModel.showFlags.get()
        val mask = gadToolsModel.flagMask.get()
        val plane = gadToolsModel.paintPlane.get().coerceIn(0, 3)
        val objSel = gadToolsModel.selectedObject
        if (!highlightDirty && sel == shownSelection && flags == shownFlags && mask == shownMask && plane == shownPlane && objSel == shownObjSel) return
        highlightDirty = false
        shownSelection = sel; shownFlags = flags; shownMask = mask; shownPlane = plane; shownObjSel = objSel

        selectedTiles.forEach { it.tilePaint?.isSelected = false; it.tileModel?.isSelected = false }
        selectedTiles.clear()
        if (sel != null) {
            for (x in sel.x1..sel.x2) for (y in sel.y1..sel.y2) {
                val s = GadTools.worldToScene(scene, x, y) ?: continue
                val t = planeTile(s.first, s.second) ?: continue
                t.tilePaint?.isSelected = true
                t.tileModel?.isSelected = true
                selectedTiles.add(t)
            }
        }
        for ((x, y) in selectedFootprint()) {
            val s = GadTools.worldToScene(scene, x, y) ?: continue
            val t = scene.getTile(objSel?.loc?.z ?: 0, s.first, s.second) ?: scene.getTile(0, s.first, s.second) ?: continue
            t.tilePaint?.isSelected = true
            t.tileModel?.isSelected = true
            selectedTiles.add(t)
        }

        markedTiles.forEach { it.tilePaint?.markColor = -1; it.tileModel?.markColor = -1 }
        markedTiles.clear()
        if (flags && mask != 0) {
            for (col in scene.getRegions()) for (region in col) {
                region ?: continue
                val def = region.regionDefinition
                for (x in 0 until REGION_SIZE) for (y in 0 until REGION_SIZE) {
                    val ct = def.tiles[plane][x][y] ?: continue
                    val bits = (ct.settings.toInt() and mask and 2.inv()) or (if (mask and 2 != 0 && def.isBridge(x, y)) 2 else 0)
                    if (bits == 0) continue
                    val t = region.tiles[0][x][y] ?: continue
                    t.tilePaint?.markColor = Constants.FLAG_HSL
                    t.tileModel?.markColor = Constants.FLAG_HSL
                    markedTiles.add(t)
                }
            }
        }
    }

    private fun handleClick(dt: Double) {
        runActions()
        applyUndoRedo()

        // object select / drag-move (tool 1, nothing held)
        val down = inputHandler.isLeftMouseDown
        // a press and release between two frames still selects (the latch keeps the press)
        val latched = inputHandler.takeLeftPress()
        val pressed = (down && !prevLeftDown) || (latched && !prevLeftDown)
        val released = !down && (prevLeftDown || latched)
        prevLeftDown = down
        if (gadToolsModel.tool.get() == TerrainTool.NPC) {
            handleNpcTool(pressed, released, down)
            inputHandler.mouseClicked = false
            updateHighlights()
            return
        }
        val selecting = gadToolsModel.tool.get() == TerrainTool.INSPECT && injectedObject == null
        if (pressed && selecting && hoverId != -1) {
            objDragging = selectAtHover()
            objDragTarget = null
        }
        if (down && objDragging && hoverId != -1) {
            val w = GadTools.sceneToWorld(scene, hoverId shr 18 and 0x1FFF, hoverId shr 5 and 0x1FFF)
            val sel = gadToolsModel.selectedObject
            if (w != null && sel != null && (w.first != sel.worldX || w.second != sel.worldY) && w != objDragTarget) {
                objDragTarget = w
                gadToolsModel.message = "Release to move ${objectName(sel.loc.objId)} to ${w.first}, ${w.second}."
            }
        }
        if (released && objDragging) {
            objDragging = false
            objDragTarget?.let { moveSelectedTo(it.first, it.second) }
            objDragTarget = null
        }

        // finish a stroke when the mouse is released
        if (!inputHandler.isLeftMouseDown) {
            finishStroke()
            finishObjectStroke()
            selectAnchor = null
            dropDraggedArea()
        }
        inputHandler.mouseClicked = false
        // paint strokes rebuild the touched regions a few times a second so you can see the paint
        if (recorder != null && strokeRebuild.isNotEmpty() && gadToolsModel.tool.get().paints &&
            System.currentTimeMillis() - lastStrokeRebuild > 300) {
            refresh(strokeRebuild.toList())
            strokeRebuild.clear()
            lastStrokeRebuild = System.currentTimeMillis()
        }
        updateHighlights()

        // actions on the selected object work even with the mouse off the map (panel buttons)
        if (gadToolsModel.copyObjectRequested.getAndSet(false)) { copySelected(); return }
        if (gadToolsModel.moveObjectRequested.getAndSet(false)) { pickUpSelected(); return }
        if (gadToolsModel.selectedObject != null) {
            if (gadToolsModel.rotateRequested.getAndSet(false)) { rotateSelected(); return }
            if (gadToolsModel.deleteRequested.getAndSet(false)) { deleteSelected(); return }
        }

        if (hoverId == -1) {
            gadToolsModel.deleteRequested.set(false)
            gadToolsModel.pasteObjectRequested.set(false)
            return
        }
        if (gadToolsModel.pasteObjectRequested.getAndSet(false)) {
            pasteObjectAtHover()
            return
        }
        if (gadToolsModel.deleteRequested.getAndSet(false)) {
            removeHovered()
            return
        }
        if (gadToolsModel.rotateRequested.getAndSet(false)) {
            rotateHovered()
            return
        }
        if (lastClick < 16) { // 60 fps
            lastClick += dt
            return
        }
        lastClick = 0.0

        val tool = gadToolsModel.tool.get()
        // terrain tools only run when not holding an object to place
        if (inputHandler.isLeftMouseDown && injectedObject == null && tool == TerrainTool.SELECT) {
            val cx = hoverId shr 18 and 0x1FFF
            val cy = hoverId shr 5 and 0x1FFF
            val w = GadTools.sceneToWorld(scene, cx, cy)
            if (w != null) {
                val cur = gadToolsModel.selection
                // pressing inside the selected box grabs it: drag to move it (hold Shift to drag a copy)
                if (selectAnchor == null && areaDragFrom == null && cur != null && cur.contains(w.first, w.second)) {
                    areaDragFrom = w
                    areaDragOrig = cur
                    areaDragCopy = inputHandler.isKeyDown(KeyCode.SHIFT)
                }
                val from = areaDragFrom
                val orig = areaDragOrig
                if (from != null && orig != null) {
                    val dx = w.first - from.first
                    val dy = w.second - from.second
                    val moved = TileRect(orig.x1 + dx, orig.y1 + dy, orig.x2 + dx, orig.y2 + dy)
                    if (moved != gadToolsModel.selection) {
                        gadToolsModel.selection = moved
                        gadToolsModel.message = (if (areaDragCopy) "Copying" else "Moving") + " the area by $dx, $dy. Let go to drop it."
                    }
                } else {
                    val a = selectAnchor ?: w.also { selectAnchor = it }
                    val rect = TileRect.of(a.first, a.second, w.first, w.second)
                    if (rect != gadToolsModel.selection) {
                        gadToolsModel.selection = rect
                        gadToolsModel.message = "Selected ${rect.width} x ${rect.height} tiles from ${rect.x1}, ${rect.y1}. Drag inside the box to move it."
                    }
                }
            }
        } else if (inputHandler.isLeftMouseDown && injectedObject == null && tool != TerrainTool.INSPECT && tool != TerrainTool.REMOVE) {
            val cx = hoverId shr 18 and 0x1FFF
            val cy = hoverId shr 5 and 0x1FFF
            val center = scene.getTile(0, cx, cy) ?: return
            val tiles = ArrayList<SceneTile>()
            tiles.add(center)
            hoverList.forEach { if (it is SceneTile && it !== center) tiles.add(it) }

            if (recorder == null) {
                recorder = TileRecorder()
                strokeHeightOnly = tool.heights
                flattenTarget = heightOf(center) ?: 0
                noiseSeed = Random().nextInt()
            }
            val strength = gadToolsModel.strength.get().coerceIn(1, 200)
            val brush = gadToolsModel.brushSize.get().coerceAtLeast(0)
            val invert = inputHandler.isKeyDown(KeyCode.SHIFT)
            val changed = HashSet<SceneRegion>()

            fun falloff(t: SceneTile): Double {
                val dist = sqrt(((t.x - cx) * (t.x - cx) + (t.y - cy) * (t.y - cy)).toDouble())
                return if (brush == 0) 1.0 else (1.0 - dist / (brush + 1)).coerceIn(0.2, 1.0)
            }

            when (tool) {
                TerrainTool.RAISE, TerrainTool.LOWER -> {
                    var dir = if (tool == TerrainTool.RAISE) -1 else 1 // negative height = up
                    if (invert) dir = -dir
                    for (t in tiles) {
                        val h = heightOf(t) ?: continue
                        setHeight(t, h + (dir * strength * falloff(t)).toInt().let { if (it == 0) dir else it }, changed)
                    }
                }
                TerrainTool.FLATTEN -> {
                    for (t in tiles) {
                        val h = heightOf(t) ?: continue
                        if (h == flattenTarget) continue
                        val step = (flattenTarget - h).coerceIn(-strength * 2, strength * 2)
                        setHeight(t, h + step, changed)
                    }
                }
                TerrainTool.SET_HEIGHT -> {
                    val target = -gadToolsModel.exactHeight.get().coerceIn(0, 255) * 8
                    for (t in tiles) {
                        val h = heightOf(t) ?: continue
                        if (h != target) setHeight(t, target, changed)
                    }
                }
                TerrainTool.NOISE -> {
                    for (t in tiles) {
                        val h = heightOf(t) ?: continue
                        val w = GadTools.sceneToWorld(scene, t.x, t.y) ?: continue
                        val n = noise(w.first / 5.0, w.second / 5.0) * 0.7 + noise(w.first / 1.7, w.second / 1.7) * 0.3
                        val d = (n * strength * falloff(t)).toInt()
                        if (d != 0) setHeight(t, h + d, changed)
                    }
                }
                TerrainTool.SMOOTH -> {
                    val targets = HashMap<SceneTile, Int>()
                    for (t in tiles) {
                        val h = heightOf(t) ?: continue
                        var sum = 0
                        var n = 0
                        for (ox in -1..1) for (oy in -1..1) {
                            val nh = heightOf(scene.getTile(0, t.x + ox, t.y + oy)) ?: continue
                            sum += nh; n++
                        }
                        if (n == 0) continue
                        val avg = sum / n
                        val blend = (strength / 40.0).coerceIn(0.05, 1.0)
                        targets[t] = h + ((avg - h) * blend).toInt()
                    }
                    targets.forEach { (t, h) -> setHeight(t, h, changed) }
                }
                TerrainTool.UNDERLAY, TerrainTool.OVERLAY, TerrainTool.FLAGS -> {
                    for (t in tiles) paintTile(t, tool, invert)
                }
                else -> {}
            }
            changed.forEach { scene.recalculateRegion(it) }
        }

        // place (holding an object) or remove (Remove tool) on every tile the mouse drags across
        if (inputHandler.isLeftMouseDown && (injectedObject != null || tool == TerrainTool.REMOVE)) {
            val x = hoverId shr 18 and 0x1FFF
            val y = hoverId shr 5 and 0x1FFF
            val k = tileKey(x, y)
            if (objStroke == null) objStroke = ArrayList()
            if (hoverId != -1 && k != lastObjKey) {
                lastObjKey = k
                if (injectedObject != null) {
                    placeAt(x, y)?.let { objStroke!!.add(it) }
                } else {
                    // Removing: the mouse can jump to a far tile (fast moves, the camera turning, the pointer
                    // passing over other buildings). Only remove next to the last removed tile, and only the
                    // same kind of object as the first one, so nothing elsewhere disappears by accident.
                    val near = strokeLastX < 0 || (Math.abs(x - strokeLastX) <= 1 && Math.abs(y - strokeLastY) <= 1)
                    val target = if (near) findTarget(x, y, hoverId and 0x1F) else null
                    if (target != null && (strokeObjId == -1 || target.second.objId == strokeObjId)) {
                        removeAt(x, y, hoverId and 0x1F, false)?.let {
                            objStroke!!.add(it)
                            strokeObjId = target.second.objId
                            strokeLastX = x; strokeLastY = y
                        }
                    }
                }
            }
        }
    }

    override fun init() {
        try {
            gl.glEnable(GLC.GL_DEPTH_TEST)
            gl.glDepthFunc(GLC.GL_LEQUAL)
            gl.glDepthRangef(0f, 1f)

            initProgram()
            initUniformBuffer()
            initBuffers()
            initPickerBuffer()
            initVao()
            dynamicChunk = Chunk(-1, -1).also { it.create() }
            modelBuffers = dynamicChunk!!.mb

            // disable vsync
//            gl.swapInterval = 0
        } catch (e: ShaderException) {
            e.printStackTrace()
        }
    }

    override fun reshape(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
        shutdownPickerFbo()
        initPickerBuffer()
        camera.centerX = canvasWidth / 2
        camera.centerY = canvasHeight / 2
        gl.glViewport(0, 0, width, height)
    }

    @Volatile private var isSceneUploadRequired = true
    /** Which floors are shown changed: every region is uploaded and queued again (from the tiles as they are now). */
    @Volatile private var requeueAll = true
    private val clientStart = System.currentTimeMillis()
    private var lastUpdate: Long = System.nanoTime()
    var deltaTime: Double = 0.0
    override fun display(): Int {
        val thisFrame = ++gadToolsModel.framesStarted
        debugModel.fps.set("%.0f".format(view.fps))
        debugModel.deltaTime.set("%.2f".format(deltaTime))
        //
        deltaTime = (System.nanoTime().toDouble() - lastUpdate.toDouble()) / 1000 / 1000
        lastUpdate = System.nanoTime()

        if (isSceneUploadRequired) {
            uploadScene()
        }

        redrawTiles()
        try {
            // while another map loads in the background, the one on screen isn't edited
            if (!GadTools.mapLoading) handleClick(deltaTime)
        } catch (e: Exception) {
            println("Click handling error (editor keeps running): $e")
            e.printStackTrace()
        }
        handleHover()
        try { drawNpcs() } catch (e: Exception) { println("NPC drawing: $e") }

        if (canvasWidth > 0 && canvasHeight > 0 && (canvasWidth != lastViewportWidth || canvasHeight != lastViewportHeight)) {
            createProjectionMatrix(
                0f,
                canvasWidth.toFloat(),
                canvasHeight.toFloat(),
                0f,
                1f,
                (Constants.MAX_DISTANCE * Constants.LOCAL_TILE_SIZE).toFloat()
            )
            lastViewportWidth = canvasWidth
            lastViewportHeight = canvasHeight
        }

        // base FBO to enable picking
        gl.glBindFramebuffer(GLC.GL_DRAW_FRAMEBUFFER, fboMainRenderer)

        // Setup anti-aliasing
        val antiAliasingMode: AntiAliasingMode = AntiAliasingMode.MSAA_16
        val aaEnabled = antiAliasingMode !== AntiAliasingMode.DISABLED
        if (aaEnabled) {
            gl.glEnable(GLC.GL_MULTISAMPLE)
            val stretchedCanvasWidth = canvasWidth
            val stretchedCanvasHeight = canvasHeight

            // Re-create fbo
            if (lastStretchedCanvasWidth != stretchedCanvasWidth || lastStretchedCanvasHeight != stretchedCanvasHeight || lastAntiAliasingMode !== antiAliasingMode
            ) {
                val maxSamples: Int = glGetInteger(gl, GLC.GL_MAX_SAMPLES)
                val samples = min(antiAliasingMode.ordinal, maxSamples)
                shutdownAAFbo()
                initAAFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples)
                lastStretchedCanvasWidth = stretchedCanvasWidth
                lastStretchedCanvasHeight = stretchedCanvasHeight
            }
            gl.glBindFramebuffer(GLC.GL_DRAW_FRAMEBUFFER, fboSceneHandle)
        }
        lastAntiAliasingMode = antiAliasingMode
        val i =
            drawBuffers.clear() as IntBuffer
        gl.glDrawBuffers(2, i)

        // Clear scene
        val sky = 9493480
        gl.glClearColor((sky shr 16 and 0xFF) / 255f, (sky shr 8 and 0xFF) / 255f, (sky and 0xFF) / 255f, 1f)
        gl.glClear(GLC.GL_COLOR_BUFFER_BIT or GLC.GL_DEPTH_BUFFER_BIT)

        val clientCycle = ((System.currentTimeMillis() - clientStart) / 20).toInt() // 50 fps

        // UBO
        gl.glBindBuffer(GLC.GL_UNIFORM_BUFFER, uniformBufferId)
        uniformBuffer.clear()
        uniformBuffer
            .put(camera.yaw)
            .put(camera.pitch)
            .put(camera.centerX)
            .put(camera.centerY)
            .put(camera.scale)
            .put(camera.cameraX) //x
            .put(camera.cameraZ) // z
            .put(camera.cameraY) // y
            .put(clientCycle) // currFrame
        uniformBuffer.flip()
        gl.glBufferSubData(
            GLC.GL_UNIFORM_BUFFER,
            0,
            uniformBuffer.limit() * Integer.BYTES.toLong(),
            uniformBuffer
        )
        gl.glBindBuffer(GLC.GL_UNIFORM_BUFFER, 0)

        val dyn = dynamicChunk
        // Draw 3d scene
        if (dyn != null) {
            gl.glUniformBlockBinding(glSmallComputeProgram, uniBlockSmall, 0)
            gl.glUniformBlockBinding(glComputeProgram, uniBlockLarge, 0)
            gl.glBindBufferBase(GLC.GL_UNIFORM_BUFFER, 0, uniformBufferId)

            // each region computes what it has queued into its own output; then NPCs and the held object
            for (row in chunks) for (c in row) computePass(c, false)
            computePass(dyn, true)
            gl.glMemoryBarrier(GLC.GL_SHADER_STORAGE_BARRIER_BIT or GLC.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)

            if (textureArrayId == -1) {
                // lazy init textures as they may not be loaded at plugin start.
                // this will return -1 and retry if not all textures are loaded yet, too.
                textureArrayId = textureManager.initTextureArray(gl)
            }
//            val textures: Array<TextureDefinition> = textureProvider.getTextureDefinitions()
            gl.glUseProgram(glProgram)
            // Brightness happens to also be stored in the texture provider, so we use that
            gl.glUniform1f(uniBrightness, 0.7f) //(float) textureProvider.getBrightness());
            gl.glUniform1i(uniDrawDistance, Constants.MAX_DISTANCE * Constants.LOCAL_TILE_SIZE)
            gl.glUniform1f(uniSmoothBanding, 1f)

            // This is just for animating!
//            for (int id = 0; id < textures.length; ++id) {
//                TextureDefinition texture = textures[id];
//                if (texture == null) {
//                    continue;
//                }
//
//                textureProvider.load(id); // trips the texture load flag which lets textures animate
//
//                textureOffsets[id * 2] = texture.field1782;
//                textureOffsets[id * 2 + 1] = texture.field1783;
//            }
            gl.glUniform1i(uniHoverId, hoverId)
            gl.glUniform2i(uniMouseCoordsId, inputHandler.mouseX, canvasHeight - inputHandler.mouseY)

            // Bind uniforms
            gl.glUniformBlockBinding(glProgram, uniBlockMain, 0)
            gl.glUniform1i(uniTextures, 1) // texture sampler array is bound to texture1
            gl.glUniform2fv(uniTextureOffsets, 128, textureOffsets, 0)

            // We just allow the GL to do face culling. Note this requires the priority renderer
            // to have logic to disregard culled faces in the priority depth testing.
            gl.glEnable(GLC.GL_CULL_FACE)

            // Draw output of compute shaders
            // regions far to near (see-through faces blend over what's behind them), skipping ones behind the camera
            val visible = ArrayList<Pair<Chunk, Double>>()
            for (row in chunks) for (c in row) {
                if (c.mb.targetBufferOffset <= 0) continue
                val d = chunkDepth(c) ?: continue
                visible.add(c to d)
            }
            visible.sortByDescending { it.second }
            for ((c, _) in visible) {
                gl.glBindVertexArray(c.vao)
                gl.glDrawArrays(GLC.GL_TRIANGLES, 0, c.mb.targetBufferOffset)
            }
            if (dyn.count > 0) {
                gl.glBindVertexArray(dyn.vao)
                gl.glDrawArrays(GLC.GL_TRIANGLES, 0, dyn.count)
            }
            gl.glDisable(GLC.GL_CULL_FACE)
            gl.glUseProgram(0)
        }

        if (aaEnabled) {
            gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, fboSceneHandle)
            gl.glBindFramebuffer(GLC.GL_DRAW_FRAMEBUFFER, fboMainRenderer)
            gl.glBlitFramebuffer(
                0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
                0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
                GLC.GL_COLOR_BUFFER_BIT or GLC.GL_DEPTH_BUFFER_BIT, GLC.GL_NEAREST
            )
            gl.glReadBuffer(GLC.GL_COLOR_ATTACHMENT1)
            gl.glDrawBuffer(GLC.GL_COLOR_ATTACHMENT1)
            gl.glBlitFramebuffer(
                0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
                0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
                GLC.GL_COLOR_BUFFER_BIT or GLC.GL_DEPTH_BUFFER_BIT, GLC.GL_NEAREST
            )
            gl.glDisable(GLC.GL_BLEND)

            // Reset
            gl.glReadBuffer(GLC.GL_COLOR_ATTACHMENT0)
            gl.glDrawBuffer(GLC.GL_COLOR_ATTACHMENT0)
        }

        // the finished picture stays in fboMainRenderer (GlView shows it); labels are drawn on top of its colour only
        gl.glBindFramebuffer(GLC.GL_DRAW_FRAMEBUFFER, fboMainRenderer)
        gl.glDrawBuffer(GLC.GL_COLOR_ATTACHMENT0)

        // tile numbers on top of the finished picture (View > Tile numbers on the map, Ctrl+T)
        val labelMode = gadToolsModel.tileLabelMode.get()
        if (labelMode != 0) {
            val p = gadToolsModel.paintPlane.get().coerceIn(0, 3)
            try { tileLabels.draw(gl, scene, camera, canvasWidth, canvasHeight, if (labelMode == 2) 0..3 else p..p, npcLabels) }
            catch (e: Exception) { println("Tile numbers: $e") }
        } else if (npcLabels.isNotEmpty()) {
            try { tileLabels.draw(gl, scene, camera, canvasWidth, canvasHeight, null, npcLabels) } catch (e: Exception) { println("NPC labels: $e") }
        }

        // only a frame begun after the request (and any camera move with it) answers it
        gadToolsModel.screenshotRequest?.takeIf { thisFrame > gadToolsModel.screenshotAfterFrame }?.let { req ->
            gadToolsModel.screenshotRequest = null
            try { req.complete(grabFrame()) } catch (e: Exception) { req.completeExceptionally(e) }
        }

        return fboMainRenderer
    }

    /** The finished frame (for the AI bridge's screenshots). */
    private fun grabFrame(): java.awt.image.BufferedImage {
        val w = canvasWidth; val h = canvasHeight
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, 0)
        gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, fboMainRenderer)
        gl.glReadBuffer(GLC.GL_COLOR_ATTACHMENT0)
        gl.glReadPixels(0, 0, w, h, GLC.GL_RGBA, GLC.GL_UNSIGNED_BYTE, buf)
        gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, 0)
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((h - 1 - y) * w + x) * 4
            img.setRGB(x, y, ((buf.get(i).toInt() and 255) shl 16) or ((buf.get(i + 1).toInt() and 255) shl 8) or (buf.get(i + 2).toInt() and 255))
        }
        return img
    }

    private val tileLabels by lazy { TileLabels(overlayLoader) }

    /** Overlays drawn see-through in game (pink 0xFF00FF, no texture): painting them leaves a black hole. */
    private fun seeThroughOverlay(id: Int): Boolean {
        val d = overlayLoader.get(id) ?: return false
        return d.texture < 0 && d.rgbColor == 0xFF00FF
    }
    private var warnedSeeThrough = 0L

    // ---- NPCs: drawn every frame from the spawn list, edited with the NPC tool ----
    // its own cache reader: models are built on a worker thread, away from the loaders the 3D view uses
    private val npcModels by lazy { NpcModels(cache.loaders.ModelLoader(com.displee.cache.CacheLibrary(cache.CachePaths.cacheDir)), true) }
    private val npcEntityDef = ObjectDefinition()
    /** (world x*128, height, world y*128, text, selected) for the id labels above NPCs. */
    private val npcLabels = ArrayList<TileLabels.Extra>()
    private var npcDrag: utils.NpcSpawn? = null
    private var npcDragBefore: utils.NpcSpawn.State? = null
    private var npcModelsBuiltFor = -1

    private fun npcSize(id: Int) = utils.NpcDefs.cachedOrNull()?.get(id)?.size ?: 1

    private fun npcHeight(s: utils.NpcSpawn, x: Int = s.x, y: Int = s.y): Int {
        val d = editorRegions.region(models.regionIdOf(x, y)) ?: return 0
        return d.tiles[s.z.coerceIn(0, 3)][x and 63][y and 63]?.height ?: 0
    }

    private fun drawNpcs() {
        npcLabels.clear()
        if (utils.NpcSpawns.all.isEmpty() || !scene.isLoaded()) return
        val defs = utils.NpcDefs.cachedOrNull() ?: run { utils.NpcDefs.loadInBackground(); return }
        if (utils.NpcDefs.generation != npcModelsBuiltFor) { npcModelsBuiltFor = utils.NpcDefs.generation; utils.NpcDefs.changedIds().forEach { npcModels.forget(it) } }
        val top = if (gadToolsModel.showAllFloors.get()) 3 else currentPlane
        val camTx = camera.cameraX / 128; val camTy = camera.cameraY / 128
        val sel = gadToolsModel.selectedNpc
        var n = 0
        // the server's extra copies of some spawns (offset lines in the spawn file) are drawn too, labelled as copies
        val shown = utils.NpcSpawns.all.flatMap { s -> listOf(Triple(s, s.x, s.y)) + s.copies.map { (dx, dy) -> Triple(s, s.x + dx, s.y + dy) } }
        for ((s, sx, sy) in shown) {
            if (s.z > top) continue
            val copy = sx != s.x || sy != s.y
            val sc = GadTools.worldToScene(scene, sx, sy) ?: continue
            if (Math.abs(sc.first - camTx) > 70 || Math.abs(sc.second - camTy) > 70) continue
            val def = defs[s.id]
            val h = npcHeight(s, sx, sy)
            val size = def?.size ?: 1
            npcLabels.add(TileLabels.Extra(sc.first * 128 + size * 64, h - 230, sc.second * 128 + size * 64, if (copy) "${s.id} (copy)" else s.id.toString(), s === sel && !copy))
            def ?: continue
            if (n >= 250) continue
            val model = npcModels.model(def, s.direction) ?: continue
            model.computeObj.offset = -1
            model.computeObj.uvOffset = -1
            sceneUploader.uploadModel(StaticObject(npcEntityDef, model, h, 10, 0), modelBuffers.vertexBuffer, modelBuffers.uvBuffer)
            model.drawDynamic(modelBuffers, sc.first, sc.second, h)
            n++
        }
    }

    private fun npcPush(s: utils.NpcSpawn, before: utils.NpcSpawn.State?, after: utils.NpcSpawn.State?) {
        if (before == after) return
        pushUndo(models.NpcEdit(s, before, after))
        gadToolsModel.npcVersion.set(gadToolsModel.npcVersion.get() + 1)
    }

    private fun npcName(id: Int) = utils.NpcDefs.cachedOrNull()?.get(id)?.name ?: "NPC"

    private fun handleNpcTool(pressed: Boolean, released: Boolean, down: Boolean) {
        val sel = gadToolsModel.selectedNpc
        if (gadToolsModel.deleteRequested.getAndSet(false) && sel != null) {
            val before = sel.state()
            utils.NpcSpawns.remove(sel)
            gadToolsModel.selectedNpc = null
            npcPush(sel, before, null)
            gadToolsModel.message = "Removed ${npcName(sel.id)} (${sel.id}). Ctrl+Z puts it back."
            return
        }
        if (gadToolsModel.rotateRequested.getAndSet(false) && sel != null) {
            val dirs = NpcModels.DIRECTIONS
            val i = dirs.indexOf(sel.direction.toUpperCase()).coerceAtLeast(0)
            // R turns clockwise (seen from above), Shift+R the other way
            val next = dirs[(i + if (inputHandler.isKeyDown(KeyCode.SHIFT)) 7 else 1) % 8]
            val before = sel.state()
            utils.NpcSpawns.apply(sel, before.copy(direction = next))
            npcPush(sel, before, sel.state())
            gadToolsModel.message = "${npcName(sel.id)} now faces ${next.toLowerCase().replace('_', '-')}."
            return
        }
        if (hoverId == -1) { if (released) { npcDrag = null; npcDragBefore = null }; return }
        val w = GadTools.sceneToWorld(scene, hoverId shr 18 and 0x1FFF, hoverId shr 5 and 0x1FFF) ?: return
        val plane = currentPlane
        if (pressed) {
            val hit = utils.NpcSpawns.at(w.first, w.second, plane) { npcSize(it) }
            if (hit != null) {
                gadToolsModel.selectedNpc = hit
                npcDrag = hit; npcDragBefore = hit.state()
                gadToolsModel.message = "${npcName(hit.id)} (${hit.id}) at ${hit.x}, ${hit.y}: drag to move, R to turn, Delete to remove."
            } else {
                val id = gadToolsModel.npcPlaceId.get()
                if (id < 0) { gadToolsModel.message = "Pick an NPC in the NPCs panel first (search by name or id)."; return }
                val s = utils.NpcSpawn(id, w.first, w.second, plane, "SOUTH", gadToolsModel.npcPlaceRadius.get())
                utils.NpcSpawns.add(s)
                gadToolsModel.selectedNpc = s
                npcPush(s, null, s.state())
                gadToolsModel.message = "Placed ${npcName(id)} ($id) at ${w.first}, ${w.second}. Drag it, R turns it, Delete removes it."
            }
            gadToolsModel.npcVersion.set(gadToolsModel.npcVersion.get() + 1)
            return
        }
        val drag = npcDrag
        if (down && drag != null && (drag.x != w.first || drag.y != w.second || drag.z != plane)) {
            utils.NpcSpawns.apply(drag, drag.state().copy(x = w.first, y = w.second, z = plane))
            gadToolsModel.message = "Moving ${npcName(drag.id)} to ${w.first}, ${w.second}..."
        }
        if (released && drag != null) {
            npcPush(drag, npcDragBefore, drag.state())
            if (npcDragBefore != drag.state()) gadToolsModel.message = "Moved ${npcName(drag.id)} to ${drag.x}, ${drag.y}. Ctrl+Z moves it back."
            npcDrag = null; npcDragBefore = null
        }
    }

    /** Queues every model of region [c] (the floors shown now) for its next compute pass, from the start of its output. */
    private fun drawTiles(c: Chunk) {
        val mb = c.mb
        mb.clear()
        mb.targetBufferOffset = 0
        val top = if (gadToolsModel.showAllFloors.get()) 3 else currentPlane
        val def = scene.getRegion(c.gx, c.gy)?.regionDefinition
        for (lx in 0 until REGION_SIZE) for (ly in 0 until REGION_SIZE) {
            val x = c.gx * REGION_SIZE + lx; val y = c.gy * REGION_SIZE + ly
            scene.getTile(0, x, y)?.let { drawTile(it, mb) }
            // floors above show up to the floor being edited, by the level the game draws them on: a bridge
            // (flag 2 on floor 1) lowers the floors above it by one, flag 8 draws a tile with the ground
            val bridge = def != null && def.isBridge(lx, ly)
            for (z in 1..3) {
                val tile = scene.getTile(z, x, y) ?: continue
                val low = def != null && ((def.tiles[z][lx][ly]?.settings?.toInt() ?: 0) and 8) != 0
                val level = if (low) 0 else if (bridge) z - 1 else z
                if (level <= top) drawTile(tile, mb)
            }
        }
        c.reserve(mb.targetBufferOffset)
    }

    private fun drawTile(tile: SceneTile, modelBuffers: ModelBuffers) {
        val x: Int = tile.x
        val y: Int = tile.y
        if (tile.tilePaint != null) {
            tile.tilePaint!!.draw(modelBuffers, x, y, 0, 30)
        }

        if (tile.tileModel != null) {
            tile.tileModel!!.draw(modelBuffers, x, y, 0, 31)
        }

        if (tile.floorDecoration != null) {
            tile.floorDecoration!!.entity!!.getModel()
                .draw(modelBuffers, x, y, tile.floorDecoration!!.entity!!.height, LocationType.FLOOR_DECORATION.id)
        }

        if (tile.wall != null) {
            tile.wall!!.entity!!.getModel()
                .draw(modelBuffers, x, y, tile.wall!!.entity!!.height, tile.wall!!.type.id)
            tile.wall!!.entity2?.getModel()
                ?.draw(modelBuffers, x, y, tile.wall!!.entity2!!.height, tile.wall!!.type.id)
        }

        if (tile.wallDecoration != null) {
            tile.wallDecoration!!.entity!!.getModel().draw(
                modelBuffers,
                x,
                y,
                tile.wallDecoration!!.entity!!.height,
                LocationType.INTERACTABLE_WALL_DECORATION.id
            )
            tile.wallDecoration!!.entity2?.getModel()?.draw(
                modelBuffers, x, y, tile.wallDecoration!!.entity2!!.height, LocationType.INTERACTABLE_WALL_DECORATION.id
            )
        }

        for (gameObject in tile.gameObjects) {
            gameObject.entity!!.getModel()
                .draw(modelBuffers, x, y, gameObject.entity.height, LocationType.INTERACTABLE.id)
        }
    }

    /**
     * Runs the compute shaders on what [c] queued this frame: its models are placed, lit and sorted into its own
     * output buffers, which stay there until something changes (only changed models are queued again).
     */
    private fun computePass(c: Chunk, dynamic: Boolean) {
        val mb = c.mb
        if (dynamic) c.count = mb.tempOffset
        if (mb.smallModelsCount + mb.largeModelsCount + mb.unorderedModelsCount == 0) { mb.clearVertUv(); mb.clear(); return }
        if (dynamic) c.reserve(mb.tempOffset)
        mb.flip()
        mb.flipVertUv()
        fun upload(id: Int, b: java.nio.Buffer, bytes: Long) {
            gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, id)
            gl.glBufferData(GLC.GL_ARRAY_BUFFER, b.limit() * bytes, b, GLC.GL_DYNAMIC_DRAW)
        }
        upload(tmpBufferId, mb.vertexBuffer.buffer, 4)
        upload(tmpUvBufferId, mb.uvBuffer.buffer, 4)
        upload(tmpModelBufferId, mb.modelBuffer.buffer, 4)
        upload(tmpModelBufferSmallId, mb.modelBufferSmall.buffer, 4)
        upload(tmpModelBufferUnorderedId, mb.modelBufferUnordered.buffer, 4)
        gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, 0)
        // unordered, then small (<= 512 faces sorted) and large (<= 4096) models
        for ((program, models, count) in listOf(
            Triple(glUnorderedComputeProgram, tmpModelBufferUnorderedId, mb.unorderedModelsCount),
            Triple(glSmallComputeProgram, tmpModelBufferSmallId, mb.smallModelsCount),
            Triple(glComputeProgram, tmpModelBufferId, mb.largeModelsCount))) {
            if (count == 0) continue
            gl.glUseProgram(program)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 0, models)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 1, c.vb)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 2, tmpBufferId)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 3, c.out)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 4, c.outUv)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 5, c.uvb)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBufferId)
            gl.glBindBufferBase(GLC.GL_SHADER_STORAGE_BUFFER, 7, c.picker)
            gl.glDispatchCompute(count, 1, 1)
        }
        mb.clearVertUv()
        mb.clear()
    }

    /**
     * How far region [c] is in front of the camera (along the ground, in local units), or null when it's wholly
     * behind it and can't be seen.
     */
    private fun chunkDepth(c: Chunk): Double? {
        val half = REGION_SIZE * Constants.LOCAL_TILE_SIZE / 2.0
        val dx = c.gx * REGION_SIZE * Constants.LOCAL_TILE_SIZE + half - camera.cameraX
        val dy = c.gy * REGION_SIZE * Constants.LOCAL_TILE_SIZE + half - camera.cameraY
        val depth = dx * (-camera.yawSin / 65536.0) + dy * (camera.yawCos / 65536.0)
        // the region's corners reach 1.42 half-widths out; looking down from high up shows a bit behind you too
        val margin = half * 1.5 + Math.abs(camera.cameraZ) * 2.0 + 2048
        return if (depth < -margin) null else Math.hypot(dx, dy)
    }

    private fun uploadScene() {
        if (!scene.isLoaded()) return
        isSceneUploadRequired = false
        val changed = scene.takeChanged()
        // size from the grid itself: a load in the background swaps the grid and the size one after the other
        val grid = scene.getRegions()
        val n = grid.size
        val all = changed == null || chunks.size != n
        // a region not uploaded now must not be queued again either: its data on the card is from its last upload,
        // and per-tile updates since (selection, flag tint, height brush) would be painted over with old colours
        val everything = all || requeueAll
        requeueAll = false
        if (all) {
            chunks.forEach { row -> row.forEach { it.delete() } }
            chunks = Array(n) { gx -> Array(n) { gy -> Chunk(gx, gy).also { it.create() } } }
        }
        var bytes = 0L
        for (gx in 0 until n) for (gy in 0 until n) {
            val c = chunks[gx][gy]
            if (!everything && (gx * 256 + gy) !in changed!!) continue
            run {
                // new tile objects only when the region was rebuilt (a floor change re-uploads the same ones)
                if (all || (gx * 256 + gy) in changed!!) c.generation++
                val mb = c.mb
                mb.clearVertUv()
                grid[gx].getOrNull(gy)?.let { sceneUploader.upload(it, mb.vertexBuffer, mb.uvBuffer) }
                mb.flipVertUv()
                for ((id, b) in listOf(c.vb to mb.vertexBuffer.buffer as java.nio.Buffer, c.uvb to mb.uvBuffer.buffer)) {
                    gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, id)
                    // an empty region still gets a few bytes: the compute shaders need something bound
                    if (b.limit() == 0) gl.glBufferData(GLC.GL_ARRAY_BUFFER, 16L, null, GLC.GL_STATIC_COPY)
                    else gl.glBufferData(GLC.GL_ARRAY_BUFFER, b.limit() * 4L, b, GLC.GL_STATIC_COPY)
                    bytes += b.limit() * 4L
                }
                gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, 0)
                // the region's data is on the card now: give the (large) copy here back
                mb.vertexBuffer.shrink()
                mb.uvBuffer.shrink()
            }
            drawTiles(c)
        }
        bindTiles(changed.takeUnless { all })
        if (bytes > 0) println("3D view: uploaded ${if (everything) "$n x $n regions" else "${changed!!.size} region(s)"}, %.1f MB".format(bytes / 1048576.0))
    }

    override fun dispose() {
        chunks.forEach { row -> row.forEach { it.delete() } }
        chunks = emptyArray()
        dynamicChunk?.delete(); dynamicChunk = null
        shutdownBuffers()
        shutdownProgram()
        shutdownVao()
        shutdownPickerFbo()
        shutdownAAFbo()
    }

    @Throws(ShaderException::class)
    private fun initProgram() {
        val template = Template()
        template.addInclude(Shader::class.java)

        glProgram = Shader.PROGRAM.compile(gl, template)
        glComputeProgram = Shader.COMPUTE_PROGRAM.compile(gl, template)
        glSmallComputeProgram = Shader.SMALL_COMPUTE_PROGRAM.compile(gl, template)
        glUnorderedComputeProgram = Shader.UNORDERED_COMPUTE_PROGRAM.compile(gl, template)

        initUniforms()
    }

    private fun initUniforms() {
        uniProjectionMatrix = gl.glGetUniformLocation(glProgram, "projectionMatrix")
        uniBrightness = gl.glGetUniformLocation(glProgram, "brightness")
        uniSmoothBanding = gl.glGetUniformLocation(glProgram, "smoothBanding")
        uniDrawDistance = gl.glGetUniformLocation(glProgram, "drawDistance")
        uniTextures = gl.glGetUniformLocation(glProgram, "textures")
        uniTextureOffsets = gl.glGetUniformLocation(glProgram, "textureOffsets")
        uniBlockSmall = gl.glGetUniformBlockIndex(glSmallComputeProgram, "uniforms")
        uniBlockLarge = gl.glGetUniformBlockIndex(glComputeProgram, "uniforms")
        uniBlockMain = gl.glGetUniformBlockIndex(glProgram, "uniforms")
        uniHoverId = gl.glGetUniformLocation(glProgram, "hoverId")
        uniMouseCoordsId = gl.glGetUniformLocation(glProgram, "mouseCoords")
    }

    private fun initUniformBuffer() {
        uniformBufferId = glGenBuffers(gl)
        gl.glBindBuffer(GLC.GL_UNIFORM_BUFFER, uniformBufferId)
        uniformBuffer.clear()
        uniformBuffer.put(IntArray(9))
        uniformBuffer.flip()
        gl.glBufferData(
            GLC.GL_UNIFORM_BUFFER,
            uniformBuffer.limit() * Integer.BYTES.toLong(),
            uniformBuffer,
            GLC.GL_DYNAMIC_DRAW
        )
        gl.glBindBuffer(GLC.GL_UNIFORM_BUFFER, 0)
    }

    private fun initPickerBuffer() {
        fboMainRenderer = GLUtil.glGenFrameBuffer(gl)
        gl.glBindFramebuffer(GLC.GL_FRAMEBUFFER, fboMainRenderer)

        // create depth buffer
        rboDepthMain = GLUtil.glGenRenderbuffer(gl)
        gl.glBindRenderbuffer(GLC.GL_RENDERBUFFER, rboDepthMain)
        gl.glRenderbufferStorage(GLC.GL_RENDERBUFFER, GLC.GL_DEPTH_COMPONENT, canvasWidth, canvasHeight)
        gl.glFramebufferRenderbuffer(GLC.GL_FRAMEBUFFER, GLC.GL_DEPTH_ATTACHMENT, GLC.GL_RENDERBUFFER, rboDepthMain)

        // Create color texture
        texColorMain = GLUtil.glGenTexture(gl)
        gl.glBindTexture(GLC.GL_TEXTURE_2D, texColorMain)
        gl.glTexImage2D(GLC.GL_TEXTURE_2D, 0, GLC.GL_RGBA, canvasWidth, canvasHeight, 0, GLC.GL_RGBA, GLC.GL_FLOAT, null)
        gl.glTexParameteri(GLC.GL_TEXTURE_2D, GLC.GL_TEXTURE_MIN_FILTER, GLC.GL_NEAREST)
        gl.glTexParameteri(GLC.GL_TEXTURE_2D, GLC.GL_TEXTURE_MAG_FILTER, GLC.GL_NEAREST)
        gl.glFramebufferTexture2D(GLC.GL_FRAMEBUFFER, GLC.GL_COLOR_ATTACHMENT0, GLC.GL_TEXTURE_2D, texColorMain, 0)
        texPickerMain = GLUtil.glGenTexture(gl)
        gl.glBindTexture(GLC.GL_TEXTURE_2D, texPickerMain)
        gl.glTexImage2D(
            GLC.GL_TEXTURE_2D,
            0,
            GLC.GL_R32I,
            canvasWidth,
            canvasHeight,
            0,
            GLC.GL_BGRA_INTEGER,
            GLC.GL_INT,
            null
        )
        gl.glTexParameteri(GLC.GL_TEXTURE_2D, GLC.GL_TEXTURE_MIN_FILTER, GLC.GL_NEAREST)
        gl.glTexParameteri(GLC.GL_TEXTURE_2D, GLC.GL_TEXTURE_MAG_FILTER, GLC.GL_NEAREST)
        gl.glFramebufferTexture2D(GLC.GL_FRAMEBUFFER, GLC.GL_COLOR_ATTACHMENT1, GLC.GL_TEXTURE_2D, texPickerMain, 0)

        // init pbo
        gl.glGenBuffers(3, pboIds, 0)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pboIds[0])
        gl.glBufferData(GLC.GL_PIXEL_PACK_BUFFER, Integer.BYTES.toLong(), null, GLC.GL_STREAM_READ)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pboIds[1])
        gl.glBufferData(GLC.GL_PIXEL_PACK_BUFFER, Integer.BYTES.toLong(), null, GLC.GL_STREAM_READ)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pboIds[2])
        gl.glBufferData(GLC.GL_PIXEL_PACK_BUFFER, Integer.BYTES.toLong(), null, GLC.GL_STREAM_READ)
        gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, 0)
        val status = gl.glCheckFramebufferStatus(GLC.GL_FRAMEBUFFER)
        if (status != GLC.GL_FRAMEBUFFER_COMPLETE) {
            println("bad picker fbo")
        }
        gl.glBindTexture(GLC.GL_TEXTURE_2D, 0)
        gl.glBindRenderbuffer(GLC.GL_RENDERBUFFER, 0)
        gl.glBindFramebuffer(GLC.GL_FRAMEBUFFER, 0)
    }

    private fun initAAFbo(width: Int, height: Int, aaSamples: Int) {
        // Create and bind the FBO
        fboSceneHandle = GLUtil.glGenFrameBuffer(gl)
        gl.glBindFramebuffer(GLC.GL_FRAMEBUFFER, fboSceneHandle)

        // Create color render buffer
        rboSceneHandle = GLUtil.glGenRenderbuffer(gl)
        gl.glBindRenderbuffer(GLC.GL_RENDERBUFFER, rboSceneHandle)
        gl.glRenderbufferStorageMultisample(GLC.GL_RENDERBUFFER, aaSamples, GLC.GL_RGBA, width, height)
        gl.glFramebufferRenderbuffer(GLC.GL_FRAMEBUFFER, GLC.GL_COLOR_ATTACHMENT0, GLC.GL_RENDERBUFFER, rboSceneHandle)

        // Create color texture
        colorTexSceneHandle = GLUtil.glGenTexture(gl)
        gl.glBindTexture(GLC.GL_TEXTURE_2D_MULTISAMPLE, colorTexSceneHandle)
        gl.glTexImage2DMultisample(GLC.GL_TEXTURE_2D_MULTISAMPLE, aaSamples, GLC.GL_RGBA, width, height, true)
        // Bind color tex
        gl.glFramebufferTexture2D(
            GLC.GL_FRAMEBUFFER,
            GLC.GL_COLOR_ATTACHMENT0,
            GLC.GL_TEXTURE_2D_MULTISAMPLE,
            colorTexSceneHandle,
            0
        )

        // Create depth texture
        depthTexSceneHandle = GLUtil.glGenTexture(gl)
        gl.glBindTexture(GLC.GL_TEXTURE_2D_MULTISAMPLE, depthTexSceneHandle)
        gl.glTexImage2DMultisample(
            GLC.GL_TEXTURE_2D_MULTISAMPLE,
            aaSamples,
            GLC.GL_DEPTH_COMPONENT,
            width,
            height,
            true
        )
        // bind depth tex
        gl.glFramebufferTexture2D(
            GLC.GL_FRAMEBUFFER,
            GLC.GL_DEPTH_ATTACHMENT,
            GLC.GL_TEXTURE_2D_MULTISAMPLE,
            depthTexSceneHandle,
            0
        )

        // Create picker texture
        val texPickerHandle = GLUtil.glGenTexture(gl)
        gl.glBindTexture(GLC.GL_TEXTURE_2D_MULTISAMPLE, texPickerHandle)
        gl.glTexImage2DMultisample(GLC.GL_TEXTURE_2D_MULTISAMPLE, aaSamples, GLC.GL_R32I, width, height, true)
        // Bind color tex
        gl.glFramebufferTexture2D(
            GLC.GL_FRAMEBUFFER,
            GLC.GL_COLOR_ATTACHMENT1,
            GLC.GL_TEXTURE_2D_MULTISAMPLE,
            texPickerHandle,
            0
        )
        val status = gl.glCheckFramebufferStatus(GLC.GL_FRAMEBUFFER)
        if (status != GLC.GL_FRAMEBUFFER_COMPLETE) {
            println("bad aaPicker fbo")
        }

        // Reset
        gl.glBindTexture(GLC.GL_TEXTURE_2D_MULTISAMPLE, 0)
        gl.glBindFramebuffer(GLC.GL_FRAMEBUFFER, 0)
        gl.glBindRenderbuffer(GLC.GL_RENDERBUFFER, 0)
    }

    private fun initBuffers() {
        bufferId = glGenBuffers(gl)
        uvBufferId = glGenBuffers(gl)
        tmpBufferId = glGenBuffers(gl)
        tmpUvBufferId = glGenBuffers(gl)
        tmpModelBufferId = glGenBuffers(gl)
        tmpModelBufferSmallId = glGenBuffers(gl)
        tmpModelBufferUnorderedId = glGenBuffers(gl)
        tmpOutBufferId = glGenBuffers(gl)
        tmpOutUvBufferId = glGenBuffers(gl)
        colorPickerBufferId = glGenBuffers(gl)
        animFrameBufferId = glGenBuffers(gl)
        selectedIdsBufferId = glGenBuffers(gl)
    }

    private fun initVao() {
        // Create VAO
        vaoHandle = glGenVertexArrays(gl)
        gl.glBindVertexArray(vaoHandle)
        gl.glEnableVertexAttribArray(0)
        gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, tmpOutBufferId)
        gl.glVertexAttribIPointer(0, 4, GLC.GL_INT, 0, 0)
        gl.glEnableVertexAttribArray(1)
        gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, tmpOutUvBufferId)
        gl.glVertexAttribPointer(1, 4, GLC.GL_FLOAT, false, 0, 0)
        gl.glEnableVertexAttribArray(2)
        gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, colorPickerBufferId)
        gl.glVertexAttribIPointer(2, 1, GLC.GL_INT, 0, 0)

//        gl.glEnableVertexAttribArray(3);
//        gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, animFrameBufferId);
//        gl.glVertexAttribIPointer(3, 4, GLC.GL_INT, 0, 0);

        // unbind VBO
        gl.glBindBuffer(GLC.GL_ARRAY_BUFFER, 0)
        gl.glBindVertexArray(0)
    }

    private fun createProjectionMatrix(
        left: Float,
        right: Float,
        bottom: Float,
        top: Float,
        near: Float,
        far: Float
    ) {
        // create a standard orthographic projection
        val tx = -((right + left) / (right - left))
        val ty = -((top + bottom) / (top - bottom))
        val tz = -((far + near) / (far - near))

        gl.glUseProgram(glProgram)
        val matrix = floatArrayOf(
            2 / (right - left), 0f, 0f, 0f, 0f, 2 / (top - bottom), 0f, 0f, 0f, 0f, -2 / (far - near), 0f,
            tx, ty, tz, 1f
        )
        gl.glUniformMatrix4fv(uniProjectionMatrix, 1, false, matrix, 0)
        gl.glUseProgram(0)
    }

    private fun shutdownBuffers() {
        if (bufferId != -1) {
            glDeleteBuffer(gl, bufferId)
            bufferId = -1
        }
        if (uvBufferId != -1) {
            glDeleteBuffer(gl, uvBufferId)
            uvBufferId = -1
        }
        if (tmpBufferId != -1) {
            glDeleteBuffer(gl, tmpBufferId)
            tmpBufferId = -1
        }
        if (tmpUvBufferId != -1) {
            glDeleteBuffer(gl, tmpUvBufferId)
            tmpUvBufferId = -1
        }
        if (tmpModelBufferId != -1) {
            glDeleteBuffer(gl, tmpModelBufferId)
            tmpModelBufferId = -1
        }
        if (tmpModelBufferSmallId != -1) {
            glDeleteBuffer(gl, tmpModelBufferSmallId)
            tmpModelBufferSmallId = -1
        }
        if (tmpModelBufferUnorderedId != -1) {
            glDeleteBuffer(gl, tmpModelBufferUnorderedId)
            tmpModelBufferUnorderedId = -1
        }
        if (tmpOutBufferId != -1) {
            glDeleteBuffer(gl, tmpOutBufferId)
            tmpOutBufferId = -1
        }
        if (tmpOutUvBufferId != -1) {
            glDeleteBuffer(gl, tmpOutUvBufferId)
            tmpOutUvBufferId = -1
        }
        if (colorPickerBufferId != -1) {
            glDeleteBuffer(gl, colorPickerBufferId)
            colorPickerBufferId = -1
        }
        if (animFrameBufferId != -1) {
            glDeleteBuffer(gl, animFrameBufferId)
            animFrameBufferId = -1
        }
        if (selectedIdsBufferId != -1) {
            glDeleteBuffer(gl, selectedIdsBufferId)
            selectedIdsBufferId = -1
        }
    }

    private fun shutdownProgram() {
        gl.glDeleteProgram(glProgram)
        glProgram = -1
        gl.glDeleteProgram(glComputeProgram)
        glComputeProgram = -1
        gl.glDeleteProgram(glSmallComputeProgram)
        glSmallComputeProgram = -1
        gl.glDeleteProgram(glUnorderedComputeProgram)
        glUnorderedComputeProgram = -1
    }

    private fun shutdownVao() {
        glDeleteVertexArrays(gl, vaoHandle)
        vaoHandle = -1
    }

    private fun shutdownAAFbo() {
        if (colorTexSceneHandle != -1) {
            glDeleteTexture(gl, colorTexSceneHandle)
            colorTexSceneHandle = -1
        }
        if (depthTexSceneHandle != -1) {
            glDeleteTexture(gl, depthTexSceneHandle)
            depthTexSceneHandle = -1
        }
        if (fboSceneHandle != -1) {
            glDeleteFrameBuffer(gl, fboSceneHandle)
            fboSceneHandle = -1
        }
        if (rboSceneHandle != -1) {
            glDeleteRenderbuffers(gl, rboSceneHandle)
            rboSceneHandle = -1
        }
    }

    private fun shutdownPickerFbo() {
        if (texPickerMain != -1) {
            glDeleteTexture(gl, texPickerMain)
            texPickerMain = -1
        }
        if (texColorMain != -1) {
            glDeleteTexture(gl, texColorMain)
            texColorMain = -1
        }
        if (fboMainRenderer != -1) {
            glDeleteFrameBuffer(gl, fboMainRenderer)
            fboMainRenderer = -1
        }
        if (rboDepthMain != -1) {
            glDeleteFrameBuffer(gl, rboDepthMain)
            rboDepthMain = -1
        }
        glDeleteBuffers(gl, pboIds)
    }
}