plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.npc"
version = "0.1.0"

dependencies {
    implementation(projects.scripts.npc.definitions)
}