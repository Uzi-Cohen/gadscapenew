package controllers

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.FileChooser
import javafx.stage.Stage
import utils.*
import java.io.File

/**
 * Models and animations, like Mallyyy's rsps-cache-viewer but on this editor's cache (rev 211):
 * look at every model, export OBJ / model files, import OBJ or model files as new models or over old ones,
 * recolour and resize; play every animation on a model or an NPC, change its timing, and copy animations
 * between caches as bundles; make and edit items (Items tab). Everything is written to the exported cache, which Deploy
 * sends to the server.
 */
class ModelAnimWindow(private val log: (String) -> Unit) {
    companion object {
        private var open: ModelAnimWindow? = null
        fun show(log: (String) -> Unit, tab: Int = 0) { (open ?: ModelAnimWindow(log).also { open = it }).show(tab) }
    }

    internal val stage = Stage()
    internal val status = Label("").apply { isWrapText = true }
    private var lib: CacheLibrary? = null
    private var models: ModelLoader? = null
    private var anims: AnimCache? = null
    private var npcs: Map<Int, NpcDef> = emptyMap()

    private fun cacheDir(): File = NpcDefs.cacheDir()

    /** (Re)open the cache: after writes, so the lists show what's there now. */
    private fun reopen() {
        try { lib?.close() } catch (e: Exception) {}
        val l = CacheLibrary(cacheDir().path)
        lib = l
        models = ModelLoader(l)
        anims = AnimCache(l)
        npcs = try { NpcDefs.load() } catch (e: Exception) { emptyMap() }
    }

    private fun say(msg: String) { status.text = msg; log(msg) }

    // ======================= models =======================
    private val modelIds = FXCollections.observableArrayList<Int>()
    private val modelFiltered = FilteredList(modelIds)
    private val modelList = ListView(modelFiltered).apply { prefWidth = 150.0 }
    private val modelSearch = TextField().apply { promptText = "model id" }
    private val modelView = ModelView3D(640.0, 520.0)
    private val modelInfo = Label("Pick a model on the left.").apply { isWrapText = true }
    private var current: ModelDefinition? = null
    private var currentId = -1
    /** The model as edited in this window (recolours, size), not saved yet. */
    private var edited: ModelDefinition? = null
    private val colourBox = VBox(3.0)

    internal fun loadModel(id: Int) {
        val md = try { models?.get(id) } catch (e: Exception) { null }
        current = md; currentId = id; edited = null
        if (md == null) { modelView.show(null); modelInfo.text = "Model $id couldn't be read."; colourBox.children.clear(); return }
        modelView.show(md)
        val animated = md.vertexSkins != null
        modelInfo.text = "Model $id: ${md.vertexCount} vertices, ${md.faceCount} faces" +
                (if (md.faceTextures?.any { it.toInt() != -1 } == true) ", textured" else "") +
                (if (animated) ", can be animated" else ", not animatable (no animation labels)")
        fillColours(md)
    }

    private fun working(): ModelDefinition? = edited ?: current

    private fun copyOf(md: ModelDefinition): ModelDefinition = ModelDefinition.merge(listOf(md)).also {
        it.priority = md.priority; it.id = md.id
    }

