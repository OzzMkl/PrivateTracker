plugins {
    id("privatetracker.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.privatetracker.core.datastore"
}

dependencies {
    api(libs.androidx.datastore)
    // KSerializer is part of createJsonDataStore's signature.
    api(libs.kotlinx.serialization.json)
}
