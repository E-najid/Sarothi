// Root build file. Plugin versions are declared here and applied per-module so that
// every module in the build resolves exactly one AGP / Kotlin / Compose toolchain.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
