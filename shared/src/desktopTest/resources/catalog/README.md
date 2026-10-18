# Catalog fixtures

All five JSON files are synthetic metadata, authored for offline tests. They contain no accounts, cookies, tokens, lyrics, audio or signed media addresses. `example.test` images deliberately have misleading names/aspect ratios so artwork classification must use metadata.

Pinned provider: InnerTubeX v0.10.0, commit `b5f8b6f1bc2b73ee9e3f3ac95b7eaa255368864e`.

Actual tagged models consumed by the tests and mapper:

- [`SearchResponse`](https://github.com/MetrolistGroup/innertubex/blob/v0.10.0/src/commonMain/kotlin/com/metrolist/innertubex/models/response/SearchResponse.kt): music sections are `JsonObject`.
- [`MusicShelfRenderer`](https://github.com/MetrolistGroup/innertubex/blob/v0.10.0/src/commonMain/kotlin/com/metrolist/innertubex/models/MusicShelfRenderer.kt) and [`MusicResponsiveListItemRenderer`](https://github.com/MetrolistGroup/innertubex/blob/v0.10.0/src/commonMain/kotlin/com/metrolist/innertubex/models/MusicResponsiveListItemRenderer.kt): typed shelves, rows, flex columns, thumbnails, playlist item data.
- [`Runs`](https://github.com/MetrolistGroup/innertubex/blob/v0.10.0/src/commonMain/kotlin/com/metrolist/innertubex/models/Runs.kt): typed artist/album browse IDs and title watch endpoints.

The upstream typed endpoint intentionally does not retain browse page type or music video type fields. Those remain private JSON traversal in the mapper. WEB's `twoColumnSearchResultsRenderer`/`videoRenderer` has no DTO in the pinned library, so it also remains private JSON traversal. No substitute provider DTO leaks into core contracts.

Canonical external/playback identity uses `youtube/video`; music metadata provenance uses `youtube_music/song`, independently of that playback identity. Ordinary WEB provenance stays tentative. An empty candidate version means unknown; the mapper does not label a recording as studio.

Cases: `song` links artist/album and explicit ATV artwork; `video` gives tentative ordinary WEB metadata with a channel credit; `album` is a non-playable browse result; `missing-fields` is a playable item with unknown relationships/duration/artwork; `changed-schema` has a replaced row renderer and must fail rather than appear empty. Test-local JSON covers explicit empty containers separately.

These fixtures verify normalization and request routing with Ktor MockEngine. They do not establish current live provider schema availability, anonymous access, regional reachability or playback capability. No live provider request runs in the ordinary test suite.
