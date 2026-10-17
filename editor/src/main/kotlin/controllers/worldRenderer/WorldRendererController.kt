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
        // the panel can be any size: what's outside it is cut off, and the view follows its size
        anchorPane.setMinSize(0.0, 0.0)
        anchorPane.clip = javafx.scene.shape.Rectangle().apply {
            widthProperty().bind(anchorPane.widthProperty()); heightProperty().bind(anchorPane.heightProperty())
        }
        renderer.initCanvas(group)
        anchorPane.children.add(javafx.scene.control.Label().apply {
            textProperty().bind(utils.GadTools.loadingText)
            visibleProperty().bind(utils.GadTools.loadingText.isNotEmpty)
            isMouseTransparent = true
            style = "-fx-background-color: rgba(0,0,0,0.65); -fx-text-fill: white; -fx-padding: 6 10; -fx-font-size: 14px;"
            javafx.scene.layout.AnchorPane.setTopAnchor(this, 10.0); javafx.scene.layout.AnchorPane.setLeftAnchor(this, 10.0)
        })
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
