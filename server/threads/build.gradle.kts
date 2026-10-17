plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape"
version = "0.1.0"

dependencies {
    api(libs.openhft.affinity)
    api(libs.openhft.chronicle.threads)

    implementation(libs.slf4j.api)
}