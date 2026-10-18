import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}
kotlin {
    android {
        namespace = "app.musicplayer.core"
        compileSdk = 37
        minSdk = 23
        withHostTest {}
    }
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { implementation(libs.coroutines.core) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
