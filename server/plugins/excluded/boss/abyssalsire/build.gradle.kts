plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.excluded.boss"
version = "0.1.0"

dependencies {
    implementation(projects.core)

    implementation(projects.scripts.npc.actions)
	implementation(kotlin("script-runtime"))
}