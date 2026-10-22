// GadScape fork of Displee's rs-cache-library 6.4 (MIT, https://github.com/Displee/rs-cache-library, tag 6.4).
// Same package and API as the Maven artifact com.displee:rs-cache-library:6.4, so the editor code is unchanged;
// the changes (all marked "GadScape fork") make it read and write OSRS rev 229+ caches such as build 241.
// See README.md in this folder.
plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.jponge:lzma-java:1.3")
    implementation("org.apache.ant:ant:1.10.7")
    api("com.displee:disio:1.7")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // the 6.4 sources as they are: only warnings, kept quiet so the editor build output stays readable
        suppressWarnings.set(true)
    }
}
