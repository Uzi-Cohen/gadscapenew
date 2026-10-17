package controllers.worldRenderer

import com.google.inject.Inject
import javafx.fxml.FXML
import javafx.scene.Group
import javafx.scene.layout.AnchorPane

class WorldRendererController @Inject constructor(val renderer: Renderer) {
    @FXML
    lateinit var anchorPane: AnchorPane

    @FXML
    lateinit var group: Group

    @FXML
    fun initialize() {
        renderer.initCanvas(group)
        renderer.loadScene()
        // the 3D view is an ordinary node now: it just takes the panel's size
        anchorPane.widthProperty().addListener { _, _, _ -> forceRefresh() }
        anchorPane.heightProperty().addListener { _, _, _ -> forceRefresh() }
        anchorPane.sceneProperty().addListener { _, _, s -> if (s != null) forceRefresh() }
        renderer.requestFocus()
    }

    fun forceRefresh() {
        renderer.resize(anchorPane.width, anchorPane.height)
    }
}
