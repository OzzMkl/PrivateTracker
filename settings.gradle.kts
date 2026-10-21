pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PrivateTracker"

include(":app")

// Pure Kotlin/JVM modules: reusable by the future Linux/Docker server.
include(":core:common")
include(":core:domain")
include(":core:protocol")
include(":core:network")
include(":server:api")
include(":server:netty")
// Key files and owner-only files of a server process on a host: the Linux server and the relay (1.0).
include(":server:hostkeys")
// The server on a Linux host or in Docker (1.0): the same API, keys in files, run from the command line.
include(":server:linux")
// Its web app (1.0): pages made on the server, for any Ktor engine, like server:api.
include(":server:web")
// The relay (1.0): its tunnel's protocol and the client servers run, and the relay itself.
include(":server:tunnel")
include(":server:relay")

// Android data layer.
include(":core:database")
include(":core:datastore")
include(":core:data")

// Android platform and UI building blocks.
include(":core:location")
include(":core:security")
include(":core:designsystem")
include(":core:map")
include(":core:qr")

// One module per role or screen group; features never depend on each other.
include(":feature:onboarding")
include(":feature:tracker")
include(":feature:server")
include(":feature:devices")
include(":feature:settings")
include(":feature:geofences")

// Developer tools that run on a PC; never part of the app.
include(":tools:simulator")
