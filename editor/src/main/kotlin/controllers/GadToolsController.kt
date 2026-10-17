package controllers

import cache.XteaManager
import com.google.inject.Inject
import com.google.inject.Provider
import controllers.worldRenderer.Camera
import javafx.animation.AnimationTimer
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import models.GadToolsModel
import models.HoverModel
import models.ObjectsModel
import models.TerrainTool
import models.scene.Scene
import utils.Bookmark
import utils.GadTools
import utils.Deployer
import cache.loaders.LocationsLoader
import cache.loaders.RegionLoader
import javafx.application.Platform
import java.awt.Desktop
import cache.loaders.OverlayLoader
import cache.loaders.UnderlayLoader
import com.google.gson.GsonBuilder
import controllers.worldRenderer.InputHandler
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.FileChooser
import models.OVERLAY_SHAPES
import models.Prefab
import models.TileFlag
import models.TileOps
import models.TileParts
import models.setGroundHeight
import utils.EditorRegions
import java.io.File

private val SHAPE_VERTS = arrayOf(
    intArrayOf(1, 3, 5, 7), intArrayOf(1, 3, 5, 7), intArrayOf(1, 3, 5, 7),
    intArrayOf(1, 3, 5, 7, 6), intArrayOf(1, 3, 5, 7, 6), intArrayOf(1, 3, 5, 7, 6), intArrayOf(1, 3, 5, 7, 6),
    intArrayOf(1, 3, 5, 7, 2, 6), intArrayOf(1, 3, 5, 7, 2, 8), intArrayOf(1, 3, 5, 7, 2, 8),
    intArrayOf(1, 3, 5, 7, 11, 12), intArrayOf(1, 3, 5, 7, 11, 12), intArrayOf(1, 3, 5, 7, 13, 14)
)
private val SHAPE_TRIS = arrayOf(
    intArrayOf(0, 1, 2, 3, 0, 0, 1, 3), intArrayOf(1, 1, 2, 3, 1, 0, 1, 3), intArrayOf(0, 1, 2, 3, 1, 0, 1, 3),
    intArrayOf(0, 0, 1, 2, 0, 0, 2, 4, 1, 0, 4, 3), intArrayOf(0, 0, 1, 4, 0, 0, 4, 3, 1, 1, 2, 4),
    intArrayOf(0, 0, 4, 3, 1, 0, 1, 2, 1, 0, 2, 4), intArrayOf(0, 1, 2, 4, 1, 0, 1, 4, 1, 0, 4, 3),
    intArrayOf(0, 4, 1, 2, 0, 4, 2, 5, 1, 0, 4, 5, 1, 0, 5, 3), intArrayOf(0, 4, 1, 2, 0, 4, 2, 3, 0, 4, 3, 5, 1, 0, 4, 5),
    intArrayOf(0, 0, 4, 5, 1, 4, 1, 2, 1, 4, 2, 3, 1, 4, 3, 5),
    intArrayOf(0, 0, 1, 5, 0, 1, 4, 5, 0, 1, 2, 4, 1, 0, 5, 3, 1, 5, 4, 3, 1, 4, 2, 3),
    intArrayOf(1, 0, 1, 5, 1, 1, 4, 5, 1, 1, 2, 4, 0, 0, 5, 3, 0, 5, 4, 3, 0, 4, 2, 3),
    intArrayOf(1, 0, 5, 4, 1, 0, 1, 5, 0, 0, 4, 3, 0, 4, 5, 3, 0, 5, 2, 3, 0, 1, 2, 5)
)

