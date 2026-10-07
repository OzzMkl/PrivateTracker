plugins {
    id("privatetracker.jvm.library")
}

// Tracker-side HTTP client. Pure JVM: OkHttp runs both on Android and in the simulator.
dependencies {
    api(project(":core:domain"))
    api(libs.ktor.client.core)
    implementation(project(":core:protocol"))
    // One TLS-pinned client per server key; see PinnedClients.
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":core:domain")))
    // Contract test: this client against the real server module.
    testImplementation(project(":server:api"))
    testImplementation(libs.ktor.server.test.host)
}
