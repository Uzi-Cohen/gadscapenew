plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.area"
version = "0.1.0"

dependencies {
    implementation(projects.scripts.npc.spawns)
}