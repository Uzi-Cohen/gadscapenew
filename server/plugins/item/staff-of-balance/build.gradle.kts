plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.item"
version = "0.1.0"

dependencies {
    implementation(projects.scripts.item.definitions)
    implementation(projects.scripts.item.actions)
}
