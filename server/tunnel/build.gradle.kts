plugins {
    id("privatetracker.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// Both ends of the relay tunnel (1.0): the protocol between a server and its relay, and the client a
// server runs, on a phone as on Linux. Plain sockets and the JDK's TLS: nothing Android lacks.
dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:protocol"))
}
