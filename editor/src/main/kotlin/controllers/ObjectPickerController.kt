package controllers

import cache.LocationType
import cache.definitions.ModelDefinition
import cache.definitions.ObjectDefinition
import cache.definitions.converters.ObjectToModelConverter
import cache.loaders.ModelLoader
import cache.loaders.ObjectLoader
import cache.loaders.SpriteLoader
import cache.loaders.TextureLoader
import controllers.worldRenderer.Renderer
import javafx.beans.property.DoubleProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.transformation.FilteredList
import javafx.event.EventHandler
import javafx.fxml.FXML
import javafx.scene.*
import javafx.scene.control.*
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.shape.MeshView
import javafx.scene.transform.Rotate
import models.ObjectsModel
import models.scene.SceneObject
import utils.JavaFxHelpers
import javax.inject.Inject

class ObjectPickerController @Inject constructor(
    private val objectLoader: ObjectLoader,
    private val textureLoader: TextureLoader,
    private val spriteLoader: SpriteLoader,
    private val objectToModelConverter: ObjectToModelConverter,
    private val objectsModel: ObjectsModel,
    private val modelLoader: ModelLoader
) {

    private lateinit var renderer: Renderer
    fun setRenderer(renderer: Renderer) {
        this.renderer = renderer
    }

    @FXML
    private lateinit var stackPane: StackPane

    @FXML
    private lateinit var listView: ListView<ObjectListItem>
    private var entries: ObservableList<ObjectListItem> = FXCollections.observableArrayList()

    @FXML
    private lateinit var searchBox: TextField
    private var selectedObject: ObjectDefinition? = null
    private var selectedModelType: LocationType? = null

    @FXML
    private lateinit var paneTypeList: javafx.scene.layout.VBox

    @FXML
    private lateinit var typeFilter: ComboBox<String>

    @FXML
    private lateinit var showUnnamed: CheckBox

    @FXML
    private lateinit var lblCount: Label

    // ---- thumbnails: made a few per frame for the rows on screen, kept in a small cache ----
    private val thumbSize = 64.0
    private val thumbCache = object : LinkedHashMap<Int, javafx.scene.image.Image?>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, javafx.scene.image.Image?>?) = size > 1500
    }
    private val thumbQueue = java.util.ArrayDeque<Pair<Int, javafx.scene.image.ImageView>>()

    private fun requestThumb(id: Int, view: javafx.scene.image.ImageView) {
        view.userData = id
        if (thumbCache.containsKey(id)) { view.image = thumbCache[id]; return }
        view.image = null
        thumbQueue.addLast(Pair(id, view))
    }

    private fun makeThumb(id: Int): javafx.scene.image.Image? = try {
        val def = objectLoader.get(id)
        val types = def?.modelTypes
        val type = when {
            types == null -> 10
            types.contains(10) -> 10
            else -> types[0]
        }
        val md = def?.let { objectToModelConverter.toModel(it, type, 0) }
        md?.let { utils.ModelThumbs.render(it, thumbSize, textureLoader) }
    } catch (e: Exception) {
        println("Object $id: no picture (${e.javaClass.simpleName}: ${e.message})")
        null
    }

    private fun category(types: IntArray?): Int {
        val t = types ?: intArrayOf(10)
        var mask = 0
        for (x in t) mask = mask or when (x) {
            in 0..3, 9 -> 1        // walls
            in 4..8 -> 2           // wall decorations
            10, 11 -> 4            // game objects
            in 12..21 -> 8         // roofs
            22 -> 16               // floor decorations
            else -> 0
        }
        return mask
    }

    // -2 = favourites, -3 = recently used
    private val filterNames = listOf("All types", "\u2605 Favorites", "\u231A Recently used", "Game objects", "Walls", "Wall decorations", "Floor decorations", "Roofs")
    private val filterMasks = listOf(-1, -2, -3, 4, 1, 2, 16, 8)

    private lateinit var subScene: SubScene

    @FXML
    private fun initialize() {
        val g = Group()
        val p = StackPane(g)
        val camera: Camera = PerspectiveCamera(true)
        camera.nearClip = 1.0
        camera.farClip = 1000000.0
        // in order for depth to work, camera and model have to stay in positive Z
        // by setting them to a very large +z at the start it gives lots of room to zoom in and out
        g.translateZ = 10000.0
        camera.translateZ = 9000.0
        g.translateY = -10.0 // offset from controls on bottom
        p.children.add(camera)

        subScene = SubScene(p, 200.0, 400.0, true, SceneAntialiasing.BALANCED)
        subScene.camera = camera
        stackPane.children.add(subScene)
        subScene.heightProperty().bind(stackPane.heightProperty())
        subScene.widthProperty().bind(stackPane.widthProperty())
        initMouseControl(g, stackPane, camera)

        entries.addAll(objectLoader.getAll().values
            .filter { it.modelIds != null && it.modelIds!!.isNotEmpty() } // nothing to place without a model
            .sortedBy { it.id }
            .map {
                val unnamed = it.name.isBlank() || it.name == "null"
                ObjectListItem(it.id, if (unnamed) describe(it.modelTypes) else it.name, category(it.modelTypes), unnamed)
            })
        val filterableEntries = FilteredList(entries)
        listView.items = filterableEntries
        listView.fixedCellSize = thumbSize + 10

        typeFilter.items.setAll(filterNames)
        typeFilter.selectionModel.select(0)
        fun applyFilter() {
            val q = searchBox.text.orEmpty().trim().toLowerCase()
            val qId = q.toIntOrNull()
            val mask = filterMasks[typeFilter.selectionModel.selectedIndex.coerceAtLeast(0)]
            val onlyNamed = showUnnamed.isSelected // "Only named": unnamed pieces (corners, decorations...) are shown unless ticked
            val recentIds = utils.Favorites.recent().toSet()
            filterableEntries.setPredicate { o ->
                val fav = mask == -2 && utils.Favorites.isFavorite(o.id)
                val rec = mask == -3 && recentIds.contains(o.id)
                (!onlyNamed || fav || rec || !o.unnamed) &&
                        (mask == -1 || fav || rec || (mask > 0 && o.categories and mask != 0)) &&
                        (q.isEmpty() || (qId != null && o.id == qId) || o.name.toLowerCase().contains(q) || o.id.toString().startsWith(q))
            }
            lblCount.text = "%,d objects".format(filterableEntries.size)
        }
        searchBox.textProperty().addListener { _, _, _ -> applyFilter() }
        typeFilter.valueProperty().addListener { _, _, _ -> applyFilter() }
        showUnnamed.selectedProperty().addListener { _, _, _ -> applyFilter() }
        applyFilter()
        utils.Favorites.onChange {
            if (filterMasks[typeFilter.selectionModel.selectedIndex.coerceAtLeast(0)] < -1) applyFilter()
            listView.refresh()
        }

        listView.setCellFactory {
            object : ListCell<ObjectListItem>() {
                private val img = javafx.scene.image.ImageView().apply { fitWidth = thumbSize; fitHeight = thumbSize; isPreserveRatio = true }
                private val frame = StackPane(img).apply {
                    minWidth = thumbSize + 4; minHeight = thumbSize + 4; maxWidth = thumbSize + 4; maxHeight = thumbSize + 4
                    style = "-fx-background-color: #15110e; -fx-border-color: #3a302a; -fx-border-radius: 4; -fx-background-radius: 4;"
                }
                private val title = Label().apply { style = "-fx-font-weight: bold;" }
                private val sub = Label().apply { styleClass.add("subtle") }
                private val star = Label("\u2606").apply {
                    style = "-fx-font-size: 20px; -fx-cursor: hand; -fx-padding: 0 6 0 6;"
                    tooltip = javafx.scene.control.Tooltip("Add to / remove from favorites")
                    addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED) { it.consume() } // don't select the row
                    setOnMouseClicked { e ->
                        val it = item
                        if (it != null) utils.Favorites.toggle(it.id)
                        e.consume() // don't pick up the object
                    }
                }
                private val spacer = javafx.scene.layout.Region().apply { HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS) }
                private val box = HBox(10.0, frame, javafx.scene.layout.VBox(2.0, title, sub), spacer, star).apply {
                    alignment = javafx.geometry.Pos.CENTER_LEFT
                }
                override fun updateItem(item: ObjectListItem?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = null
                    if (empty || item == null) { graphic = null; return }
                    title.text = "${item.id}  -  ${item.name}" + if (item.unnamed) "  (no name)" else ""
                    sub.text = filterNames.indices.filter { filterMasks[it] > 0 && item.categories and filterMasks[it] != 0 }
                        .joinToString(", ") { filterNames[it] }
                    val fav = utils.Favorites.isFavorite(item.id)
                    star.text = if (fav) "\u2605" else "\u2606"
                    star.style = "-fx-font-size: 20px; -fx-cursor: hand; -fx-padding: 0 6 0 6; -fx-text-fill: " + (if (fav) "#f0c450;" else "#6f655b;")
                    requestThumb(item.id, img)
                    graphic = box
                }
            }
        }
        object : javafx.animation.AnimationTimer() {
            override fun handle(now: Long) {
                var n = 0
                while (n < 4) {
                    val (id, view) = thumbQueue.pollFirst() ?: return
                    if (view.userData != id) continue // row was reused for another object
                    val image = thumbCache[id] ?: makeThumb(id).also { thumbCache[id] = it }
                    if (view.userData == id) view.image = image
                    n++
                }
            }
        }.start()

