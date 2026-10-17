plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins"
version = "0.1.0"

dependencies {
    api(projects.scripts.npc.spawns)
}