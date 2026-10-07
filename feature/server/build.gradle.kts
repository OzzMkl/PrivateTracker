plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.server"
    // The instrumented tests run Netty in their own APK, which needs the same exclusions as the app's.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/license/**",
                "META-INF/native-image/**",
                "META-INF/native/**",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

// Server role: the screen, the foreground service hosting the API on Netty, maintenance and the start at boot.
dependencies {
    implementation(project(":server:api"))
    implementation(project(":core:protocol"))
    implementation(project(":core:qr"))
    // The server's TLS key lives in the Keystore with its identity key: the same key.
    implementation(project(":core:security"))

    androidTestImplementation(project(":core:network"))
    implementation(libs.ktor.server.netty) {
        // Only desktop binaries for HTTP/3; the QUIC classes stay in netty-codec-classes-quic.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
