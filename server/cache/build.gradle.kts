import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString

plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape"
version = "0.1.0"

dependencies {
    api(projects.util)

    implementation(libs.jackson.dataformat.toml)

    // START - for jire to change
    api(projects.coreModel)
    // END - for jire to change

    implementation(projects.util)
    implementation(libs.kryo)
    implementation(libs.google.guava)
    implementation(libs.apache.commons.io)
    implementation(libs.zip4j)
    implementation(libs.apache.commons.compress)
    implementation(libs.apache.commons.lang3)
    implementation(libs.jsoup)
    implementation(libs.tradukisto)
    implementation(libs.apache.ant)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.apache.commons.cli)

    implementation(projects.cs2)

    api(libs.toml4j)

    api(libs.runelite.api)
    api(libs.runelite.cache)
}

/*
 * Source caches for the old 211 cache build (mgi.tools.parser.TypeParser). The 241 server reads its cache with
 * -Dgadscape.cache.dir and needs none of them. They are only downloaded when a host is given:
 *   ./gradlew :cache:build -Pgadscape.sourceCacheUrl=https://<host>/<folder>
 * (the zips/keys below are fetched from that folder); otherwise put them in cache/data/ by hand.
 */
val sourceCacheUrl: String? = (findProperty("gadscape.sourceCacheUrl") as String?)?.trimEnd('/')?.takeIf { it.isNotBlank() }

fun registerSourceCacheDownload(taskName: String, label: String, vararg fileNames: String) =
    tasks.register(taskName) {
        group = "_gs_data"

        doLast {
            val dataDirectory = projectDir.toPath().resolve("data")
            for (fileName in fileNames) {
                val target = dataDirectory.resolve(fileName)
                if (Files.exists(target)) {
                    logger.lifecycle("{} ({}) already exists at: {}", label, fileName, target.absolutePathString())
                    continue
                }
                if (sourceCacheUrl == null) {
                    logger.lifecycle("{} ({}) is missing; set -Pgadscape.sourceCacheUrl or copy it to {}", label, fileName, dataDirectory)
                    continue
                }
                logger.lifecycle("{} ({}) does not exist. Downloading...", label, fileName)
                Files.createDirectories(dataDirectory)
                URI.create("$sourceCacheUrl/$fileName")
                    .toURL()
                    .openStream()
                    .use { inputStream -> Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING) }
                logger.lifecycle("{} ({}) download complete: {}", label, fileName, target.absolutePathString())
            }
        }
    }

val download225Cache = registerSourceCacheDownload("download225Cache", "Cache (225)", "cache-225.zip", "cache-225-keys.json")
val download219Cache = registerSourceCacheDownload("download219Cache", "Cache (219)", "cache-219.zip")
val downloadLegacyContentCache = registerSourceCacheDownload("downloadLegacyContentCache", "Cache (legacy content)", "cache-legacy-content.zip")

tasks.named("build") {
    dependsOn(download225Cache, download219Cache, downloadLegacyContentCache)
}

/*
 * Decodes a whole cache in strict mode and, optionally, compares every decoded value with RuneLite master's own
 * decoders (see src/test/java/com/gadscape/cache/verify/CacheVerifier.java and REV241.md):
 *
 *   ./gradlew :cache:verifyCache --offline -Dgadscape.cache.dir=/path/to/cache241 \
 *       [-Dgadscape.rlref.jar=/path/to/runelite-cache-master.jar]
 *
 * Every -Dgadscape.* property given to Gradle is passed on to the verifier JVM.
 */
tasks.register<JavaExec>("verifyCache") {
    group = "verification"
    description = "Decodes every archive, config, interface, clientscript and map of -Dgadscape.cache.dir in strict mode."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gadscape.cache.verify.CacheVerifier")
    maxHeapSize = "3g"
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("gadscape.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
    // lets the check be re-run without Gradle: java -cp "$(cat build/verify-cache-classpath.txt)" <main class>
    doFirst { layout.buildDirectory.file("verify-cache-classpath.txt").get().asFile.writeText(classpath.asPath) }
}
