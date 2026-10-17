plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape"
version = "0.1.0"

dependencies {
    api(libs.netty.buffer)

    implementation(projects.util)
}