plugins {
    id("privatetracker.jvm.library")
    application
}

version = providers.gradleProperty("privatetracker.versionName").get()

// The relay (1.0): for servers behind NAT, on a host with a public address and a domain. It passes
// trackers' TLS through to the servers' tunnels and never opens it. No database, no Android, small.
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":server:tunnel"))
    implementation(project(":server:hostkeys"))
    implementation(libs.slf4j.simple)

    testImplementation(project(":core:protocol"))
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "PrivateTracker Relay", "Implementation-Version" to project.version)
    }
}

application {
    mainClass.set("org.privatetracker.server.relay.MainKt")
    applicationName = "privatetracker-relay"
}
