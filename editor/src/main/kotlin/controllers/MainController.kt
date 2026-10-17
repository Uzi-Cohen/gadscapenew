package controllers

import JfxApplication.Companion.injector
import cache.loaders.LocationsLoader
import cache.loaders.RegionLoader
import com.displee.cache.CacheLibrary
import com.google.inject.Inject
import controllers.worldRenderer.WorldRendererController
import javafx.animation.AnimationTimer
import javafx.beans.binding.BooleanBinding
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.stage.Stage
import javafx.util.Callback
import models.DebugModel
import models.scene.Scene
import org.dockfx.DockNode
import org.dockfx.DockPane
import org.dockfx.DockPos
import java.io.File

class MainController @Inject constructor(
    private val debugModel: DebugModel,
    private val scene: Scene,
    private val locationsLoader: LocationsLoader,
    private val regionLoader: RegionLoader,
    private val xteaProvider: com.google.inject.Provider<cache.XteaManager>
) {
    private var unsavedFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    private var saveAction: (() -> Unit)? = null
    /** True when there are edits that are not saved yet. */
    fun hasUnsaved() = unsavedFlag?.get() == true || utils.NpcSpawns.dirty
    /** Save the loaded map now (same as Ctrl+S). */
    fun save() { saveAction?.invoke() }

    private fun saveNow(auto: Boolean) {
        try {
            val toolsModel = injector.getInstance(models.GadToolsModel::class.java)
            val n = utils.GadTools.exportLoaded(scene, locationsLoader, regionLoader, xteaProvider.get())
            // NPC spawns live in the server's files, saved along with the map
            if (utils.NpcSpawns.dirty) try { println(utils.NpcSpawns.save()) } catch (e: Exception) { println("NPC spawns not saved: ${e.message}") }
            toolsModel.unsaved.set(false)
            println((if (auto) "Autosaved" else "Saved") + " $n regions to " + utils.GadTools.exportCacheDir.path)
            toolsModel.message = if (auto) "Autosaved ($n regions)." else "Saved $n regions (not sent to the server)."
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @FXML
    lateinit var menuChangeRegion: MenuItem
    @FXML
    lateinit var menuAbout: MenuItem

    @FXML
    lateinit var menuBar: javafx.scene.control.MenuBar

    @FXML
    lateinit var dockPane: DockPane

    @FXML
    lateinit var lblFps: Label

    @FXML
    lateinit var lblStatus: Label

    @FXML
    lateinit var btnTest: Button

    @FXML
    lateinit var btnPlace: Button

    @FXML
    lateinit var btnDelete: Button

    private lateinit var worldRendererControllerController: WorldRendererController

    @FXML
    fun initialize() {
        // Topdown view removed: it never drew anything and ate half the window.



        val worldLoader = FXMLLoader()
        worldLoader.controllerFactory = Callback { type: Class<*>? ->
            injector.getInstance(type)
        }

        worldLoader.location = javaClass.getResource("/views/widgets/world-renderer-widget.fxml")
        val worldRendererWidget = worldLoader.load<Parent>()
        worldRendererControllerController = worldLoader.getController()
        val worldRendererNode = DockNode(worldRendererWidget)
        worldRendererNode.setPrefSize(1000.0, 700.0)
        worldRendererNode.dock(dockPane, DockPos.LEFT)
        worldRendererNode.dockTitleBar.isVisible = false


        val gadTools = injector.getInstance(GadToolsController::class.java)
        val gadToolsNode = DockNode(gadTools.build())
        // View / Tiles / Tools menus go between World and Help
        menuBar.menus.addAll(1, gadTools.menus())
        menuBar.menus.add(1, utils.Branding.themeMenu()) // right after World
        menuBar.menus.last().items.add(0, gadTools.shortcutsMenuItem())
        menuBar.menus.last().items.add(1, gadTools.logsMenuItem())
        gadToolsNode.title = "Tools"
        gadToolsNode.setPrefSize(370.0, 700.0)
        gadToolsNode.dock(dockPane, DockPos.RIGHT)

        // objects get their own full-height column on the far right, the inspector sits under them
        val objectPickerLoader = FXMLLoader()
        objectPickerLoader.controllerFactory = Callback { type: Class<*>? ->
            injector.getInstance(type)
        }
        objectPickerLoader.location = javaClass.getResource("/views/object-picker.fxml")
        val objectPickerNode = DockNode(objectPickerLoader.load<Parent>())
        val objectPickerController: ObjectPickerController = objectPickerLoader.getController()
        objectPickerController.setRenderer(worldRendererControllerController.renderer)
        objectPickerNode.title = "Object Picker"
        objectPickerNode.setPrefSize(360.0, 700.0)



        val inspectorLoader = FXMLLoader()
        inspectorLoader.controllerFactory = Callback { type: Class<*>? ->
            injector.getInstance(type)
        }
        inspectorLoader.location = javaClass.getResource("/views/inspector.fxml")
        val inspectorNode = DockNode(inspectorLoader.load<Parent>())
        inspectorNode.title = "Inspector"
        inspectorNode.setPrefSize(360.0, 160.0)
        inspectorNode.dockedProperty().not().and(inspectorNode.floatingProperty().not()).addListener { _, _, newValue ->
            if (newValue) {
                (inspectorLoader.getController() as InspectorController).onClose()
            }
        }


        objectPickerNode.dock(dockPane, DockPos.RIGHT, gadToolsNode)
        inspectorNode.dock(dockPane, DockPos.BOTTOM, objectPickerNode)


        DockPane.initializeDefaultUserAgentStylesheet()

        val toolsModel = injector.getInstance(models.GadToolsModel::class.java)
        btnTest.text = "Save map"
        btnTest.tooltip = javafx.scene.control.Tooltip("Save your edits (Ctrl+S). Stays on this PC: nothing is sent to the server. " +
                "Deploy sends it; Publish shares it with friends.")
        btnTest.setOnAction { saveNow(false) }
        object : javafx.animation.AnimationTimer() {
            var lastAuto = System.nanoTime()
            override fun handle(now: Long) {
                if (toolsModel.saveRequested.getAndSet(false)) btnTest.fire()
                // autosave every 2 minutes when something changed
                if (now - lastAuto > 120_000_000_000L) {
                    lastAuto = now
                    if (toolsModel.unsaved.get()) saveNow(true)
                }
            }
        }.start()
        unsavedFlag = toolsModel.unsaved
        saveAction = { saveNow(false) }

        menuChangeRegion.setOnAction {
            val regionChangeLoader = FXMLLoader()
            regionChangeLoader.controllerFactory = Callback { type: Class<*>? ->
                injector.getInstance(type)
            }
            regionChangeLoader.location = javaClass.getResource("/views/region-chooser.fxml")
            val regionChangeRoot = regionChangeLoader.load<Parent>()
            val stage = Stage()
            stage.scene = javafx.scene.Scene(regionChangeRoot)
            utils.Branding.apply(stage, "change region")
            stage.show()
        }

        menuAbout.setOnAction {

            val aboutLoader = FXMLLoader()
            aboutLoader.controllerFactory = Callback { type: Class<*>? ->
                injector.getInstance(type)
            }
            aboutLoader.location = javaClass.getResource("/views/about.fxml")
            val aboutRoot = aboutLoader.load<Parent>()
            val stage = Stage()
            stage.scene = javafx.scene.Scene(aboutRoot)
            utils.Branding.apply(stage, "about")
            stage.show()
        }

        object : AnimationTimer() {
            override fun handle(now: Long) {
                lblFps.text = "FPS: ${debugModel.fps.get()} - DT: ${debugModel.deltaTime.get()}"
            }
        }.start()
    }

    fun forceRefresh() {
        worldRendererControllerController.forceRefresh()
    }
}