class GadToolsController @Inject constructor(
    private val scene: Scene,
    private val camera: Camera,
    private val hoverModel: HoverModel,
    private val gadToolsModel: GadToolsModel,
    private val objectsModel: ObjectsModel,
    private val xteaProvider: Provider<XteaManager>,
    private val locationsLoader: LocationsLoader,
    private val regionLoader: RegionLoader,
    private val underlayLoader: UnderlayLoader,
    private val overlayLoader: OverlayLoader,
    private val inputHandler: InputHandler,
    private val editorRegions: EditorRegions,
    private val textureLoader: cache.loaders.TextureLoader,
    private val spriteLoader: cache.loaders.SpriteLoader,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val modelLoader: cache.loaders.ModelLoader,
    private val configuration: models.Configuration
) {
    private val tileOps = TileOps(editorRegions)
    /** Lets Claude read and edit the loaded map (Tools > AI bridge). */
    private val aiBridge by lazy { utils.AiBridge(scene, camera, gadToolsModel, editorRegions, objectLoader, underlayLoader, overlayLoader, textureLoader) { log(it) } }
    private val lblStatus = Label("")
    private val lblHover = Label("Hovered: -")
    private val lblPinned = Label("Pinned: none (hover the map and press P)")

    private val txtX = TextField()
    private val txtY = TextField()
    private val txtZ = TextField("0")
    private val txtRadius = TextField(controllers.worldRenderer.Renderer.mapSize().toString())

    private val bookmarks = GadTools.loadBookmarks()
    private val lstBookmarks = ListView<String>()

    private val txtNpcId = TextField()
    private val cmbDir = ComboBox<String>()
    private val txtWalk = TextField("3")
    private val txtObjId = TextField()
    private val txtObjType = TextField("10")
    private val txtObjRot = TextField("0")

    private val tabs = TabPane()
    private lateinit var tabArea: Tab
    private lateinit var tabGoTo: Tab
    private lateinit var tabServer: Tab
    private var btnExportPrefabRef: Button? = null
    private var btnImportPrefabRef: Button? = null
    private var btnBackupRef: Button? = null

    private fun scroll(n: Node): ScrollPane = ScrollPane(n).apply {
        isFitToWidth = true
        (n as? javafx.scene.layout.Region)?.padding = Insets(8.0)
    }

    private fun header(text: String) = Label(text).apply { styleClass.add("section-header") }

    fun build(): Node {
        configuration.getProp("paste-heights").toIntOrNull()?.let { models.PasteHeights.mode = it }
        if (configuration.getProp("ai-bridge") == "on") Platform.runLater { status(aiBridge.start()) }
        listOf(txtX, txtY, txtZ, txtRadius, txtNpcId, txtWalk, txtObjId, txtObjType, txtObjRot).forEach { it.prefColumnCount = 6 }
        lblStatus.isWrapText = true
        lblPinned.isWrapText = true

        tabArea = Tab("Area", scroll(VBox(6.0, buildSelection(), Separator(), header("Find & replace"), buildRemap())))
        tabGoTo = Tab("Go to", scroll(VBox(6.0, buildTile(), Separator(), header("Go to coords"), buildGoTo(),
            Separator(), header("Bookmarks"), buildBookmarks(), Separator(), header("Spawn lines"), buildSpawns())))
        tabServer = Tab("Server", scroll(VBox(6.0, header("Region backups"), buildBackups(), Separator(),
            header("Deploy to server"), buildDeploy(), Separator(), header("Package editor + map"), buildPackage())))
        tabs.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        tabs.tabs.setAll(
            Tab("Tools", scroll(buildTerrain())),
            Tab("Paint", scroll(buildPaint())),
            tabArea, tabGoTo, tabServer,
            Tab("Log", txtLog)
        )

        // always visible: floor, held object, last message
        val lblMsg = Label().apply { isWrapText = true; styleClass.add("status-message") }
        object : AnimationTimer() {
            override fun handle(now: Long) {
                val m = gadToolsModel.message
                if (lblMsg.text != m) lblMsg.text = m
            }
        }.start()
        val top = VBox(6.0, buildFloorBar(), buildHeldBar()).apply { padding = Insets(6.0, 6.0, 4.0, 6.0) }
        val bottom = VBox(2.0, lblMsg, lblStatus).apply {
            padding = Insets(6.0)
            styleClass.add("status-bar")
        }

        gadToolsModel.pinned.addListener { _, _, p ->
            if (p == null) return@addListener
            lblPinned.text = "Pinned: ${p.x}, ${p.y}, plane ${p.z}  (region ${GadTools.regionOf(p.x, p.y)})" +
                    if (p.objectIds.isNotEmpty()) "\nObjects here: ${p.objectIds.joinToString(", ")}" else ""
            txtX.text = p.x.toString()
            txtY.text = p.y.toString()
            txtZ.text = p.z.toString()
            if (p.objectIds.isNotEmpty()) txtObjId.text = p.objectIds.first().toString()
        }

        object : AnimationTimer() {
            var last = ""
            override fun handle(now: Long) {
                if (gadToolsModel.tileNumbersRequested.getAndSet(false)) {
                    val m = gadToolsModel.tileLabelMode
                    m.set(if (m.get() == 0) gadToolsModel.tileLabelLastOn else 0)
                    gadToolsModel.message = if (m.get() == 0) "Tile numbers off." else
                        "Tile numbers on (underlay/overlay, ${if (m.get() == 1) "your floor" else "all floors"}; red = dead in game). Ctrl+T hides them."
                }
                val h = hoverModel.hovered.get() ?: return
                val w = GadTools.sceneToWorld(scene, h.x, h.y) ?: return
                val rid = GadTools.regionOf(w.first, w.second)
                val place = utils.RegionNames.describe(rid)
                val text = "Hovered: ${w.first}, ${w.second}, plane ${h.sceneTile.z}  (region $rid${if (place.isNotEmpty()) ", $place" else ""})"
                if (text != last) {
                    lblHover.text = text
                    last = text
                }
            }
        }.start()

        return javafx.scene.layout.BorderPane(tabs, top, null, bottom, null)
    }

    // ---------- menus (added to the main menu bar) ----------

    fun menus(): List<Menu> {
        val view = Menu("View").apply {
            items.addAll(
                MenuItem("World map...").apply {
                    accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+M")
                    setOnAction { openWorldMap() }
                },
                SeparatorMenuItem(),
                CheckMenuItem("Show floors above").apply { selectedProperty().bindBidirectional(gadToolsModel.showAllFloors) },
                CheckMenuItem("Show flagged tiles (red)").apply { selectedProperty().bindBidirectional(gadToolsModel.showFlags) },
                CheckMenuItem("Tile numbers on the map").apply {
                    accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+T")
                    isSelected = gadToolsModel.tileLabelMode.get() != 0
                    gadToolsModel.tileLabelMode.addListener { _, _, v -> if (isSelected != (v != 0)) isSelected = v != 0 }
                    setOnAction { gadToolsModel.tileLabelMode.set(if (isSelected) gadToolsModel.tileLabelLastOn else 0) }
                },
                CheckMenuItem("Tile numbers: only the floor I'm on").apply {
                    setOnAction {
                        gadToolsModel.tileLabelLastOn = if (isSelected) 1 else 2
                        if (gadToolsModel.tileLabelMode.get() != 0) gadToolsModel.tileLabelMode.set(gadToolsModel.tileLabelLastOn)
                    }
                },
                CheckMenuItem("Tile numbers window").apply {
                    TileNumbersWindow.showing.addListener { _, _, v -> if (isSelected != v) isSelected = v }
                    setOnAction { toggleTileNumbers(isSelected) }
                },
                Menu("Map size (how much is loaded around you)").apply {
                    val group = ToggleGroup()
                    for (n in controllers.worldRenderer.Renderer.SIZES) items.add(RadioMenuItem("$n x $n regions (${n * 64} x ${n * 64} tiles)").apply {
                        toggleGroup = group
                        isSelected = n == controllers.worldRenderer.Renderer.mapSize()
                        setOnAction {
                            configuration.saveProp("map-size", n.toString())
                            txtRadius.text = n.toString()
                            if (scene.isLoaded() && scene.radius != n) {
                                status("Loading $n x $n regions...")
                                GadTools.loadAsync(scene, scene.centerRegionId, n) { e ->
                                    status(if (e == null) "Showing $n x $n regions around region ${scene.centerRegionId}." else "Couldn't load the map: ${e.message}")
                                }
                            }
                        }
                    })
                },
                SeparatorMenuItem(),
                MenuItem("Floor up\tCtrl+PgUp").apply { setOnAction { gadToolsModel.paintPlane.set((gadToolsModel.paintPlane.get() + 1).coerceAtMost(3)) } },
                MenuItem("Floor down\tCtrl+PgDn").apply { setOnAction { gadToolsModel.paintPlane.set((gadToolsModel.paintPlane.get() - 1).coerceAtLeast(0)) } }
            )
        }
        val tiles = Menu("Tiles").apply {
            items.addAll(
                MenuItem("Pick from hovered tile\tI").apply { setOnAction { inputHandler.pickFromHover() } },
                MenuItem("Copy selected object\tCtrl+C").apply { setOnAction { gadToolsModel.copyObjectRequested.set(true) } },
                MenuItem("Options of selected object...").apply { setOnAction { ObjectOptionsWindow.show(gadToolsModel, configuration, objectLoader, locationsLoader, ::log) } },
                MenuItem("Pick up selected object\tM").apply { setOnAction { gadToolsModel.moveObjectRequested.set(true) } },
                MenuItem("Rotate selected object\tR").apply { setOnAction { gadToolsModel.rotateRequested.set(true) } },
                MenuItem("Delete selected object\tDelete").apply { setOnAction { gadToolsModel.deleteRequested.set(true) } },
                SeparatorMenuItem(),
                MenuItem("Cut area (keep ground)\tCtrl+X").apply { setOnAction { inputHandler.cutSelection() } },
                MenuItem("Turn copy 90\u00B0\tCtrl+R").apply { setOnAction { inputHandler.rotateClipboard() } },
                MenuItem("Rotate held / hovered object\tR").apply { setOnAction { gadToolsModel.rotateRequested.set(true) } },
                SeparatorMenuItem(),
                MenuItem("Select area tool\t=").apply { setOnAction { gadToolsModel.tool.set(TerrainTool.SELECT) } },
                MenuItem("Copy selection\tCtrl+C").apply { setOnAction { inputHandler.copySelection() } },
                MenuItem("Paste at hovered tile\tCtrl+V").apply { setOnAction { inputHandler.pasteAtHover() } },
                Menu("Pasted / moved areas: height").apply {
                    val g = ToggleGroup()
                    fun opt(label: String, mode: Int) = RadioMenuItem(label).apply {
                        toggleGroup = g; isSelected = models.PasteHeights.mode == mode
                        setOnAction { models.PasteHeights.mode = mode; configuration.saveProp("paste-heights", mode.toString()); status(label) }
                    }
                    items.addAll(opt("Fit to the ground where it lands (no hills or holes)", models.PasteHeights.FIT),
                        opt("Keep its own heights exactly", models.PasteHeights.KEEP),
                        opt("Keep the ground already there (flat paste)", models.PasteHeights.GROUND))
                },
                MenuItem("Clear selection\tEsc").apply { setOnAction { gadToolsModel.selection = null } },
                MenuItem("Move selection to floor 0").apply { setOnAction { moveSelectionToGround(false) } },
                MenuItem("Move current floor up one").apply { setOnAction {
                    val f = gadToolsModel.paintPlane.get(); if (f < 3) moveSelectionFloors(listOf(f), f + 1, false) else status("Already on floor 3.") } },
                MenuItem("Move current floor down one").apply { setOnAction {
                    val f = gadToolsModel.paintPlane.get(); if (f > 0) moveSelectionFloors(listOf(f), f - 1, false) else status("Already on floor 0.") } },
                MenuItem("Fill dead tiles in selection").apply { setOnAction { fillSelectionHoles() } },
                MenuItem("Delete roofs in selection").apply { setOnAction { deleteRoofs(false) } },
                MenuItem("Delete roofs in loaded map").apply { setOnAction { deleteRoofs(true) } },
                SeparatorMenuItem(),
                MenuItem("Undo\tCtrl+Z").apply { setOnAction { gadToolsModel.undoRequested.set(true) } },
                MenuItem("Redo\tCtrl+Y").apply { setOnAction { gadToolsModel.redoRequested.set(true) } }
            )
        }
        val tools = Menu("Tools").apply {
            items.addAll(
                MenuItem("Interfaces (view + edit)...").apply { setOnAction { InterfaceEditorWindow(spriteLoader, ::log).show() } },
                MenuItem("Look at another cache's interfaces...").apply { setOnAction {
                    val d = javafx.stage.DirectoryChooser().apply { title = "Folder with the other cache (e.g. C:\\GadScape\\RoatPkz)" }
                        .showDialog(tabs.scene?.window) ?: return@setOnAction
                    // either format: new (.dat2) or old-style (.dat, 317 clients like Roat Pkz)
                    val dat = d.walkTopDown().maxDepth(6).filter { it.isFile && (it.name.equals("main_file_cache.dat2", true) || it.name.equals("main_file_cache.dat", true)) }
                        .sortedBy { it.path.length }.firstOrNull()
                        ?: return@setOnAction status("No game cache found in ${d.path}")
                    InterfaceEditorWindow(spriteLoader, ::log, dat.parentFile).show()
                } },
                MenuItem("Shops...").apply { setOnAction { ShopBuilderWindow(configuration, modelLoader, textureLoader, ::log).show() } },
                MenuItem("Models and animations (view, import, export, edit)...").apply {
                    accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+Shift+M")
                    setOnAction { ModelAnimWindow.show(::log) }
                },
                CheckMenuItem("AI bridge (let Claude edit the map)").apply {
                    isSelected = configuration.getProp("ai-bridge") == "on"
                    setOnAction {
                        configuration.saveProp("ai-bridge", if (isSelected) "on" else "off")
                        status(if (isSelected) aiBridge.start() else aiBridge.stop())
                    }
                },
                MenuItem("Copy what to tell Claude...").apply {
                    setOnAction {
                        if (!aiBridge.running) status(aiBridge.start()).also { configuration.saveProp("ai-bridge", "on") }
                        copy("Uzi's Map Editor is open with its AI bridge on. Read ${utils.AiBridge.infoFile.path} for the url and key, " +
                            "then GET <url>/api/help (send the key in the X-Bridge-Key header) and follow it. Then: ")
                        status("Copied. Paste it into Claude Code, then say what you want done (e.g. \"make the selected area a graveyard\").")
                    }
                },
                MenuItem("Items (make new ones, edit)...").apply {
                    accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+Shift+I")
                    setOnAction { ModelAnimWindow.show(::log, 2) }
                },
                MenuItem("Rebrand the cache (Near-Reality -> GadScape)...").apply { setOnAction { rebrandCache() } },
                MenuItem("Object options + works like (picked object)...").apply {
                    accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+Shift+O")
                    setOnAction { ObjectOptionsWindow.show(gadToolsModel, configuration, objectLoader, locationsLoader, ::log) }
                },
                MenuItem("Teleports, home and teleporting objects...").apply {
                    setOnAction { TeleportWindow.show(gadToolsModel, configuration, { x, y, z -> goTo(x, y, z) }, ::log) }
                },
                MenuItem("NPCs (place, move, turn, options)...").apply {
                    accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+Shift+N")
                    setOnAction { NpcWindow.show(gadToolsModel, configuration, ::log) }
                },
                SeparatorMenuItem(),
                MenuItem("Other map side by side (copy areas between maps)...").apply { setOnAction { openSideMap() } },
                MenuItem("Area picker (GE, Edgeville, your saved areas)...").apply { setOnAction { openAreaPicker() } },
                MenuItem("Bring an area from another cache (GE, Edgeville...)...").apply { setOnAction {
                    AreaFromCacheWindow(gadToolsModel, configuration, objectLoader, underlayLoader, overlayLoader,
                        { p, x, y -> gadToolsModel.actions.add {
                            val e = tileOps.paste(p, x, y, models.TileParts(), -1)
                            gadToolsModel.message = if (e == null) "Paste changed nothing." else "Pasted ${p.width} x ${p.height} at $x, $y. Ctrl+Z undoes it."
                            e
                        } }, ::log, regionLoader, locationsLoader).show()
                } },
                MenuItem("Find custom areas in another cache...").apply { setOnAction {
                    CacheCompareWindow(configuration, { dir, keys, ids -> doImport(dir, keys, ids) }, { x, y -> goTo(x, y, 0) }, ::log, regionLoader, locationsLoader).show()
                } },
                MenuItem("Import regions from another cache...").apply { setOnAction { importRegions() } },
                MenuItem("Import map files (.dat)...").apply { setOnAction { importMapFiles() } },
                SeparatorMenuItem(),
                MenuItem("Import prefab...").apply { setOnAction { btnImportPrefabRef?.fire() } },
                MenuItem("Export selection as prefab...").apply { setOnAction { btnExportPrefabRef?.fire() } },
                MenuItem("Find & replace...").apply { setOnAction { tabs.selectionModel.select(tabArea) } },
                SeparatorMenuItem(),
                MenuItem("Go to coords / bookmarks...").apply { setOnAction { tabs.selectionModel.select(tabGoTo) } },
                SeparatorMenuItem(),
                MenuItem("Back up loaded regions").apply { setOnAction { btnBackupRef?.fire() } },
                MenuItem("Deploy to server...").apply { setOnAction { tabs.selectionModel.select(tabServer) } },
                MenuItem("Package editor + map...").apply { setOnAction { tabs.selectionModel.select(tabServer) } }
            )
        }
        return listOf(view, tiles, tools)
    }

    /**
     * Replace regions (terrain + objects) with the ones from another cache, e.g. an older copy of the server.
     * The change lives in the editor like any edit: export or deploy to keep it.
     */
    fun importRegions() {
        val chooser = javafx.stage.DirectoryChooser().apply { title = "Folder with the other cache (contains cache\\ and xteas.json)" }
        val dir = chooser.showDialog(tabs.scene?.window) ?: return
        // look inside the picked folder (a few levels down) for the cache and the keys
        fun findUp(name: String): java.io.File? = dir.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.equals(name, true) }
            .sortedBy { it.path.length }.firstOrNull()
        val dat = findUp("main_file_cache.dat2") ?: findUp("main_file_cache.dat")
        val xteaFile = listOfNotNull(dat?.parentFile, dat?.parentFile?.parentFile).map { java.io.File(it, "xteas.json") }.firstOrNull { it.exists() }
            ?: findUp("xteas.json")
        val problem = when {
            dat == null -> "No main_file_cache.dat2 or .dat anywhere in:\n${dir.path}"
            dat.length() < 100_000 -> "Found ${dat.path}\nbut it's only ${dat.length()} bytes. That's not a real cache (maybe a Git LFS placeholder or a broken extract)."
            else -> null
        }
        if (problem != null) {
            Alert(Alert.AlertType.ERROR, problem).apply { headerText = "Can't import from this folder"; dialogPane.minWidth = 520.0 }.showAndWait()
            return
        }
        val cacheDir = dat!!.parentFile
        // many private-server caches don't encrypt their maps: no xteas.json is fine then
        val xteaDir = xteaFile?.parentFile ?: GadTools.noKeysDir()
        val loaded = GadTools.loadedRegionIds(scene)
        val dialog = TextInputDialog(loaded.joinToString(", ")).apply {
            title = "Import regions"
            headerText = "Regions to take from:\n${cacheDir.path}\n\nThey replace the same regions here (terrain and all objects). " +
                    "Filled in with the regions you have loaded now."
            contentText = "Region ids:"
            dialogPane.minWidth = 520.0
        }
        val text = dialog.showAndWait().orElse(null) ?: return
        val ids = text.split(Regex("[,\\s]+")).mapNotNull { it.trim().toIntOrNull() }.distinct()
        if (ids.isEmpty()) return

        doImport(cacheDir, xteaDir, ids)
    }

    /** Copy whole regions (terrain + objects) from another cache over the same regions here. */
    fun doImport(cacheDir: java.io.File, xteaDir: java.io.File, ids: List<Int>) {
        status("Importing ${ids.size} regions...")
        gadToolsModel.actions.add {
            val other = utils.OtherCache.open(cacheDir, regionLoader, locationsLoader)
            val done = ArrayList<Int>()
            val missing = ArrayList<Int>()
            var unknownObjects = 0
            try {
                for (id in ids) {
                    val r = other.access.region(id)
                    if (r == null) { missing.add(id); continue }
                    regionLoader.replace(id, r)
                    val l = other.access.locations(id)
                    // objects your cache doesn't have (another server's custom ones) would be invisible or crash the client
                    l?.locations?.removeIf { (objectLoader.get(it.objId) == null).also { bad -> if (bad) unknownObjects++ } }
                    locationsLoader.replace(id, l ?: cache.definitions.LocationsDefinition(id))
                    GadTools.dirtyRegions.add(id)
                    done.add(id)
                }
            } finally {
                other.close()
            }
            gadToolsModel.message = "Imported ${done.size} regions" +
                    (if (missing.isNotEmpty()) " (not in that cache: ${missing.joinToString(", ")})" else "") +
                    (if (unknownObjects > 0) ". Left out $unknownObjects objects your cache doesn't have (their custom objects)" else "") +
                    ". Export or deploy to keep them."
            javafx.application.Platform.runLater {
                GadTools.shiftRegions(scene, camera, 0, 0) // rebuild the view from the imported data
                status(gadToolsModel.message)
            }
            null
        }
    }

    /** Unwrap a raw map file: plain, gzip, or a cache container (compression byte + lengths). */
    private fun unpackMapFile(raw: ByteArray): ByteArray {
        fun gunzip(b: ByteArray, off: Int) = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(b, off, b.size - off)).readBytes()
        if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) return gunzip(raw, 0)
        if (raw.size > 9) {
            val type = raw[0].toInt()
            val len = java.nio.ByteBuffer.wrap(raw, 1, 4).int
            if (type == 2 && len == raw.size - 9) return gunzip(raw, 9)
            if (type == 0 && len == raw.size - 5) return raw.copyOfRange(5, raw.size)
        }
        return raw
    }

    /**
     * Load a server's custom map files (terrain and/or objects .dat, like cache/assets custom_maps)
     * into a region. Picks what each file is by trying to read it.
     */
    fun importMapFiles() {
        val chooser = javafx.stage.FileChooser().apply {
            title = "Pick the map file(s): terrain and/or objects (.dat)"
            extensionFilters.addAll(
                javafx.stage.FileChooser.ExtensionFilter("Map files", "*.dat", "*.gz", "*")
            )
        }
        val files = chooser.showOpenMultipleDialog(tabs.scene?.window) ?: return
        val center = if (scene.isLoaded()) scene.centerRegionId else 12342
        val dialog = TextInputDialog(center.toString()).apply {
            title = "Import map files"
            headerText = "Which region should these go into?\n" + files.joinToString("\n") { it.name } +
                    "\n\nFilled in with the region in the middle of your view."
            contentText = "Region id:"
            dialogPane.minWidth = 480.0
        }
        val id = dialog.showAndWait().orElse(null)?.trim()?.toIntOrNull() ?: return

        gadToolsModel.actions.add {
            val report = ArrayList<String>()
            var gotAny = false
            for (f in files) {
                val bytes = try { unpackMapFile(f.readBytes()) } catch (e: Exception) {
                    report.add("${f.name}: can't unpack (${e.message})"); continue
                }
                // decided by what's inside, not the name: terrain is a fixed grid of 4 x 64 x 64 tiles and only reads
                // as terrain if it fits exactly; anything else is tried as objects. (Names lie: "_l"/"landscape" are objects
                // in Jagex naming, but some servers call their terrain "landscape".)
                val terrain = try { regionLoader.fromBytes(id, bytes) } catch (e: Exception) { null }
                if (terrain != null) {
                    regionLoader.replace(id, terrain)
                    report.add("${f.name}: terrain")
                    gotAny = true
                    continue
                }
                val objects = try { locationsLoader.fromBytes(id, bytes) } catch (e: Exception) { null }
                if (objects != null && objects.locations.isNotEmpty()) {
                    locationsLoader.replace(id, objects)
                    report.add("${f.name}: ${objects.locations.size} objects")
                    gotAny = true
                } else if (terrain != null) {
                    regionLoader.replace(id, terrain)
                    report.add("${f.name}: terrain")
                    gotAny = true
                } else {
                    report.add("${f.name}: not a map file this editor can read")
                }
            }
            if (gotAny) GadTools.dirtyRegions.add(id)
            gadToolsModel.message = "Region $id: " + report.joinToString("; ") + if (gotAny) ". Export or deploy to keep it." else ""
            javafx.application.Platform.runLater {
                if (gotAny) {
                    // show the region we just filled
                    GadTools.goToAsync(scene, camera, ((id shr 8) shl 6) + 32, ((id and 0xFF) shl 6) + 32, scene.radius.coerceAtLeast(3))
                }
                status(gadToolsModel.message)
            }
            null
        }
    }

    private fun buildFloorMover(chkBridge: CheckBox): Node {
        val fromChoices = listOf("All floors above", "Floor 0", "Floor 1", "Floor 2", "Floor 3")
        val cmbFrom = ComboBox<String>().apply { items.setAll(fromChoices); value = "Floor 0" }
        val cmbTo = ComboBox<String>().apply { items.setAll("Floor 0", "Floor 1", "Floor 2", "Floor 3"); value = "Floor 1" }
        val btn = Button("Move").apply {
            tooltip = Tooltip("Moves tile paint, flags and objects in the selected area from one floor to another. Ctrl+Z undoes it.")
            setOnAction {
                val to = cmbTo.value.last().toString().toInt()
                val from = if (cmbFrom.value.startsWith("All")) (to + 1..3).toList() else listOf(cmbFrom.value.last().toString().toInt())
                if (from.isEmpty() || from == listOf(to)) { status("Nothing to move: pick a different From floor."); return@setOnAction }
                moveSelectionFloors(from, to, chkBridge.isSelected)
            }
        }
        return row(Label("From"), cmbFrom, Label("to"), cmbTo, btn).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
    }

    fun moveSelectionFloors(from: List<Int>, to: Int, clearBridge: Boolean) {
        val sel = gadToolsModel.selection ?: run { status("Select an area first (Select area tool, drag on the map)."); return }
        if (!confirm("Move floors?", "Tile paint, flags and objects in ${sel.width} x ${sel.height} tiles move from floor " +
                    "${from.joinToString("+")} to floor $to. Ctrl+Z undoes it.")) return
        gadToolsModel.actions.add {
            val (e, msg) = tileOps.moveFloors(sel, from, to, clearBridge)
            gadToolsModel.message = if (e == null) "Nothing to move on those floors in that area." else "$msg Ctrl+Z undoes it."
            e
        }
    }

    /** Swap the old server name in the cache's text (interfaces, scripts, item / NPC / object names) in cache-out. */
    fun rebrandCache() {
        val dir = GadTools.ensureExportCache()
        status("Looking for Near-Reality in the cache's text...")
        Thread({
            val hits = try { utils.CacheRebrand.scan(dir) { m -> Platform.runLater { status(m) } } } catch (e: Exception) { e.printStackTrace(); null }
            Platform.runLater {
                if (hits == null) { status("Couldn't read the cache."); return@runLater }
                if (hits.isEmpty()) { status("No Near-Reality text left in the cache."); return@runLater }
                val list = ListView(javafx.collections.FXCollections.observableArrayList(hits)).apply { prefHeight = 360.0; prefWidth = 760.0 }
                val a = Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.OK, ButtonType.CANCEL).apply {
                    title = "Rebrand the cache"
                    headerText = "${hits.size} cache file(s) still say Near-Reality. Swap them for GadScape (web addresses become gadscape.com)?\n" +
                            "Changes the exported map cache; deploy the map to send it. Back up first if unsure (Server tab)."
                    dialogPane.content = list
                    isResizable = true
                }
                if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return@runLater
                val n = try { utils.CacheRebrand.apply(dir, hits) } catch (e: Exception) { e.printStackTrace(); -1 }
                status(if (n < 0) "Rebrand failed, see the log." else "Rebranded $n cache file(s). Deploy the map to send them to the server.")
                log("Cache rebrand: $n file(s)")
            }
        }, "cache-rebrand").apply { isDaemon = true }.start()
    }

    fun toggleTileNumbers(on: Boolean) =
        TileNumbersWindow.toggle(on, gadToolsModel, hoverModel, scene, editorRegions, underlayLoader, overlayLoader)

    /** Selected area: give tiles with no underlay the underlay around them. */
    fun openSideMap() {
        SideMapWindow(gadToolsModel, configuration, objectLoader, underlayLoader, overlayLoader, regionLoader, locationsLoader,
            {
                val ids = GadTools.loadedRegionIds(scene)
                if (ids.isEmpty()) null else Pair(ids.map { ((it shr 8) shl 6) + 32 }.average().toInt(), ids.map { ((it and 0xFF) shl 6) + 32 }.average().toInt())
            },
            { p, x, y -> gadToolsModel.actions.add {
                val e = tileOps.paste(p, x, y, models.TileParts(), -1)
                gadToolsModel.message = if (e == null) "Paste changed nothing." else "Put ${p.width} x ${p.height} at $x, $y. Ctrl+Z undoes it."
                e
            } }, ::log).show()
    }

    fun openAreaPicker() {
        AreaPickerWindow(gadToolsModel, configuration, objectLoader, underlayLoader, overlayLoader,
            { rect -> tileOps.copy(rect) },
            { p, x, y -> gadToolsModel.actions.add {
                val e = tileOps.paste(p, x, y, models.TileParts(), -1)
                gadToolsModel.message = if (e == null) "Paste changed nothing." else "Placed ${p.width} x ${p.height} at $x, $y. Ctrl+Z undoes it."
                e
            } }, ::log, regionLoader, locationsLoader).show()
    }

    /** Roof pieces are object types 12-21 (roof edges, slopes, flat tops). */
    fun deleteRoofs(wholeMap: Boolean) {
        val rect = if (wholeMap) {
            val ids = GadTools.loadedRegionIds(scene)
            if (ids.isEmpty()) { status("No map loaded."); return }
            val xs = ids.map { (it shr 8) shl 6 }; val ys = ids.map { (it and 0xFF) shl 6 }
            models.TileRect(xs.minOrNull()!!, ys.minOrNull()!!, xs.maxOrNull()!! + 63, ys.maxOrNull()!! + 63)
        } else gadToolsModel.selection ?: run { status("Select an area first (Select area tool, drag on the map)."); return }
        if (!confirm("Delete roofs?", "Removes every roof piece " + (if (wholeMap) "in all loaded regions" else "in ${rect.width} x ${rect.height} tiles") +
                    ", on all floors. Walls and everything else stay. Ctrl+Z undoes it.")) return
        gadToolsModel.actions.add {
            var n = 0
            val e = tileOps.removeObjects(rect) { loc -> (loc.type in 12..21).also { if (it) n++ } }
            gadToolsModel.message = if (e == null) "No roofs there." else "Deleted $n roof pieces. Ctrl+Z undoes it."
            e
        }
    }

    fun fillSelectionHoles() {
        val sel = gadToolsModel.selection ?: run { status("Select an area first (Select area tool, drag on the map)."); return }
        val plane = gadToolsModel.paintPlane.get()
        gadToolsModel.actions.add {
            val (e, n) = tileOps.fillHoles(sel, plane)
            gadToolsModel.message = if (n == 0) "No dead tiles in that area." else "Filled $n dead tiles on floor $plane. Ctrl+Z undoes it."
            e
        }
    }

    /** Selected area: set or clear the bridge flag (on floor 1, where the game reads it). */
    fun bridgeSelection(on: Boolean) {
        val sel = gadToolsModel.selection ?: run { status("Select an area first (Select area tool, drag on the map)."); return }
        gadToolsModel.actions.add {
            val e = tileOps.fill(sel, 1..1, false) { t, z -> models.applyFlags(t, z, 1, 2, on) }
            gadToolsModel.message = when {
                e == null -> if (on) "That area is already a bridge." else "No bridge in that area."
                on -> "${sel.width} x ${sel.height} tiles are a bridge now: floor 1 there is walked on as the ground. Build the deck on floor 1; Ctrl+Z undoes it."
                else -> "Removed the bridge from ${sel.width} x ${sel.height} tiles. Ctrl+Z undoes it."
            }
            e
        }
    }

    /** Selected area: bring all upper-floor tiles and objects down to floor 0. */
    fun moveSelectionToGround(clearBridge: Boolean) {
        val sel = gadToolsModel.selection ?: run {
            status("Select an area first (Select area tool, drag on the map).")
            return
        }
        if (!confirm("Move to floor 0?", "Everything on floors 1-3 in ${sel.width} x ${sel.height} tiles (tile paint, flags and objects) " +
                    "goes down to floor 0. Ctrl+Z undoes it.")) return
        gadToolsModel.actions.add {
            val (e, msg) = tileOps.moveToGround(sel, clearBridge)
            gadToolsModel.message = if (e == null) "Nothing on the upper floors in that area." else "$msg Ctrl+Z undoes it."
            e
        }
    }

    fun openWorldMap() {
        WorldMapWindow(scene, camera, gadToolsModel, regionLoader, locationsLoader, underlayLoader, overlayLoader,
            textureLoader, objectLoader, xteaProvider.get()).show()
    }

    fun logsMenuItem(): MenuItem = MenuItem("Open logs folder").apply {
        setOnAction {
            val dir = utils.Updater.logDir
            try { java.awt.Desktop.getDesktop().open(dir) } catch (e: Exception) { status("Logs are in ${dir.path}") }
        }
    }

    fun shortcutsMenuItem(): MenuItem = MenuItem("Keyboard shortcuts").apply {
        setOnAction {
            Alert(Alert.AlertType.INFORMATION).apply {
                title = "Keyboard shortcuts"
                headerText = "Keyboard shortcuts"
                contentText = listOf(
                    "Move: WASD or arrows (Shift = fast), Ctrl+arrows = next region",
                    "Camera up/down: E / Q or Page Up / Page Down, mouse wheel = zoom, right-drag = look",
                    "Floors: Ctrl+Page Up / Ctrl+Page Down",
                    "Tools: 1-9, 0, -, =   Brush size: [ ]",
                    "Select object (tool 1): click an object, drag it to move, R rotate, Ctrl+C / Ctrl+V copy + paste, M pick up, Delete remove, Esc deselect",
                    "Held object: click to place, drag to place a line, R rotate, Esc drop, F = add held/selected/copied object to favorites",
                    "Areas: Ctrl+X cut objects (ground stays), Ctrl+R turn the copy 90\u00B0 before pasting",
                    "Remove tool: drag across objects to delete many",
                    "I = pick underlay/overlay/height from hovered tile, P = pin tile",
                    "Ctrl+C / Ctrl+V = copy / paste selected area, Ctrl+Z / Ctrl+Y = undo / redo"
                ).joinToString("\n")
                dialogPane.minWidth = 560.0
            }.showAndWait()
        }
    }

    private fun section(title: String, content: Node): TitledPane =
        TitledPane(title, content).apply { isAnimated = false }

    private fun row(vararg nodes: Node) = HBox(6.0, *nodes)

    // ---------- floors ----------

    private fun buildFloorBar(): Node {
        val lbl = Label().apply { styleClass.add("floor-label"); minWidth = 110.0 }
        fun refresh() {
            val p = gadToolsModel.paintPlane.get()
            lbl.text = "Floor $p" + if (p == 0) " (ground)" else ""
        }
        refresh()
        gadToolsModel.paintPlane.addListener { _, _, _ -> refresh() }
        val down = Button("\u25BC").apply {
            tooltip = Tooltip("Floor down (Ctrl + Page Down)")
            setOnAction { gadToolsModel.paintPlane.set((gadToolsModel.paintPlane.get() - 1).coerceAtLeast(0)) }
        }
        val up = Button("\u25B2").apply {
            tooltip = Tooltip("Floor up (Ctrl + Page Up)")
            setOnAction { gadToolsModel.paintPlane.set((gadToolsModel.paintPlane.get() + 1).coerceAtMost(3)) }
        }
        val chkAll = CheckBox("Floors above").apply {
            tooltip = Tooltip("Placing, removing and painting work on the current floor. Floors above it are hidden unless this is ticked.")
            selectedProperty().bindBidirectional(gadToolsModel.showAllFloors)
        }
        return row(down, lbl, up, chkAll).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
    }

    private fun buildHeldBar(): Node {
        val lblHeld = Label("Holding: nothing").apply { styleClass.add("subtle"); maxWidth = 190.0 }
        val btnDrop = Button("Drop").apply {
            tooltip = Tooltip("Drop the held object (Esc) so clicks edit the ground again")
            isDisable = objectsModel.heldObject.get() == null
            setOnAction { objectsModel.heldObject.set(null) }
        }
        val btnRotate = Button("\u21BB").apply {
            tooltip = Tooltip("Rotate the selected object, or the held one (R)")
            setOnAction {
                val held = objectsModel.heldObject.get()
                if (held != null) objectsModel.heldObject.set(models.scene.SceneObject(held.objectDefinition, held.type, (held.orientation + 1) and 3))
                else if (gadToolsModel.selectedObject != null) gadToolsModel.rotateRequested.set(true)
                else gadToolsModel.message = "Click an object on the map first (tool 1)."
            }
        }
        objectsModel.heldObject.addListener { _, _, v ->
            lblHeld.text = if (v == null) "Holding: nothing" else "Holding: ${v.objectDefinition.name} (${v.objectDefinition.id}), rot ${v.orientation * 90}\u00B0"
            btnDrop.isDisable = v == null
        }
        // the object selected on the map with tool 1
        val lblSel = Label("Selected: none (tool 1, click an object)").apply { styleClass.add("subtle") }
        // clipboard: work on the selected object if there is one, otherwise the selected area
        val btnCopyObj = Button("Copy").apply {
            tooltip = Tooltip("Copy the selected object, or the selected area (Ctrl+C)")
            setOnAction {
                if (gadToolsModel.selectedObject != null) gadToolsModel.copyObjectRequested.set(true) else inputHandler.copySelection()
            }
        }
        val btnPaste = Button("Paste").apply {
            tooltip = Tooltip("Paste on the tile the mouse was last over in the map (Ctrl+V). Hovering the map and pressing Ctrl+V is easier.")
            setOnAction {
                if (gadToolsModel.lastCopyWasObject && gadToolsModel.objectClipboard != null) gadToolsModel.pasteObjectRequested.set(true)
                else inputHandler.pasteAtHover()
            }
        }
        val btnCut = Button("Cut").apply {
            tooltip = Tooltip("Cut the selected object, or the objects in the selected area (Ctrl+X). Paste to put them somewhere else.")
            setOnAction {
                if (gadToolsModel.selectedObject != null) {
                    gadToolsModel.copyObjectRequested.set(true)
                    gadToolsModel.deleteRequested.set(true)
                } else inputHandler.cutSelection()
            }
        }
        val btnPickUp = Button("Pick up").apply {
            tooltip = Tooltip("Lift the selected object off the map; click to put it down somewhere else (M)")
            setOnAction { gadToolsModel.moveObjectRequested.set(true) }
        }
        val btnDelete = Button("Delete").apply {
            tooltip = Tooltip("Remove the selected object (Delete)")
            setOnAction { gadToolsModel.deleteRequested.set(true) }
        }
        // star the held object, else the selected one, else the copied one
        fun favTarget(): Int? = objectsModel.heldObject.get()?.objectDefinition?.id
            ?: gadToolsModel.selectedObject?.loc?.objId ?: gadToolsModel.objectClipboard?.objId
        val btnFav = Button("\u2606 Favorite").apply {
            tooltip = Tooltip("Add the held, selected or copied object to favorites (F). Find them under \u2605 Favorites in the Object Picker.")
            setOnAction {
                val id = favTarget() ?: return@setOnAction status("Hold, select or copy an object first.")
                val on = utils.Favorites.toggle(id)
                status(if (on) "Object $id added to favorites." else "Object $id removed from favorites.")
            }
        }
        object : AnimationTimer() {
            var last: Pair<Int?, Boolean>? = null
            override fun handle(now: Long) {
                val id = favTarget()
                val state = Pair(id, id != null && utils.Favorites.isFavorite(id))
                if (state == last) return
                last = state
                btnFav.isDisable = id == null
                btnFav.text = if (state.second) "\u2605 Favorite" else "\u2606 Favorite"
            }
        }.start()
        objectsModel.heldObject.addListener { _, _, v -> if (v != null) utils.Favorites.used(v.objectDefinition.id) }
        val selButtons = listOf(btnPickUp, btnDelete)
        object : AnimationTimer() {
            var last: models.SelectedObject? = null
            var first = true
            override fun handle(now: Long) {
                val sel = gadToolsModel.selectedObject
                if (!first && sel == last) return
                first = false
                last = sel
                lblSel.text = if (sel == null) "Selected: none (tool 1, click an object)" else {
                    val n = objectLoader.get(sel.loc.objId)?.name?.takeIf { it != "null" } ?: "object"
                    "Selected: $n (${sel.loc.objId}) at ${sel.worldX}, ${sel.worldY}, rot ${sel.loc.orientation * 90}\u00B0"
                }
                selButtons.forEach { it.isDisable = sel == null }
            }
        }.start()
        return VBox(4.0,
            row(lblSel).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            row(btnPickUp, btnRotate, btnDelete).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            row(lblHeld).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            row(btnDrop, btnCopyObj, btnPaste, btnCut, btnFav).apply { alignment = javafx.geometry.Pos.CENTER_LEFT })
    }

    // ---------- terrain ----------

    private val shortNames = mapOf(
        TerrainTool.INSPECT to "Select obj", TerrainTool.RAISE to "Raise", TerrainTool.LOWER to "Lower",
        TerrainTool.FLATTEN to "Flatten", TerrainTool.SMOOTH to "Smooth", TerrainTool.REMOVE to "Remove",
        TerrainTool.SET_HEIGHT to "Set height", TerrainTool.NOISE to "Roughen", TerrainTool.UNDERLAY to "Underlay",
        TerrainTool.OVERLAY to "Overlay", TerrainTool.FLAGS to "Flags", TerrainTool.SELECT to "Select"
    )
    private val toolTips = mapOf(
        TerrainTool.INSPECT to "Click an object to select it. Drag it to move it. R rotates, Ctrl+C / Ctrl+V copy and paste, M picks it up, Delete removes it.",
        TerrainTool.RAISE to "Hold left mouse to raise the ground. Shift lowers.",
        TerrainTool.LOWER to "Hold left mouse to lower the ground. Shift raises.",
        TerrainTool.FLATTEN to "Levels the brush to the height of the tile you start on.",
        TerrainTool.SMOOTH to "Evens out bumps and sharp edges.",
        TerrainTool.REMOVE to "Click or drag across objects to delete them (on the current floor).",
        TerrainTool.SET_HEIGHT to "Sets tiles to the exact height below (0-255, map file units).",
        TerrainTool.NOISE to "Adds bumpy noise. Strength sets how rough.",
        TerrainTool.UNDERLAY to "Paints the underlay picked in the Paint tab. Shift erases.",
        TerrainTool.OVERLAY to "Paints the overlay + shape picked in the Paint tab. Shift erases, R rotates.",
        TerrainTool.FLAGS to "Paints the tile flags picked in the Paint tab. Shift clears them.",
        TerrainTool.SELECT to "Drag a box. Then drag inside the box to move it (hold Shift to drag a copy), or Ctrl+C / Ctrl+V."
    )

    /** Tool buttons shared by the Tools and Paint tabs, kept in sync with the model. */
    private fun toolButtons(tools: List<TerrainTool>, columns: Int): Node {
        val group = ToggleGroup()
        val buttons = tools.map { t ->
            val key = InputHandler.TOOL_KEY_NAMES.getOrElse(t.ordinal) { "" }
            ToggleButton("$key  ${shortNames[t] ?: t.label}").apply {
                toggleGroup = group
                userData = t
                maxWidth = Double.MAX_VALUE
                prefWidth = 108.0
                isSelected = gadToolsModel.tool.get() == t
                tooltip = Tooltip(toolTips[t] ?: t.label)
            }
        }
        group.selectedToggleProperty().addListener { _, _, new ->
            if (new != null) gadToolsModel.tool.set(new.userData as TerrainTool)
        }
        gadToolsModel.tool.addListener { _, _, t ->
            val b = buttons.firstOrNull { it.userData == t }
            if (b != null) b.isSelected = true else group.selectToggle(null)
        }
        return javafx.scene.layout.TilePane(4.0, 4.0, *buttons.toTypedArray()).apply { prefColumns = columns }
    }

    private fun sliderRow(title: String, min: Double, max: Double, prop: javafx.beans.property.IntegerProperty): Node {
        val lbl = Label().apply { minWidth = 92.0 }
        val sld = Slider(min, max, prop.get().toDouble()).apply { isSnapToTicks = true; majorTickUnit = 1.0; minorTickCount = 0; blockIncrement = 1.0 }
        javafx.scene.layout.HBox.setHgrow(sld, javafx.scene.layout.Priority.ALWAYS)
        fun refresh() { lbl.text = "$title: ${prop.get()}" }
        refresh()
        sld.valueProperty().addListener { _, _, v -> prop.set(v.toInt()) }
        prop.addListener { _, _, v -> sld.value = v.toDouble(); refresh() }
        return row(lbl, sld).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
    }

    private fun buildTerrain(): Node {
        val tools = toolButtons(TerrainTool.values().toList(), 3)

        val spnHeight = intSpinner(0, 255, gadToolsModel.exactHeight.get()) { gadToolsModel.exactHeight.set(it) }
        gadToolsModel.exactHeight.addListener { _, _, v -> spnHeight.valueFactory.value = v.toInt() }

        val lblUndo = Label().apply { styleClass.add("subtle") }
        val btnUndo = Button("\u21B6 Undo").apply { tooltip = Tooltip("Ctrl+Z"); setOnAction { gadToolsModel.undoRequested.set(true) } }
        val btnRedo = Button("Redo \u21B7").apply { tooltip = Tooltip("Ctrl+Y"); setOnAction { gadToolsModel.redoRequested.set(true) } }
        object : AnimationTimer() {
            override fun handle(now: Long) {
                val t = "${gadToolsModel.undoCount} / ${gadToolsModel.redoCount}"
                if (lblUndo.text != t) lblUndo.text = t
            }
        }.start()

        return VBox(8.0,
            tools,
            Separator(),
            sliderRow("Brush", 0.0, 15.0, gadToolsModel.brushSize),
            sliderRow("Strength", 1.0, 40.0, gadToolsModel.strength),
            row(Label("Exact height").apply { minWidth = 92.0 }, spnHeight).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            row(btnUndo, btnRedo, lblUndo).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
        )
    }

    // ---------- paint tiles ----------

    private fun intSpinner(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): Spinner<Int> =
        Spinner<Int>(min, max, value.coerceIn(min, max)).apply {
            isEditable = true
            prefWidth = 90.0
            valueProperty().addListener { _, _, v -> if (v != null) onChange(v) }
        }

    private fun rgb(color: Int): Color = Color.rgb(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)

    /** Picker for underlay or overlay definitions, with a colour swatch. -1 = none. */
    private fun defPicker(ids: List<Int>, describe: (Int) -> Pair<Color?, String>): ComboBox<Int> {
        val cmb = ComboBox<Int>()
        cmb.items.setAll(listOf(-1) + ids)
        cmb.maxWidth = Double.MAX_VALUE
        cmb.visibleRowCount = 16
        cmb.setCellFactory {
            object : ListCell<Int>() {
                override fun updateItem(item: Int?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) { text = null; graphic = null; return }
                    if (item < 0) { text = "None (erase)"; graphic = null; return }
                    val (c, label) = describe(item)
                    text = label
                    graphic = if (c == null) null else Rectangle(14.0, 14.0, c).apply { stroke = Color.gray(0.3) }
                }
            }
        }
        cmb.buttonCell = cmb.cellFactory.call(null)
        return cmb
    }

    private val gold = Color.web("#f0c450")
    private val textureThumbs = HashMap<Int, javafx.scene.image.Image?>()

    /** Small image of a texture for swatches, or null if it can't be read. */
    private fun textureThumb(textureId: Int): javafx.scene.image.Image? = textureThumbs.getOrPut(textureId) {
        try {
            val tex = textureLoader.get(textureId) ?: return@getOrPut null
            if (!tex.loadPixels(0.8, 128, spriteLoader)) return@getOrPut null
            val img = javafx.scene.image.WritableImage(32, 32)
            for (x in 0 until 32) for (y in 0 until 32) {
                val p = tex.pixels[(x * 4) + (y * 4) * 128]
                img.pixelWriter.setArgb(x, y, if (p == 0) 0xFF000000.toInt() else (0xFF shl 24) or p)
            }
            img
        } catch (e: Exception) { null }
    }

    /** Pink (0xFF00FF), untextured overlays: no colour of their own. */
    private fun seeThroughOverlay(id: Int): Boolean {
        val d = overlayLoader.get(id) ?: return false
        return d.texture < 0 && d.rgbColor == 0xFF00FF
    }

    private fun overlayColor(id: Int): Color? {
        val d = overlayLoader.get(id) ?: return null
        return if (d.rgbColor == 0xFF00FF) null else rgb(d.rgbColor)
    }

    /** A grid of clickable swatches bound to an id property (-1 = none). */
    private fun swatchGrid(ids: List<Int>, prop: javafx.beans.property.IntegerProperty,
                           paint: (Int, javafx.scene.canvas.GraphicsContext, Double) -> Unit, tip: (Int) -> String): Node {
        val size = 24.0
        val cells = HashMap<Int, javafx.scene.canvas.Canvas>()
        fun draw(id: Int) {
            val c = cells[id] ?: return
            val g = c.graphicsContext2D
            g.clearRect(0.0, 0.0, size, size)
            if (id < 0) {
                g.fill = Color.web("#1f1a16"); g.fillRect(0.0, 0.0, size, size)
                g.stroke = Color.web("#8c241e"); g.lineWidth = 2.0; g.strokeLine(4.0, 4.0, size - 4, size - 4); g.strokeLine(size - 4, 4.0, 4.0, size - 4)
            } else paint(id, g, size)
            g.lineWidth = if (prop.get() == id) 3.0 else 1.0
            g.stroke = if (prop.get() == id) gold else Color.web("#3a302a")
            g.strokeRect(0.5, 0.5, size - 1, size - 1)
        }
        val pane = javafx.scene.layout.FlowPane(3.0, 3.0)
        for (id in listOf(-1) + ids) {
            val c = javafx.scene.canvas.Canvas(size, size)
            cells[id] = c
            Tooltip.install(c, Tooltip(if (id < 0) "None (erase)" else tip(id)))
            c.setOnMouseClicked { prop.set(id) }
            c.style = "-fx-cursor: hand;"
            pane.children.add(c)
            draw(id)
        }
        prop.addListener { _, old, new -> draw(old.toInt()); draw(new.toInt()) }
        return ScrollPane(pane).apply { isFitToWidth = true; prefViewportHeight = 150.0 }
    }

    /** Draw an overlay shape (0-11) with rotation, underlay part dark and overlay part light. */
    private fun drawShape(g: javafx.scene.canvas.GraphicsContext, x0: Double, y0: Double, size: Double, shape: Int, rot: Int,
                          under: Color, over: Color) {
        val pts = mapOf(
            1 to (0.0 to 0.0), 2 to (0.5 to 0.0), 3 to (1.0 to 0.0), 4 to (1.0 to 0.5), 5 to (1.0 to 1.0),
            6 to (0.5 to 1.0), 7 to (0.0 to 1.0), 8 to (0.0 to 0.5), 9 to (0.5 to 0.25), 10 to (0.75 to 0.5),
            11 to (0.5 to 0.75), 12 to (0.25 to 0.5), 13 to (0.25 to 0.25), 14 to (0.75 to 0.25),
            15 to (0.75 to 0.75), 16 to (0.25 to 0.75)
        )
        val verts = SHAPE_VERTS[shape + 1].map { v0 ->
            var v = v0
            if (v and 1 == 0 && v <= 8) v = (v - rot - rot - 1 and 7) + 1
            if (v in 9..12) v = (v - 9 - rot and 3) + 9
            if (v in 13..16) v = (v - 13 - rot and 3) + 13
            pts.getValue(v)
        }
        val tris = SHAPE_TRIS[shape + 1]
        var i = 0
        while (i + 3 < tris.size) {
            val isOver = tris[i] == 1
            // corner indices (0-3) turn with the rotation, same as the tile renderer
            fun corner(j: Int) = if (j < 4) (j - rot) and 3 else j
            val a = verts[corner(tris[i + 1])]; val b = verts[corner(tris[i + 2])]; val c = verts[corner(tris[i + 3])]
            g.fill = if (isOver) over else under
            // map y so north is up
            g.fillPolygon(doubleArrayOf(x0 + a.first * size, x0 + b.first * size, x0 + c.first * size),
                doubleArrayOf(y0 + (1 - a.second) * size, y0 + (1 - b.second) * size, y0 + (1 - c.second) * size), 3)
            i += 4
        }
    }

    private fun buildPaint(): Node {
        // preview of the tile you'll paint
        val preview = javafx.scene.canvas.Canvas(64.0, 64.0)
        val lblPick = Label().apply { isWrapText = true; styleClass.add("subtle") }
        fun redrawPreview() {
            val g = preview.graphicsContext2D
            val u = gadToolsModel.underlayId.get().let { if (it < 0) Color.web("#2a241f") else underlayLoader.get(it)?.let { d -> rgb(d.color) } ?: Color.GRAY }
            val oId = gadToolsModel.overlayId.get()
            val tex = if (oId >= 0) overlayLoader.get(oId)?.texture ?: -1 else -1
            g.clearRect(0.0, 0.0, 64.0, 64.0)
            if (oId >= 0) {
                val o = overlayColor(oId) ?: Color.web("#9b8f7a")
                drawShape(g, 0.0, 0.0, 64.0, gadToolsModel.overlayShape.get(), gadToolsModel.overlayRotation.get(), u, o)
                if (tex >= 0) textureThumb(tex)?.let { img ->
                    g.globalAlpha = 0.35; g.drawImage(img, 0.0, 0.0, 64.0, 64.0); g.globalAlpha = 1.0
                    drawShape(g, 0.0, 0.0, 64.0, gadToolsModel.overlayShape.get(), gadToolsModel.overlayRotation.get(), u, Color.TRANSPARENT)
                }
            } else {
                g.fill = u; g.fillRect(0.0, 0.0, 64.0, 64.0)
            }
            g.stroke = gold; g.lineWidth = 1.0; g.strokeRect(0.5, 0.5, 63.0, 63.0)
            val under = gadToolsModel.underlayId.get()
            lblPick.text = "Underlay: ${if (under < 0) "none" else under}\n" +
                    "Overlay: ${if (oId < 0) "none" else "$oId" + if (tex >= 0) " (texture $tex)" else ""}\n" +
                    "Shape ${gadToolsModel.overlayShape.get()}, rotation ${gadToolsModel.overlayRotation.get() * 90}\u00B0"
        }
        listOf(gadToolsModel.underlayId, gadToolsModel.overlayId, gadToolsModel.overlayShape, gadToolsModel.overlayRotation)
            .forEach { it.addListener { _, _, _ -> redrawPreview() } }
        redrawPreview()

        val paintTools = toolButtons(listOf(TerrainTool.UNDERLAY, TerrainTool.OVERLAY, TerrainTool.FLAGS), 3)
        val btnMapNumbers = ToggleButton("Numbers on map").apply {
            tooltip = Tooltip("Every tile's underlay/overlay number drawn on the 3D map, all floors (Ctrl+T). Red = a tile that's dead in game.")
            isSelected = gadToolsModel.tileLabelMode.get() != 0
            gadToolsModel.tileLabelMode.addListener { _, _, v -> if (isSelected != (v != 0)) isSelected = v != 0 }
            setOnAction { gadToolsModel.tileLabelMode.set(if (isSelected) gadToolsModel.tileLabelLastOn else 0) }
        }
        val btnNumbersWin = ToggleButton("Tile numbers window").apply {
            tooltip = Tooltip("Grid of the tiles around the mouse, all floors. Click one to paint with it, or type numbers in.")
            TileNumbersWindow.showing.addListener { _, _, v -> if (isSelected != v) isSelected = v }
            setOnAction { toggleTileNumbers(isSelected) }
        }
        val btnNumbers = HBox(6.0, btnMapNumbers, btnNumbersWin)

        // underlay swatches
        val underGrid = swatchGrid(underlayLoader.ids(), gadToolsModel.underlayId, { id, g, sz ->
            g.fill = underlayLoader.get(id)?.let { rgb(it.color) } ?: Color.GRAY; g.fillRect(0.0, 0.0, sz, sz)
        }) { id -> "Underlay $id  #%06X".format(underlayLoader.get(id)?.color ?: 0) }

        // overlay swatches: texture thumbnail where there is one
        val overGrid = swatchGrid(overlayLoader.ids(), gadToolsModel.overlayId, { id, g, sz ->
            val d = overlayLoader.get(id)
            val img = if (d != null && d.texture >= 0) textureThumb(d.texture) else null
            if (img != null) g.drawImage(img, 0.0, 0.0, sz, sz)
            else { g.fill = overlayColor(id) ?: Color.web("#555"); g.fillRect(0.0, 0.0, sz, sz) }
            // pink, untextured overlays: no colour of their own, marked with a small red corner
            if (seeThroughOverlay(id)) { g.fill = Color.web("#d0302a"); g.fillPolygon(doubleArrayOf(sz - 9, sz, sz), doubleArrayOf(0.0, 0.0, 9.0), 3) }
        }) { id ->
            val d = overlayLoader.get(id)
            "Overlay $id" + (if (d != null && d.texture >= 0) "  texture ${d.texture}" else "  #%06X".format(d?.rgbColor ?: 0)) +
                    if (seeThroughOverlay(id)) "  (no colour of its own: shows the underlay, or nothing where there isn't one)" else ""
        }

        // shapes, drawn with the current rotation
        val shapeCells = ArrayList<javafx.scene.canvas.Canvas>()
        fun redrawShapes() {
            shapeCells.forEachIndexed { s, c ->
                val g = c.graphicsContext2D
                g.clearRect(0.0, 0.0, c.width, c.height)
                drawShape(g, 2.0, 2.0, 30.0, s, gadToolsModel.overlayRotation.get(), Color.web("#2e2620"), Color.web("#e9e1d6"))
                g.lineWidth = if (gadToolsModel.overlayShape.get() == s) 3.0 else 1.0
                g.stroke = if (gadToolsModel.overlayShape.get() == s) gold else Color.web("#3a302a")
                g.strokeRect(1.0, 1.0, 32.0, 32.0)
            }
        }
        for (s in 0 until OVERLAY_SHAPES) {
            val c = javafx.scene.canvas.Canvas(34.0, 34.0)
            Tooltip.install(c, Tooltip(if (s == 0) "Shape 0: full tile" else "Shape $s"))
            c.setOnMouseClicked { gadToolsModel.overlayShape.set(s) }
            c.style = "-fx-cursor: hand;"
            shapeCells.add(c)
        }
        gadToolsModel.overlayShape.addListener { _, _, _ -> redrawShapes() }
        gadToolsModel.overlayRotation.addListener { _, _, _ -> redrawShapes() }
        redrawShapes()
        val rotL = Button("\u21BA").apply { tooltip = Tooltip("Rotate left"); setOnAction { gadToolsModel.overlayRotation.set((gadToolsModel.overlayRotation.get() + 3) and 3) } }
        val rotR = Button("\u21BB").apply { tooltip = Tooltip("Rotate right (R)"); setOnAction { gadToolsModel.overlayRotation.set((gadToolsModel.overlayRotation.get() + 1) and 3) } }
        val shapesBox = VBox(6.0, javafx.scene.layout.FlowPane(3.0, 3.0, *shapeCells.toTypedArray()),
            row(Label("Rotation"), rotL, rotR))

        // flags
        val flagBoxes = TileFlag.values().map { f ->
            CheckBox(f.label).apply {
                isSelected = gadToolsModel.flagMask.get() and f.bit != 0
                selectedProperty().addListener { _, _, on ->
                    val m = gadToolsModel.flagMask.get()
                    gadToolsModel.flagMask.set(if (on) m or f.bit else m and f.bit.inv())
                }
            }
        }
        val chkShow = CheckBox("Show tiles with these flags (red)").apply { selectedProperty().bindBidirectional(gadToolsModel.showFlags) }

        val inner = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.addAll(
                Tab("Underlay", underGrid),
                Tab("Overlay", overGrid),
                Tab("Shape", VBox(6.0, shapesBox).apply { padding = Insets(6.0) }),
                Tab("Flags", VBox(4.0, *(flagBoxes + chkShow).toTypedArray()).apply { padding = Insets(6.0) })
            )
        }
        val top = row(preview, lblPick).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
        val note = Label("Pick a paint tool, then hold left mouse on the map. Shift erases, R rotates the overlay, I picks from a tile. " +
                "Paint goes on the floor shown at the top.").apply { isWrapText = true; styleClass.add("subtle") }
        return VBox(8.0, top, paintTools, btnNumbers, inner, note)
    }

    // ---------- selection + copy/paste ----------

    private val prefabGson = GsonBuilder().create()

    private fun needSelection(): models.TileRect? =
        gadToolsModel.selection ?: run { status("Select an area first: pick the Select area tool and drag on the map."); null }

    private fun buildSelection(): Node {
        val lblSel = Label("Nothing selected").apply { isWrapText = true }
        val lblClip = Label("Clipboard: empty").apply { isWrapText = true }
        object : AnimationTimer() {
            override fun handle(now: Long) {
                val s = gadToolsModel.selection
                val t = if (s == null) "Nothing selected" else "Selected ${s.width} x ${s.height}: ${s.x1}, ${s.y1} to ${s.x2}, ${s.y2}"
                if (lblSel.text != t) lblSel.text = t
                val c = gadToolsModel.clipboard
                val ct = if (c == null) "Clipboard: empty" else "Clipboard: ${c.width} x ${c.height}, ${c.objects.size} objects"
                if (lblClip.text != ct) lblClip.text = ct
            }
        }.start()

        val parts = listOf(
            CheckBox("Heights"), CheckBox("Underlays"), CheckBox("Overlays"), CheckBox("Flags"), CheckBox("Objects")
        ).onEach { it.isSelected = true }
        fun readParts() = TileParts(parts[0].isSelected, parts[1].isSelected, parts[2].isSelected, parts[3].isSelected, parts[4].isSelected)
        parts.forEach { cb -> cb.selectedProperty().addListener { _, _, _ -> gadToolsModel.pasteParts = readParts() } }

        val btnCopy = Button("Copy (Ctrl+C)").apply { setOnAction { inputHandler.copySelection() } }
        val btnPastePin = Button("Paste at pinned tile").apply {
            setOnAction {
                val clip = gadToolsModel.clipboard ?: return@setOnAction status("Nothing copied yet.")
                val p = gadToolsModel.pinned.get() ?: return@setOnAction status("Pin a tile first (hover it and press P).")
                val opts = readParts()
                gadToolsModel.actions.add {
                    val e = tileOps.paste(clip, p.x, p.y, opts, gadToolsModel.paintPlane.get())
                    gadToolsModel.message = if (e == null) "Paste changed nothing." else "Pasted at ${p.x}, ${p.y}."
                    e
                }
            }
        }
        val btnClear = Button("Clear area").apply {
            setOnAction {
                val sel = needSelection() ?: return@setOnAction
                val opts = readParts()
                if (!confirm("Clear the selected area?", "Removes the ticked parts (except heights) on every plane of ${sel.width} x ${sel.height} tiles. Ctrl+Z undoes it.")) return@setOnAction
                gadToolsModel.actions.add {
                    val e = tileOps.clear(sel, opts, 0..3)
                    gadToolsModel.message = if (e == null) "Nothing to clear." else "Cleared ${sel.width} x ${sel.height}."
                    e
                }
            }
        }
        val btnDeselect = Button("Deselect (Esc)").apply { setOnAction { gadToolsModel.selection = null } }
        val chkBridge = CheckBox("Also clear bridge flags here").apply {
            tooltip = Tooltip("A bridge flag on the ground makes the floor above act as ground. Tick if the area should be plain ground.")
        }
        val btnToGround = Button("\u2B07 Move everything to floor 0").apply {
            tooltip = Tooltip("Every tile's paint/flags and every object on floors 1-3 in the selected area goes down to floor 0. Ctrl+Z undoes it.")
            setOnAction { moveSelectionToGround(chkBridge.isSelected) }
        }

        fun fill(label: String, heightOnly: Boolean, groundOnly: Boolean, change: (cache.definitions.RegionDefinition.Tile) -> Unit) {
            val sel = needSelection() ?: return
            val plane = if (groundOnly) 0 else gadToolsModel.paintPlane.get()
            gadToolsModel.actions.add {
                val e = tileOps.fill(sel, plane..plane, heightOnly) { t, _ -> change(t) }
                gadToolsModel.message = if (e == null) "$label: nothing changed." else "$label on ${sel.width} x ${sel.height} tiles."
                e
            }
        }
        fun fillFlags(label: String, add: Boolean) {
            val sel = needSelection() ?: return
            val plane = gadToolsModel.paintPlane.get().coerceIn(0, 3)
            val m = gadToolsModel.flagMask.get()
            if (m == 0) { status("Tick the flags to change first (Paint tab, Flags)."); return }
            gadToolsModel.actions.add {
                val e = tileOps.fill(sel, models.flagPlanes(plane, m), false) { t, z -> models.applyFlags(t, z, plane, m, add) }
                gadToolsModel.message = if (e == null) "$label: nothing changed." else "$label on ${sel.width} x ${sel.height} tiles" +
                        (if (m and 2 != 0) " (the bridge flag goes on floor 1, where the game reads it)." else ".")
                e
            }
        }
        val btnBridge = Button("Make it a bridge").apply {
            tooltip = Tooltip("Floor 1 in the selected area becomes the ground people walk on (a bridge deck); floor 0 is what's underneath. " +
                    "Build the deck on floor 1 (paint it and put objects there). Ctrl+Z undoes it.")
            setOnAction { bridgeSelection(true) }
        }
        val btnUnbridge = Button("Remove bridge").apply {
            tooltip = Tooltip("Clears the bridge flag in the selected area: floor 1 there is an upper floor again.")
            setOnAction { bridgeSelection(false) }
        }
        val btnFillUnder = Button("Underlay").apply {
            setOnAction { val id = gadToolsModel.underlayId.get(); fill("Filled underlay", false, false) { it.underlayId = (id + 1) } }
        }
        val btnFillOver = Button("Overlay").apply {
            setOnAction {
                val id = gadToolsModel.overlayId.get()
                val shape = gadToolsModel.overlayShape.get()
                val rot = gadToolsModel.overlayRotation.get() and 3
                fill("Filled overlay", false, false) {
                    if (id < 0) { it.attrOpcode = 0; it.overlayId = 0; it.overlayPath = 0; it.overlayRotation = 0 }
                    else { it.attrOpcode = 2 + shape * 4 + rot; it.overlayId = (id + 1); it.overlayPath = shape.toByte(); it.overlayRotation = rot.toByte() }
                }
            }
        }
        val btnAddFlags = Button("Add flags").apply {
            setOnAction { fillFlags("Added flags", true) }
        }
        val btnRemoveFlags = Button("Remove flags").apply {
            setOnAction { fillFlags("Removed flags", false) }
        }
        val btnFillHeight = Button("Exact height").apply {
            setOnAction { val h = gadToolsModel.exactHeight.get(); fill("Set height $h", true, true) { it.setGroundHeight(-h * 8) } }
        }

        val prefabDir = { File(GadTools.editorDir, "prefabs").apply { mkdirs() } }
        val btnExport = Button("Export prefab...").apply {
            setOnAction {
                val sel = needSelection() ?: return@setOnAction
                val file = FileChooser().apply {
                    title = "Save prefab"
                    initialDirectory = prefabDir()
                    initialFileName = "prefab-${sel.x1}-${sel.y1}.json"
                    extensionFilters.add(FileChooser.ExtensionFilter("Prefab", "*.json"))
                }.showSaveDialog(this.scene?.window) ?: return@setOnAction
                gadToolsModel.actions.add {
                    val prefab = tileOps.copy(sel)
                    try {
                        file.writeText(prefabGson.toJson(prefab))
                        gadToolsModel.message = "Saved ${sel.width} x ${sel.height} prefab to ${file.name}."
                    } catch (e: Exception) {
                        gadToolsModel.message = "Could not save prefab: ${e.message}"
                    }
                    null
                }
            }
        }
        val btnImport = Button("Import prefab...").apply {
            setOnAction {
                val file = FileChooser().apply {
                    title = "Open prefab"
                    initialDirectory = prefabDir()
                    extensionFilters.add(FileChooser.ExtensionFilter("Prefab", "*.json"))
                }.showOpenDialog(this.scene?.window) ?: return@setOnAction
                val prefab = try { prefabGson.fromJson(file.readText(), Prefab::class.java) } catch (e: Exception) { null }
                if (prefab?.tiles == null || prefab.objects == null) return@setOnAction status("${file.name} is not a prefab file.")
                gadToolsModel.clipboard = prefab
                status("Loaded ${file.name} (${prefab.width} x ${prefab.height}). Hover a tile and press Ctrl+V to place it.")
            }
        }

        val note = Label("Select area tool: drag a box on the map. Copy takes every plane of the box, objects included. " +
                "Ctrl+V pastes with the hovered tile as the south-west corner; the ticked parts are what gets pasted (and cleared by Clear area). " +
                "Pasting objects replaces the objects already in the target box. Fill uses the Paint tiles settings on the chosen plane. " +
                "Prefabs are saved in the prefabs folder next to your cache.").apply { isWrapText = true }
        btnExportPrefabRef = btnExport
        btnImportPrefabRef = btnImport
        return VBox(6.0, Button("\u25A6 Area picker (GE, Edgeville...)").apply {
                styleClass.add("accent"); maxWidth = Double.MAX_VALUE
                setOnAction { openAreaPicker() }
            }, Button("\u29C9 Other map side by side").apply {
                maxWidth = Double.MAX_VALUE
                tooltip = Tooltip("Opens another cache's map in a small window. Drag a box there, then Ctrl+V here.")
                setOnAction { openSideMap() }
            }, lblSel, lblClip, row(*parts.toTypedArray()).apply { isFillHeight = false },
            row(btnCopy, btnPastePin),
            row(Label("Copy from"), ComboBox<String>().apply {
                val choices = listOf("Auto (ground = whole building)", "The floor I'm on", "All floors", "Floor 0", "Floor 1", "Floor 2", "Floor 3")
                val values = listOf(-2, -3, -1, 0, 1, 2, 3)
                items.setAll(choices)
                value = choices[values.indexOf(gadToolsModel.copyFloor.get()).coerceAtLeast(0)]
                tooltip = Tooltip("Pick one floor to copy it on its own. Then go to any floor (the arrows at the top) " +
                        "and Ctrl+V pastes it onto that floor. All floors keeps a building's floors as they are.")
                valueProperty().addListener { _, _, v -> gadToolsModel.copyFloor.set(values[choices.indexOf(v).coerceAtLeast(0)]) }
            }).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
            row(btnClear, btnDeselect),
            Separator(), header("Move between floors"), buildFloorMover(chkBridge), btnToGround, chkBridge,
            row(Button("Delete roofs").apply {
                tooltip = Tooltip("Deletes every roof piece (object types 12-21) in the selected area, on all floors. Ctrl+Z undoes it.")
                setOnAction { deleteRoofs(false) }
            }, Button("Delete roofs (whole map)").apply {
                tooltip = Tooltip("Deletes every roof piece in all loaded regions. Ctrl+Z undoes it.")
                setOnAction { deleteRoofs(true) }
            }),
            Button("Fill dead tiles").apply {
                tooltip = Tooltip("Tiles with no ground colour (holes) in the selected area get the ground colour around them. Ctrl+Z undoes it.")
                setOnAction { fillSelectionHoles() }
            },
            Label("Fill selection with:"), row(btnFillUnder, btnFillOver, btnFillHeight), row(btnAddFlags, btnRemoveFlags), row(btnBridge, btnUnbridge),
            row(btnExport, btnImport), note)
    }

    // ---------- find & replace ----------

    private fun buildRemap(): Node {
        val cmbKind = ComboBox<TileOps.RemapKind>().apply {
            items.setAll(*TileOps.RemapKind.values())
            value = TileOps.RemapKind.OBJECT
            setCellFactory { object : ListCell<TileOps.RemapKind>() {
                override fun updateItem(item: TileOps.RemapKind?, empty: Boolean) {
                    super.updateItem(item, empty); text = item?.label
                }
            } }
            buttonCell = cellFactory.call(null)
        }
        val txtFrom = TextField().apply { prefColumnCount = 6 }
        val txtTo = TextField().apply { prefColumnCount = 6 }
        val chkSel = CheckBox("Only inside the selection")
        val grid = GridPane().apply { hgap = 6.0; vgap = 6.0 }
        grid.addRow(0, Label("Replace"), cmbKind)
        grid.addRow(1, Label("From id"), txtFrom, Label("To id"), txtTo)

        fun scope(): Pair<List<Int>, models.TileRect?>? {
            if (chkSel.isSelected) {
                val sel = needSelection() ?: return null
                val ids = ArrayList<Int>()
                for (x in (sel.x1 shr 6)..(sel.x2 shr 6)) for (y in (sel.y1 shr 6)..(sel.y2 shr 6)) ids.add((x shl 8) or y)
                return Pair(ids, sel)
            }
            val ids = GadTools.loadedRegionIds(this@GadToolsController.scene)
            if (ids.isEmpty()) { status("Load a map area first."); return null }
            return Pair(ids, null)
        }
        val btnCount = Button("Count").apply {
            setOnAction {
                val from = txtFrom.text.trim().toIntOrNull() ?: return@setOnAction status("Enter the id to look for.")
                val (ids, rect) = scope() ?: return@setOnAction
                val kind = cmbKind.value
                gadToolsModel.actions.add {
                    val n = tileOps.count(kind, from, ids, rect)
                    gadToolsModel.message = "${kind.label} $from is used $n times ${if (rect != null) "in the selection" else "in the loaded regions"}."
                    null
                }
            }
        }
        val btnReplace = Button("Replace all").apply {
            setOnAction {
                val from = txtFrom.text.trim().toIntOrNull() ?: return@setOnAction status("Enter the id to replace.")
                val to = txtTo.text.trim().toIntOrNull() ?: return@setOnAction status("Enter the new id.")
                if (to < 0) return@setOnAction status("The new id can't be negative.")
                val kind = cmbKind.value
                if (kind != TileOps.RemapKind.OBJECT && to > 254) return@setOnAction status("Underlay and overlay ids go up to 254 in this map format.")
                val (ids, rect) = scope() ?: return@setOnAction
                gadToolsModel.actions.add {
                    val e = tileOps.remap(kind, from, to, ids, rect)
                    gadToolsModel.message = if (e == null) "No ${kind.label.toLowerCase()} $from found." else "Replaced ${kind.label.toLowerCase()} $from with $to. Ctrl+Z undoes it."
                    e
                }
            }
        }
        val note = Label("Swaps one object, underlay or overlay for another across the loaded regions (or just the selection). " +
                "Results show in the Terrain section. Handy for re-theming an area or fixing an id after a cache update.").apply { isWrapText = true }
        return VBox(6.0, grid, chkSel, row(btnCount, btnReplace), note)
    }

    // ---------- tile + go to ----------

    private fun buildTile(): Node {
        val btnTele = Button("Copy ::tele").apply { setOnAction { copyTele() } }
        val btnCoords = Button("Copy x, y, z").apply {
            setOnAction {
                val c = coords() ?: return@setOnAction
                copy("${c.first}, ${c.second}, ${c.third}")
            }
        }
        return VBox(6.0, lblHover, lblPinned, row(btnTele, btnCoords))
    }

    private fun buildGoTo(): Node {
        val grid = GridPane().apply { hgap = 6.0; vgap = 6.0 }
        grid.addRow(0, Label("X"), txtX, Label("Y"), txtY)
        grid.addRow(1, Label("Plane"), txtZ, Label("Regions across"), txtRadius)
        // allow pasting "3222, 3218, 0" straight into X
        txtX.textProperty().addListener { _, _, v ->
            val parts = v.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
            if (parts.size >= 2 && parts.all { it.toIntOrNull() != null }) {
                txtX.text = parts[0]; txtY.text = parts[1]
                if (parts.size >= 3) txtZ.text = parts[2]
            }
        }
        val btnGo = Button("Go").apply { isDefaultButton = false; setOnAction { goToFields() } }
        txtY.setOnAction { goToFields() }
        return VBox(6.0, grid, row(btnGo, Button("Copy ::tele").apply { setOnAction { copyTele() } }))
    }

    private fun coords(): Triple<Int, Int, Int>? {
        val x = txtX.text.trim().toIntOrNull()
        val y = txtY.text.trim().toIntOrNull()
        val z = txtZ.text.trim().toIntOrNull() ?: 0
        if (x == null || y == null) {
            status("Enter X and Y first (or pin a tile with P).")
            return null
        }
        return Triple(x, y, z)
    }

    private fun goToFields() {
        val c = coords() ?: return
        goTo(c.first, c.second, c.third)
    }

    private fun goTo(x: Int, y: Int, z: Int) {
        val region = GadTools.regionOf(x, y)
        if (region < 4647 || region > 15522) {
            status("$x, $y is outside the map (region $region).")
            return
        }
        val radius = (txtRadius.text.trim().toIntOrNull() ?: controllers.worldRenderer.Renderer.mapSize()).coerceIn(1, 9)
        txtX.text = x.toString(); txtY.text = y.toString(); txtZ.text = z.toString()
        status("Loading region $region around $x, $y...")
        GadTools.goToAsync(scene, camera, x, y, radius) { e ->
            status(if (e == null) "Loaded region $region around $x, $y." else "Couldn't load region $region: ${e.message}")
        }
    }

    private fun copyTele() {
        val c = coords() ?: return
        copy(GadTools.fill(GadTools.templates().tele!!, mapOf("x" to c.first, "y" to c.second, "z" to c.third)))
    }

    // ---------- bookmarks ----------

    private fun refreshBookmarks() {
        lstBookmarks.items.setAll(bookmarks.map { "${it.name}  (${it.x}, ${it.y}, ${it.z})" })
    }

    private fun buildBookmarks(): Node {
        refreshBookmarks()
        lstBookmarks.prefHeight = 150.0
        lstBookmarks.setOnMouseClicked { if (it.clickCount == 2) goToSelected() }
        val btnGo = Button("Go").apply { setOnAction { goToSelected() } }
        val btnAdd = Button("Add current X/Y").apply {
            setOnAction {
                val c = coords() ?: return@setOnAction
                val dialog = TextInputDialog().apply { title = "New bookmark"; headerText = "Name for ${c.first}, ${c.second}, ${c.third}" }
                val name = dialog.showAndWait().orElse(null)?.trim()
                if (name.isNullOrEmpty()) return@setOnAction
                bookmarks.add(Bookmark(name, c.first, c.second, c.third))
                GadTools.saveBookmarks(bookmarks)
                refreshBookmarks()
                status("Bookmarked $name.")
            }
        }
        val btnDel = Button("Delete").apply {
            setOnAction {
                val i = lstBookmarks.selectionModel.selectedIndex
                if (i < 0) return@setOnAction
                val removed = bookmarks.removeAt(i)
                GadTools.saveBookmarks(bookmarks)
                refreshBookmarks()
                status("Deleted ${removed.name}.")
            }
        }
        return VBox(6.0, lstBookmarks, row(btnGo, btnAdd, btnDel))
    }

    private fun goToSelected() {
        val i = lstBookmarks.selectionModel.selectedIndex
        if (i < 0) return
        val b = bookmarks[i]
        goTo(b.x, b.y, b.z)
    }

    // ---------- spawn lines ----------

    private fun buildSpawns(): Node {
        cmbDir.items.setAll("SOUTH", "SOUTH_WEST", "WEST", "NORTH_WEST", "NORTH", "NORTH_EAST", "EAST", "SOUTH_EAST")
        cmbDir.value = "SOUTH"
        val btnTele = Button("Teleports, home, teleporting objects...").apply {
            setOnAction { TeleportWindow.show(gadToolsModel, configuration, { x, y, z -> goTo(x, y, z) }, ::log) }
        }
        val btnNpcs = Button("NPCs: place, drag, turn, remove...").apply {
            styleClass.add("accent")
            setOnAction { NpcWindow.show(gadToolsModel, configuration, ::log) }
        }
        val npcGrid = GridPane().apply { hgap = 6.0; vgap = 6.0 }
        npcGrid.addRow(0, Label("NPC id"), txtNpcId, Label("Walk"), txtWalk)
        npcGrid.addRow(1, Label("Facing"), cmbDir)
        val btnNpc = Button("Copy NPC spawn").apply {
            setOnAction {
                val c = coords() ?: return@setOnAction
                val id = txtNpcId.text.trim().toIntOrNull() ?: return@setOnAction status("Enter an NPC id.")
                copy(GadTools.fill(GadTools.templates().npcSpawn!!, mapOf(
                    "id" to id, "x" to c.first, "y" to c.second, "z" to c.third,
                    "dir" to cmbDir.value, "radius" to (txtWalk.text.trim().toIntOrNull() ?: 0))))
            }
        }

        val objGrid = GridPane().apply { hgap = 6.0; vgap = 6.0 }
        objGrid.addRow(0, Label("Object id"), txtObjId, Label("Type"), txtObjType)
        objGrid.addRow(1, Label("Rotation"), txtObjRot)
        val btnHeld = Button("Use held object").apply {
            setOnAction {
                val held = objectsModel.heldObject.get() ?: return@setOnAction status("Pick an object in the Object Picker first.")
                txtObjId.text = held.objectDefinition.id.toString()
                txtObjType.text = held.type.toString()
                txtObjRot.text = (held.orientation and 3).toString()
            }
        }
        val btnObj = Button("Copy object spawn").apply {
            setOnAction {
                val c = coords() ?: return@setOnAction
                val id = txtObjId.text.trim().toIntOrNull() ?: return@setOnAction status("Enter an object id.")
                copy(GadTools.fill(GadTools.templates().objectSpawn!!, mapOf(
                    "id" to id, "x" to c.first, "y" to c.second, "z" to c.third,
                    "type" to (txtObjType.text.trim().toIntOrNull() ?: 10),
                    "rot" to (txtObjRot.text.trim().toIntOrNull() ?: 0))))
            }
        }
        val note = Label("Uses the X/Y/Plane above. Line formats live in gadscape-tools.json in your editor folder.").apply { isWrapText = true }
        return VBox(6.0, btnNpcs, btnTele, npcGrid, btnNpc, Separator(), objGrid, row(btnHeld, btnObj), note)
    }

    // ---------- backups ----------

    private fun buildBackups(): Node {
        val btnBackup = Button("Back up loaded regions").apply {
            setOnAction {
                val ids = GadTools.loadedRegionIds(this@GadToolsController.scene)
                val dir = try { GadTools.backupRegions(ids, xteaProvider.get()) } catch (e: Exception) {
                    e.printStackTrace(); null
                }
                status(if (dir != null) "Backed up ${ids.size} regions to ${dir.name}." else "Backup failed, see console.")
            }
        }
        val btnRestore = Button("Restore...").apply {
            setOnAction {
                val backups = GadTools.listBackups()
                if (backups.isEmpty()) return@setOnAction status("No backups yet.")
                val dialog = ChoiceDialog(backups.first().name, backups.map { it.name }).apply {
                    title = "Restore backup"
                    headerText = "Writes these regions back into cache-out (the exported cache)."
                }
                val pick = dialog.showAndWait().orElse(null) ?: return@setOnAction
                val dir = backups.first { it.name == pick }
                val n = try { GadTools.restoreBackup(dir, xteaProvider.get()) } catch (e: Exception) {
                    e.printStackTrace(); -1
                }
                status(if (n >= 0) "Restored $n files from $pick into cache-out." else "Restore failed, see console.")
            }
        }
        val btnOpen = Button("Open folder").apply {
            setOnAction {
                GadTools.backupsDir.mkdirs()
                try { Desktop.getDesktop().open(GadTools.backupsDir) } catch (e: Exception) { status(GadTools.backupsDir.path) }
            }
        }
        val note = Label("Export also makes an automatic backup first.").apply { isWrapText = true }
        btnBackupRef = btnBackup
        return VBox(6.0, row(btnBackup, btnRestore, btnOpen), note)
    }

    // ---------- deploy ----------

    private val txtLog = TextArea().apply { isEditable = false; prefRowCount = 12; isWrapText = true }
    private val deployButtons = ArrayList<Button>()

    private fun log(line: String) = Platform.runLater { txtLog.appendText(line + "\n") }
    /** Adds a line to the Tools log (for the editor tabs). */
    fun logLine(line: String) = log(line)

    private fun runJob(name: String, job: () -> Unit) {
        deployButtons.forEach { it.isDisable = true }
        txtLog.clear()
        Thread({
            try { job() } catch (e: Exception) { log("$name failed: ${e.message}"); e.printStackTrace() }
            Platform.runLater { deployButtons.forEach { it.isDisable = false } }
        }, "gadscape-$name").apply { isDaemon = true }.start()
    }

    private fun confirm(header: String, text: String): Boolean {
        val a = Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL)
        a.title = "GadScape"
        a.headerText = header
        return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }

    private fun buildDeploy(): Node {
        val cfg = GadTools.templates()
        val lblTarget = Label("${cfg.deployUser}@${cfg.deployHost}:${cfg.deployRemoteCache}  (service: ${cfg.deployService})").apply { isWrapText = true }

        val btnTest = Button("Test connection").apply {
            setOnAction { runJob("test") { Deployer(::log).test() } }
        }
        val btnExportDeploy = Button("Export + deploy").apply {
            setOnAction {
                if (!confirm("Export the loaded regions and deploy?",
                        "This restarts the ${cfg.deployService} service, so anyone online gets disconnected. The current live cache is backed up on the server first.")) return@setOnAction
                val ids = GadTools.loadedRegionIds(this@GadToolsController.scene)
                val n = try { GadTools.exportLoaded(this@GadToolsController.scene, locationsLoader, regionLoader, xteaProvider.get()) } catch (e: Exception) {
                    e.printStackTrace(); -1
                }
                if (n < 0) return@setOnAction status("Export failed, see console. Nothing was deployed.")
                status("Exported $n of ${ids.size} regions.")
                // NPC spawns are server code, not map: save them, and send them in the same deploy if you want
                if (utils.NpcSpawns.dirty) try { log(utils.NpcSpawns.save()) } catch (e: Exception) { log("NPC spawns not saved: ${e.message}") }
                val dir = NpcWindow.serverDir(configuration)
                val pending = Deployer(::log).pendingShops()
                if (dir != null && pending.isNotEmpty() && confirm("Send your NPC spawns / shops too?",
                        "${pending.size} edited spawn or shop file(s) are waiting: ${pending.joinToString(", ") { it.substringAfterLast('/') }}.\n" +
                        "They're server code, so the server is compiled on this PC and just those classes are swapped in. OK = map + these, Cancel = map only.")) {
                    runJob("deploy-all") {
                        val d = Deployer(::log)
                        val classes = d.buildShopClasses(dir) ?: return@runJob
                        d.deployBundle(true, classes, dir)
                    }
                    return@setOnAction
                }
                runJob("deploy") { Deployer(::log).deploy() }
            }
        }
        val btnDeploy = Button("Deploy cache-out only").apply {
            setOnAction {
                if (!confirm("Deploy cache-out as it is now?",
                        "This restarts the ${cfg.deployService} service. The current live cache is backed up on the server first.")) return@setOnAction
                runJob("deploy") { Deployer(::log).deploy() }
            }
        }
        val btnRollback = Button("Rollback").apply {
            setOnAction {
                if (!confirm("Roll the server back?",
                        "Restores the cache from before the last deploy and restarts the ${cfg.deployService} service.")) return@setOnAction
                runJob("rollback") { Deployer(::log).rollback() }
            }
        }
        val btnSettings = Button("Settings file").apply {
            setOnAction {
                GadTools.templates() // make sure the file exists
                try { Desktop.getDesktop().open(Deployer.settingsFile()) } catch (e: Exception) { status(Deployer.settingsFile().path) }
            }
        }
        fun serverDir(): java.io.File? = utils.ServerSource.dir(configuration)
            ?: java.io.File(System.getProperty("user.home"), "Downloads/RSPS-NEAR-REALITY-main/RSPS-NEAR-REALITY-main/near-reality-server-main").takeIf { it.isDirectory }
        val btnShops = Button("Deploy shops + NPC spawns").apply {
            tooltip = Tooltip("Builds the server from your edited shop and spawn files, swaps just those classes in the live jar and restarts. The map is not touched.")
            setOnAction {
                val dir = serverDir() ?: return@setOnAction status("Pick the server folder in Tools > Shops first.")
                if (!confirm("Build the server and deploy your shops?",
                        "Compiles the shops you edited, swaps just those shops inside the live server.jar and restarts ${cfg.deployService}. " +
                        "Nothing else on the server changes. The live jar is backed up first; Rollback restores it.")) return@setOnAction
                runJob("deploy-shops") {
                    val d = Deployer(::log)
                    val classes = d.buildShopClasses(dir) ?: return@runJob
                    d.deployBundle(false, classes, dir)
                }
            }
        }
        val btnAll = Button("Save + deploy map + shops").apply {
            styleClass.add("accent")
            tooltip = Tooltip("Saves the loaded map, builds the server with your shops, uploads both and restarts once.")
            setOnAction {
                val dir = serverDir() ?: return@setOnAction status("Pick the server folder in Tools > Shops first.")
                if (!confirm("Save the map, build the server, deploy both?",
                        "Saves your map, builds ${dir.name} on this PC, uploads the map and the server jar, and restarts ${cfg.deployService} once. " +
                        "Both are backed up on the server first; Rollback restores them.")) return@setOnAction
                val n = try { GadTools.exportLoaded(this@GadToolsController.scene, locationsLoader, regionLoader, xteaProvider.get()) } catch (e: Exception) { e.printStackTrace(); -1 }
                if (n < 0) return@setOnAction status("Saving the map failed, see console. Nothing was deployed.")
                status("Saved $n regions.")
                runJob("deploy-all") {
                    val d = Deployer(::log)
                    val classes = if (d.pendingShops().isEmpty()) null else d.buildShopClasses(dir) ?: return@runJob
                    d.deployBundle(true, classes, dir)
                }
            }
        }
        deployButtons.addAll(listOf(btnTest, btnExportDeploy, btnDeploy, btnRollback, btnShops, btnAll))
        val note = Label("Uses SSH key login. Change host, user, cache path or service in the settings file, then reopen the editor.").apply { isWrapText = true }
        return VBox(6.0, lblTarget, row(btnTest, btnSettings),
            Label("Map"), row(btnExportDeploy, btnDeploy),
            Label("Shops (server code)"), row(btnShops, btnAll),
            btnRollback, note)
    }

    // ---------- package ----------

    private fun buildPackage(): Node {
        val cmbSource = ComboBox<utils.Packager.MapSource>().apply {
            items.setAll(*utils.Packager.MapSource.values())
            value = utils.Packager.MapSource.EDITED
            setCellFactory { object : ListCell<utils.Packager.MapSource>() {
                override fun updateItem(item: utils.Packager.MapSource?, empty: Boolean) {
                    super.updateItem(item, empty); text = item?.label
                }
            } }
            buttonCell = cellFactory.call(null)
            maxWidth = Double.MAX_VALUE
        }
        val chkExport = CheckBox("Export loaded regions first").apply { isSelected = true }
        val chkJava = CheckBox("Include Java (+~300 MB, runs on any Windows PC)").apply { isSelected = true }
        val chkZip = CheckBox("Make a .zip").apply { isSelected = true }
        val btnBuild = Button("Build package...").apply {
            setOnAction {
                val chooser = javafx.stage.DirectoryChooser().apply {
                    title = "Where should the package go?"
                    val def = java.io.File(GadTools.editorDir, "packages").apply { mkdirs() }
                    initialDirectory = def
                }
                val outRoot = chooser.showDialog(scene?.window) ?: return@setOnAction
                val source = cmbSource.value
                if (chkExport.isSelected && source == utils.Packager.MapSource.EDITED) {
                    val sc = this@GadToolsController.scene
                    try {
                        val n = GadTools.exportLoaded(sc, locationsLoader, regionLoader, xteaProvider.get())
                        status("Exported $n regions.")
                    } catch (e: Exception) {
                        e.printStackTrace(); return@setOnAction status("Export failed: ${e.message}")
                    }
                }
                val java = chkJava.isSelected
                val zip = chkZip.isSelected
                runJob("package") {
                    val out = utils.Packager(::log).build(outRoot, source, java, zip)
                    if (out != null) Platform.runLater {
                        status("Package ready: ${out.name}")
                        try { Desktop.getDesktop().open(out.parentFile) } catch (e: Exception) {}
                    }
                }
            }
        }
        val note = Label("Makes a portable copy of this editor (with all GadScape tools) plus the map, " +
                "with a Start .bat. Progress shows in the Log section.").apply { isWrapText = true }
        // ---- publish an update that everyone's copy downloads by itself ----
        val txtNotes = TextField().apply { promptText = "What changed (shows on the download page)" }
        val chkMap = CheckBox("Include my saved map (friends get it next time they open)").apply { isSelected = true }
        val chkFull = CheckBox("Also upload the full download (the app with icon, Java and map) - tick the first time")
        val btnPublish = Button("Publish update for friends").apply {
            styleClass.add("accent")
            tooltip = Tooltip("Uploads THIS running version to a public downloads repo on your GitHub. " +
                    "Friends' copies update themselves the next time they start. Your source code stays private.")
            setOnAction {
                if (!confirm("Publish update", "Publish the editor you're running now as an update for everyone?")) return@setOnAction
                val full = chkFull.isSelected
                val withMap = chkMap.isSelected
                val notes = txtNotes.text.orEmpty()
                // save first, so what you see in the editor is what gets sent
                if (withMap || full) {
                    val n = try { GadTools.exportLoaded(this@GadToolsController.scene, locationsLoader, regionLoader, xteaProvider.get()) }
                        catch (e: Exception) { status("Saving before publish failed: ${e.message}"); return@setOnAction }
                    status("Saved $n regions before publishing.")
                }
                runJob("publish") {
                    val dir = java.io.File(GadTools.editorDir, "publish/" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date()))
                    utils.Packager(::log).publish(dir, full, notes, withMap)
                }
            }
        }
        val notePub = Label("Friends unzip the full download once and double-click \"Uzis Map Editor.exe\". Every time they open it, " +
                "it fetches your newest editor and map by itself. Save your map first (Ctrl+S): the saved map is what gets sent.")
            .apply { isWrapText = true; styleClass.add("subtle") }
        return VBox(6.0, Label("Map to include:"), cmbSource, chkExport, chkJava, chkZip, btnBuild, note,
            Separator(), header("Publish update for friends"), txtNotes, chkMap, chkFull, btnPublish, notePub)
    }

    // ---------- helpers ----------

    private fun copy(text: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
        status("Copied: $text")
    }

    private fun status(text: String) {
        lblStatus.text = text
    }
}
