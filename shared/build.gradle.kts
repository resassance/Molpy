import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
}
kotlin {
    android {
        namespace = "app.musicplayer.shared"
        compileSdk = 37
        minSdk = 23
    }
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.innertubex)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.coil.compose)
            implementation(libs.coil.network)
        }
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.okhttp)
            }
        }
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("desktopMain").dependsOn(jvmCommonMain)
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
    }
}
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
