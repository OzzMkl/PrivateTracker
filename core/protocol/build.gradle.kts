plugins {
    id("privatetracker.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":core:domain")))
}
