plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.item"
version = "0.1.0"

dependencies {
    api(projects.scripts.item.definitions)
}
