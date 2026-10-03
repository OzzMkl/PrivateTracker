plugins {
    id("privatetracker.jvm.library")
}

// Tracker-side HTTP client. Pure JVM: the engine (OkHttp on Android) is injected by the platform.
dependencies {
    api(project(":core:domain"))
    api(libs.ktor.client.core)
    implementation(project(":core:protocol"))

    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":core:domain")))
    // Contract test: this client against the real server module.
    testImplementation(project(":server:api"))
    testImplementation(libs.ktor.server.test.host)
}
