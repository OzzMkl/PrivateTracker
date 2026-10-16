plugins {
    id("privatetracker.kmp.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// The Room tables and DAOs, for Android and, from 1.0, for the Linux server on the JVM.
kotlin {
    android {
        namespace = "org.privatetracker.core.database"
        // Off by default in KMP; without it the device test has no assets for the schemas below.
        androidResources {
            enable = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.room.runtime)
        }
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.room.testing)
            implementation(libs.androidx.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

// Exported schemas are versioned in git; migrations are tested against them.
room {
    schemaDirectory("$projectDir/schemas")
}

// The Room plugin hands the schemas to com.android.library tests only; MigrationTestHelper reads them as assets.
androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("$projectDir/schemas") }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}
