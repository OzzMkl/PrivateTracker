plugins {
    id("privatetracker.jvm.library")
}

// The HTTP API without an engine: Android hosts it with Netty, and so will the Linux server.
dependencies {
    api(project(":core:domain"))
    api(libs.ktor.server.core)
    implementation(project(":core:protocol"))
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":core:domain")))
}
