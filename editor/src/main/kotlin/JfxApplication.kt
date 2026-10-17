import cache.CacheModule
import cache.loaders.LoaderModule
import com.google.inject.Guice
import com.sun.javafx.css.StyleManager
import controllers.MainController
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.util.Callback
import models.ModelsModule
import controllers.worldRenderer.WorldRendererModule


class JfxApplication : Application() {
    companion object {
        val injector = Guice.createInjector(LoaderModule(), CacheModule(), ModelsModule(), WorldRendererModule())
    }

    override fun start(stage: Stage) {
        // packaged app: loading window while it checks for updates in the background
        if (utils.Updater.installDir != null) {
            utils.Updater.showSplash()
            Thread {
                val restarting = try { utils.Updater.checkAndUpdate() } catch (e: Exception) { e.printStackTrace(); false }
                javafx.application.Platform.runLater {
                    if (restarting) { javafx.application.Platform.exit(); System.exit(0) }
                    else { utils.Updater.splashText("Loading..."); openChooser(stage) }
                }
            }.apply { isDaemon = true; name = "update-check" }.start()
            return
        }
        openChooser(stage)
    }

    private fun openChooser(stage: Stage) {
        // set global theme
        setUserAgentStylesheet(STYLESHEET_MODENA)

        StyleManager.getInstance().addUserAgentStylesheet(javaClass.getResource("theme.css").toExternalForm())
        utils.Branding.installUserAgentTheme()

        val loadCacheLoader = FXMLLoader()
        loadCacheLoader.controllerFactory = Callback { type: Class<*>? ->
            injector.getInstance(type)
        }
        loadCacheLoader.location = javaClass.getResource("/views/cache-chooser.fxml")
        val cacheChooserRoot = loadCacheLoader.load<Parent>()
        stage.scene = Scene(cacheChooserRoot)
        utils.Branding.apply(stage, "choose a cache")
        stage.show()
        utils.Updater.closeSplash()
    }
}