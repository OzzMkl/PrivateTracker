plugins {
    id("privatetracker.android.library")
    id("privatetracker.android.compose")
}

android {
    namespace = "org.privatetracker.core.map"
}

// The only module that sees MapLibre; screens use PrivateTrackerMap with markers and a camera.
dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.maplibre.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
