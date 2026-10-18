# Molpy

**A Kotlin music player with the YouTube Music catalog, playback through YouTube, and lyrics.**

[Русский](README.md) · [**English**](README.en.md)

<p>
  <a href="https://kotlinlang.org/"><img alt="Kotlin 2.4.21" src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"></a>
  <a href="https://www.jetbrains.com/compose-multiplatform/"><img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose-Multiplatform-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white"></a>
  <a href="https://developer.android.com/"><img alt="Android API 23+" src="https://img.shields.io/badge/Android-API_23%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://developer.android.com/media/media3"><img alt="Media3 1.11.1" src="https://img.shields.io/badge/Media3-1.11.1-00897B?style=for-the-badge&logo=android&logoColor=white"></a>
</p>

[![Prerelease](https://img.shields.io/badge/release-v0.1.0--alpha.1-D97706?style=flat-square)](https://github.com/resassance/Molpy/releases/tag/v0.1.0-alpha.1)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-546E7A?style=flat-square)](LICENSE)

> **A technical prerelease for device validation.** The core pipeline is implemented and covered by offline tests. Live operation on Android and full-recording playback have not yet been verified. The interface is deliberately plain; the final visual direction has not been chosen.

**[Download APK](https://github.com/resassance/Molpy/releases/download/v0.1.0-alpha.1/Molpy-v0.1.0-alpha.1-debug.apk)** · [Release page](https://github.com/resassance/Molpy/releases/tag/v0.1.0-alpha.1) · [Report an issue](https://github.com/resassance/Molpy/issues)

## Contents

- [About](#about)
- [Implemented](#implemented)
- [Limitations and roadmap](#limitations-and-roadmap)
- [Install and try](#install-and-try)
- [Build from source](#build-from-source)
- [Architecture and stack](#architecture-and-stack)
- [Checks and evidence](#checks-and-evidence)
- [Contributing](#contributing)
- [License and author](#license-and-author)

## About

Molpy centers on the musical recording: title, artists, release, artwork, and track version. Provider responses are converted into a shared `Track` model so the interface does not depend on YouTube API structures.

Android comes first. Windows and Linux are planned next, with web considered later. iOS is outside the current scope. Shared Kotlin modules do not yet constitute a working desktop or browser app.

No Molpy account or dedicated backend is required. Google sign-in is not implemented. This project is independent of YouTube, Google, and lyrics providers; it does not provide DRM or paid-access bypasses.

## Implemented

These capabilities describe the current technical prototype. Their readiness for real use still requires device validation.

| Capability | Current implementation |
| --- | --- |
| Music search | YouTube Music catalog through InnerTubeX; results normalized into `Track`, with metadata and artwork |
| Playback resolution | Separate YouTube resolver using InnerTubeX 0.10.0; a temporary stream URL is obtained when selecting a recording |
| Android audio | Media3 ExoPlayer and `MediaSessionService`; play, pause, resume, seek, position, and duration |
| System integration | Playback service, media session, and system media controls |
| Lyrics | Loaded when opened: plain lyrics from YouTube Music, plain and synced lyrics from LRCLIB when available |
| Errors and track changes | Normalized errors and protection against stale responses replacing current state |

Lyrics may be unavailable, and supplied timestamps still need listening checks. A lyrics failure must not interrupt audio.

## Limitations and roadmap

- **Network:** the app currently uses the system connection. A working system VPN is needed where YouTube cannot be reached directly. In-app HTTP/SOCKS5 proxy support is not implemented.
- **Playback:** PROGRESSIVE/HLS streams are supported; complete recordings and compatibility with specific live sources have not yet been verified. Upstream changes may break search or stream resolution.
- **Storage:** downloads, offline playback, a persistent library, and restoration after process termination are not implemented. Room/SQLite are planned for a later stage.
- **Queue:** a complete user-facing queue, favorites, and history are not implemented.
- **Interface:** the current screen supports engineering validation. Final UX and visual design follow validation of the core pipeline.

The next step is Android validation of search, recording and artwork identity, full playback, controls, and lyrics. Network settings, persistence, the queue, and explicit offline downloads follow. Desktop and web belong to later stages.

See the [specification](docs/superpowers/specs/2026-10-17-music-player-design.md) for detailed goals and boundaries. It includes future capabilities and is not a list of shipped features.

## Install and try

**Requirements:** Android 6.0 / API 23 or later, on a 64-bit `arm64-v8a` device or `x86_64` emulator/device. The APK does not include 32-bit ABIs.

1. Open [release v0.1.0-alpha.1](https://github.com/resassance/Molpy/releases/tag/v0.1.0-alpha.1) and download `Molpy-v0.1.0-alpha.1-debug.apk`.
2. Install the APK, allowing installation from your chosen source in Android settings if prompted.
3. Check the system connection. If YouTube is unavailable on your network, enable a system VPN before testing.
4. Open Molpy, enter an artist and title, press «Найти» (Search), and select a recording.
5. Check audible playback, pause, resume, and seek. Open «Текст песни» (Lyrics) to load available lyrics.

This is a **debug APK for validation**, not a stable release. Successful installation, a search result, or an advancing timer alone does not prove full-song playback. Use the [device procedure](docs/validation/core-playback.md) for a reproducible check.

## Build from source

You need:

- JDK 17, available through `JAVA_HOME`;
- Android SDK Platform **37** and Build Tools **36.0.0**;
- an Android SDK path through `ANDROID_HOME` or `sdk.dir` in a local `local.properties`;
- network access to Gradle distributions, Google Maven, Maven Central, Gradle Plugin Portal, and JitPack to obtain tooling and dependencies.

Library versions are pinned in the [version catalog](gradle/libs.versions.toml); the Gradle Wrapper is included.

```sh
git clone https://github.com/resassance/Molpy.git
cd Molpy
./gradlew projectCheck
./gradlew :androidApp:assembleDebug
```

On Windows, use the Windows Gradle Wrapper:

```powershell
.\gradlew.bat projectCheck
.\gradlew.bat :androidApp:assembleDebug
```

Local APK output: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

`projectCheck` runs `core` and `shared` tests, JVM/desktop compilation of shared modules, Android unit tests, lint, and debug APK packaging. These tests do not need live music providers; the initial build still needs to download dependencies.

## Architecture and stack

| Module | Responsibility |
| --- | --- |
| [`core`](core) | Models, track identity, provider contracts, search and matching, playback and lyrics coordination |
| [`shared`](shared) | Catalog, resolver, and lyrics adapters; HTTP, artwork loading, shared state, and the technical Compose UI |
| [`androidApp`](androidApp) | Android entry point, Media3 engine, media session, and playback service |

Recording identity and provenance are separate from the playback identifier. A signed stream URL is a temporary value held in process memory, not a permanent track ID or a persisted link.

| Purpose | Technology |
| --- | --- |
| Shared logic and UI | Kotlin 2.4.21, Compose Multiplatform 1.12.2, coroutines, and Flow |
| Catalog and resolver | InnerTubeX 0.10.0 |
| HTTP | Ktor 3.6.0 with OkHttp; Media3 OkHttp data source |
| Android audio | Media3 1.11.1 |
| Artwork | Coil 3.6.3 |
| Lyrics | YouTube Music and [LRCLIB](https://lrclib.net/) |
| Persistent data — planned | Room / SQLite; dependencies are present, but user data persistence is not implemented |

## Checks and evidence

The current suite has **160 passing offline tests**. They cover recording normalization and identity, adapter boundaries, lyrics selection and parsing, and state and playback coordination. This checks logic; it does not establish catalog availability or complete audio playback on a particular network.

**Not yet verified:** execution on a real Android device, this app's pipeline against live music sources, listening from the beginning to the natural end of a full recording, and audible lyrics alignment.

The [validation procedure](docs/validation/core-playback.md) covers recording selection, metadata and artwork, a full listen, pause/resume/seek, background operation, screen recreation, and lyrics. Instrumented checks using synthetic audio are recorded separately from live playback.

## Contributing

Report bugs and suggestions through [GitHub Issues](https://github.com/resassance/Molpy/issues). For a bug, include:

- release version, device model, Android/API, and ABI;
- network mode and whether a system VPN is enabled, without its credentials;
- steps, expected and actual behavior, and any displayed error code;
- where relevant, the query, artist, title, recording version, and stable source ID.

Do not post tokens, cookies, proxy/VPN passwords, signed media URLs, or raw provider payloads. Audio copies and full lyrics are not needed for diagnosis.

Before opening a pull request, describe the change and run `projectCheck`. For resolver or player changes, distinguish device observations from test-only coverage.

## License and author

[GNU GPL v3.0 only](LICENSE) (`GPL-3.0-only`).

Author: [@resassance](https://github.com/resassance).
