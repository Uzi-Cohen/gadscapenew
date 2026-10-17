package controllers

import javafx.fxml.FXML
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import java.awt.Desktop
import java.net.URI

class AboutController {

    @FXML
    private lateinit var lblVersion: Label

    @FXML
    private lateinit var lblAbout: Label

    @FXML
    private lateinit var linkGithub: Hyperlink

    @FXML
    fun initialize() {
        lblVersion.text = "v${utils.Branding.VERSION}  ·  built for GadScape"
        linkGithub.setOnAction {
            Desktop.getDesktop().browse(URI("https://github.com/tpetrychyn/osrs-map-editor"))
        }
        lblAbout.text = "Map editor for the GadScape server: terrain sculpting, object placing and removing, " +
                "go-to coords and bookmarks, spawn line copying, region backups, one-click deploy to the live server, " +
                "and portable packaging.\n\n" +
                "Based on the open-source osrs-map-editor by tpetrychyn (link above).\n\n" +
                "Thanks to Displee (rs-cache-library), Explv (OSRS map), Runestats (cache archive) and OpenOSRS (definitions)."
    }
}