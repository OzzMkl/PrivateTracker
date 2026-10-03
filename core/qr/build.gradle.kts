plugins {
    id("privatetracker.android.library")
    id("privatetracker.android.compose")
}

android {
    namespace = "org.privatetracker.core.qr"
}

// QR codes both ways: drawing one for the server screen and reading one with the camera. ZXing and
// CameraX are free software and need no Google Play Services, so the app stays fit for F-Droid.
dependencies {
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
}
