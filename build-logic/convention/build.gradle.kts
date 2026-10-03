plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "privatetracker.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "privatetracker.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
