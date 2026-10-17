plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.excluded"
version = "0.1.0"

dependencies {
    implementation(projects.core)

    implementation(projects.scripts.npc.drops)
    implementation(projects.scripts.npc.definitions)
    implementation(projects.scripts.npc.actions)
    implementation(projects.scripts.npc.spawns)

    implementation(projects.scripts.item.definitions)
    implementation(projects.scripts.item.actions)

    implementation(projects.scripts.interfaces.user)
}
