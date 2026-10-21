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
    implementation(project(":server:netty"))
    // The tunnel to a relay (1.0), for a phone server behind CGNAT.
    implementation(project(":server:tunnel"))
    // Paired servers (1.0): this phone calls other servers with the pinned client the tracker uses.
    implementation(project(":core:network"))
    implementation(project(":core:protocol"))
    implementation(project(":core:qr"))
    // The server's TLS key lives in the Keystore with its identity key: the same key.
    implementation(project(":core:security"))

    androidTestImplementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    // The camera permission for reading another server's invite.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
