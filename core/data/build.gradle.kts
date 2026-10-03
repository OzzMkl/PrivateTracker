plugins {
    id("privatetracker.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.privatetracker.core.data"
}

// Repository implementations over Room and DataStore, plus their Hilt bindings.
dependencies {
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.room.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Instrumented tests run the domain use cases against real Room databases.
    androidTestImplementation(testFixtures(project(":core:domain")))
}
