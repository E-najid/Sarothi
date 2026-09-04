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

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
        jniLibs {
            keepDebugSymbols += "**/*.so"
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
