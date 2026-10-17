package controllers.worldRenderer

import com.jogamp.newt.event.KeyEvent
import com.jogamp.newt.event.KeyListener
import com.jogamp.newt.event.MouseEvent
import com.jogamp.newt.event.MouseListener
import javafx.animation.AnimationTimer
import javafx.scene.input.KeyCode
import jogamp.newt.driver.DriverClearFocus
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
) : KeyListener, MouseListener {

    private val tileOps = TileOps(editorRegions)

    var renderer: Renderer? = null

    var isLeftMouseDown = false
    var leftMousePressed = false
    var isRightMouseDown = false
    private val keys = BooleanArray(250)
    var mouseClicked = false
    private var previousMouseX = 0
    private var previousMouseY = 0
    var mouseX = 0
    var mouseY = 0

    private fun handleKeys(dt: Double) {
        if (dt > 1000) { // big lag spike, don't send the user flying
            return
        }
        val xVec = (-camera.yawSin).toDouble() / 65535
        val yVec = camera.yawCos.toDouble() / 65535
        val zVec = camera.pitchSin.toDouble() / 65535
        var speed = 1
        if (keys[KeyEvent.VK_SHIFT.toInt()]) {
            speed = 4
        }
        if (keys[KeyEvent.VK_W.toInt()]) {
            camera.addX((dt * xVec * speed).toInt())
            camera.addY((dt * yVec * speed).toInt())
            camera.addZ((dt * zVec * speed).toInt())
        }
        if (keys[KeyEvent.VK_S.toInt()]) {
            camera.addX((-(dt * xVec * speed)).toInt())
            camera.addY((-(dt * yVec * speed)).toInt())
            camera.addZ((-(dt * zVec * speed)).toInt())
        }
        if (keys[KeyEvent.VK_A.toInt()]) {
            // X uses yVec because we want to move perpendicular
            camera.addX((-(dt * yVec * speed)).toInt())
            camera.addY((dt * xVec * speed).toInt())
        }
        if (keys[KeyEvent.VK_D.toInt()]) {
            camera.addX((dt * yVec * speed).toInt())
            camera.addY((-(dt * xVec * speed)).toInt())
        }
        if (keys[KeyEvent.VK_SPACE.toInt()]) {
            camera.addZ((-dt).toInt() * speed)
        }
        if (keys[KeyEvent.VK_X.toInt()]) {
            camera.addZ(dt.toInt() * speed)
        }
        // floors: Ctrl + Page Up / Page Down changes the floor you see and edit
        val ctrlDown = keys[KeyEvent.VK_CONTROL.toInt()]
        if (ctrlDown && (keys[KeyEvent.VK_PAGE_UP.toInt()] || keys[KeyEvent.VK_PAGE_DOWN.toInt()])) {
            val dir = if (keys[KeyEvent.VK_PAGE_UP.toInt()]) 1 else -1
            keys[KeyEvent.VK_PAGE_UP.toInt()] = false
            keys[KeyEvent.VK_PAGE_DOWN.toInt()] = false
            val p = (gadToolsModel.paintPlane.get() + dir).coerceIn(0, 3)
            if (p != gadToolsModel.paintPlane.get()) {
                gadToolsModel.paintPlane.set(p)
                gadToolsModel.message = "Floor $p" + if (p == 0) " (ground)" else ""
            }
        }
        // camera up / down: Page Up/Down or E/Q (Shift = faster)
        if (!ctrlDown && (keys[KeyEvent.VK_PAGE_UP.toInt()] || keys[KeyEvent.VK_E.toInt()])) {
            camera.addZ((-dt * speed).toInt())
        }
        if (!ctrlDown && (keys[KeyEvent.VK_PAGE_DOWN.toInt()] || keys[KeyEvent.VK_Q.toInt()])) {
            camera.addZ((dt * speed).toInt())
        }
        // Arrow keys: pan flat across the map. Shift = faster. Ctrl + arrow = load the next region over.
        val ctrl = keys[KeyEvent.VK_CONTROL.toInt()]
        val pan = if (keys[KeyEvent.VK_SHIFT.toInt()]) 8 else 2
        val up = keys[KeyEvent.VK_UP.toInt()]
        val down = keys[KeyEvent.VK_DOWN.toInt()]
        val left = keys[KeyEvent.VK_LEFT.toInt()]
        val right = keys[KeyEvent.VK_RIGHT.toInt()]
        if (ctrl) {
            var dx = 0
            var dy = 0
            if (up) { dy = 1; keys[KeyEvent.VK_UP.toInt()] = false }
            if (down) { dy = -1; keys[KeyEvent.VK_DOWN.toInt()] = false }
            if (left) { dx = -1; keys[KeyEvent.VK_LEFT.toInt()] = false }
            if (right) { dx = 1; keys[KeyEvent.VK_RIGHT.toInt()] = false }
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
            if (keys[k.toInt()] && i < TerrainTool.values().size) {
                keys[k.toInt()] = false
                gadToolsModel.tool.set(TerrainTool.values()[i])
            }
        }
        if (keys[KeyEvent.VK_OPEN_BRACKET.toInt()]) {
            keys[KeyEvent.VK_OPEN_BRACKET.toInt()] = false
            gadToolsModel.brushSize.set((gadToolsModel.brushSize.get() - 1).coerceAtLeast(0))
        }
        if (keys[KeyEvent.VK_CLOSE_BRACKET.toInt()]) {
            keys[KeyEvent.VK_CLOSE_BRACKET.toInt()] = false
            gadToolsModel.brushSize.set((gadToolsModel.brushSize.get() + 1).coerceAtMost(15))
        }
        if (ctrl && keys[KeyEvent.VK_Z.toInt()]) {
            keys[KeyEvent.VK_Z.toInt()] = false
            if (keys[KeyEvent.VK_SHIFT.toInt()]) gadToolsModel.redoRequested.set(true)
            else gadToolsModel.undoRequested.set(true)
        }
        if (ctrl && keys[KeyEvent.VK_Y.toInt()]) {
            keys[KeyEvent.VK_Y.toInt()] = false
            gadToolsModel.redoRequested.set(true)
        }
        // F = star / unstar the held, selected or copied object
        if (!ctrl && keys[KeyEvent.VK_F.toInt()]) {
            keys[KeyEvent.VK_F.toInt()] = false
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
        if (!ctrl && keys[KeyEvent.VK_M.toInt()]) {
            keys[KeyEvent.VK_M.toInt()] = false
            gadToolsModel.moveObjectRequested.set(true)
        }
        // Ctrl+C / Ctrl+V: the selected object if there is one, otherwise the selected area
        if (ctrl && keys[KeyEvent.VK_C.toInt()]) {
            keys[KeyEvent.VK_C.toInt()] = false
            // with the Select area tool and a box selected, Ctrl+C is always the area (a leftover selected object used to win)
            val areaFirst = gadToolsModel.tool.get() == models.TerrainTool.SELECT && gadToolsModel.selection != null
            if (!areaFirst && gadToolsModel.selectedObject != null) gadToolsModel.copyObjectRequested.set(true) else copySelection()
        }
        if (ctrl && keys[KeyEvent.VK_V.toInt()]) {
            keys[KeyEvent.VK_V.toInt()] = false
            val areaPaste = gadToolsModel.tool.get() == models.TerrainTool.SELECT && gadToolsModel.clipboard != null
            if (!areaPaste && gadToolsModel.lastCopyWasObject && gadToolsModel.objectClipboard != null) gadToolsModel.pasteObjectRequested.set(true)
            else pasteAtHover()
        }
        // Ctrl+T: tile numbers window on / off
        if (ctrl && keys[KeyEvent.VK_T.toInt()]) {
            keys[KeyEvent.VK_T.toInt()] = false
            gadToolsModel.tileNumbersRequested.set(true)
        }
        // Ctrl+S: save the map (export to cache-out), not sent anywhere
        if (ctrl && keys[KeyEvent.VK_S.toInt()]) {
            keys[KeyEvent.VK_S.toInt()] = false
            keys[KeyEvent.VK_W.toInt()] = false
            gadToolsModel.saveRequested.set(true)
        }
        // Ctrl+X: copy the selected area and take its objects away (paste it somewhere else to move a building)
        if (ctrl && keys[KeyEvent.VK_X.toInt()]) {
            keys[KeyEvent.VK_X.toInt()] = false
            cutSelection()
        }
        // Ctrl+R: turn what you copied 90 degrees before pasting
        if (ctrl && keys[KeyEvent.VK_R.toInt()]) {
            keys[KeyEvent.VK_R.toInt()] = false
            keys[KeyEvent.VK_R.toInt()] = false
            rotateClipboard()
        }
        if (keys[KeyEvent.VK_I.toInt()]) {
            keys[KeyEvent.VK_I.toInt()] = false
            pickFromHover()
        }
        if (keys[KeyEvent.VK_DELETE.toInt()] || keys[KeyEvent.VK_BACK_SPACE.toInt()]) {
            keys[KeyEvent.VK_DELETE.toInt()] = false
            keys[KeyEvent.VK_BACK_SPACE.toInt()] = false
            gadToolsModel.deleteRequested.set(true)
        }
        if (keys[KeyEvent.VK_P.toInt()]) {
            keys[KeyEvent.VK_P.toInt()] = false // debounce
            val h = hoverModel.hovered.get()
            if (h != null) {
                val w = GadTools.sceneToWorld(scene, h.x, h.y)
                if (w != null) {
                    gadToolsModel.pinned.set(PinnedTile(w.first, w.second, h.sceneTile.z, h.sceneTile.locations.map { it.objId }))
                }
            }
        }
        if (!ctrl && keys[KeyEvent.VK_R.toInt()]) {
            keys[KeyEvent.VK_R.toInt()] = false // debounce
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
        if (keys[KeyEvent.VK_ESCAPE.toInt()]) {
            keys[KeyEvent.VK_ESCAPE.toInt()] = false
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
        val TOOL_KEYS = listOf(KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, KeyEvent.VK_6,
            KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS)
        val TOOL_KEY_NAMES = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")
    }

    fun isKeyDown(keyCode: KeyCode): Boolean {
        return keys[keyCode.code-1]
    }

    override fun keyPressed(e: KeyEvent) {
        keys[e.keyCode.toInt()] = true
    }

    override fun keyReleased(e: KeyEvent) {
        if (e.isAutoRepeat) {
            return
        }
        keys[e.keyCode.toInt()] = false
    }

    private fun handleCameraDrag(e: MouseEvent) {
        val dx = previousMouseX - e.x
        camera.addYaw(dx)

        val dy = previousMouseY - e.y
        camera.addPitch(-dy)
    }

    override fun mouseClicked(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON1) {
            mouseClicked = true
        }

    }

    override fun mouseEntered(e: MouseEvent) {
        // a button released outside the 3D view never reaches us: trust the real button state
        if (!e.isButtonDown(MouseEvent.BUTTON1.toInt())) isLeftMouseDown = false
        if (!e.isButtonDown(MouseEvent.BUTTON3.toInt())) isRightMouseDown = false
    }

    override fun mouseExited(e: MouseEvent) {
        // leaving the 3D view ends any stroke, so nothing keeps painting while you use the panels
        isLeftMouseDown = false
        isRightMouseDown = false
        mouseClicked = false
        java.util.Arrays.fill(keys, false)
    }
    override fun mousePressed(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON3) {
            isRightMouseDown = true
            previousMouseX = e.x
            previousMouseY = e.y
        }
        if (e.button == MouseEvent.BUTTON1) {
            leftMousePressed = true
            isLeftMouseDown = true
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON3) {
            isRightMouseDown = false
        }
        if (e.button == MouseEvent.BUTTON1) {
            isLeftMouseDown = false
        }
    }

    override fun mouseMoved(e: MouseEvent) {
        // moved (not dragged) means no button is held: clear anything left stuck down
        isLeftMouseDown = false
        isRightMouseDown = false
        mouseX = e.x
        mouseY = e.y
    }

    override fun mouseDragged(e: MouseEvent) {
        if (isRightMouseDown) {
            handleCameraDrag(e)
        }
        mouseX = e.x
        mouseY = e.y
        previousMouseX = e.x
        previousMouseY = e.y
    }

    /** Mouse wheel zooms toward / away from where the camera looks. */
    override fun mouseWheelMoved(e: MouseEvent) {
        val r = e.rotation
        val notches = if (r.size > 1 && r[1] != 0f) r[1] else if (r.isNotEmpty()) r[0] else 0f
        if (notches == 0f) return
        val step = notches * (if (keys[KeyEvent.VK_SHIFT.toInt()]) 1200 else 400)
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