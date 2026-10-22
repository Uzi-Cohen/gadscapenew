plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape.plugins.raids"
version = "0.1.0"

dependencies {
    implementation(projects.core)

    implementation(libs.google.gson)

    // JUnit 5 through kotlin-test, as in :core
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
