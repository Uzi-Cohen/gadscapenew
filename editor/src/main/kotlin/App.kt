import javafx.application.Application
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream

fun main() {
    // packaged app has no console window: keep a log in %USERPROFILE%\.uzis-map-editor\last-run.log
    if (System.getProperty("ume.appdir") != null) {
        try {
            val dir = File(System.getProperty("user.home"), ".uzis-map-editor").apply { mkdirs() }
            val log = PrintStream(FileOutputStream(File(dir, "last-run.log")), true)
            System.setOut(log)
            System.setErr(log)
        } catch (e: Exception) {}
    }
    // anything that crashes a background thread ends up in the log instead of vanishing
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        println("Uncaught error in thread ${t.name}:")
        e.printStackTrace()
    }
    try {
        Application.launch(JfxApplication::class.java)
    } catch (e: Throwable) {
        e.printStackTrace()
        utils.Updater.closeSplash()
        try {
            javax.swing.JOptionPane.showMessageDialog(null,
                "Uzi's Map Editor couldn't start:\n${e}\n\nDetails are in %USERPROFILE%\\.uzis-map-editor\\last-run.log",
                "Uzi's Map Editor", javax.swing.JOptionPane.ERROR_MESSAGE)
        } catch (x: Throwable) {}
    }
}
