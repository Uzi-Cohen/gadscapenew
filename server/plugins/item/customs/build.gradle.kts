plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.item"
version = "0.1.0"

dependencies {
    implementation(projects.core)

    implementation(projects.plugins.item.definitions)
    implementation(projects.plugins.item.actions)
}
