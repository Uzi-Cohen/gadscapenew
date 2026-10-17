plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.boss"
version = "0.1.0"

dependencies {
    implementation(projects.plugins.rewards)
    implementation(projects.core)
}