    /** The colours used by the model, each with a picker to change it. */
    private fun fillColours(md: ModelDefinition) {
        colourBox.children.clear()
        val counts = LinkedHashMap<Int, Int>()
        for (i in 0 until md.faceCount) {
            if ((md.faceTextures?.get(i)?.toInt() ?: -1) != -1) continue
            val c = (md.faceColors?.get(i)?.toInt() ?: 0) and 0xFFFF
            if (c == 65535) continue
            counts[c] = (counts[c] ?: 0) + 1
        }
        for ((hsl, n) in counts.entries.sortedByDescending { it.value }.take(40)) {
            val rgb = ModelCodec.palette[hsl]
            val picker = ColorPicker(javafx.scene.paint.Color.rgb(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255)).apply { prefWidth = 90.0 }
            picker.setOnAction {
                val c = picker.value
                val newHsl = ModelCodec.nearestHsl(((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt())
                val base = working() ?: return@setOnAction
                val e = copyOf(base)
                for (i in 0 until e.faceCount) if ((e.faceColors!![i].toInt() and 0xFFFF) == hsl && (e.faceTextures?.get(i)?.toInt() ?: -1) == -1) e.faceColors!![i] = newHsl.toShort()
                edited = e; modelView.show(e); fillColours(e)
                say("Colour $hsl -> $newHsl on the preview. Save it as a new model or over this one to keep it.")
            }
            colourBox.children.add(HBox(6.0, picker, Label("$hsl  ($n faces)")).apply { alignment = Pos.CENTER_LEFT })
        }
        if (counts.size > 40) colourBox.children.add(Label("(${counts.size - 40} more colours not listed)"))
    }

    private fun resize(percentX: Double, percentY: Double, percentZ: Double, liftY: Int) {
        val base = working() ?: return
        val e = copyOf(base)
        for (v in 0 until e.vertexCount) {
            e.vertexPositionsX[v] = Math.round(e.vertexPositionsX[v] * percentX / 100).toInt()
            e.vertexPositionsY[v] = Math.round(e.vertexPositionsY[v] * percentY / 100).toInt() - liftY
            e.vertexPositionsZ[v] = Math.round(e.vertexPositionsZ[v] * percentZ / 100).toInt()
        }
        edited = e; modelView.show(e)
        say("Resized on the preview. Save it to keep it.")
    }

    private fun saveModel(asNew: Boolean) {
        val md = working() ?: return say("Pick a model first.")
        try {
            val enc = ModelCodec.encodeOld(md)
            val id = CacheEdits.putModel(enc.data, if (asNew) null else currentId)
            reopen(); refreshModelIds(); edited = null
            loadModel(id); modelList.items.indexOf(id).takeIf { it >= 0 }?.let { modelList.selectionModel.clearAndSelect(it) }
            say((if (asNew) "Saved as new model $id." else "Saved over model $id.") + " " + enc.warnings.joinToString(" ") +
                    " It's in the exported cache: Deploy sends it to the server.")
        } catch (e: Exception) { say("Couldn't save: ${e.message}") }
    }

    private fun importModels() {
        val files = FileChooser().apply {
            title = "Model files to import (OBJ with its MTL, or .dat model files)"
            extensionFilters.add(FileChooser.ExtensionFilter("Models", "*.obj", "*.dat", "*.mtl"))
        }.showOpenMultipleDialog(stage) ?: return
        val scale = askNumber("Scale for OBJ files (1 = the numbers in the file are game units, 128 = one tile per unit)", "1") ?: return
        val replace = if (files.count { !it.name.endsWith(".mtl", true) } == 1)
            askText("Model id to replace (leave empty to add it as a new model)", if (currentId >= 0) "" else "")
        else ""
        val results = ArrayList<String>()
        for (f in files.filter { !it.name.endsWith(".mtl", true) }) {
            try {
                val bytes = if (f.name.endsWith(".obj", true)) {
                    val mtl = File(f.parentFile, f.nameWithoutExtension + ".mtl").takeIf { it.isFile } ?: files.firstOrNull { it.name.endsWith(".mtl", true) }
                    val r = ModelCodec.fromObj(f.readBytes(), mtl?.readBytes(), scale, true)
                    results.add("${f.name}: ${r.vertices} vertices, ${r.faces} faces, ${r.colours} colours")
                    r.data
                } else {
                    val b = f.readBytes()
                    // check it reads as a model first; it goes in exactly as it is
                    val m = models!!.decode(b)
                    results.add("${f.name}: ${m.vertexCount} vertices, ${m.faceCount} faces")
                    b
                }
                val id = CacheEdits.putModel(bytes, replace?.trim()?.toIntOrNull())
                results.add("${f.name} -> model $id")
            } catch (e: Exception) { results.add("${f.name}: ${e.message}") }
        }
        reopen(); refreshModelIds()
        say("Import: " + results.joinToString("; ") + ". Undo last change puts the cache back.")
    }

    private fun exportObj() {
        val md = working() ?: return say("Pick a model first.")
        val f = FileChooser().apply { initialFileName = "model_$currentId.obj"; extensionFilters.add(FileChooser.ExtensionFilter("OBJ", "*.obj")) }.showSaveDialog(stage) ?: return
        f.writeText(ModelCodec.toObj(md, "model $currentId"))
        say("Saved ${f.name} (colours are vertex colours; Blender and MeshLab read them).")
    }

    private fun exportDat() {
        val l = lib ?: return
        if (currentId < 0) return say("Pick a model first.")
        val f = FileChooser().apply { initialFileName = "$currentId.dat" }.showSaveDialog(stage) ?: return
        val bytes = if (edited != null) ModelCodec.encodeOld(edited!!).data else (l.data(7, currentId, 0) ?: return say("Model $currentId isn't in the cache."))
        f.writeBytes(bytes)
        say("Saved ${f.name} (${bytes.size} bytes, the game's own model file).")
    }

    private fun refreshModelIds() {
        val ids = try { lib?.index(7)?.archiveIds()?.sorted() ?: emptyList() } catch (e: Exception) { emptyList<Int>() }
        modelIds.setAll(ids)
    }

    private fun modelsTab(): Tab {
        modelSearch.textProperty().addListener { _, _, q -> val t = q.trim(); modelFiltered.setPredicate { t.isEmpty() || it.toString().startsWith(t) } }
        modelSearch.setOnAction { modelSearch.text.trim().toIntOrNull()?.let { loadModel(it) } }
        modelList.selectionModel.selectedItemProperty().addListener { _, _, id -> if (id != null) loadModel(id) }
        val sx = TextField("100").apply { prefColumnCount = 4 }; val sy = TextField("100").apply { prefColumnCount = 4 }; val sz = TextField("100").apply { prefColumnCount = 4 }
        val lift = TextField("0").apply { prefColumnCount = 4 }
        val right = VBox(8.0,
            modelInfo,
            HBox(6.0, Button("Export OBJ...").apply { setOnAction { exportObj() } }, Button("Export model file...").apply { setOnAction { exportDat() } }),
            Button("Import OBJ / model files...").apply { setOnAction { importModels() } },
            Separator(),
            Label("Size in %: width, height, depth; then raise by (units)"),
            HBox(4.0, sx, sy, sz, lift, Button("Apply").apply { setOnAction {
                resize(sx.text.toDoubleOrNull() ?: 100.0, sy.text.toDoubleOrNull() ?: 100.0, sz.text.toDoubleOrNull() ?: 100.0, lift.text.toIntOrNull() ?: 0)
            } }),
            Label("Colours: change one and every face with it changes"),
            ScrollPane(colourBox).apply { prefHeight = 260.0; isFitToWidth = true },
            HBox(6.0, Button("Save as new").apply { styleClass.add("accent"); setOnAction { saveModel(true) } },
                Button("Save over it").apply { setOnAction { saveModel(false) } },
                Button("Reset").apply { setOnAction { if (currentId >= 0) loadModel(currentId) } }),
            CheckBox("Wireframe").apply { selectedProperty().addListener { _, _, v -> modelView.wireframe = v } }
        ).apply { padding = Insets(8.0); prefWidth = 400.0; minWidth = 400.0 }
        val left = VBox(6.0, modelSearch, modelList).apply { padding = Insets(8.0); VBox.setVgrow(modelList, Priority.ALWAYS) }
        HBox.setHgrow(modelView, Priority.ALWAYS)
        return Tab("Models", BorderPane(modelView, null, right, null, left)).apply { isClosable = false }
    }

    // ======================= animations =======================
    private val seqIds = FXCollections.observableArrayList<Int>()
    private val seqFiltered = FilteredList(seqIds)
    private val seqList = ListView(seqFiltered).apply { prefWidth = 150.0 }
    private val seqSearch = TextField().apply { promptText = "animation id" }
    private val animView = ModelView3D(640.0, 520.0)
    private val animInfo = Label("Pick an animation on the left.").apply { isWrapText = true }
    private val onNpc = TextField().apply { prefColumnCount = 6; promptText = "NPC id" }
    private val onModel = TextField().apply { prefColumnCount = 6; promptText = "model id" }
    private val frameSlider = Slider(0.0, 0.0, 0.0).apply { blockIncrement = 1.0; majorTickUnit = 1.0; isSnapToTicks = true }
    private val frameLabel = Label("")
    private val speed = ComboBox(FXCollections.observableArrayList("0.25x", "0.5x", "1x", "2x")).apply { selectionModel.select(2) }
    private val timing = FXCollections.observableArrayList<IntArray>() // [frameId, delay]
    private val timingList = ListView(timing).apply { prefHeight = 220.0 }
    private var seq: SeqDef? = null
    private var animator: Animator? = null
    /** Set while a skeletal (2021+) animation is shown: one frame per client cycle, no frame list to edit. */
    private var skelClip: AnimClip? = null
    private var playing = true
    private var frameIndex = 0
    private var frameTime = 0.0
    private var lastNanos = 0L
    private val timer = object : AnimationTimer() {
        override fun handle(now: Long) {
            val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1e9
            lastNanos = now
            val mult = when (speed.selectionModel.selectedIndex) { 0 -> 0.25; 1 -> 0.5; 3 -> 2.0; else -> 1.0 }
            skelClip?.let { c ->
                if (!playing || c.frameCount == 0) return
                frameTime += dt * 50 * mult
                val steps = frameTime.toInt()
                if (steps > 0) { frameTime -= steps; showFrame((frameIndex + steps) % c.frameCount) }
                return
            }
            if (!playing || timing.isEmpty()) return
            frameTime += dt * 50 * mult // the game counts frame delays in 20 ms cycles
            val d = timing[frameIndex.coerceIn(0, timing.size - 1)][1].coerceAtLeast(1)
            if (frameTime >= d) { frameTime -= d; showFrame((frameIndex + 1) % timing.size) }
        }
    }

    private fun showFrame(i: Int) {
        skelClip?.let { c ->
            if (c.frameCount == 0) return
            frameIndex = Math.floorMod(i, c.frameCount)
            val (x, y, z) = c.pose(frameIndex)
            animView.update(x, y, z)
            if (frameSlider.value.toInt() != frameIndex) frameSlider.value = frameIndex.toDouble()
            frameLabel.text = "frame ${frameIndex + 1} / ${c.frameCount}   (skeletal, 1 cycle = 20 ms each)"
            return
        }
        if (timing.isEmpty()) return
        frameIndex = i.coerceIn(0, timing.size - 1)
        val a = animator ?: return
        val fid = timing[frameIndex][0]
        val fr = anims?.frame(fid)
        a.pose(fr)
        animView.update(a.x, a.y, a.z)
        if (frameSlider.value.toInt() != frameIndex) frameSlider.value = frameIndex.toDouble()
        frameLabel.text = "frame ${frameIndex + 1} / ${timing.size}   (${fid ushr 16}:${fid and 0xFFFF}, ${timing[frameIndex][1]} cycles)" + if (fr == null) "  - frame missing" else ""
    }

    /** The model an animation plays on: the typed NPC or model, else an NPC that uses this animation. */
    private fun previewModel(s: SeqDef): Pair<ModelDefinition?, String> {
        onNpc.text.trim().toIntOrNull()?.let { return npcModel(it) to "NPC $it" }
        onModel.text.trim().toIntOrNull()?.let { return (try { models?.get(it) } catch (e: Exception) { null }) to "model $it" }
        val user = npcs.values.firstOrNull { (it.standAnim == s.id || it.walkAnim == s.id) && it.models.isNotEmpty() }
        if (user != null) return npcModel(user.id) to "NPC ${user.id} ${user.name} (it uses this animation)"
        if (s.skeletalModel != -1) {
            // an NPC whose own skeletal stand/walk uses the same skeleton
            val a = anims ?: return null to "nothing (type an NPC or model id)"
            val skel = try { SkeletalAnims.load(a, s)?.skeletonId } catch (e: Exception) { null }
            if (skel != null) for (n in npcs.values) {
                if (n.standAnim < 0 || n.models.isEmpty()) continue
                val ns = try { a.seq(n.standAnim) } catch (e: Exception) { null } ?: continue
                if (ns.skeletalModel == -1) continue
                val nskel = try { SkeletalAnims.load(a, ns)?.skeletonId } catch (e: Exception) { null }
                if (nskel == skel) return npcModel(n.id) to "NPC ${n.id} ${n.name} (same skeleton)"
            }
            return null to "nothing (type an NPC or model id)"
        }
        // find an NPC whose model fits this animation's skeleton
        val skel = s.frameIds.firstOrNull()?.let { anims?.frame(it)?.skeleton?.id }
        if (skel != null) {
            for (n in npcs.values) {
                if (n.standAnim < 0) continue
                val ns = try { anims?.seq(n.standAnim) } catch (e: Exception) { null } ?: continue
                val nskel = ns.frameIds.firstOrNull()?.let { anims?.frame(it)?.skeleton?.id }
                if (nskel == skel) return npcModel(n.id) to "NPC ${n.id} ${n.name} (same skeleton)"
            }
        }
        return null to "nothing (type an NPC or model id)"
    }

    private fun npcModel(id: Int): ModelDefinition? {
        val n = npcs[id] ?: return null
        val loader = models
        val parts = n.models.toList().mapNotNull { mid -> try { loader?.get(mid) } catch (e: Exception) { null } }
        if (parts.isEmpty()) return null
        val m = ModelDefinition.merge(parts)
        val from = n.recolorFrom; val to = n.recolorTo
        if (from != null && to != null) for (i in 0 until m.faceCount) for (k in from.indices) if (m.faceColors!![i] == from[k]) m.faceColors!![i] = to[k]
        return m
    }

    internal fun loadSeq(id: Int) {
        val s = try { anims?.seq(id) } catch (e: Exception) { null }
        seq = s
        timing.clear()
        skelClip = null
        if (s == null) { animInfo.text = "Animation $id couldn't be read."; return }
        if (s.skeletalModel != -1) {
            val (md, onWhat) = previewModel(s)
            animView.show(md)
            if (md == null) { animInfo.text = "Animation $id is skeletal (2021+). Type an NPC or model id to show it on."; return }
            val (clip, note) = AnimPlayback.clipFor(md, s.id, anims ?: return)
            skelClip = clip
            frameSlider.max = ((clip?.frameCount ?: 1) - 1).coerceAtLeast(0).toDouble()
            animInfo.text = "Animation $id: skeletal (2021+), ${clip?.frameCount ?: 0} cycles (${(clip?.frameCount ?: 0) * 20} ms). Showing it on $onWhat. " +
                    (if (note.isNotEmpty()) "$note " else "") + "Its timing is in its keyframes, so there's no frame list to edit."
            frameIndex = 0; frameTime = 0.0
            showFrame(0)
            return
        }
        for (i in s.frameIds.indices) timing.add(intArrayOf(s.frameIds[i], s.delays[i]))
        val (md, onWhat) = previewModel(s)
        animator = md?.let { Animator(it.vertexPositionsX.clone(), it.vertexPositionsY.clone(), it.vertexPositionsZ.clone(), it.vertexSkins) }
        animView.show(md)
        frameSlider.max = (timing.size - 1).coerceAtLeast(0).toDouble()
        animInfo.text = "Animation $id: ${s.frameCount} frames, ${s.lengthCycles * 20} ms" +
                (if (s.loopOffset >= 0) ", loops from frame ${s.loopOffset}" else "") +
                (if (s.weapon >= 0 || s.shield >= 0) ", holds items" else "") + ". Showing it on $onWhat." +
                (if (animator?.animatable == false) " That model can't move (no animation labels)." else "")
        frameIndex = 0; frameTime = 0.0
        showFrame(0)
    }

    private fun refreshSeqIds() { seqIds.setAll(try { anims?.seqIds() ?: emptyList() } catch (e: Exception) { emptyList<Int>() }) }

    private fun saveTiming() {
        val s = seq ?: return say("Pick an animation first.")
        try {
            CacheEdits.saveSeq(s, timing.map { it[0] }.toIntArray(), timing.map { it[1] }.toIntArray())
            reopen(); loadSeq(s.id)
            say("Saved the timing of animation ${s.id} in the exported cache. Deploy sends it to the server.")
        } catch (e: Exception) { say("Couldn't save: ${e.message}") }
    }

    private fun animationsTab(): Tab {
        seqSearch.textProperty().addListener { _, _, q -> val t = q.trim(); seqFiltered.setPredicate { t.isEmpty() || it.toString().startsWith(t) } }
        seqSearch.setOnAction { seqSearch.text.trim().toIntOrNull()?.let { loadSeq(it) } }
        seqList.selectionModel.selectedItemProperty().addListener { _, _, id -> if (id != null) loadSeq(id) }
        frameSlider.valueProperty().addListener { _, _, v -> if (v.toInt() != frameIndex) showFrame(v.toInt()) }
        timingList.setCellFactory {
            object : ListCell<IntArray>() {
                override fun updateItem(item: IntArray?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else "${index + 1}.  frame ${item[0] ushr 16}:${item[0] and 0xFFFF}   ${item[1]} cycles (${item[1] * 20} ms)"
                }
            }
        }
        timingList.selectionModel.selectedIndexProperty().addListener { _, _, i -> if (i.toInt() >= 0) { playing = false; showFrame(i.toInt()) } }
        val delay = TextField().apply { prefColumnCount = 4; promptText = "cycles" }
        fun sel() = timingList.selectionModel.selectedIndex
        val controls = VBox(8.0,
            animInfo,
            HBox(6.0, Label("Show on"), onNpc, onModel, Button("Show").apply { setOnAction { seq?.let { loadSeq(it.id) } } }).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0, Button("Play / pause").apply { setOnAction { playing = !playing } }, speed,
                Button("<").apply { setOnAction { playing = false; showFrame(frameIndex - 1) } },
                Button(">").apply { setOnAction { playing = false; showFrame(frameIndex + 1) } }).apply { alignment = Pos.CENTER_LEFT },
            frameSlider, frameLabel,
            Separator(),
            Label("Timing (1 cycle = 20 ms). Pick a frame to change it."),
            timingList,
            HBox(6.0, Label("Delay"), delay,
                Button("Set").apply { setOnAction { val i = sel(); val d = delay.text.trim().toIntOrNull(); if (i >= 0 && d != null && d in 1..65535) { timing[i] = intArrayOf(timing[i][0], d); timingList.selectionModel.select(i) } } },
                Button("All").apply { setOnAction { val d = delay.text.trim().toIntOrNull() ?: return@setOnAction; if (d in 1..65535) timing.replaceAll { intArrayOf(it[0], d) } } }).apply { alignment = Pos.CENTER_LEFT },
            HBox(6.0,
                Button("Copy frame").apply { setOnAction { val i = sel(); if (i >= 0) timing.add(i + 1, timing[i].clone()) } },
                Button("Remove frame").apply { setOnAction { val i = sel(); if (i >= 0 && timing.size > 1) timing.removeAt(i) } },
                Button("Up").apply { setOnAction { val i = sel(); if (i > 0) { val t = timing.removeAt(i); timing.add(i - 1, t); timingList.selectionModel.select(i - 1) } } },
                Button("Down").apply { setOnAction { val i = sel(); if (i >= 0 && i < timing.size - 1) { val t = timing.removeAt(i); timing.add(i + 1, t); timingList.selectionModel.select(i + 1) } } }),
            HBox(6.0, Button("Save timing").apply { styleClass.add("accent"); setOnAction { saveTiming() } },
                Button("Reset").apply { setOnAction { seq?.let { loadSeq(it.id) } } }),
            Separator(),
            Label("Animation bundles: the animation with its frames and skeleton, to move it between caches").apply { isWrapText = true },
            HBox(6.0, Button("Export...").apply { setOnAction { exportBundle() } },
                Button("Import...").apply { setOnAction { importBundle() } },
                Button("Copy from another cache...").apply { setOnAction { fromOtherCache() } })
        ).apply { padding = Insets(8.0); prefWidth = 440.0; minWidth = 440.0 }
        val left = VBox(6.0, seqSearch, seqList).apply { padding = Insets(8.0); VBox.setVgrow(seqList, Priority.ALWAYS) }
        return Tab("Animations", BorderPane(animView, null, controls, null, left)).apply { isClosable = false }
    }

    private fun exportBundle() {
        val l = lib ?: return
        val s = seq ?: return say("Pick an animation first.")
        val f = FileChooser().apply { initialFileName = "animation_${s.id}.zip"; extensionFilters.add(FileChooser.ExtensionFilter("Animation bundle", "*.zip")) }.showSaveDialog(stage) ?: return
        say(CacheEdits.exportBundle(l, listOf(s.id), f) + " Saved ${f.name}.")
    }

    private fun importBundle() {
        val f = FileChooser().apply { title = "Animation bundle (.zip)"; extensionFilters.add(FileChooser.ExtensionFilter("Animation bundle", "*.zip")) }.showOpenDialog(stage) ?: return
        val replace = askText("Animation id to replace (leave empty to add it as a new animation)", "") ?: return
        try {
            val r = CacheEdits.importBundle(f, replace.trim().toIntOrNull())
            reopen(); refreshSeqIds(); r.newSeqIds.firstOrNull()?.let { loadSeq(it) }
            say(r.message + " Undo last change puts the cache back.")
        } catch (e: Exception) { say("Couldn't import: ${e.message}") }
    }

    /** Copy animations straight from another cache (e.g. a newer OSRS cache) into this one. */
    private fun fromOtherCache() {
        val d = javafx.stage.DirectoryChooser().apply { title = "Folder with the other cache (main_file_cache.dat2)" }.showDialog(stage) ?: return
        val dat = d.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.equals("main_file_cache.dat2", true) }
            ?: return say("No 211-style cache (main_file_cache.dat2) in ${d.path}.")
        val ids = askText("Animation ids to copy (e.g. 808, 819, 7000-7010)", "") ?: return
        val list = parseIds(ids)
        if (list.isEmpty()) return say("No ids given.")
        try {
            val tmp = File.createTempFile("anims", ".zip")
            val other = CacheLibrary(dat.parentFile.path)
            val msg = try { CacheEdits.exportBundle(other, list, tmp) } finally { other.close() }
            val r = CacheEdits.importBundle(tmp, null)
            tmp.delete()
            reopen(); refreshSeqIds(); r.newSeqIds.firstOrNull()?.let { loadSeq(it) }
            say("$msg ${r.message} Undo last change puts the cache back.")
        } catch (e: Exception) { say("Couldn't copy: ${e.message}") }
    }

