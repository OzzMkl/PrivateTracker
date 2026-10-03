plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.server"
}

// Server role: the screen, the foreground service hosting the API on Netty, maintenance and the start at boot.
dependencies {
    implementation(project(":server:api"))
    implementation(project(":core:protocol"))
    implementation(project(":core:qr"))
    implementation(libs.ktor.server.netty) {
        // Only desktop binaries for HTTP/3; the QUIC classes stay in netty-codec-classes-quic.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
