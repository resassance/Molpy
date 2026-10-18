# Core playback device/live procedure

Task 7 technical slice; authoritative acceptance set: [spec §17](../superpowers/specs/2026-10-17-music-player-design.md#17-тестирование-и-критерии-доказательств). This procedure is prepared, not an execution record. The current result ledger is [core-playback-results.md](core-playback-results.md).

## Preconditions and artifacts

Use a connected physical Android device or a compatible emulator, API 23 or later. The device must have a working system connection and, where needed, an external system VPN. Task 7 has SYSTEM routing only; explicit proxy configuration is later scope. Record the device/API, date, app/APK SHA-256, resolver/library version, country/region if known, network mode, and VPN enabled/disabled. Do not record VPN credentials, tokens, signatures, media URLs, raw provider payloads, lyrics text, or copyrighted audio.

Build from the reviewed repository root:

```sh
./gradlew projectCheck :androidApp:assembleDebugAndroidTest
sha256sum androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

On the connected authorized test device, install the reviewed APK and open Molpy from the launcher. Optional shell equivalents (procedure only; not executed in Task 7):

```sh
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n app.musicplayer.android/app.musicplayer.MainActivity
```

Instrumented tests can be executed once on that already connected device using `./gradlew :androidApp:connectedDebugAndroidTest`. Their synthetic media checks validate the engine; they do not establish live full-track playback. Record their result separately from the manifest. Task 7 only packages the instrumented APK where connected execution is unavailable.

## Per-recording procedure

1. Enter the exact query in the technical screen and press «Найти». Record whether YTM gave a suitable release; for a video fallback, capture the exact query and the observed lack of a suitable primary result. Verify the title, artist, album when supplied, release/video artwork label, image identity, duration, and recording version against the selected result. Missing fields stay unknown. Copy the first candidate's stable provider/kind/source ID and the literal version displayed by the technical screen. «не указана источником» means the stored version is the empty string; do not reinterpret it as studio.
2. Select the recording. Confirm audible playback belongs to this recording/version. A successful resolve, HTTP response, advancing timer alone, or playback in a third-party client is insufficient. Record initial playback latency and any normalized error code. The implemented engine accepts PROGRESSIVE/HLS only; unsupported bounded-range/SABR transport is a classified failure, not a reason to invent an adapter in this task.
3. Check pause: the audible audio and position stop. Resume: audio and position advance from the paused point. Seek forward well beyond the initial buffer window (for example to 60% of known duration), verify audible audio at that part, then seek backward (for example to 20%) and verify again. Record requested and observed positions and behavior. Never call an initial short window a full recording.
4. Perform a separate full listen from position zero to the natural end, without seeking over any part. Record wall-clock start/end, expected duration, observed final position/status, whether the audible ending is complete, and any early cutoff. Compare duration with the actual recording; successful HTTP with a bounded opening window fails this check. A seek-to-end check alone cannot replace full playback.
5. Open «Текст песни» only after selection. Check plain, synced, unavailable, instrumental, and provider-failure outcomes where observed. For synced lyrics, compare at least the beginning, middle, and a point after seeking with the audible line; record alignment observations without copying lyric text. Text failure must not stop audio. Close/reopen lyrics; switch A→B while A is loading and verify no late A text appears on B. Record providers and kind/absence/error separately; do not assume the example songs still have synced results.
6. While audio plays, put the app in the background, verify continued playback, reopen, and confirm the actual selected metadata, artwork, position, and playback state remain correct. Rotate/recreate the Activity and repeat. On a test device, Developer options «Don't keep activities» can exercise recreation; record the actual method and restore the setting afterward. Closing the screen must not release the service player. Process death/persistent queue is later scope.
7. Switch results rapidly A→B and issue searches A→B. Confirm late search, resolve failure, and lyrics results never replace B. If a network/source failure occurs, record the category and visible error. Retry manually once; do not bypass source restrictions or loop. Preserve only redacted observations and stable IDs in the result record.

## Representative set

Use all categories below; select exact source identities from the live result set. One live recording may cover multiple categories, but record which. The examples are starting points, not promises of current availability.

| Category | Starting query / selection rule |
| --- | --- |
| Mainstream international | Linkin Park — Numb |
| Russian-language recording | Кино — Группа крови |
| Older release | Queen — Bohemian Rhapsody |
| Recent release | Official release from the preceding 12 months; record the actual title, artist, release date, version and stable source ID |
| Proper YTM release | Daft Punk — Get Lucky; confirm the actual release in the returned catalog |
| Ordinary YouTube fallback | Find an exact query with no suitable YTM song and an available ordinary YouTube music recording; record the observed primary/fallback evidence |
| Synced lyrics | Numb and Группа крови are initial candidates only; measure actual response and audio alignment |
| Plain-only lyrics | Find a real provider response with plain text and no usable synced lines |
| Unavailable lyrics | Find a real provider absence; distinguish confirmed absence from provider failure |
| Instrumental | Aphex Twin — Avril 14th; provider instrumental state must be observed |
| Ambiguous title | Hello: select and distinguish Adele and Lionel Richie |
| Alternate versions | Radiohead — Creep: original and separately requested live/cover; confirm no silent version substitution |

## Result and gate rules

For every category, fill the exact manifest fields in the results file. PASS requires actual observed evidence for that check. FAIL requires an observed failed check and classification. NOT_RUN means no attempt; BLOCKED means an attempted prerequisite failed; PENDING means the gate awaits evidence. Device packaging is not device execution. Unit/mock/generated WAV tests and third-party clients are not live music evidence.

Milestone 1 remains PENDING until real basic catalog/artwork and full recording playback, controls, lyrics when available, dependency packaging, and the selected supported transport have been observed on-device. If the core set lacks full playback or correct catalog/artwork, stop before the visual milestone and revisit the integration decision. Do not begin Task 8 or final visual design on a packaging-only result. Proxy/offline scenarios in spec §17 belong to later planned tasks and must be marked outside Task 7 execution, not PASS.
