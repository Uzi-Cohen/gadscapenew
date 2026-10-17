plugins {
    id("gadscape-project-kotlin")
}

group = "com.gadscape"
version = "0.1.0"

dependencies {
    api(libs.slf4j.api)
    api(libs.fastutil)
    implementation(libs.apache.commons.text)
    implementation(libs.kotlinx.datetime)
    api(libs.hikari.cp)
    api(libs.mysql.connector.j)
    api(libs.google.gson)
}
