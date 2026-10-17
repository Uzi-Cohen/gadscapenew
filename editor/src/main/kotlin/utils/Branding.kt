package utils

import com.sun.javafx.css.StyleManager
import javafx.scene.control.Menu
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.image.Image
import javafx.stage.Stage
import javafx.stage.Window
import java.io.File

object Branding {
    const val NAME = "Uzi's Map Editor"
    const val VERSION = "1.0"

    data class Theme(val id: String, val name: String, val file: String?)

    val THEMES = listOf(
        Theme("forge", "Forge (dark gold)", null),
        Theme("osrs", "OSRS stone", "/themes/osrs.css"),
        Theme("midnight", "Midnight black", "/themes/midnight.css"),
        Theme("aurora", "Aurora gradients", "/themes/aurora.css")
    )

    private val icons: List<Image> by lazy {
        listOf(16, 32, 64, 256).mapNotNull { s ->
            Branding::class.java.getResource("/icon-$s.png")?.let { Image(it.toExternalForm()) }
        }
    }

    private val css: String? by lazy { Branding::class.java.getResource("/theme.css")?.toExternalForm() }
    private fun themeUrl(t: Theme): String? = t.file?.let { Branding::class.java.getResource(it)?.toExternalForm() }
    private val allThemeUrls: List<String> by lazy { THEMES.mapNotNull { themeUrl(it) } }

    private val themeFile = File(System.getProperty("user.home"), ".uzis-map-editor/theme.txt")

    /** The chosen theme, remembered between runs. */
    var current: Theme = THEMES.firstOrNull { t ->
        themeFile.isFile && try { themeFile.readText().trim() == t.id } catch (e: Exception) { false }
    } ?: THEMES[0]
        private set

    /** Call once at startup, after the base stylesheet: themes dialogs and popups too. */
    fun installUserAgentTheme() {
        themeUrl(current)?.let { StyleManager.getInstance().addUserAgentStylesheet(it) }
    }

    /** Title, icon and theme for any window. Call after stage.scene is set. */
    fun apply(stage: Stage, subtitle: String? = null) {
        stage.title = if (subtitle == null) NAME else "$NAME - $subtitle"
        if (stage.icons.isEmpty()) stage.icons.addAll(icons)
        val scene = stage.scene ?: return
        css?.let { if (!scene.stylesheets.contains(it)) scene.stylesheets.add(it) }
        scene.stylesheets.removeAll(allThemeUrls)
        themeUrl(current)?.let { scene.stylesheets.add(it) }
    }

    /** Switch theme in every open window right away, and remember it. */
    fun setTheme(t: Theme) {
        val old = themeUrl(current)
        current = t
        val url = themeUrl(t)
        try {
            val sm = StyleManager.getInstance()
            old?.let { sm.removeUserAgentStylesheet(it) }
            url?.let { sm.addUserAgentStylesheet(it) }
        } catch (e: Exception) {}
        for (w in Window.getWindows()) {
            val scene = w.scene ?: continue
            scene.stylesheets.removeAll(allThemeUrls)
            url?.let { scene.stylesheets.add(it) }
        }
        try {
            themeFile.parentFile.mkdirs()
            themeFile.writeText(t.id)
        } catch (e: Exception) {}
    }

    /** The Themes menu for the menu bar. */
    fun themeMenu(): Menu {
        val group = ToggleGroup()
        val menu = Menu("Themes")
        for (t in THEMES) {
            menu.items.add(RadioMenuItem(t.name).apply {
                toggleGroup = group
                isSelected = t == current
                setOnAction { setTheme(t) }
            })
        }
        return menu
    }
}
