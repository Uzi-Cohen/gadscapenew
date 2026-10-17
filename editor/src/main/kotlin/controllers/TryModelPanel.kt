package controllers

import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Window
import utils.AnimCache
import utils.AnimCatalog
import utils.ItemFull
import utils.ModelCodec
import utils.PendingModels
import java.io.File

/**
 * "Try a model on the player": put any model (from the cache, the Models tab, or a file) on a plain player, in place
 * of the item's worn model or in any slot, and play any animation on it. Nothing is written: "Use this model" only
 * fills in the item's fields, and a model from a file goes into the cache when the item is saved.
 */
class TryModelPanel(
    private val owner: () -> Window?,
    private val lib: () -> CacheLibrary?,
    private val models: () -> ModelLoader?,
    private val anims: () -> AnimCache?,
    private val view: ModelView3D,
    private val playback: AnimPlayback,
    /** The item being made or changed (null = none yet). */
    private val item: () -> ItemFull?,
    /** The item's own animation for "stand", "walk", "run", "accurate", "block", or null. */
    private val itemAnim: (String) -> Int?,
    /** The special attack's animation, or why there isn't one. */
    private val specialAnim: () -> Pair<Int?, String>,
    /** The model open in the Models tab, or -1. */
    private val modelsTabModel: () -> Int,
    /** Puts the tried model in the item's fields. */
    private val use: (modelId: Int, target: Target, place: ItemOnPlayer.Place) -> String,
    /** Called before this panel takes over the view (so the host stops its own animation). */
    private val takeView: () -> Unit = {},
    /** Also shows each message elsewhere (the window's status line). */
    private val report: (String) -> Unit = {}
) {
    enum class Target(val label: String) {
        WORN("As the worn model (male and female)"), MALE("As the male worn model"), FEMALE("As the female worn model"), INVENTORY("As the inventory model")
    }

    private val modelId = TextField().apply { promptText = "model id"; prefColumnCount = 7 }
    private val loaded = Label("No model picked yet.").apply { isWrapText = true }
    private val place = ComboBox(FXCollections.observableArrayList(ItemOnPlayer.PLACES.map { it.label })).apply { selectionModel.select(0); maxWidth = 360.0 }
    private val female = CheckBox("Female")
    private val speed = ComboBox(FXCollections.observableArrayList("0.25x", "0.5x", "1x", "2x")).apply { selectionModel.select(2) }
    private val search = TextField().apply { promptText = "Find an animation by name or id (whip, walk, 808...)" }
    private val allAnims = FXCollections.observableArrayList<Int>()
    private val filteredAnims = FilteredList(allAnims)
    private val animList = ListView(filteredAnims).apply { prefHeight = 130.0 }
    private val status = Label("").apply { isWrapText = true }
    private val pauseButton = Button("Pause")

    /** The model being tried: a cache model id or a reserved id of a model from a file ([PendingModels]). */
    private var tried: Int = -1
    private var lastAnim: Int? = null
    private var animsLoadedFor: AnimCache? = null

    val node: TitledPane by lazy { build() }

    /**
     * [view] with this panel under it. Closed, the panel is one line; opened, it takes up to 60% of the height (it
     * scrolls past that) and the view shrinks to make room.
     */
    fun under(view: javafx.scene.layout.Region): javafx.scene.layout.VBox {
        // the 3D view would otherwise refuse to get smaller than its first size
        view.minHeight = 0.0; view.minWidth = 0.0
        val box = javafx.scene.layout.StackPane(view).apply {
            minHeight = 100.0; prefHeight = 200.0
            clip = javafx.scene.shape.Rectangle().also { r -> r.widthProperty().bind(widthProperty()); r.heightProperty().bind(heightProperty()) }
        }
        val scroll = ScrollPane(node).apply { isFitToWidth = true; minHeight = 34.0; hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER }
        val outer = javafx.scene.layout.VBox(4.0, box, scroll)
        javafx.scene.layout.VBox.setVgrow(box, javafx.scene.layout.Priority.ALWAYS)
        scroll.maxHeightProperty().bind(outer.heightProperty().multiply(0.6))
        return outer
    }

    /** Stops the animation (the host is about to show something else). */
    fun stop() { playback.stop() }

    private fun say(s: String) { status.text = s; report(s) }

    private fun build(): TitledPane {
        animList.setCellFactory { object : ListCell<Int>() { override fun updateItem(id: Int?, empty: Boolean) {
            super.updateItem(id, empty)
            text = if (empty || id == null) null else AnimCatalog.label(id) + "   [" + AnimCatalog.category(id) + "]"
        } } }
        search.textProperty().addListener { _, _, q ->
            ensureAnims()
            val t = q.trim()
            filteredAnims.setPredicate { t.isEmpty() || AnimCatalog.matches(it, null, t) }
        }
        search.setOnAction {
            // Enter: an id plays straight away, otherwise the first match
            val t = search.text.trim()
            if (t.isEmpty()) { say("Type an animation name or id, or double-click one in the list."); return@setOnAction }
            ensureAnims()
            val id = t.toIntOrNull() ?: filteredAnims.firstOrNull()
            if (id != null) play(id) else say("No animation matches \"$t\".")
        }
        animList.setOnMouseClicked { e -> if (e.clickCount == 2) animList.selectionModel.selectedItem?.let { play(it) } }
        animList.setOnKeyPressed { e -> if (e.code == javafx.scene.input.KeyCode.ENTER) animList.selectionModel.selectedItem?.let { play(it) } }
        modelId.setOnAction { pickId() }
        place.setOnAction { replay() }
        female.setOnAction { replay() }
        speed.setOnAction { playback.speed = speed.value.removeSuffix("x").toDoubleOrNull() ?: 1.0 }
        pauseButton.setOnAction { playback.playing = !playback.playing; pauseButton.text = if (playback.playing) "Pause" else "Play" }

        fun itemAnim(label: String, key: String, default: Int) = Button(label).apply { setOnAction {
            val a = itemAnim(key)
            if (a != null) play(a) else play(default, "The item has no ${label.lowercase()} animation of its own here (it comes from the server's item stats), so this is the plain player's.")
        } }
        val content = VBox(7.0,
            Label("Any model on a plain player, playing any animation. Nothing is saved until you Use it and save the item.").apply { isWrapText = true },
            FlowPane(6.0, 6.0, Label("Model").apply { minWidth = Region.USE_PREF_SIZE }, modelId, Button("Show").apply { setOnAction { pickId() } },
                Button("The one open in Models").apply { setOnAction {
                    val id = modelsTabModel()
                    if (id < 0) say("Open a model in the Models tab first.") else { modelId.text = id.toString(); pickId() }
                } },
                Button("From a file...").apply { setOnAction { pickFile() } }).apply { alignment = Pos.CENTER_LEFT },
            loaded,
            HBox(6.0, MenuButton("Use this model").apply {
                styleClass.add("accent")
                items.addAll(Target.values().map { t -> MenuItem(t.label).apply { setOnAction { useIt(t) } } })
            }).apply { alignment = Pos.CENTER_LEFT },
            FlowPane(6.0, 6.0, Label("Where").apply { minWidth = Region.USE_PREF_SIZE }, place, female).apply { alignment = Pos.CENTER_LEFT },
            Label("Its own animations, or find any animation below (double-click to play):").apply { isWrapText = true },
            FlowPane(6.0, 6.0,
                itemAnim("Stand", "stand", 808), itemAnim("Walk", "walk", 819), itemAnim("Run", "run", 824),
                itemAnim("Attack", "accurate", 422), itemAnim("Block", "block", 424),
                Button("Special attack").apply { setOnAction { val (a, why) = specialAnim(); if (a == null) say(why) else play(a) } }),
            search, animList,
            FlowPane(6.0, 6.0, Button("Play").apply { setOnAction { (animList.selectionModel.selectedItem ?: lastAnim ?: 808).let { play(it) } } },
                pauseButton,
                Button("<").apply { setOnAction { playback.playing = false; pauseButton.text = "Play"; playback.show(playback.frame - 1) } },
                Button(">").apply { setOnAction { playback.playing = false; pauseButton.text = "Play"; playback.show(playback.frame + 1) } },
                Label("Speed"), speed),
            status
        ).apply { padding = Insets(6.0) }
        return TitledPane("Try a model on the player", content).apply {
            isExpanded = false; isAnimated = false
            // the animation list fills when it's first opened
            expandedProperty().addListener { _, _, open -> if (open) ensureAnims() }
        }
    }

    private fun ensureAnims() {
        val a = anims() ?: return
        if (animsLoadedFor === a) return
        animsLoadedFor = a
        allAnims.setAll(try { a.seqIds() } catch (e: Exception) { emptyList() })
    }

    /** The model id field -> the model being tried (checked, nothing changes when it isn't a model). */
    private fun pickId() {
        val t = modelId.text.trim()
        val m = models() ?: return say("Open a cache first.")
        val id = t.toIntOrNull() ?: return say(if (t.isEmpty()) "Type a model id." else "\"$t\" isn't a model id (a whole number from 0 to 65535).")
        if (id !in 0..65535) return say("Model ids go from 0 to 65535.")
        val md = PendingModels.model(m, id) ?: return say("There's no model $id in the cache.")
        if (md.faceCount == 0) return say("Model $id has no faces, so there's nothing to see.")
        tried = id
        loaded.text = "Model $id: ${md.vertexCount} corners, ${md.faceCount} faces" + (PendingModels.get(id)?.let { " (from ${it.name}; it goes into the cache when the item is saved)" } ?: "") + "."
        replay()
    }

    private fun pickFile() {
        val l = lib() ?: return say("Open a cache first.")
        val m = models() ?: return say("Open a cache first.")
        val f = FileChooser().apply {
            title = "A model to try (OBJ, or a .dat model file)"
            extensionFilters.addAll(FileChooser.ExtensionFilter("Models", "*.obj", "*.dat"), FileChooser.ExtensionFilter("All files", "*.*"))
        }.showOpenDialog(owner()) ?: return
        val isObj = f.name.endsWith(".obj", true)
        val scale = if (!isObj) 1.0 else (TextInputDialog("1").apply {
            title = "Scale"; headerText = null; initOwner(owner())
            contentText = "Scale (1 = the numbers in the file are game units, 128 = one tile per unit)"
        }.showAndWait().orElse(null)?.trim()?.toDoubleOrNull() ?: return)
        try {
            if (f.length() > 64L * 1024 * 1024) return say("${f.name} is too big for a model (over 64 MB).")
            val bytes = if (isObj) {
                val mtl = File(f.parentFile, f.nameWithoutExtension + ".mtl").takeIf { it.isFile }
                ModelCodec.fromObj(f.readBytes(), mtl?.readBytes(), scale, true).data
            } else f.readBytes()
            val md = try { m.decode(bytes) } catch (e: Throwable) { null }
                ?: return say("${f.name} isn't a game model file (or an OBJ).")
            if (md.vertexCount == 0 || md.faceCount == 0) return say("${f.name} has no faces, so there's nothing to show.")
            val p = PendingModels.add(l, f.name, bytes, md)
            tried = p.id
            modelId.text = p.id.toString()
            loaded.text = "${f.name}: ${md.vertexCount} corners, ${md.faceCount} faces. It isn't in the cache yet: it'll be model ${p.id} once an item using it is saved."
            replay()
        } catch (e: Throwable) {
            say("Couldn't read ${f.name}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun currentPlace() = ItemOnPlayer.PLACES[place.selectionModel.selectedIndex.coerceIn(0, ItemOnPlayer.PLACES.size - 1)]

    private fun replay() { if (tried >= 0) play(lastAnim ?: itemAnim("stand") ?: 808) }

    private fun play(seq: Int, note: String = "") {
        if (tried < 0) return say("Pick a model first (an id, the one open in Models, or a file).")
        val l = lib() ?: return say("Open a cache first.")
        val m = models() ?: return say("Open a cache first.")
        val a = anims() ?: return say("Animations aren't loaded.")
        lastAnim = seq
        takeView()
        val (main, also) = ItemOnPlayer.dress(item(), tried, currentPlace())
        val r = try { ItemOnPlayer.play(main, female.isSelected, seq, l, m, a, view, playback, also) }
            catch (e: Exception) { playback.stop(); ItemOnPlayer.Result(null, "Couldn't play it: ${e.message}") }
        playback.speed = speed.value.removeSuffix("x").toDoubleOrNull() ?: 1.0
        pauseButton.text = "Pause"; playback.playing = true
        say(r.message.replace(main.name, if (main.id == -2) "the model" else main.name) + (if (note.isNotEmpty() && r.model != null) " $note" else ""))
    }

    private fun useIt(t: Target) {
        if (tried < 0) return say("Pick a model first.")
        if (item() == null) return say("Pick or make an item first.")
        say(use(tried, t, currentPlace()))
    }
}
