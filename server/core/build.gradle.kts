plugins {
    id("gadscape-project-kotlin")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
}

group = "com.gadscape"
version = "0.1.0"

/*
 * Rev 241 test build: builds collision for every map square of -Dgadscape.cache.dir with the server's Region code
 * (src/test/java/com/gadscape/cache/verify/RegionCollisionCheck.java):
 *   ./gradlew :core:verifyRegions --offline -Dgadscape.cache.dir=/path/to/cache241
 */
tasks.register<JavaExec>("verifyRegions") {
    group = "verification"
    description = "Loads every map square of -Dgadscape.cache.dir through Region and checks the collision flags."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gadscape.cache.verify.RegionCollisionCheck")
    maxHeapSize = "4g"
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("gadscape.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
    // lets the check be re-run without Gradle: java -cp "$(cat build/verify-regions-classpath.txt)" <main class>
    doFirst { layout.buildDirectory.file("verify-regions-classpath.txt").get().asFile.writeText(classpath.asPath) }
}

dependencies {
    api(projects.threads)
    api(projects.net)
    api(projects.cache)
    api(projects.coreModel)

    api(libs.kaml)
    api(libs.logback.classic)
    api(libs.fastutil)
    api(libs.google.guava)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    api(libs.kotlinx.datetime)
    api(libs.classgraph)
    api(libs.okhttp)
    api(libs.checker.qual)
    api(libs.javaparser.core)
    api(libs.javaparser.symbol.solver.core)

    implementation(libs.apache.commons.lang3)
    implementation(libs.apache.commons.codec)
    implementation(libs.jctools.core)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.afterburner)
    implementation(libs.jackson.module.kotlin)
    //implementation(libs.logstash.gelf)
    implementation(libs.mXparser)

    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)

    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)

    implementation(libs.kord.core)

    implementation(libs.netty.codec.haproxy)

    implementation(libs.mockk)
}
