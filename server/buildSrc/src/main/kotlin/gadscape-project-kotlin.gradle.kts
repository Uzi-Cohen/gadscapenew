plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven(url = "https://repo.runelite.net") // official
    maven(url = "https://rl211.jire.org/") // RuneLite
}

kotlin {
    jvmToolchain(21)
}
