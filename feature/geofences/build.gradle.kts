plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.geofences"
    // Timber's lint checks arrive with MapLibre through core:map, but this project logs with android.util.Log.
    lint {
        disable += "LogNotTimber"
    }
}

// Geofences on the server, from 0.7: the list with recent crossings, the editor on the map, and the alerts.
dependencies {
    implementation(project(":core:map"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
