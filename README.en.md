# Molpy

**A Kotlin music player with the YouTube Music catalog, playback through YouTube, and lyrics.**

[Русский](README.md) · [**English**](README.en.md)

<p>
  <a href="https://kotlinlang.org/"><img alt="Kotlin 2.4.21" src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"></a>
  <a href="https://www.jetbrains.com/compose-multiplatform/"><img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose-Multiplatform-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white"></a>
  <a href="https://developer.android.com/"><img alt="Android API 23+" src="https://img.shields.io/badge/Android-API_23%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white"></a>
  <a href="https://developer.android.com/media/media3"><img alt="Media3 1.11.1" src="https://img.shields.io/badge/Media3-1.11.1-00897B?style=for-the-badge&logo=android&logoColor=white"></a>
</p>

[![Prerelease](https://img.shields.io/badge/release-v0.1.0--alpha.9-D97706?style=flat-square)](https://github.com/resassance/Molpy/releases/tag/v0.1.0-alpha.9)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-546E7A?style=flat-square)](LICENSE)

**[Download APK](https://github.com/resassance/Molpy/releases/download/v0.1.0-alpha.9/Molpy-v0.1.0-alpha.9-debug.apk)** · [Release page](https://github.com/resassance/Molpy/releases/tag/v0.1.0-alpha.9) · [Report an issue](https://github.com/resassance/Molpy/issues)

## Contents

- [About](#about)
- [Implemented](#implemented)
- [Limitations and roadmap](#limitations-and-roadmap)
- [Install and try](#install-and-try)
- [Build from source](#build-from-source)
- [Architecture and stack](#architecture-and-stack)
- [Contributing](#contributing)
- [License and author](#license-and-author)

## About

Molpy centers on the musical recording: title, artists, release, artwork, and track version. Provider responses are converted into a shared `Track` model so the interface does not depend on YouTube API structures.

Android comes first. Windows and Linux are planned next, with web considered later. iOS is outside the current scope. Desktop and browser apps have not been released yet.

No Molpy account or dedicated backend is required. Google sign-in is not implemented. This project is independent of YouTube, Google, and lyrics providers; it does not provide DRM or paid-access bypasses.

## Implemented

| Capability | What is available |
| --- | --- |
| Search | YouTube Music catalog, YouTube search fallback, track titles, artists, albums, and artwork |
| Player | Play, pause, seek, next and previous track, position, duration, and playback buffer |
| Volume | Source-based volume normalization for online and offline playback; can be disabled in settings |
| Queue | Add, remove, select, and reorder entries; keep the queue between launches |
| Library | Favorites, local playlists, albums, and artists; downloads in a separate section |
| Profile | Total listening time, most-played tracks and artists with listening time, and history |
| Appearance | Dark interface with colors from the current artwork, a mini player, swipe gestures to expand and collapse the player, and track transitions |
| Background playback | Android media session and system playback controls |
| Lyrics | YouTube Music and LRCLIB; the current line in the player and full lyrics when available for the recording |
| Network | System connection, HTTP CONNECT proxy with no authentication or username/password, and SOCKS5 without authentication |
| Downloads | One track at a time, pause and resume, deletion, storage usage, and local playback |

After restarting the app, the queue is restored on pause. Press Play to continue. Lyrics and artwork loading errors do not interrupt audio.

## Limitations and roadmap

- **YouTube access:** use a system VPN or configure a proxy in Molpy if YouTube is unavailable on your network. Changes to YouTube can affect search and playback.
- **Offline storage:** downloads stay inside the app. Uninstalling Molpy or clearing its data deletes downloaded music. Export to the shared Music folder is not available yet.
- **Formats:** downloads use a single progressive audio stream without transcoding; offline HLS and DASH are not supported. Compatibility depends on the recording format and Android codecs.
- **Background downloads:** Android may pause them due to system restrictions. After force-stopping the app, open it to continue.

Windows and Linux are planned after Android, followed by web. Accounts, cloud sync, and integrations with other music services are outside the immediate scope.

## Install and try

**Requirements:** Android 6.0 / API 23 or later, on a 64-bit `arm64-v8a` device or `x86_64` emulator/device. The APK does not include 32-bit ABIs.

1. Open [release v0.1.0-alpha.9](https://github.com/resassance/Molpy/releases/tag/v0.1.0-alpha.9) and download `Molpy-v0.1.0-alpha.9-debug.apk`.
2. Install the APK, allowing installation from your chosen source in Android settings if prompted.
3. If YouTube is unavailable on your network, enable a system VPN or configure a proxy in Molpy’s network settings.
4. Search by artist and title and select a recording from the results.
5. Use the player controls, open available lyrics, or add a recording to favorites. Downloaded tracks can be played offline.

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
./gradlew :androidApp:assembleDebug
```

On Windows, use the Windows Gradle Wrapper:

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

Local APK output: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Architecture and stack

| Module | Responsibility |
| --- | --- |
| [`core`](core) | Models, track identity, provider contracts, search and matching, playback coordination, queue, and download policies |
| [`shared`](shared) | Catalog, resolver, and lyrics adapters; HTTP, Room storage, artwork, shared state, and Compose UI |
| [`androidApp`](androidApp) | Android entry point, Media3 engine, media session, background downloads, and file storage |

Recording identity and provenance are separate from the playback identifier. A signed stream URL is a temporary value held in process memory, not a permanent track ID or a persisted link.

| Purpose | Technology |
| --- | --- |
| Shared logic and UI | Kotlin 2.4.21, Compose Multiplatform 1.12.2, coroutines, and Flow |
| Catalog and resolver | InnerTubeX 0.10.0 |
| HTTP | Ktor 3.6.0 with OkHttp; Media3 OkHttp data source |
| Android audio | Media3 1.11.1 |
| Artwork | Coil 3.6.3 |
| Lyrics | YouTube Music and [LRCLIB](https://lrclib.net/) |
| Persistent data | Room 2.8.5 / SQLite 2.7.1: library, queue, history, settings, and metadata |

## Contributing

Report bugs and suggestions through [GitHub Issues](https://github.com/resassance/Molpy/issues). For a bug, include:

- release version, device model, Android/API, and ABI;
- network mode and VPN or proxy use, without credentials;
- steps, expected and actual behavior, and any displayed error code;
- where relevant, the query, artist, title, recording version, and stable source ID.

Do not post tokens, cookies, proxy/VPN passwords, signed media URLs, or raw provider payloads. Audio copies and full lyrics are not needed for diagnosis.

In your pull request, describe the change and the problem it solves.

## License and author

[GNU GPL v3.0 only](LICENSE) (`GPL-3.0-only`).

Author: [@resassance](https://github.com/resassance).
