plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.devices"
    // Timber's lint checks arrive with MapLibre through core:map, but this project logs with android.util.Log.
    lint {
        disable += "LogNotTimber"
    }
}

// What the server knows: the device list, the map with last locations, each device's detail and history.
dependencies {
    implementation(project(":core:map"))
    // "Save as" for exported histories.
    implementation(libs.androidx.activity.compose)
}
