plugins {
    id("privatetracker.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

// The web app of the Linux server, from 1.0: HTML made in Kotlin, htmx for the small updates and one
// script per map page. Like server:api it needs no engine; server:linux hosts it on Netty over TLS.
dependencies {
    api(project(":core:domain"))
    api(libs.ktor.server.core)
    implementation(project(":core:protocol"))
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.html)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(testFixtures(project(":core:domain")))
}

// The browser libraries come as WebJars, without their dependencies: each one ships a bundle with
// everything inside. Only the files the pages load go into the jar, not the whole npm package.
val webJars: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    webJars(libs.webjar.htmx)
    webJars(libs.webjar.maplibre.gl)
}

// The configuration cache keeps no reference to this script: the zip reader comes as a service, and the
// lambdas below see only local values.
interface ArchiveAccess {
    @get:Inject val archives: ArchiveOperations
}

val vendorAssets = tasks.register<Sync>("vendorAssets") {
    val archives = objects.newInstance<ArchiveAccess>().archives
    val jars = webJars.elements
    from(jars.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        include(
            "META-INF/resources/webjars/htmx.org/*/dist/htmx.min.js",
            "META-INF/resources/webjars/htmx.org/*/LICENSE",
            "META-INF/resources/webjars/maplibre-gl/*/dist/maplibre-gl-csp.js",
            "META-INF/resources/webjars/maplibre-gl/*/dist/maplibre-gl-csp-worker.js",
            "META-INF/resources/webjars/maplibre-gl/*/dist/maplibre-gl.css",
            "META-INF/resources/webjars/maplibre-gl/*/dist/LICENSE.txt",
        )
        // static/vendor/<library>/<file>, without the version: the pages name files, the build picks versions.
        eachFile {
            val parts = relativePath.segments
            relativePath = RelativePath(true, parts[3], parts.last())
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/vendor/static/vendor"))
}

sourceSets.main {
    resources.srcDir(vendorAssets.map { it.destinationDir.parentFile.parentFile })
}
