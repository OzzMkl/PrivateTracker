plugins {
    id("privatetracker.android.library")
    id("privatetracker.android.hilt")
}

android {
    namespace = "org.privatetracker.core.location"
}

// Location and permissions from the platform, without Google Play Services.
dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
}
