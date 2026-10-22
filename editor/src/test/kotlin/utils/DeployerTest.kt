package utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where Deploy sends the cache: the folder the live (build 241) server runs with, read off the server. */
class DeployerTest {

    private fun probe(service: String, vararg procs: Pair<String, String>) =
        "#SERVICE\n$service\n#PROCS\n" + procs.joinToString("") { (head, cmd) -> "$head\n$cmd\n" } + "#END\n"

    private val unit = """
        # /etc/systemd/system/gadscape.service
        [Service]
        WorkingDirectory=/opt/gadscape
        ExecStart=/usr/bin/java -Xmx6g -jar /opt/gadscape/server.jar
    """.trimIndent()

    @Test
    fun runningServersCacheDir() {
        val out = probe(unit,
            "PID 811 CWD /home/u" to "java -Xmx2g -cp gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain run",
            "PID 1234 CWD /opt/gadscape" to "/usr/bin/java -Xmx6g -Dgadscape.cache.dir=/opt/gadscape/cache241 -Dgadscape.cache.revision=241 -jar /opt/gadscape/server.jar")
        assertEquals("/opt/gadscape/cache241" to "/opt/gadscape", Deployer.serverCacheFrom(out))
    }

    @Test
    fun theServerNotAnotherJavaProgram() {
        val out = probe(unit,
            "PID 5 CWD /opt/gadscape" to "java -Dgadscape.cache.dir=/opt/gadscape/cache241 -jar server.jar",
            "PID 6 CWD /home/u/.runelite" to "java -Xmx768m -XX:+UseG1GC -cp /home/u/.runelite/repository2/a.jar:/home/u/.runelite/repository2/b.jar:/home/u/.runelite/repository2/c.jar net.runelite.client.RuneLite")
        assertEquals("/opt/gadscape/cache241" to "/opt/gadscape", Deployer.serverCacheFrom(out))
    }

    @Test
    fun relativeToTheServersFolder() {
        val out = probe(unit, "PID 77 CWD /srv/gs" to "java -Dgadscape.cache.dir=cache/241/ -jar server.jar")
        assertEquals("/srv/gs/cache/241" to "/srv/gs", Deployer.serverCacheFrom(out))
    }

    @Test
    fun fromTheServiceWhenItIsntRunning() {
        val svc = unit + "\nEnvironment=\"JAVA_OPTS=-Dgadscape.cache.dir='data/cache241'\""
        assertEquals("/opt/gadscape/data/cache241" to "/opt/gadscape", Deployer.serverCacheFrom(probe(svc)))
    }

    @Test
    fun theServersDefaultFolder() {
        // the rev 211 server (no -Dgadscape.cache.dir): cache/data/cache in the folder it runs in
        assertEquals("/opt/gadscape/cache/data/cache" to "/opt/gadscape", Deployer.serverCacheFrom(probe(unit)))
        val out = probe("", "PID 9 CWD /home/game/server" to "java -jar app.jar")
        assertEquals("/home/game/server/cache/data/cache" to "/home/game/server", Deployer.serverCacheFrom(out))
    }

    @Test
    fun nothingKnown() {
        assertEquals(null to null, Deployer.serverCacheFrom(""))
        assertEquals(null to null, Deployer.serverCacheFrom(probe("")))
    }
}
