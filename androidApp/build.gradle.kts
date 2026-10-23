plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "app.musicplayer.android"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.musicplayer.android"
        minSdk = 23
        targetSdk = 37
        versionCode = 10
        versionName = "0.1.0-alpha.10"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":core"))
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.media3.hls)
    implementation(libs.innertubex)
    implementation(libs.compose.runtime)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.rules)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.sqlite.bundled)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
}
