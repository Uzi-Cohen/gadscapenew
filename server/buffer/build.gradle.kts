plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape"
version = "0.1.0"

dependencies {
    api(projects.util)
    api(libs.netty.buffer)

    implementation(libs.google.guava)
}