plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.devices"
}

// What the server knows: the device list, the map with last locations, each device's detail and history.
dependencies {
    implementation(project(":core:map"))
    // "Save as" for exported histories.
    implementation(libs.androidx.activity.compose)
}
