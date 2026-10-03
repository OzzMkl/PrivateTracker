plugins {
    id("privatetracker.jvm.library")
    application
}

// Simulated trackers for the 0.1 exit criterion. They run the app's own tracker use cases and HTTP
// client against a real Server, write down every position, and check server.db for losses afterwards.
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    // Software ECDSA keys: a simulated phone has no Keystore.
    implementation(project(":core:protocol"))
    // The engine the app uses on Android, so the simulator speaks HTTP exactly like a phone.
    implementation(libs.ktor.client.okhttp)
    implementation(libs.sqlite.jdbc)
    // Ktor logs through SLF4J; the console shows the simulator's own report instead.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(testFixtures(project(":core:domain")))
    testImplementation(project(":server:api"))
    testImplementation(libs.ktor.server.test.host)
    // Reads the schema Room exports, so the server.db reader is tested against the real tables.
    testImplementation(libs.kotlinx.serialization.json)
}

// sqlite-jdbc loads a native library; JDK 24+ warns unless that is allowed. JDK 17 accepts the flag (JEP 412).
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.test {
    val serverSchemas = rootProject.file("core/database/schemas/org.privatetracker.core.database.ServerDatabase")
    inputs.dir(serverSchemas)
    systemProperty("serverSchemas", serverSchemas.path)
    jvmArgs(nativeAccess)
}

application {
    mainClass.set("org.privatetracker.tools.simulator.MainKt")
    applicationName = "simulator"
    applicationDefaultJvmArgs = listOf(nativeAccess)
}
