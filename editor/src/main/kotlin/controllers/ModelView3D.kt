package controllers

import cache.definitions.ModelDefinition
import javafx.scene.AmbientLight
import javafx.scene.Group
import javafx.scene.PerspectiveCamera
import javafx.scene.PointLight
import javafx.scene.SceneAntialiasing
import javafx.scene.SubScene
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseButton
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.CullFace
import javafx.scene.shape.DrawMode
import javafx.scene.shape.MeshView
import javafx.scene.shape.TriangleMesh
import javafx.scene.transform.Rotate
import javafx.scene.transform.Translate
import utils.ModelCodec

/**
 * A 3D view of one game model: drag to turn it, right-drag to move it, scroll to zoom. The whole model is one
 * mesh (each face's colour comes from a small colour strip), so moving its vertices for an animation is quick.
 */
class ModelView3D(width: Double, height: Double) : Pane() {
    private val root = Group()
    private val holder = Group()
    private val rotX = Rotate(-20.0, Rotate.X_AXIS)
    private val rotY = Rotate(200.0, Rotate.Y_AXIS)
    private val pan = Translate()
    private val center = Translate()
    private val camera = PerspectiveCamera(true)
    private val sub: SubScene
    private var mesh: TriangleMesh? = null
    private var meshView: MeshView? = null
    private var faceVerts: IntArray = IntArray(0) // per kept face: a, b, c
    private var points = FloatArray(0)
    var wireframe = false
        set(v) { field = v; meshView?.drawMode = if (v) DrawMode.LINE else DrawMode.FILL }

    init {
        holder.transforms.addAll(pan, rotX, rotY, center)
        root.children.addAll(holder, AmbientLight(Color.rgb(150, 150, 150)), PointLight(Color.rgb(140, 140, 140)).apply { translateZ = -3000.0; translateY = -1500.0 })
        camera.nearClip = 1.0; camera.farClip = 100000.0; camera.translateZ = -1200.0; camera.fieldOfView = 35.0
        sub = SubScene(root, width, height, true, SceneAntialiasing.BALANCED)
        sub.fill = Color.rgb(28, 24, 22)
        sub.camera = camera
        sub.widthProperty().bind(widthProperty()); sub.heightProperty().bind(heightProperty())
        children.add(sub)
        setPrefSize(width, height)
        var ax = 0.0; var ay = 0.0; var rx = 0.0; var ry = 0.0; var px = 0.0; var py = 0.0
        setOnMousePressed { ax = it.sceneX; ay = it.sceneY; rx = rotX.angle; ry = rotY.angle; px = pan.x; py = pan.y }
        setOnMouseDragged {
            if (it.button == MouseButton.PRIMARY) { rotY.angle = ry - (it.sceneX - ax) * 0.6; rotX.angle = (rx + (it.sceneY - ay) * 0.6).coerceIn(-90.0, 90.0) }
            else { pan.x = px + (it.sceneX - ax) * (-camera.translateZ / 900.0); pan.y = py + (it.sceneY - ay) * (-camera.translateZ / 900.0) }
        }
        setOnScroll { camera.translateZ = (camera.translateZ * if (it.deltaY > 0) 0.88 else 1.13).coerceIn(-60000.0, -40.0) }
    }

