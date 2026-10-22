plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.area.gadscape_home"
version = "0.1.0"

dependencies {
    implementation(projects.core)
    implementation(projects.scripts.npc.actions)
    implementation(projects.scripts.npc)
    implementation(projects.scripts.npc.definitions)
}