//        g.children.clear()
//        val mv: Array<MeshView?> =
//            JavaFxHelpers.modelToMeshViews(modelLoader.get(4695)!!, textureLoader, spriteLoader)
//        g.children.addAll(*mv)
//
//        g.transforms.addAll(
//            Rotate(360.0 * 512 / 2048, Rotate.X_AXIS))
//
//        camera.translateZ += 440

        val typeToggleGroup = ToggleGroup()
        // when the held object is dropped (Esc / Drop button), clear the picker so the same item can be picked again
        objectsModel.heldObject.addListener { _, _, v ->
            if (v == null) {
                typeToggleGroup.selectToggle(null)
                listView.selectionModel.clearSelection()
                paneTypeList.children.clear()
            }
        }
        listView.selectionModel.selectedItemProperty()
            .addListener { _: ObservableValue<out ObjectListItem?>?, oldVal: ObjectListItem?, newVal: ObjectListItem? ->
                if (newVal == null || newVal === oldVal) {
                    return@addListener
                }
                val objectDefinition = objectLoader.get(newVal.id) ?: return@addListener
                paneTypeList.children.clear()
                val modelDefinitionMap: Map<Int, ModelDefinition?> =
                    objectToModelConverter.toModelTypesMap(objectDefinition)
                if (modelDefinitionMap.isEmpty()) {
                    return@addListener
                }
                var first = true
                for (t in modelDefinitionMap.keys) {
                    if (modelDefinitionMap[t] == null) {
                        continue
                    }
                    val b = ToggleButton()
                    b.text = LocationType.fromId(t).toString().toLowerCase().replace("_", " ").capitalize()
                    b.toggleGroup = typeToggleGroup
                    b.maxWidth = Double.MAX_VALUE
                    paneTypeList.children.add(b)
                    b.onAction = EventHandler {
                        g.children.clear()
                        val mv: Array<MeshView?> =
                            JavaFxHelpers.modelToMeshViews(modelDefinitionMap[t]!!, textureLoader, spriteLoader)
                        g.children.addAll(*mv)
                        selectedModelType = LocationType.fromId(t)

                        objectsModel.heldObject.set(
                            SceneObject(
                                objectDefinition = objectDefinition,
                                type = t,
                                orientation = 0
                            )
                        )
                    }
                    if (first) {
                        b.fire()
                        first = false
                    }
                }
                selectedObject = objectDefinition
            }
    }

    //Tracks drag starting point for x and y
    private var anchorX = 0.0
    private var anchorY = 0.0

    //Keep track of current angle for x and y
    private var anchorAngleX = 0.0
    private var anchorAngleY = 0.0

    //We will update these after drag. Using JavaFX property to bind with object
    private val angleX: DoubleProperty = SimpleDoubleProperty(0.0)
    private val angleY: DoubleProperty = SimpleDoubleProperty(0.0)
    private fun initMouseControl(group: Group, scene: Pane?, camera: Camera) {
        var xRotate: Rotate
        var yRotate: Rotate
        group.transforms.addAll(
            Rotate(0.0, Rotate.X_AXIS).also {
                xRotate = it
            },
            Rotate(0.0, Rotate.Y_AXIS).also {
                yRotate = it
            }
        )
        xRotate.angleProperty().bind(angleX)
        yRotate.angleProperty().bind(angleY)
        scene!!.onMousePressed = EventHandler { event: MouseEvent ->
            anchorX = event.sceneX
            anchorY = event.sceneY
            anchorAngleX = angleX.get()
            anchorAngleY = angleY.get()
        }
        scene.onMouseDragged = EventHandler { event: MouseEvent ->
            angleX.set(anchorAngleX - (anchorY - event.sceneY))
            angleY.set(anchorAngleY + anchorX - event.sceneX)
        }

        //Attach a scroll listener
        scene.addEventHandler(
            ScrollEvent.SCROLL
        ) { event: ScrollEvent ->
            //Get how much scroll was done in Y axis.
            val delta = event.deltaY
            //Add it to the Z-axis location.
            camera.translateZ = camera.translateZ + delta
        }
    }

    /** A name for objects that have none in the game files, from what kind of piece they are. */
    private fun describe(types: IntArray?): String {
        val t = (types ?: intArrayOf(10)).toSet()
        return when {
            1 in t -> "Wall corner (triangle)"
            3 in t -> "Wall corner (square)"
            2 in t -> "Wall corner (L)"
            0 in t -> "Wall"
            9 in t -> "Diagonal wall"
            t.any { it in 4..8 } -> "Wall decoration"
            t.any { it in 12..21 } -> "Roof piece"
            22 in t -> "Floor decoration"
            else -> "Object"
        }
    }

    class ObjectListItem(val id: Int, val name: String, val categories: Int, val unnamed: Boolean = false) {
        override fun toString(): String = "$name ($id)"
    }
}