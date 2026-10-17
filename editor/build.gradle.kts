import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm") version "1.9.25"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.example"
// 1.0 is build 100 (after 93+ test builds); CI passes -PbuildNumber (100 + its run count)
val buildNumber = (findProperty("buildNumber") ?: System.getenv("BUILD_NUMBER") ?: "100").toString()
version = "1.0"

val lwjglVersion = "3.3.4"
val lwjglNatives = listOf("natives-windows", "natives-linux", "natives-macos", "natives-macos-arm64")

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.displee:rs-cache-library:6.4")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.bitbucket.akornilov.kotlin:binary-streams:0.33")
    // 3D view: LWJGL 3 (OpenGL 4.5 core, GLFW for the context), natives for every desktop OS in the one jar
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    for (m in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl")) {
        implementation("org.lwjgl:$m")
        for (n in lwjglNatives) runtimeOnly("org.lwjgl:$m::$n")
    }
    implementation(files("lib/dockfx-0.4-SNAPSHOT.jar"))
    implementation("com.google.inject:guice:4.2.3")
    implementation("com.jfoenix:jfoenix:9.0.9")
    implementation("org.jsoup:jsoup:1.11.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7")
    implementation("org.apache.commons:commons-compress:1.20")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks {
    build {
        dependsOn("fatJar")
    }
}

application {
    mainClass.set("AppKt")
    applicationDefaultJvmArgs = listOf(
        "--add-exports=javafx.graphics/com.sun.javafx.scene.input=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.scene.input=ALL-UNNAMED",
        "--add-opens=javafx.graphics/javafx.stage=ALL-UNNAMED",
        "--add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.tk.quantum=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.embed=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.embed=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.scene=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.scene.traversal=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.util=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.util=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
        "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
        "--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
        "--add-exports=javafx.base/com.sun.javafx.binding=ALL-UNNAMED",
        "--add-opens=javafx.base/com.sun.javafx.binding=ALL-UNNAMED",
        "--add-exports=javafx.base/com.sun.javafx.event=ALL-UNNAMED",
        "--add-opens=javafx.base/com.sun.javafx.event=ALL-UNNAMED",
        "--add-exports=javafx.base/com.sun.javafx.collections=ALL-UNNAMED",
        "--add-opens=javafx.base/com.sun.javafx.collections=ALL-UNNAMED",
        "--add-exports=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
        "--add-opens=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
        "--add-exports=javafx.controls/com.sun.javafx.scene.control.behavior=ALL-UNNAMED",
        "--add-opens=javafx.controls/com.sun.javafx.scene.control.behavior=ALL-UNNAMED",
        "--add-exports=javafx.controls/javafx.scene.control.skin=ALL-UNNAMED",
        "--add-opens=javafx.controls/javafx.scene.control.skin=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED"
    )
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.web")
}

tasks.processResources {
    inputs.property("buildNumber", buildNumber)
    filesMatching("version.properties") { expand("version" to project.version, "build" to buildNumber) }
}

val fatJar = tasks.register<Jar>("fatJar") {
    archiveBaseName.set("uzis-map-editor")
    archiveVersion.set("${project.version}-build-$buildNumber")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    manifest {
        attributes["Implementation-Version"] = "${project.version} (build $buildNumber)"
        attributes["Main-Class"] = "AppKt"
    }
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    with(tasks.jar.get() as CopySpec)
}



