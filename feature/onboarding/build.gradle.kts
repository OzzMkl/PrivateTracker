plugins {
    id("privatetracker.android.feature")
}

android {
    namespace = "org.privatetracker.feature.onboarding"
}

// First run: the choice of mode and the permissions it needs. The permission screen is reused from settings.
dependencies {
    implementation(project(":core:location"))
    implementation(libs.androidx.activity.compose)
}
