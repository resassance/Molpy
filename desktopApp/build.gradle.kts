plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.coroutines.core)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
            implementation(libs.serialization.json)
            implementation(libs.ktor.core)
            implementation(libs.ktor.okhttp)
            implementation(libs.coil.compose)
            implementation(libs.innertubex)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

compose.desktop {
    application { mainClass = "app.musicplayer.desktop.MainKt" }
}
