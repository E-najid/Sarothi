plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The native runtime (llama.cpp / whisper.cpp / espeak-ng) is fetched by
// scripts/setup_native.sh into <root>/third_party. When it is absent we skip the
// CMake step entirely so that Kotlin-only builds still succeed; Sarothi then
// reports the on-device model runtimes as unavailable rather than faking output.
val skipNative: Boolean =
    (findProperty("sarothi.skipNative") as String?)?.toBoolean() ?: false
val thirdPartyDir: File = rootProject.layout.projectDirectory.dir("third_party").asFile
val nativeSourcesReady: Boolean = !skipNative && thirdPartyDir.isDirectory &&
    (thirdPartyDir.listFiles()?.isNotEmpty() == true)

android {
    namespace = "com.ngi.sarothi.core"
    compileSdk = 35

    // Only pinned when there is something for the NDK to compile. AGP resolves
    // `ndkVersion` eagerly: setting it unconditionally makes a Kotlin-only build
    // fail on any machine (CI runners included) that does not already have this
    // exact NDK installed, even though no native code would be built.
    if (nativeSourcesReady) {
        ndkVersion = "27.0.12077973"
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        if (nativeSourcesReady) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_static",
                        "-DSAROTHI_THIRD_PARTY=${thirdPartyDir.absolutePath}",
                    )
                    cppFlags += "-std=c++17"
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                }
            }
        }
    }

    if (nativeSourcesReady) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
        jniLibs {
            // Model inference touches these .so files through JNI; keeping symbols
            // makes native crashes in llama.cpp/ggml readable in bug reports.
            keepDebugSymbols += "**/*.so"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// A failing test has to say so in the console log. Gradle's default is to print only
// "There were failing tests" and point at an HTML report on the runner, which is
// exactly where a CI failure cannot be read from: the report is not in the log, and
// the artifacts live on blob storage that tooling often cannot reach. Naming the test
// and its assertion message here is what lets scripts/report_build_failure.py put them
// on the pull request.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = false
    }
}

dependencies {
    // `api` because :plugins and :app compile against the plugin contract and the
    // JSON types it exposes.
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.gson)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)
    // `api` rather than implementation: BiometricKeyVault's public signatures hand back a
    // BiometricPrompt.CryptoObject, so any module that calls it -- :app drives the prompt --
    // needs androidx.biometric on its compile classpath, not just at runtime.
    api(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
}
