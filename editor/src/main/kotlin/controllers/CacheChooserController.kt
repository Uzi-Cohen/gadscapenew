package controllers

import JfxApplication.Companion.injector
import cache.CacheLibraryProvider
import cache.XteaManagerProvider
import com.google.inject.Inject
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.transformation.FilteredList
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.text.TextAlignment
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import javafx.util.Callback
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import models.Configuration
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.jsoup.Jsoup
import java.io.*
import java.net.URL
import javax.net.ssl.SSLHandshakeException


class CacheChooserController @Inject constructor(
    private val cacheLibraryProvider: CacheLibraryProvider,
    private val xteaManagerProvider: XteaManagerProvider,
    private val configuration: Configuration
) {

    @FXML
    private lateinit var listCaches: ListView<String>
    private var entries: ObservableList<String> = FXCollections.observableArrayList()

    @FXML
    private lateinit var btnChooseDirectory: Button

    @FXML
    private lateinit var txtCacheLocation: TextField

    @FXML
    private lateinit var txtFilter: TextField

    @FXML
    private lateinit var btnDownload: Button

    @FXML
    private lateinit var lblStatusText: Label
    @FXML
    private lateinit var lblErrorText: Label

    private var selectedCache: String? = null

    @FXML
    private lateinit var btnLaunch: Button

    private var launched = false
    private var autoTimer: javafx.animation.Timeline? = null

    // @FXML must sit directly on initialize(), or JavaFX never calls it (that broke v23-v27)
    @FXML
    private fun initialize() {
        val javaVer = System.getProperty("java.version")
        println("Java version: $javaVer")
        val majorVer = javaVer.split(".")[0].toInt()
        if (majorVer < 11) {
            lblErrorText.isVisible = true
            lblErrorText.text = "Java version detected ($javaVer) is too low, please update Java to at least version 11."
            btnLaunch.isDisable = true
            return
        }

        val listCachesPlaceholder = Label("No downloadable caches found.")
        listCachesPlaceholder.isWrapText = true
        listCachesPlaceholder.textAlignment = TextAlignment.CENTER
        listCaches.placeholder = listCachesPlaceholder

        try {
            val doc = Jsoup.connect(RUNESTATS_URL).get()
            entries.addAll(doc.select("a")
                .map { col -> col.attr("href") }
                .filter { it.length > 10 } // get rid of ../ and ./types
                .reversed()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            listCachesPlaceholder.text += "\n\n${e.message}"
            if (e is SSLHandshakeException) {
                listCachesPlaceholder.text += "\n\nSSLHandshakeException is a known bug with certain Java versions, try updating."
            }
        }

        val filterableEntries = FilteredList(entries)

        listCaches.items = filterableEntries

        listCaches.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            if (newValue != null) {
                selectedCache = newValue
                btnDownload.isDisable = false
            }
        }

        txtFilter.textProperty()
            .addListener { _: ObservableValue<out String>?, _: String?, newVal: String ->
                filterableEntries.setPredicate { obj ->
                    newVal.isEmpty() || obj.toString().toLowerCase().contains(newVal.toLowerCase())
                }
            }

        btnDownload.setOnAction {
            btnDownload.isDisable = true
            downloadCache(selectedCache!!)
        }

        txtCacheLocation.textProperty().addListener { _, _, newVal ->
            if (newVal != "") {
                btnLaunch.isDisable = false
                lblErrorText.isVisible = false
                try {
                    // open the edited copy (cache-out) when there is one, so earlier exported edits are there
                    val edited = File(txtCacheLocation.text, "cache-out/main_file_cache.dat2").isFile
                    cacheLibraryProvider.setLibraryLocation("${txtCacheLocation.text}/" + if (edited) "cache-out" else "cache")
                    lblStatusText.isVisible = true
                    lblStatusText.text = if (edited) "Your edited map (cache-out) will open." else "Original map (cache) will open."
                }  catch (e: Exception) {
                    e.printStackTrace()
                    lblErrorText.text = e.message
                    lblErrorText.isVisible = true
                    btnLaunch.isDisable = true
                }
                try {
                    xteaManagerProvider.setXteaLocation(txtCacheLocation.text)
                } catch (e: Exception) {
                    e.printStackTrace()
                    lblErrorText.text = e.message
                    lblErrorText.isVisible = true
                    btnLaunch.isDisable = true
                }
            } else {
                btnLaunch.isDisable = true
            }
        }

        btnChooseDirectory.setOnAction {
            val directoryChooser = DirectoryChooser()
            directoryChooser.title = "Pick the map folder (the one with cache\\ and xteas.json in it)"
            // start somewhere that really exists: the current map, the app's folder, or your user folder
            val start = listOfNotNull(
                txtCacheLocation.text?.takeIf { it.isNotBlank() }?.let { File(it) },
                txtCacheLocation.text?.takeIf { it.isNotBlank() }?.let { File(it).parentFile },
                utils.Updater.installDir,
                File(System.getProperty("user.home"))
            ).firstOrNull { it.isDirectory }
            if (start != null) directoryChooser.initialDirectory = start
            // owned by this window, so it opens in front of it instead of behind
            val opened = System.currentTimeMillis()
            val f = try {
                directoryChooser.showDialog(btnChooseDirectory.scene?.window)
            } catch (e: Exception) {
                println("Browse failed: ${e.message}")
                null
            }
            if (f != null) {
                txtCacheLocation.text = f.absolutePath
            } else if (System.currentTimeMillis() - opened < 400) {
                // the folder window never really opened: ask for the path instead
                println("Browse: folder window didn't open, asking for the path")
                val dialog = javafx.scene.control.TextInputDialog(txtCacheLocation.text ?: "C:\\GadScape\\Editor").apply {
                    title = "Map folder"
                    headerText = "Paste the map folder (the one with cache\\ and xteas.json in it)"
                    contentText = "Folder:"
                    dialogPane.minWidth = 520.0
                }
                dialog.showAndWait().orElse(null)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let { txtCacheLocation.text = it }
            }
        }

        btnLaunch.setOnAction {
            // only ever one editor window (two launches = two 3D render loops = crash)
            if (launched) return@setOnAction
            launched = true
            btnLaunch.isDisable = true
            autoTimer?.stop()
            lblStatusText.isVisible = true
            lblStatusText.text = "Launching Uzi's Map Editor, please wait..."

            GlobalScope.launch {
              try {
                configuration.saveProp("last-cache-dir", txtCacheLocation.text)
                btnLaunch.isDisable = true
                // load and open main scene
                val fxmlLoader = FXMLLoader()
                fxmlLoader.controllerFactory = Callback { type: Class<*>? ->
                    injector.getInstance(type)
                }
                fxmlLoader.location = javaClass.getResource("/views/main.fxml")
                val root = fxmlLoader.load<Parent>()
                val controller = fxmlLoader.getController<MainController>()
                val jfxScene = Scene(root)
                Platform.runLater {
                    val stage = Stage()
                    stage.scene = jfxScene
                    utils.Branding.apply(stage, "v${utils.Branding.VERSION}")
                    stage.isMaximized = true
                    stage.setOnShown { controller.forceRefresh() }
                    // stop the 3D drawing before anything is torn down, then quit cleanly
                    stage.setOnCloseRequest { ev ->
                        // never lose work: ask when there are unsaved edits
                        if (controller.hasUnsaved()) {
                            val save = ButtonType("Save")
                            val dont = ButtonType("Don't save")
                            val alert = Alert(Alert.AlertType.WARNING, "You have changes that are not saved yet. Save them before closing?",
                                save, dont, ButtonType.CANCEL).apply { title = "Unsaved changes"; headerText = null; initOwner(stage) }
                            when (alert.showAndWait().orElse(ButtonType.CANCEL)) {
                                save -> controller.save()
                                dont -> {}
                                else -> { ev.consume(); return@setOnCloseRequest }
                            }
                        }
                        controllers.worldRenderer.Renderer.shutdownAll()
                        Platform.exit()
                        System.exit(0)
                    }
                    // the 3D view's native window pumps mouse events while the window is still being built,
                    // which threw hundreds of harmless "Index -1" errors: ignore the mouse until it's up
                    root.isMouseTransparent = true
                    stage.show()
                    javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5)).apply { setOnFinished { root.isMouseTransparent = false } }.play()

                    (btnLaunch.scene.window as Stage).close()
                }
              } catch (e: Throwable) {
                // the launch failed: say why and let you pick again (v23 left the window stuck here)
                e.printStackTrace()
                Platform.runLater {
                    launched = false
                    btnLaunch.isDisable = false
                    lblStatusText.text = "Couldn't open that map. Pick another one or check the folder."
                    lblErrorText.text = (e.cause ?: e).toString().take(200)
                    lblErrorText.isVisible = true
                }
              }
            }
        }

        // the path box can be typed / pasted into
        txtCacheLocation.isDisable = false
        fun isMap(p: String?) = !p.isNullOrBlank() &&
                (File(p, "cache/main_file_cache.dat2").isFile || File(p, "cache-out/main_file_cache.dat2").isFile)
        val pick = listOfNotNull(
            configuration.getProp("last-cache-dir"),
            "C:\\GadScape\\Editor",
            utils.Updater.installDir?.let { File(it, "map").path }
        ).firstOrNull { isMap(it) } ?: configuration.getProp("last-cache-dir")
        println("Cache window: picked ${if (pick.isBlank()) "nothing" else pick}")
        txtCacheLocation.text = pick

        // portable packages open the last map by themselves after a short countdown;
        // Browse (or changing the path) stops it so you can pick a different map
        if (System.getProperty("ume.autolaunch") == "true" && !btnLaunch.isDisable && isMap(txtCacheLocation.text)) {
            var left = 3
            val shown = txtCacheLocation.text
            lateinit var timer: javafx.animation.Timeline
            timer = javafx.animation.Timeline(javafx.animation.KeyFrame(javafx.util.Duration.seconds(1.0), javafx.event.EventHandler {
                if (txtCacheLocation.text != shown) { timer.stop(); lblStatusText.text = "Pick a map and click Launch."; return@EventHandler }
                left--
                if (left <= 0) { timer.stop(); btnLaunch.fire() }
                else lblStatusText.text = "Opening $shown in $left... (Browse to pick another map)"
            }))
            timer.cycleCount = 3
            autoTimer = timer
            lblStatusText.isVisible = true
            lblStatusText.text = "Opening $shown in $left... (Browse to pick another map)"
            btnChooseDirectory.addEventFilter(javafx.event.ActionEvent.ACTION) { timer.stop() }
            timer.play()
        }

        if (configuration.getProp("debug") == "true") {
            btnLaunch.fire()
        }
    }

    private fun downloadCache(cacheName: String) {
        lblStatusText.isVisible = true
        lblStatusText.text = "Downloading cache $cacheName please wait.."
        txtCacheLocation.text = ""
        val destFolder = File("caches/${cacheName.removeSuffix(".tar.gz")}")

        GlobalScope.launch {
            try {
                val conn = URL("$RUNESTATS_URL/$cacheName").openConnection()
                conn.addRequestProperty("User-Agent", "uzis-map-editor")
                BufferedInputStream(conn.getInputStream()).use { inputStream ->
                    val tarIn = TarArchiveInputStream(GzipCompressorInputStream(inputStream))
                    var tarEntry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (tarEntry != null) {
                        val dest = File(destFolder, tarEntry.name)
                        if (tarEntry.isDirectory) {
                            dest.mkdirs()
                        } else {
                            dest.createNewFile()
                            val btoRead = ByteArray(1024)
                            val bout = BufferedOutputStream(FileOutputStream(dest))
                            var len: Int

                            while (tarIn.read(btoRead).also { len = it } != -1) {
                                bout.write(btoRead, 0, len)
                            }

                            bout.close()
                        }
                        tarEntry = tarIn.nextTarEntry
                    }
                    tarIn.close()
                    lblStatusText.isVisible = false
                    txtCacheLocation.text = destFolder.absolutePath
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val RUNESTATS_URL = "https://archive.runestats.com/osrs"
    }
}