package cache

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The container layer on real caches, one test per cache given with -Pgadscape.test.caches=<dir>[:<dir>...]
 * (skipped without it). Read-only checks on the cache itself, then writes into a copy (in gadscape.test.work):
 * see [CacheCheck.all] and [CacheCheck.writeRoundTrip].
 *
 * Results, 2026-10-22: stock 241 (OpenRS2 2740 and 2746), the merged GadScape 241 cache and the user's and vanilla
 * 211 caches: every reference table re-encodes byte for byte (7 of the user's 211 tables are stored with zero
 * padding, which the old server packer added) and every group matches its crc and sizes; the written 241 copy passes
 * the server's strict CacheVerifier (0 errors, "sizes ok" on every archive).
 */
class ReferenceCachesTest {

    companion object {
        fun caches(): List<File> = (System.getProperty("gadscape.test.caches") ?: "")
            .split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it.trim()) }

        fun work(): File = System.getProperty("gadscape.test.work")?.let { File(it) }
            ?: File(System.getProperty("java.io.tmpdir"), "cache-test-work")
    }

    @TestFactory
    fun readOnlyChecks(): List<DynamicTest> {
        val caches = caches()
        if (caches.isEmpty()) return listOf(DynamicTest.dynamicTest("no caches given (-Pgadscape.test.caches)") { assumeTrue(false) })
        return caches.map { dir ->
            DynamicTest.dynamicTest("tables and groups: ${dir.path}") {
                assumeTrue(File(dir, "main_file_cache.dat2").isFile, "no cache in $dir")
                val r = CacheCheck.all(dir.path)
                println(r.lines.joinToString("\n") + "\n" + r)
                assertTrue(r.ok, r.toString())
            }
        }
    }

    @TestFactory
    fun writeIntoACopy(): List<DynamicTest> {
        val caches = caches()
        if (caches.isEmpty()) return listOf(DynamicTest.dynamicTest("no caches given (-Pgadscape.test.caches)") { assumeTrue(false) })
        return caches.mapIndexed { i, dir ->
            DynamicTest.dynamicTest("write into a copy: ${dir.path}") {
                assumeTrue(File(dir, "main_file_cache.dat2").isFile, "no cache in $dir")
                val copy = File(work(), "copy$i")
                try {
                    val r = CacheCheck.writeRoundTrip(dir.path, copy)
                    println(r.lines.joinToString("\n") + "\n" + r)
                    assertTrue(r.ok, r.toString())
                } finally { copy.deleteRecursively() }
            }
        }
    }
}
