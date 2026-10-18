# Dependency validation — Molpy

Initial dependency validation: 2026-10-17; integrated technical-preview verification: 2026-10-18. Application ID remains `app.musicplayer.android`; display name and Gradle root are `Molpy`. This document preserves the initial build/domain validation. The current technical preview includes a launcher and music pipeline; see [the project README](../../README.md) and [device/live results](core-playback-results.md) for current capabilities and unverified acceptance checks.

## Verified matrix

| Component | Pinned version / setting | Evidence |
| --- | --- | --- |
| JDK / consumer bytecode | Temurin 17.0.20.1+1 / JVM 17 | Official Adoptium metadata, SHA256-verified archive; actual Gradle runs |
| Gradle | 9.6.0 | Official distribution, generated wrapper, checksums below |
| AGP | 9.4.1 | Google Maven POM; actual configuration/D8/lint/package execution |
| Kotlin / Compose compiler | 2.4.21 | Maven Central metadata; actual core Android/JVM compilation |
| Compose Multiplatform runtime/foundation/plugin | 1.12.2 | Maven Central metadata; graph and compatibility task |
| Compose Material3 | 1.9.0 | Latest stable in official Maven metadata; separately versioned from Compose |
| KSP | 2.3.12 | Official plugin artifact; plugin config succeeds, processors have no source yet |
| Android | compile/target 37; Build Tools 36.0.0; min 23 | Official SDK37.0 r02, packaged manifest and `aapt2 dump badging` |
| InnerTubeX | 0.10.0, exact source tag v0.10.0 | JitPack POM/module/AAR/JAR and release/tag status |
| Ktor | 3.6.0 | Maven Central POM and resolved graph |
| coroutines / serialization | 1.11.0 / 1.11.0 | Resolved published dependencies |
| Room / bundled SQLite | 2.8.5 / 2.7.1 | Google Maven artifacts and actual Android package |
| Media3 ExoPlayer/session/OkHttp datasource | 1.11.1 | Google Maven artifacts and actual Android package |
| Coil Compose / Ktor3 network | 3.6.3 | Latest stable official Maven metadata; graph |
| AndroidX WebKit | **1.15.0** | Highest stable supporting API23; see compatibility decision |
| Kotlin test / JUnit | 2.4.21 / 4.13.2 | Core JVM tests actually executed |
| AndroidX test runner / JUnit extension | 1.7.0 / 1.3.0 | Official Maven metadata; declared device-test harness |
| Activity Compose | 1.13.0 | Google Maven stable metadata/AAR; minSdk23, minCompile36, AGP8.9.1; actual resolved graph and Android build |
| Lifecycle runtime Compose | 2.11.0 | Google Maven stable metadata/AAR; minSdk23, minCompile37, AGP9.1.0; actual resolved graph and Android build |

No dynamic versions, snapshots, alternative extractor or Room3 API were introduced. AGP9.4 documentation specifies Gradle9.6.0, JDK17 and Build Tools36.0.0; Android17 setup documents `compileSdk = 37`.

## Official artifact sources

