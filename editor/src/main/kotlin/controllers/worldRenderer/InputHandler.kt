package controllers.worldRenderer

import javafx.animation.AnimationTimer
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import models.DebugModel
import models.GadToolsModel
import models.HoverModel
import models.PinnedTile
import models.TerrainTool
import utils.EditorRegions
import utils.GadTools
import models.TileOps
import models.rotatedClockwise
import models.ObjectsModel
import models.scene.Scene
import models.scene.SceneObject
import javax.inject.Inject

class InputHandler @Inject internal constructor(
    private val camera: Camera,
    private val scene: Scene,
    private val objectsModel: ObjectsModel,
    private val hoverModel: HoverModel,
    private val gadToolsModel: GadToolsModel,
    editorRegions: EditorRegions,
    private val objectLoader: cache.loaders.ObjectLoader,
    private val underlayLoader: cache.loaders.UnderlayLoader,
    private val overlayLoader: cache.loaders.OverlayLoader
) {

    private val tileOps = TileOps(editorRegions)

    var renderer: Renderer? = null

    @Volatile var isLeftMouseDown = false
    /** Set by every left press, cleared by the renderer: a click quicker than one frame still counts. */
    private val leftPressLatch = java.util.concurrent.atomic.AtomicBoolean(false)
    fun takeLeftPress() = leftPressLatch.getAndSet(false)
    var leftMousePressed = false
    var isRightMouseDown = false
    private val keys = object {
        // JavaFX key codes (KeyCode.code); anything past the end is simply never "down"
        private val down = BooleanArray(1024)
        operator fun get(i: Int) = i in down.indices && down[i]
        operator fun set(i: Int, v: Boolean) { if (i in down.indices) down[i] = v }
        fun clear() = java.util.Arrays.fill(down, false)
    }
    var mouseClicked = false
    private var previousMouseX = 0
    private var previousMouseY = 0
    var mouseX = 0
    var mouseY = 0

    /** Keys pressed since the last tick, and releases held back until that tick has seen them. */
    private val fresh = HashSet<Int>()
    private val late = HashSet<Int>()

    private fun handleKeys(dt: Double) {
        try { readKeys(dt) } finally {
            fresh.clear()
            for (c in late) keys[c] = false
            late.clear()
        }
    }

    private fun readKeys(elapsed: Double) {
        // big lag spike: don't send the user flying (shortcuts below still run)
        val dt = if (elapsed > 1000) 0.0 else elapsed
        val xVec = (-camera.yawSin).toDouble() / 65535
        val yVec = camera.yawCos.toDouble() / 65535
        val zVec = camera.pitchSin.toDouble() / 65535
        var speed = 1
        if (keys[KeyCode.SHIFT.code]) {
            speed = 4
        }
        if (keys[KeyCode.W.code]) {
            camera.addX((dt * xVec * speed).toInt())
            camera.addY((dt * yVec * speed).toInt())
            camera.addZ((dt * zVec * speed).toInt())
        }
        if (keys[KeyCode.S.code]) {
            camera.addX((-(dt * xVec * speed)).toInt())
            camera.addY((-(dt * yVec * speed)).toInt())
            camera.addZ((-(dt * zVec * speed)).toInt())
        }
        if (keys[KeyCode.A.code]) {
            // X uses yVec because we want to move perpendicular
            camera.addX((-(dt * yVec * speed)).toInt())
            camera.addY((dt * xVec * speed).toInt())
        }
        if (keys[KeyCode.D.code]) {
            camera.addX((dt * yVec * speed).toInt())
            camera.addY((-(dt * xVec * speed)).toInt())
        }
        if (keys[KeyCode.SPACE.code]) {
            camera.addZ((-dt).toInt() * speed)
        }
        if (keys[KeyCode.X.code]) {
            camera.addZ(dt.toInt() * speed)
        }
        // floors: Ctrl + Page Up / Page Down changes the floor you see and edit
        val ctrlDown = keys[KeyCode.CONTROL.code]
        if (ctrlDown && (keys[KeyCode.PAGE_UP.code] || keys[KeyCode.PAGE_DOWN.code])) {
            val dir = if (keys[KeyCode.PAGE_UP.code]) 1 else -1
            keys[KeyCode.PAGE_UP.code] = false
            keys[KeyCode.PAGE_DOWN.code] = false
            val p = (gadToolsModel.paintPlane.get() + dir).coerceIn(0, 3)
            if (p != gadToolsModel.paintPlane.get()) {
                gadToolsModel.paintPlane.set(p)
                gadToolsModel.message = "Floor $p" + if (p == 0) " (ground)" else ""
            }
        }
        // camera up / down: Page Up/Down or E/Q (Shift = faster)
        if (!ctrlDown && (keys[KeyCode.PAGE_UP.code] || keys[KeyCode.E.code])) {
            camera.addZ((-dt * speed).toInt())
        }
        if (!ctrlDown && (keys[KeyCode.PAGE_DOWN.code] || keys[KeyCode.Q.code])) {
            camera.addZ((dt * speed).toInt())
        }
        // Arrow keys: pan flat across the map. Shift = faster. Ctrl + arrow = load the next region over.
        val ctrl = keys[KeyCode.CONTROL.code]
        val pan = if (keys[KeyCode.SHIFT.code]) 8 else 2
        val up = keys[KeyCode.UP.code]
        val down = keys[KeyCode.DOWN.code]
        val left = keys[KeyCode.LEFT.code]
        val right = keys[KeyCode.RIGHT.code]
        if (ctrl) {
            var dx = 0
            var dy = 0
            if (up) { dy = 1; keys[KeyCode.UP.code] = false }
            if (down) { dy = -1; keys[KeyCode.DOWN.code] = false }
            if (left) { dx = -1; keys[KeyCode.LEFT.code] = false }
            if (right) { dx = 1; keys[KeyCode.RIGHT.code] = false }
            if (dx != 0 || dy != 0) GadTools.shiftRegions(scene, camera, dx, dy)
        } else {
            if (up) {
                camera.addX((dt * xVec * pan).toInt())
                camera.addY((dt * yVec * pan).toInt())
            }
            if (down) {
                camera.addX((-(dt * xVec * pan)).toInt())
                camera.addY((-(dt * yVec * pan)).toInt())
            }
            if (left) {
                camera.addX((-(dt * yVec * pan)).toInt())
                camera.addY((dt * xVec * pan).toInt())
            }
            if (right) {
                camera.addX((dt * yVec * pan).toInt())
                camera.addY((-(dt * xVec * pan)).toInt())
            }
        }
        // Tools: 1-9, 0, -, = pick tool, [ ] brush size, Ctrl+Z undo, Ctrl+Y redo
        TOOL_KEYS.forEachIndexed { i, k ->
            if (keys[k] && i < TerrainTool.values().size) {
                keys[k] = false
                gadToolsModel.tool.set(TerrainTool.values()[i])
            }
        }
        if (keys[KeyCode.OPEN_BRACKET.code]) {
            keys[KeyCode.OPEN_BRACKET.code] = false
            gadToolsModel.brushSize.set((gadToolsModel.brushSize.get() - 1).coerceAtLeast(0))
        }
        if (keys[KeyCode.CLOSE_BRACKET.code]) {
            keys[KeyCode.CLOSE_BRACKET.code] = false
            gadToolsModel.brushSize.set((gadToolsModel.brushSize.get() + 1).coerceAtMost(15))
        }
        if (ctrl && keys[KeyCode.Z.code]) {
            keys[KeyCode.Z.code] = false
            if (keys[KeyCode.SHIFT.code]) gadToolsModel.redoRequested.set(true)
            else gadToolsModel.undoRequested.set(true)
        }
        if (ctrl && keys[KeyCode.Y.code]) {
            keys[KeyCode.Y.code] = false
            gadToolsModel.redoRequested.set(true)
        }
        // F = star / unstar the held, selected or copied object
        if (!ctrl && keys[KeyCode.F.code]) {
            keys[KeyCode.F.code] = false
            val id = objectsModel.heldObject.get()?.objectDefinition?.id
                ?: gadToolsModel.selectedObject?.loc?.objId
                ?: gadToolsModel.objectClipboard?.objId
            if (id == null) gadToolsModel.message = "Hold, select or copy an object first, then F adds it to favorites."
            else {
                val on = utils.Favorites.toggle(id)
                gadToolsModel.message = if (on) "Object $id added to favorites (\u2605 in the Object Picker filter)." else "Object $id removed from favorites."
            }
        }
        // M = pick up the selected object
        if (!ctrl && keys[KeyCode.M.code]) {
            keys[KeyCode.M.code] = false
            gadToolsModel.moveObjectRequested.set(true)
        }
        // Ctrl+C / Ctrl+V: the selected object if there is one, otherwise the selected area
        if (ctrl && keys[KeyCode.C.code]) {
            keys[KeyCode.C.code] = false
            // with the Select area tool and a box selected, Ctrl+C is always the area (a leftover selected object used to win)
            val areaFirst = gadToolsModel.tool.get() == models.TerrainTool.SELECT && gadToolsModel.selection != null
            if (!areaFirst && gadToolsModel.selectedObject != null) gadToolsModel.copyObjectRequested.set(true) else copySelection()
        }
        if (ctrl && keys[KeyCode.V.code]) {
            keys[KeyCode.V.code] = false
            val areaPaste = gadToolsModel.tool.get() == models.TerrainTool.SELECT && gadToolsModel.clipboard != null
            if (!areaPaste && gadToolsModel.lastCopyWasObject && gadToolsModel.objectClipboard != null) gadToolsModel.pasteObjectRequested.set(true)
            else pasteAtHover()
        }
        // Ctrl+T: tile numbers window on / off
        if (ctrl && keys[KeyCode.T.code]) {
            keys[KeyCode.T.code] = false
            gadToolsModel.tileNumbersRequested.set(true)
        }
        // Ctrl+S: save the map (export to cache-out), not sent anywhere
        if (ctrl && keys[KeyCode.S.code]) {
            keys[KeyCode.S.code] = false
            keys[KeyCode.W.code] = false
            gadToolsModel.saveRequested.set(true)
        }
        // Ctrl+X: copy the selected area and take its objects away (paste it somewhere else to move a building)
        if (ctrl && keys[KeyCode.X.code]) {
            keys[KeyCode.X.code] = false
            cutSelection()
        }
        // Ctrl+R: turn what you copied 90 degrees before pasting
        if (ctrl && keys[KeyCode.R.code]) {
            keys[KeyCode.R.code] = false
            keys[KeyCode.R.code] = false
            rotateClipboard()
        }
        if (keys[KeyCode.I.code]) {
            keys[KeyCode.I.code] = false
            pickFromHover()
        }
        if (keys[KeyCode.DELETE.code] || keys[KeyCode.BACK_SPACE.code]) {
            keys[KeyCode.DELETE.code] = false
            keys[KeyCode.BACK_SPACE.code] = false
            gadToolsModel.deleteRequested.set(true)
        }
        if (keys[KeyCode.P.code]) {
            keys[KeyCode.P.code] = false // debounce
            val h = hoverModel.hovered.get()
            if (h != null) {
                val w = GadTools.sceneToWorld(scene, h.x, h.y)
                if (w != null) {
                    gadToolsModel.pinned.set(PinnedTile(w.first, w.second, h.sceneTile.z, h.sceneTile.locations.map { it.objId }))
                }
            }
        }
        if (!ctrl && keys[KeyCode.R.code]) {
            keys[KeyCode.R.code] = false // debounce
            val heldObject = objectsModel.heldObject.get()
            if (heldObject != null) {
                objectsModel.heldObject.set(SceneObject(heldObject.objectDefinition, heldObject.type, (heldObject.orientation + 1) and 3))
            } else if (gadToolsModel.selectedObject != null) {
                gadToolsModel.rotateRequested.set(true) // rotate the selected object
            } else if (gadToolsModel.tool.get() == TerrainTool.OVERLAY) {
                gadToolsModel.overlayRotation.set((gadToolsModel.overlayRotation.get() + 1) and 3)
            } else {
                gadToolsModel.rotateRequested.set(true) // rotate the placed object under the mouse
            }
        }
        if (keys[KeyCode.ESCAPE.code]) {
            keys[KeyCode.ESCAPE.code] = false
            if (objectsModel.heldObject.get() != null) objectsModel.heldObject.set(null)
            else if (gadToolsModel.selectedObject != null) {
                gadToolsModel.selectedObject = null
                gadToolsModel.message = "Object deselected."
            } else if (gadToolsModel.selection != null) {
                gadToolsModel.selection = null
                gadToolsModel.message = "Selection cleared."
            }
        }
    }

    /** World tile under the mouse. */
    private fun hoveredWorld(): Pair<Int, Int>? {
        val h = hoverModel.hovered.get() ?: return null
        return GadTools.sceneToWorld(scene, h.x, h.y)
    }

    /** Copy the selected area, then remove its objects (on the copied floors), ready to paste elsewhere. */
    fun cutSelection() {
        val sel = gadToolsModel.selection ?: run {
            gadToolsModel.message = "Select an area first (Select area tool, drag on the map)."
            return
        }
        copySelection()
        gadToolsModel.actions.add {
            val onlyFloor = gadToolsModel.floorToCopy()
            val e = tileOps.removeObjects(sel) { onlyFloor < 0 || it.z == onlyFloor }
            gadToolsModel.message = "Cut ${sel.width} x ${sel.height}: objects removed, copy kept. Hover the new spot and press Ctrl+V (Ctrl+R turns it)."
            e
        }
    }

    /** Turn the copied area or object 90 degrees clockwise. */
    fun rotateClipboard() {
        if (gadToolsModel.lastCopyWasObject) {
            val o = gadToolsModel.objectClipboard ?: return
            gadToolsModel.objectClipboard = o.copy(orientation = (o.orientation + 1) and 3)
            gadToolsModel.message = "Copied object turned to ${((o.orientation + 1) and 3) * 90}\u00B0."
            return
        }
        val clip = gadToolsModel.clipboard ?: run {
            gadToolsModel.message = "Nothing copied yet."
            return
        }
        gadToolsModel.clipboard = clip.rotatedClockwise { id ->
            val d = objectLoader.get(id)
            Pair(d?.sizeX ?: 1, d?.sizeY ?: 1)
        }
        gadToolsModel.message = "Copied area turned 90\u00B0 clockwise (now ${gadToolsModel.clipboard!!.width} x ${gadToolsModel.clipboard!!.height})."
    }

    fun copySelection() {
        gadToolsModel.lastCopyWasObject = false
        val sel = gadToolsModel.selection ?: run {
            gadToolsModel.message = "Select an area first (Select area tool, drag on the map)."
            return
        }
        gadToolsModel.actions.add {
            val onlyFloor = gadToolsModel.floorToCopy()
            val clip = tileOps.copy(sel, onlyFloor)
            gadToolsModel.clipboard = clip
            val painted = clip.tiles.count { it.underlay != 0 || it.overlay != 0 }
            gadToolsModel.message = "Copied ${sel.width} x ${sel.height} tiles" + (if (onlyFloor >= 0) " from floor $onlyFloor" else " (all floors)") +
                    ": $painted painted tiles, ${clip.objects.size} objects." +
                    (if (painted == 0 && clip.objects.isEmpty()) " That floor is empty here - is it really on floor $onlyFloor? Try Copy from: All floors." else
                        " Go to any floor (arrows at the top), hover a tile and press Ctrl+V.")
            null
        }
    }

    fun pasteAtHover() {
        gadToolsModel.clipboardLoading?.let {
            gadToolsModel.message = "Still reading $it from the other cache. Wait for \"Picked\" and press Ctrl+V again."
            return
        }
        val clip = gadToolsModel.clipboard ?: run {
            gadToolsModel.message = "Nothing copied yet (Ctrl+C with an area selected, or pick one in the Area picker)."
            return
        }
        val w = hoveredWorld() ?: run {
            gadToolsModel.message = "Hover a tile to paste on."
            return
        }
        val parts = gadToolsModel.pasteParts
        // anything from another server's cache that your cache doesn't have is left out (it would be invisible or crash the client)
        var unknown = 0
        val safeObjs = clip.objects.filter { (objectLoader.get(it.id) != null).also { ok -> if (!ok) unknown++ } }
        val safeTiles = clip.tiles.map { t ->
            var u = t.underlay; var o = t.overlay; var op = t.opcode
            if (u != 0 && underlayLoader.get(u - 1) == null && underlayLoader.get(u) == null) { u = 0; unknown++ }
            if (o != 0 && overlayLoader.get(o - 1) == null && overlayLoader.get(o) == null) { o = 0; op = 0; unknown++ }
            if (u == t.underlay && o == t.overlay) t else t.copy(underlay = u, overlay = o, opcode = op)
        }
        val safe = if (unknown == 0) clip else clip.copy(tiles = safeTiles, objects = safeObjs)
        gadToolsModel.actions.add {
            val target = gadToolsModel.paintPlane.get()
            val clip = safe
            val e = tileOps.paste(clip, w.first, w.second, parts, target)
            val from = clip.basePlane
            val where = if (from != null) "from floor $from onto floor $target" else "keeping its floors (it was copied with all floors - set Copy from to one floor to move it between floors)"
            val tiles = clip.tiles.count { it.underlay != 0 || it.overlay != 0 }
            gadToolsModel.message = if (e == null) "Paste changed nothing ($where)."
                else "Pasted ${clip.width} x ${clip.height} at ${w.first}, ${w.second} $where: $tiles painted tiles, ${clip.objects.size} objects." +
                    (if (unknown > 0) " Left out $unknown things your cache doesn't have." else "") + " Ctrl+Z undoes it."
            e
        }
    }

    /** Eyedropper: copy underlay, overlay, flags and height of the hovered tile into the tool settings. */
    fun pickFromHover() {
        val h = hoverModel.hovered.get() ?: return
        val region = scene.getRegionFromSceneCoord(h.x, h.y) ?: return
        val def = region.regionDefinition
        val lx = h.x % 64
        val ly = h.y % 64
        val plane = gadToolsModel.paintPlane.get().coerceIn(0, 3)
        val t = def.tiles[plane][lx][ly] ?: return
        val under = (t.underlayId) - 1
        val over = (t.overlayId) - 1
        gadToolsModel.underlayId.set(under)
        gadToolsModel.overlayId.set(if (t.attrOpcode > 0) over else -1)
        if (t.attrOpcode > 1) {
            gadToolsModel.overlayShape.set((t.attrOpcode - 2) / 4)
            gadToolsModel.overlayRotation.set((t.attrOpcode - 2) and 3)
        }
        val ground = def.tiles[0][lx][ly]
        if (ground != null) gadToolsModel.exactHeight.set((ground.height / -8).coerceIn(0, 255))
        gadToolsModel.message = "Picked plane $plane: underlay ${if (under < 0) "none" else under}, " +
                "overlay ${if (t.attrOpcode > 0 && over >= 0) "$over (shape ${(t.attrOpcode - 2) / 4}, rot ${(t.attrOpcode - 2) and 3})" else "none"}, " +
                "flags ${t.settings.toInt() and 0xFF}, height ${gadToolsModel.exactHeight.get()}."
    }

    companion object {
        val TOOL_KEYS = listOf(KeyCode.DIGIT1.code, KeyCode.DIGIT2.code, KeyCode.DIGIT3.code, KeyCode.DIGIT4.code, KeyCode.DIGIT5.code, KeyCode.DIGIT6.code,
            KeyCode.DIGIT7.code, KeyCode.DIGIT8.code, KeyCode.DIGIT9.code, KeyCode.DIGIT0.code, KeyCode.MINUS.code, KeyCode.EQUALS.code)
        val TOOL_KEY_NAMES = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")
    }

    fun isKeyDown(keyCode: KeyCode): Boolean = keys[keyCode.code]

    private var view: GlView? = null
    /** Mouse position in the view's pixels (the view draws at the screen's real resolution). */
    private fun px(v: Double) = (v * (view?.pixelScale ?: 1.0)).toInt()

    /** Takes keyboard and mouse input from the 3D view. */
    fun attach(v: GlView) {
        view = v
        val n = v.node
        // keys go to the map while it has focus (as before, when it was its own window): consumed, so arrows and
        // Tab don't move focus to the panels and shortcuts aren't run twice
        n.addEventFilter(KeyEvent.KEY_PRESSED) { val c = it.code.code; if (!keys[c]) fresh.add(c); keys[c] = true; it.consume() }
        n.addEventFilter(KeyEvent.KEY_RELEASED) {
            val c = it.code.code
            // a tap shorter than one tick is still seen once: the release waits until the keys have been read
            if (c in fresh) late.add(c) else keys[c] = false
            it.consume()
        }
        n.addEventFilter(KeyEvent.KEY_TYPED) { it.consume() }
        n.focusedProperty().addListener { _, _, has -> if (!has) keys.clear() }
        n.setOnMousePressed { v.requestFocus(); mousePressed(it) }
        n.setOnMouseReleased { mouseReleased(it) }
        n.setOnMouseClicked { mouseClicked(it) }
        n.setOnMouseMoved { mouseMoved(it) }
        n.setOnMouseDragged { mouseDragged(it) }
        n.setOnMouseEntered { mouseEntered(it) }
        n.setOnMouseExited { mouseExited(it) }
        n.setOnScroll { mouseWheelMoved(it) }
    }

    private fun handleCameraDrag(x: Int, y: Int) {
        val dx = previousMouseX - x
        camera.addYaw(dx)

        val dy = previousMouseY - y
        camera.addPitch(-dy)
    }

    private fun mouseClicked(e: MouseEvent) {
        if (e.button == MouseButton.PRIMARY && e.isStillSincePress) {
            mouseClicked = true
        }
    }

    private fun mouseEntered(e: MouseEvent) {
        // a button released outside the 3D view never reaches us: trust the real button state
        if (!e.isPrimaryButtonDown) isLeftMouseDown = false
        if (!e.isSecondaryButtonDown) isRightMouseDown = false
    }

    private fun mouseExited(e: MouseEvent) {
        // a drag that leaves the view keeps going (JavaFX keeps sending it to us); a plain move out ends the stroke
        if (e.isPrimaryButtonDown || e.isSecondaryButtonDown) return
        isLeftMouseDown = false
        isRightMouseDown = false
        mouseClicked = false
        keys.clear()
    }

    private fun mousePressed(e: MouseEvent) {
        if (e.button == MouseButton.SECONDARY) {
            isRightMouseDown = true
            previousMouseX = px(e.x)
            previousMouseY = px(e.y)
        }
        if (e.button == MouseButton.PRIMARY) {
            leftMousePressed = true
            isLeftMouseDown = true
            leftPressLatch.set(true)
        }
    }

    private fun mouseReleased(e: MouseEvent) {
        if (e.button == MouseButton.SECONDARY) {
            isRightMouseDown = false
        }
        if (e.button == MouseButton.PRIMARY) {
            isLeftMouseDown = false
        }
    }

    private fun mouseMoved(e: MouseEvent) {
        // moved (not dragged) means no button is held: clear anything left stuck down
        isLeftMouseDown = false
        isRightMouseDown = false
        mouseX = px(e.x)
        mouseY = px(e.y)
    }

    private fun mouseDragged(e: MouseEvent) {
        val x = px(e.x); val y = px(e.y)
        if (isRightMouseDown) {
            handleCameraDrag(x, y)
        }
        mouseX = x
        mouseY = y
        previousMouseX = x
        previousMouseY = y
    }

    /** Mouse wheel zooms toward / away from where the camera looks. */
    private fun mouseWheelMoved(e: ScrollEvent) {
        // one wheel notch is 40 units of deltaY on every platform JavaFX runs on
        val notches = (e.deltaY / 40.0).toFloat()
        if (notches == 0f) return
        val step = notches * (if (keys[KeyCode.SHIFT.code]) 1200 else 400)
        val xVec = (-camera.yawSin).toDouble() / 65535
        val yVec = camera.yawCos.toDouble() / 65535
        val zVec = camera.pitchSin.toDouble() / 65535
        camera.addX((step * xVec).toInt())
        camera.addY((step * yVec).toInt())
        camera.addZ((step * zVec).toInt())
    }

    init {
        object : AnimationTimer() {
            var lastNanoTime = System.nanoTime()
            override fun handle(now: Long) {
                val dt = (now - lastNanoTime) / 1000000.toDouble()
                lastNanoTime = now
                handleKeys(dt)
            }
        }.start()
    }
}