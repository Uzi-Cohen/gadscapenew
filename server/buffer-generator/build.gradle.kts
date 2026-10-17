plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape"
version = "0.1.0"

dependencies {
    api(libs.clikt)

    implementation(projects.util)
    implementation(libs.netty.buffer)
    implementation(libs.kotlinpoet)
}