- [Gradle9.6 distribution checksum](https://services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256): `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`.
- [Gradle9.6 wrapper checksum](https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar.sha256): `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. Matches `gradle/wrapper/gradle-wrapper.jar`.
- [InnerTubeX release](https://github.com/MetrolistGroup/innertubex/releases/tag/v0.10.0), published 2026-10-15; [JitPack status](https://jitpack.io/api/builds/com.github.MetrolistGroup/innertubex/v0.10.0) is `ok`, exact commit `b5f8b6f1bc2b73ee9e3f3ac95b7eaa255368864e`.
- Exact KMP coordinate: `com.github.MetrolistGroup.innertubex:innertubex:0.10.0`. [POM](https://jitpack.io/com/github/MetrolistGroup/innertubex/innertubex/0.10.0/innertubex-0.10.0.pom), [module metadata](https://jitpack.io/com/github/MetrolistGroup/innertubex/innertubex/0.10.0/innertubex-0.10.0.module).
- Resolved Android: `com.github.MetrolistGroup.innertubex:innertubex-android:0.10.0`; [AAR](https://jitpack.io/com/github/MetrolistGroup/innertubex/innertubex-android/0.10.0/innertubex-android-0.10.0.aar), SHA256 `43717b92f3c3965b0eb172db4404776ea90e8565eff298e2d784e2981f8fd23b`.
- Resolved desktop: `com.github.MetrolistGroup.innertubex:innertubex-desktop:0.10.0`; [JAR](https://jitpack.io/com/github/MetrolistGroup/innertubex/innertubex-desktop/0.10.0/innertubex-desktop-0.10.0.jar), SHA256 `1578616d973031bb8a14e0757600917da09b74865ce4bdf779142d232e5f316e`.
- [AGP9.4.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom), [Room2.8.5 POM](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.8.5/room-runtime-2.8.5.pom), [Media3 1.11.1 POM](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.1/media3-exoplayer-1.11.1.pom).
- [Kotlin metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml), [Compose metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml), [Material3 metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/material3/material3/maven-metadata.xml), [Coil metadata](https://repo.maven.apache.org/maven2/io/coil-kt/coil3/coil/maven-metadata.xml), [WebKit metadata](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/maven-metadata.xml).
- [SDK repository](https://dl.google.com/android/repository/repository2-3.xml), [AGP compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Android17 setup](https://developer.android.com/about/versions/17/setup-sdk).

The JitPack module's `component.group` is `com.github.MetrolistGroup`, whereas the public KMP coordinate and platform redirects use `com.github.MetrolistGroup.innertubex`. Actual Gradle resolution selected both required variants with no metadata rewriting or group substitution.

## Compatibility decision and ABI evidence

Latest stable WebKit1.17.1 declares minSdk24; 1.16.0 also declares24. The first `projectCheck` reached successful D8 tasks, then failed manifest merge with `uses-sdk:minSdkVersion 23 cannot be smaller than version 24 declared in library [androidx.webkit:webkit:1.17.1]`. API23 takes priority; the highest compatible stable release is [WebKit1.15.0 AAR](https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.15.0/webkit-1.15.0.aar), whose manifest declares23. There is no minSdk bump or manifest override. The corrected full check passes.

InnerTubeX's published desktop classes are major61 (Java17); its Android classes are **major65 (Java21)**. The exact upstream build sets toolchain21 and desktop target17. Consumer target17 does not establish dependency target17. AGP9.4.1 D8 successfully converted the Android dependency under the JDK17 build; this is packaging evidence, not API23 runtime verification.

| Native dependency | AAR minimum SDK | Present ABIs | Packaged APK ABIs |
| --- | --- | --- | --- |
| QuickJS1.0.15 | 23 | arm64-v8a, armeabi-v7a, x86, x86_64 | arm64-v8a, x86_64 |
| Bundled SQLite2.7.1 | 23 | arm64-v8a, armeabi-v7a, x86, x86_64 | arm64-v8a, x86_64 |
| Compose graphics path (transitive) | At most23 in merged dependency scan | Includes required64-bit variants | arm64-v8a, x86_64 |

APK ZIP inspection found `libquickjs.so`, `libsqliteJni.so` and `libandroidx.graphics.path.so` under each of the two supported ABI directories. `aapt2` reports minSdk23, targetSdk37, labelMolpy. No32-bit platform support is claimed.

## Current integrated verification

The final integrated technical preview passed `projectCheck` and Android instrumentation APK packaging on 2026-10-18: core65 + shared88 + Android JVM7 = **160 tests**, zero failures/errors/skips. Android lint reported zero errors and four known warnings: UnusedAttribute, ExportedService, DataExtractionRules and MissingApplicationIcon. The launcher is `app.musicplayer.MainActivity`; application ID is `app.musicplayer.android`.

Room/SQLite and WebKit dependencies are packaged, but persistent library/offline downloads and token-host capabilities are not implemented. Packaging does not establish dependency runtime behavior on a device. Connected instrumentation tests, live catalog availability, audible full recordings, seek accuracy and real lyrics alignment remain **NOT_RUN**; see [the result ledger](core-playback-results.md).

## Reproduce from the repository root

Use JDK17, Android SDK Platform37 and Build Tools36.0.0. Configure `JAVA_HOME` and the Android SDK through `ANDROID_HOME` or local `local.properties`. The first build needs access to the configured public artifact repositories.

```sh
./gradlew projectCheck
./gradlew :androidApp:assembleDebugAndroidTest
./gradlew :androidApp:dependencies --configuration debugRuntimeClasspath
```

Use `gradlew.bat` on Windows. A connected compatible device is required to execute `:androidApp:connectedDebugAndroidTest`; APK packaging alone does not execute it. Live YouTube requests are outside the automated test suite.

Current AndroidX artifacts: [Activity Compose1.13.0](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0.aar), [Lifecycle runtime Compose2.11.0](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.11.0/lifecycle-runtime-compose-android-2.11.0.aar). Exact direct pins are maintained in [libs.versions.toml](../../gradle/libs.versions.toml).
