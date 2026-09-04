plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ngi.sarothi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ngi.sarothi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Sarothi must run on a 3 GB phone, so the resource configurations it ships
        // are the ones those devices actually use. Anything else is dead weight in the
        // APK and in memory.
        resourceConfigurations += listOf("en", "bn")
    }

    buildFeatures {
        compose = true
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
            // Left off until the whole graph is exercised: enabling it now would strip
            // the Gson reflection used by the plugin parameter schemas, and silently
            // broken tool calls are far worse than a larger APK.
            isMinifyEnabled = false
        }
    }

    // One APK per ABI. Every native dependency here -- ONNX Runtime for Piper TTS, ML
    // Kit's text recognizer, and llama.cpp/whisper.cpp when they are built -- ships a
    // library for each ABI it supports, so a universal APK carries all of them and only
    // ever loads one. On a 3 GB phone the download is the barrier to trying the app at
    // all, and paying it four times over for libraries that will never be dlopened is not
    // a trade worth making.
    //
    // The cost is stated rather than hidden: an x86_64 emulator cannot install these APKs.
    // Add "x86_64" to `include` for a local emulator build, or install on a device.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
        jniLibs {
            // Only Sarothi's own bridge keeps its symbols, so a native crash in
            // llama.cpp/ggml stays readable in a bug report. This was "**/*.so", which
            // also shipped the full symbol tables of every prebuilt third-party library --
            // symbols nothing of ours can produce a stack trace from, and the single
            // largest contributor to a 195 MB debug APK.
            keepDebugSymbols += "**/libsarothi_native.so"
        }
    }
}

dependencies {
    // :core exposes the plugin contract and JSON types as `api`, so :plugins only
    // needs to be on the compile path for BuiltinPlugins.all().
    implementation(project(":core"))
    implementation(project(":plugins"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
