plugins {
    id("privatetracker.kmp.library")
}

// Repository implementations: over Room for both platforms, over DataStore on Android. Framework-free
// like the domain; the app wires them with Hilt and the Linux server by hand.
kotlin {
    android {
        namespace = "org.privatetracker.core.data"
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:database"))
        }
        androidMain.dependencies {
            implementation(project(":core:datastore"))
            implementation(libs.androidx.room.ktx)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.testFixtures(project.dependencies.project(":core:domain")))
        }
        // Instrumented tests run the domain use cases against real Room databases.
        getByName("androidDeviceTest").dependencies {
            implementation(project.dependencies.testFixtures(project.dependencies.project(":core:domain")))
            implementation(libs.androidx.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
