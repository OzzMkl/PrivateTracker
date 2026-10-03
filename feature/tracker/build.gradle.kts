plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.tracker"
    // Timber's lint checks arrive with MapLibre through core:map, but this project logs with android.util.Log.
    lint {
        disable += "LogNotTimber"
    }
}

// Tracker role: the screen, the location service, uploads with WorkManager and the restart at boot.
dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:protocol"))
    implementation(project(":core:qr"))
    implementation(libs.androidx.core.ktx)
    // The camera permission request on the pairing screen.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
