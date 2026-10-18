plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
tasks.register("projectCheck") {
    group = "verification"
    description = "Runs offline core/shared tests, desktop compilation, Android unit tests, lint and packaging."
    dependsOn(":core:desktopTest", ":shared:desktopTest", ":shared:compileKotlinDesktop",
        ":androidApp:lintDebug", ":androidApp:assembleDebug", ":androidApp:testDebugUnitTest")
}