    /** Show [md]. Returns false when it has nothing drawable. */
    fun show(md: ModelDefinition?): Boolean {
        holder.children.clear(); mesh = null; meshView = null
        if (md == null || md.faceCount == 0 || md.vertexCount == 0) return false
        // the faces the game draws, and their colours
        val colours = LinkedHashMap<Int, Int>()
        val keep = ArrayList<Int>()
        for (i in 0 until md.faceCount) {
            val tex = md.faceTextures?.get(i)?.toInt() ?: -1
            val c = (md.faceColors?.get(i)?.toInt() ?: 0) and 0xFFFF
            if (tex == -1 && c == 65535) continue
            if ((md.faceAlphas?.get(i)?.toInt() ?: 0) and 255 == 255) continue
            val rgb = if (tex != -1) 0x9A9A9A else ModelCodec.palette[c]
            colours.getOrPut(rgb) { colours.size }
            keep.add(i)
        }
        if (keep.isEmpty()) return false
        val w = colours.size.coerceAtLeast(1)
        val img = WritableImage(w, 1)
        for ((rgb, idx) in colours) img.pixelWriter.setArgb(idx, 0, 0xFF000000.toInt() or rgb)
        val m = TriangleMesh()
        points = FloatArray(md.vertexCount * 3)
        setPoints(md.vertexPositionsX, md.vertexPositionsY, md.vertexPositionsZ)
        m.points.setAll(*points)
        val tc = FloatArray(w * 2)
        for (i in 0 until w) { tc[i * 2] = (i + 0.5f) / w; tc[i * 2 + 1] = 0.5f }
        m.texCoords.setAll(*tc)
        val faces = IntArray(keep.size * 6)
        faceVerts = IntArray(keep.size * 3)
        for ((k, i) in keep.withIndex()) {
            val tex = md.faceTextures?.get(i)?.toInt() ?: -1
            val rgb = if (tex != -1) 0x9A9A9A else ModelCodec.palette[(md.faceColors?.get(i)?.toInt() ?: 0) and 0xFFFF]
            val t = colours[rgb]!!
            val a = md.faceVertexIndices1!![i]; val b = md.faceVertexIndices2!![i]; val c = md.faceVertexIndices3!![i]
            // with both axes turned round the game's face order is already the way JavaFX wants it
            faces[k * 6] = a; faces[k * 6 + 1] = t; faces[k * 6 + 2] = b; faces[k * 6 + 3] = t; faces[k * 6 + 4] = c; faces[k * 6 + 5] = t
            faceVerts[k * 3] = a; faceVerts[k * 3 + 1] = b; faceVerts[k * 3 + 2] = c
        }
        m.faces.setAll(*faces)
        m.faceSmoothingGroups.setAll(*IntArray(keep.size)) // flat shading, like most game models
        val view = MeshView(m)
        view.material = PhongMaterial().apply { diffuseMap = img; specularColor = Color.rgb(25, 25, 25) }
        view.cullFace = CullFace.NONE
        view.drawMode = if (wireframe) DrawMode.LINE else DrawMode.FILL
        holder.children.add(view)
        mesh = m; meshView = view
        frame(md)
        return true
    }

    /** x and z both flipped: the game's axes turned round to JavaFX's without mirroring the model (one flip alone swaps left and right). */
    private fun setPoints(xs: IntArray, ys: IntArray, zs: IntArray) {
        for (v in xs.indices) { points[v * 3] = -xs[v].toFloat(); points[v * 3 + 1] = ys[v].toFloat(); points[v * 3 + 2] = -zs[v].toFloat() }
    }

    /** Move the vertices (for animation frames). */
    fun update(xs: IntArray, ys: IntArray, zs: IntArray) {
        val m = mesh ?: return
        if (xs.size * 3 != points.size) return
        setPoints(xs, ys, zs)
        m.points.setAll(*points)
    }

    /** Zoom so the whole model fits. */
    private fun frame(md: ModelDefinition) {
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE; var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
        for (v in 0 until md.vertexCount) {
            minX = minOf(minX, md.vertexPositionsX[v]); maxX = maxOf(maxX, md.vertexPositionsX[v])
            minY = minOf(minY, md.vertexPositionsY[v]); maxY = maxOf(maxY, md.vertexPositionsY[v])
            minZ = minOf(minZ, md.vertexPositionsZ[v]); maxZ = maxOf(maxZ, md.vertexPositionsZ[v])
        }
        val size = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(16)
        center.x = (minX + maxX) / 2.0; center.y = -(minY + maxY) / 2.0; center.z = (minZ + maxZ) / 2.0
        pan.x = 0.0; pan.y = 0.0
        camera.translateZ = -size * 2.6
    }
}
