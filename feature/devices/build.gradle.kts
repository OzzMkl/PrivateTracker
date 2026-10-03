plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.devices"
}

// What the server knows: the device list, the map with last locations and each device's detail.
dependencies {
    implementation(project(":core:map"))
}
