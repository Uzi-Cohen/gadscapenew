/*
 * gadconv: the reproducible GadScape build-241 cache converter (see README.md).
 *
 *   ./gradlew :cache-convert:build --offline                    compile + unit tests
 *   ./gradlew :cache-convert:run --args="inspect base" --offline
 *   ./gradlew :cache-convert:installDist --offline               build/install/gadconv/bin/gadconv
 */
plugins {
    `java-library`
    application
}

group = "com.gadscape"
version = "0.1.0"

// the same repositories the Kotlin modules use, so :cache's transitive dependencies resolve (also offline)
repositories {
    mavenCentral()
    maven(url = "https://repo.runelite.net")
    maven(url = "https://rl211.jire.org/")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // mgi.tools.jagcached (cache reading/writing), the config types and RuneLite's cache loaders
    implementation(projects.cache)
    // :cache pins guava with `implementation` (not exported to our compile classpath); without it RuneLite cache
    // 1.9.8's guava 23.2-jre would be requested, which the server build never resolves (offline builds fail)
    implementation(libs.google.guava)
    runtimeOnly(libs.logback.classic)

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

application {
    mainClass.set("com.gadscape.cacheconv.cli.GadConv")
    applicationName = "gadconv"
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // golden tests against the real caches run only when the cache layout root is given
    System.getProperty("gadconv.root")?.let { systemProperty("gadconv.root", it) }
}

tasks.named<JavaExec>("run") {
    System.getProperty("gadconv.root")?.let { systemProperty("gadconv.root", it) }
}

// the distribution archives would bundle every runtime jar of :cache (hundreds of MB); use installDist when needed
tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }
