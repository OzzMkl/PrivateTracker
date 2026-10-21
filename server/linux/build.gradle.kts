plugins {
    id("privatetracker.jvm.library")
    alias(libs.plugins.kotlin.serialization)
    application
}

version = providers.gradleProperty("privatetracker.versionName").get()

// The server for a Linux host or Docker, from 1.0: the phone server's API, domain and Room tables on the
// JVM, with its keys in files instead of the Keystore and a command line instead of the app's screens.
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:protocol"))
    implementation(project(":core:data"))
    implementation(project(":server:api"))
    implementation(project(":server:netty"))
    implementation(project(":server:hostkeys"))
    implementation(project(":server:tunnel"))
    implementation(project(":server:web"))
    // Paired servers (1.0): the pinned HTTP client the trackers use, for this server's own calls.
    implementation(project(":core:network"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    // Ktor and Netty log through SLF4J; the server's own lines go the same way, to stderr.
    implementation(libs.slf4j.simple)

    testImplementation(testFixtures(project(":core:domain")))
    // A relay between tracker and server, for the end-to-end test.
    testImplementation(project(":server:relay"))
}

// The bundled SQLite loads a native library; JDK 24+ warns unless that is allowed. JDK 17 accepts the flag.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.test {
    jvmArgs(nativeAccess)
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "PrivateTracker Server", "Implementation-Version" to project.version)
    }
}

application {
    mainClass.set("org.privatetracker.server.linux.MainKt")
    applicationName = "privatetracker-server"
    // Headless: the QR PNG is drawn without a display.
    applicationDefaultJvmArgs = listOf(nativeAccess, "-Djava.awt.headless=true")
}
