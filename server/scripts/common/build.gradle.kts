plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.scripts"
version = "0.1.0"

dependencies {
    api(projects.core)

    api(kotlin("scripting-common"))
    api(kotlin("scripting-jvm"))
    api(kotlin("scripting-jvm-host"))
}