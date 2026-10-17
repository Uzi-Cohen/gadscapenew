plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.interfaces"
version = "0.1.0"

dependencies {
    implementation(projects.scripts.interfaces)
}
