plugins {
    id("privatetracker.android.library")
    id("privatetracker.android.compose")
}

android {
    namespace = "org.privatetracker.core.designsystem"
}

// Theme, shared components and the texts for domain errors, used by every feature.
dependencies {
    api(project(":core:common"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
}
