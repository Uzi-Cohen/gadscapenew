plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.excluded"
version = "0.1.0"

dependencies {
    implementation(libs.apache.commons.lang3)

    implementation(projects.core)
}