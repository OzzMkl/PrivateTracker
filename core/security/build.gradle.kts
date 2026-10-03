plugins {
    id("privatetracker.android.library")
    id("privatetracker.android.hilt")
}

android {
    namespace = "org.privatetracker.core.security"
}

// Device keys in the Android Keystore for the tracker, and the protocol's signature checks for the server.
dependencies {
    api(project(":core:domain"))
    implementation(project(":core:protocol"))
}
