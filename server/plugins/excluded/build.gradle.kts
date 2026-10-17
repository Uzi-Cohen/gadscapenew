plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins"
version = "0.1.0"

dependencies {
    implementation(projects.core)

    implementation(libs.apache.commons.lang3)
}