    private fun parseIds(s: String): List<Int> = s.split(',', ' ', ';').filter { it.isNotBlank() }.flatMap { p ->
        val r = p.trim().split('-')
        if (r.size == 2) { val a = r[0].toIntOrNull(); val b = r[1].toIntOrNull(); if (a != null && b != null && b >= a && b - a < 5000) (a..b).toList() else emptyList() }
        else listOfNotNull(p.trim().toIntOrNull())
    }

    private fun askText(q: String, def: String): String? = TextInputDialog(def).apply { title = "Models and animations"; headerText = null; contentText = q; initOwner(stage) }.showAndWait().orElse(null)
    private fun askNumber(q: String, def: String): Double? = askText(q, def)?.trim()?.toDoubleOrNull()

    private val itemsTab = ItemsTab(stage, { lib }, { models }, { anims }, { currentId }, { say(it) }, { reopen(); refreshModelIds() })
    private var tabs: TabPane? = null

    fun show(tab: Int = 0) {
        if (stage.scene != null) { tabs?.selectionModel?.select(tab); stage.show(); stage.toFront(); return }
        reopen()
        refreshModelIds(); refreshSeqIds()
        itemsTab.refreshList()
        val tabs = TabPane(modelsTab(), animationsTab(), itemsTab.tab())
        this.tabs = tabs
        tabs.selectionModel.select(tab)
        val undo = Button("Undo last change").apply { setOnAction {
            try { lib?.close() } catch (e: Exception) {}
            lib = null
            say(try { CacheEdits.undoLast() } catch (e: Exception) { "Couldn't undo: ${e.message}" })
            reopen(); refreshModelIds(); refreshSeqIds()
            if (currentId >= 0) loadModel(currentId)
            itemsTab.reloaded()
        } }
        val bottom = HBox(10.0, undo, status).apply { padding = Insets(6.0, 8.0, 8.0, 8.0); alignment = Pos.CENTER_LEFT; HBox.setHgrow(status, Priority.ALWAYS) }
        stage.title = "Uzi's Map Editor - models, animations and items"
        stage.scene = Scene(BorderPane(tabs, null, null, bottom, null), 1200.0, 760.0)
        stage.setOnHidden { timer.stop() }
        stage.setOnShown { timer.start() }
        timer.start()
        say("Reading ${cacheDir().path}. Changes go into the exported cache; Deploy sends them to the server.")
        stage.show()
        Platform.runLater { modelSearch.requestFocus() }
    }
}
