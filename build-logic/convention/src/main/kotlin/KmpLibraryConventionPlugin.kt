import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Code compiled twice, for Android and for the JVM, from 1.0 on: the phone and the Linux server share
 * it. commonMain sees no Android API. Each module sets its namespace in `kotlin { android { } }`.
 * JVM tests use JUnit 5 like the pure JVM modules; Android instrumented tests live in androidDeviceTest.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            extensions.configure<KotlinMultiplatformExtension> {
                jvm {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                        freeCompilerArgs.add("-Xjdk-release=17")
                    }
                }
                (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                    compileSdk = 37
                    minSdk = 26
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                }

                sourceSets.getByName("jvmTest").dependencies {
                    implementation(project.dependencies.platform(libs.findLibrary("junit-bom").get()))
                    implementation(libs.findLibrary("junit-jupiter").get())
                    implementation(libs.findLibrary("kotlin-test-junit5").get())
                    implementation(libs.findLibrary("kotlinx-coroutines-test").get())
                    runtimeOnly(libs.findLibrary("junit-platform-launcher").get())
                }
            }

            tasks.withType<Test>().configureEach {
                if (name == "jvmTest") {
                    useJUnitPlatform()
                    // The bundled SQLite loads a native library; JDK 24+ warns unless that is allowed.
                    jvmArgs("--enable-native-access=ALL-UNNAMED")
                }
            }
        }
    }
}
