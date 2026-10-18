# Milestone 1 — Core Playback Implementation Plan

**Goal:** Реальный search → normalized Track/artwork → полное audio → pause/resume/seek/progress → lyrics на Android с техническим UI.

**Architecture:** `core` задаёт модели и narrow ports, `shared` нормализует providers и содержит технический экран, `androidApp` владеет Media3 player/session и platform token components.

**Tech Stack:** Матрица и команды из [основного плана](2026-10-17-android-mvp.md).

**Spec:** [Одобренный дизайн](../specs/2026-10-17-music-player-design.md), разделы 1–11, 14–18.

Global Constraints и пять Review Focus основного плана применяются ко всем задачам ниже. Scope — engineering UI без выбранного visual language.

## Task 1. Сборка и независимая Track identity

**Files:** Create `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `.gitignore`, `LICENSE`; `core/build.gradle.kts`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`; `androidApp/src/main/AndroidManifest.xml`; `docs/validation/dependencies.md`. Create в `core/src/commonMain/kotlin/app/musicplayer/model/`: `Track.kt`, `SourceRef.kt`, `Network.kt`, `AppResult.kt`. Tests: `core/src/commonTest/kotlin/app/musicplayer/model/TrackIdentityTest.kt`, `core/src/commonTest/kotlin/app/musicplayer/fixtures/Fixtures.kt`.

**Interfaces produced:**
- IDs — inline wrappers над String: `TrackId`, `CandidateId`, `QueueEntryId`, `AssetId`, `SecretId`; blank IDs недопустимы.
- `SourceRef(provider:String, kind:String, id:String)`, `ArtistRef(name:String, source:SourceRef?)`, `AlbumRef(name:String, source:SourceRef?)`; SourceRef.id — ID, не URL.
- `Track(id:TrackId, title:String, artists:List<ArtistRef>, album:AlbumRef?, artwork:Artwork, durationMs:Long?, externalIds:Set<SourceRef>, provenance:MetadataProvenance, candidates:List<PlaybackCandidate>)`.
- `MetadataProvenance(source:SourceRef, fetchedAtMs:Long, tentative:Boolean)`; `Artwork(kind:ArtworkKind, remoteUrl:String?, localKey:String?)`, kind `RELEASE/VIDEO/PLACEHOLDER`.
- `PlaybackCandidate(id:CandidateId, source:SourceRef, version:String, durationMs:Long?, confidence:MatchConfidence)`, confidence `HIGH/TENTATIVE`.
- `AppResult<out T>` — sealed `Success<T>(value:T)` / `Failure(error:AppError):AppResult<Nothing>`; `AppResult<T>.errorOrNull():AppError?`, `valueOrNull():T?`; `AppError(code:ErrorCode, operationId:String, retryable:Boolean)`. Codes: `INVALID_CONFIG`, `NETWORK`, `PROXY_AUTH`, `PROXY_UNSUPPORTED`, `SOURCE_UNAVAILABLE`, `REGION_RESTRICTED`, `LOGIN_REQUIRED`, `RATE_LIMIT`, `PROVIDER_CHANGED`, `UNSUPPORTED_TRANSPORT`, `URL_REJECTED`, `STORAGE_FULL`, `INCOMPLETE_MEDIA`, `INCOMPATIBLE_RESOURCE`, `DATABASE`, `UNKNOWN`. Coroutine cancellation пробрасывается, не становится provider error.
- Для repository/secret-store контрактов с обычным return type: `AppFailureException(error:AppError)` имеет безопасное сообщение только из error code. Storage implementation переводит failure в DATABASE, controller перехватывает эту exception и публикует AppError; отсутствие записи отличается от ошибки чтения. Raw provider/SQL Throwable не выходит в UI.
- `NetworkMode(SYSTEM,HTTP_CONNECT,SOCKS5)`; `ProxyEndpoint(host:String,port:Int)`; `NetworkConfig(mode:NetworkMode,endpoint:ProxyEndpoint?,credentialRef:SecretId?)`; `NetworkContext(generation:Long,config:NetworkConfig,ready:Boolean,online:Boolean)`. SYSTEM без endpoint/secret; credentials для SOCKS5 отклоняются. Ready означает применённую конфигурацию, online — известную доступность сети по platform connectivity, не доказанную доступность YouTube.
- Test-only `Fixtures.track(id:String="t1", title:String="Numb", artist:String="Linkin Park", durationMs:Long?=187000, version:String="studio", sourceId:String="v1"):Track`; дальнейшие core tests используют этот builder, не provider JSON.

- [ ] Зафиксировать matrix основного плана, checksum официального Gradle wrapper, SDK/JDK setup в task-local workspace при исполнении. Использовать Gradle 9.6.0/AGP 9.4.1; SDK 37, min 23. Проверить official artifacts InnerTubeX 0.10.0, Room 2.8.5 и Media3 1.11.1; дополнительные Coil/WebKit/test artifacts брать из latest stable official release metadata на дату исполнения с обязательной совместимостью с API 23; если absolute latest требует более высокий API, закрепить последнюю совместимую stable и записать причину и закрепить числовыми версиями в TOML. Зафиксировать фактический dependency graph и ABI в `dependencies.md`; при нерешаемой несовместимости остановиться до изменения integration decision.
- [ ] Создать только build/test harness, GPL-3.0-only LICENSE и exclusions для local.properties/build/media/credentials. Объявить `projectCheck`. Команда `./gradlew :core:tasks --all :shared:tasks --all :androidApp:tasks --all` должна завершиться без resolution/configuration errors.
- [ ] Написать `TrackIdentityTest.metadataAndPlaybackAreIndependent` и `rejectsUrlAsSourceId` с assertions ниже до model implementation.

```kotlin
assertEquals("youtube_music", track.provenance.source.provider)
assertEquals("youtube", track.candidates.single().source.provider)
assertNotEquals(track.id.value, track.candidates.single().source.id)
assertFailsWith<IllegalArgumentException> { SourceRef("youtube", "video", "https://cdn.invalid/audio?sig=test") }
```

- [ ] Запустить `./gradlew :core:desktopTest --tests 'app.musicplayer.model.TrackIdentityTest'`: ожидается RED из-за отсутствующего contract или нужного поведения, не tooling failure.
- [ ] Реализовать только перечисленные immutable models/validation, fixtures; blank title/artist не заменять придуманной metadata.
- [ ] Повторить targeted test и `./gradlew projectCheck`: ожидается GREEN/exit 0. APK этого шага не считается музыкальным результатом.

## Task 2. YTM catalog adapter и стабильный upsert source identities

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/catalog/CatalogPort.kt`, `IdentityStore.kt`; `shared/src/commonMain/kotlin/app/musicplayer/catalog/InnerTubeCatalog.kt`, `CatalogMapper.kt`, `SessionIdentityStore.kt`; `shared/src/desktopTest/kotlin/app/musicplayer/catalog/CatalogMapperTest.kt`; fixtures `shared/src/desktopTest/resources/catalog/{song,video,album,missing-fields,changed-schema}.json`.

**Consumes:** Task 1 models; InnerTubeX v0.10.0 API из exact tag.
**Produces:** `CatalogPort.searchSongs(query:String):AppResult<List<Track>>`, `searchVideos(query:String):AppResult<List<Track>>`, `findMusicMatches(track:Track):AppResult<List<Track>>` — все suspend; `IdentityStore.getOrCreate(source:SourceRef):TrackId` — suspend. `InnerTubeCatalog(client:InnerTube, identities:IdentityStore)` implements CatalogPort; `SessionIdentityStore` временно хранит mapping in memory до задачи 9.

- [ ] Написать `CatalogMapperTest.mapsMusicRelationships`, `doesNotInventAlbum`, `reusesSourceIdentity`, `schemaFailureIsNotEmptySearch`. Fixtures получить из разрешённой/синтетической формы actual tagged renderer models; не включать cookies/tokens/lyrics/media URLs.
- [ ] Запустить RED-проверку: `./gradlew :shared:desktopTest --tests 'app.musicplayer.catalog.CatalogMapperTest'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(first.id, second.id) // один SourceRef при повторном query
assertEquals("Meteora", song.album?.name)
assertEquals(ArtworkKind.RELEASE, song.artwork.kind)
assertNull(partial.album)
assertEquals(ErrorCode.PROVIDER_CHANGED, changedSchema.errorOrNull()?.code)
```

- [ ] Реализовать WEB_REMIX music search и WEB ordinary search через actual InnerTubeX models; responses/renderer traversal находятся только в mapper/adapter. Artist/album IDs и type artwork берутся из metadata, не из формы URL. Прочитать next/browse response для будущего YTM lyrics ID без требования собственного Google login.
- [ ] Повторить test command и `./gradlew projectCheck`; проверить empty success отдельно от malformed/provider failure. Provider DTO не появляется в core/UI signature.

## Task 3. Normalization, matching и search fallback

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/search/TrackNormalizer.kt`, `TrackMatcher.kt`, `SearchCoordinator.kt`; tests `core/src/commonTest/kotlin/app/musicplayer/search/NormalizationTest.kt`, `MatchingTest.kt`, `SearchFallbackTest.kt`, `SearchTestDoubles.kt`.

**Consumes:** CatalogPort, Track, IdentityStore. **Produces:** `TrackNormalizer.normalizeTitle(text:String):String`, `normalizeArtist(text:String):String`; `TrackMatcher.rank(reference:Track, candidates:List<Track>, intent:SearchIntent):List<MatchDecision>`; `SearchIntent(query:String, requestedVersions:Set<String>)`; `MatchDecision(track:Track, autoLink:Boolean, reasons:Set<String>)`; `SearchCoordinator.search(query:String):AppResult<SearchResults>` (suspend), `SearchResults(tracks:List<Track>, primaryFailure:AppError?)`.

- [ ] Создать parameterized tests для punctuation/Unicode/Cyrillic/feat/version markers; fixture candidates содержат live, remaster, cover, karaoke, reaction, tutorial, full album, slowed/reverb, duplicates и missing duration. Включить `SearchFallbackTest.enrichmentPreservesCanonicalId`: `assertEquals(original.id, enriched.id)` и `assertEquals(original.externalIds, enriched.externalIds)`, provenance указывает на YTM entity, но не регистрируется identity alias. Enrichment не объединяет canonical rows и не переносит favorites между ID. Написать assertions до implementation;
- [ ] Запустить RED-проверку: `./gradlew :core:desktopTest --tests 'app.musicplayer.search.*'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals("numb", normalizer.normalizeTitle("Numb [Official Video]"))
assertEquals("live forever", normalizer.normalizeTitle("Live Forever"))
assertTrue(ambiguous.all { !it.autoLink })
assertTrue(uniqueStudioMatch.autoLink)
assertFalse(missingDurationMatch.autoLink)
assertEquals(primaryNetworkError, fallbackSuccess.primaryFailure)
assertEquals(0, fakeCatalog.videoCalls) // непустой подходящий YTM результат
```

- [ ] Реализовать deterministic rank: совместимость version, title/artist similarity, duration, album, music/official hints; auto-link только exact normalized title/artist, compatible version, `abs(delta)<=max(2000, durationMs*0.02)`, нет contradictory album и tie. Явно запрошенная live/cover не получает штраф за эту метку. Отсечь blank query без сетевого вызова. Fallback при пустом подходящем наборе или primary failure вызывается один раз; primary failure сохраняется.
- [ ] Test doubles — только commonTest. Прогнать targeted tests, затем `./gradlew projectCheck`. Проверить boundary tolerance на обеих сторонах, не только популярные примеры.

## Task 4. Lyrics normalization/selection и artwork delivery

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/model/Lyrics.kt`; `core/src/commonMain/kotlin/app/musicplayer/lyrics/LyricsPort.kt`, `LyricsCache.kt`, `LrcParser.kt`, `LyricsSelector.kt`; `shared/src/commonMain/kotlin/app/musicplayer/lyrics/LrcLibLyrics.kt`, `YouTubeLyrics.kt`, `LyricsLoader.kt`, `SessionLyricsCache.kt`; `shared/src/commonMain/kotlin/app/musicplayer/artwork/ArtworkLoader.kt`; tests `core/src/commonTest/kotlin/app/musicplayer/lyrics/LyricsSelectionTest.kt`, `LrcParserTest.kt`, `shared/src/desktopTest/kotlin/app/musicplayer/lyrics/LyricsProvidersTest.kt`, `shared/src/desktopTest/kotlin/app/musicplayer/artwork/ArtworkLoaderTest.kt`.

**Consumes:** Track/candidate from tasks 1–3, injected HTTP client. **Produces:** в core model `LyricLine(text:String,startMs:Long,endMs:Long?)`, `NormalizedLyrics(plainText:String?,lines:List<LyricLine>,kind:LyricsKind,source:SourceRef)`, kind `PLAIN/LINE_SYNCED/INSTRUMENTAL`, sealed `LyricsLookup.Available(lyrics:NormalizedLyrics)`, `Instrumental(source:SourceRef)`, `Unavailable`, `Failed(error:AppError)`; `LyricsPort.fetch(track:Track,candidate:PlaybackCandidate):AppResult<LyricsCandidate?>` (suspend); `LyricsCandidate(lyrics:NormalizedLyrics,versionMatched:Boolean,timingValid:Boolean)`; `LyricsSelector.select(candidates:List<LyricsCandidate>):NormalizedLyrics?`; `LrcParser.parse(text:String,durationMs:Long?):AppResult<List<LyricLine>>`; shared `LyricsLoader.load(track:Track,candidate:PlaybackCandidate):LyricsLookup` (suspend); `ArtworkLoader` configures a Coil image loader with injected network transport.

`LyricsCache.getLyrics(id:TrackId,candidateId:CandidateId,nowMs:Long):LyricsLookup?` / `saveLyrics(id:TrackId,candidateId:CandidateId,lookup:LyricsLookup,atMs:Long):Unit` — suspend, реализуется SessionLyricsCache, затем Room repository. LyricsLoader получает providers, selector, cache и clock. `ArtworkLoader.imageLoader:ImageLoader` для UI; suspend `fetch(artwork:Artwork):AppResult<ArtworkBytes>` для сохранения в задаче 16; `ArtworkBytes(bytes:ByteArray,mimeType:String)`. Все fetch пути используют injected network, без отдельного default client.

- [ ] Написать tests parser и selector: repeated timestamps/lines, malformed/negative/beyond-duration timing, correct plain vs wrong-version synced; provider timeout/error отдельно от null/instrumental. ArtworkLoaderTest проверяет failure → placeholder, а fetch использует supplied client (`assertEquals(1, injectedClientRequestCount)`) на синтетическом изображении.
- [ ] Запустить RED-проверку: `./gradlew :core:desktopTest --tests 'app.musicplayer.lyrics.*' :shared:desktopTest --tests 'app.musicplayer.lyrics.*' --tests 'app.musicplayer.artwork.ArtworkLoaderTest'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(correctPlain, selector.select(listOf(wrongVersionSynced, correctPlainCandidate)))
assertEquals(listOf(0L, 1200L), parsedLines.map { it.startMs })
assertIs<LyricsLookup.Failed>(bothProvidersFailed)
assertIs<LyricsLookup.Instrumental>(instrumentalResponse)
```

- [ ] Реализовать LRCLIB title/artist/optional album/duration query и tagged YTM next/browse lyrics mapping; timestamped lyrics использовать только если actual response их содержит. Запросы lazy при открытии lyrics; максимум 6 секунд на provider; coroutine cancellation не проглатывается. Привязка к selected recording обязательна; word-level fields не выходят в UI. Artwork priority — release, matched album, video, placeholder; failure даёт placeholder.
- [ ] Прогнать targeted tests и `./gradlew projectCheck`. Тексты fixture lyrics синтетические; cache и offline persistence подключаются задачами 9/16.

## Task 5. Реальный resolver и ограниченный lifetime

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/model/Playback.kt`; `core/src/commonMain/kotlin/app/musicplayer/playback/PlaybackResolver.kt`, `StreamLeaseCache.kt`; `shared/src/jvmCommonMain/kotlin/app/musicplayer/playback/InnerTubePlaybackResolver.kt`, `ExtractionFactory.kt`; tests `core/src/commonTest/kotlin/app/musicplayer/playback/StreamLeaseCacheTest.kt`, `shared/src/desktopTest/kotlin/app/musicplayer/playback/ResolverMappingTest.kt`; `androidApp/src/main/kotlin/app/musicplayer/playback/AndroidTokenProvider.kt`.

**Consumes:** PlaybackCandidate/NetworkContext задачи 1, tagged extractor/client после задачи 2 и injected clock/network transport.

**Produces:** `TransportKind(PROGRESSIVE,HLS,BOUNDED_RANGE)`; `Representation(id:String,mimeType:String,codec:String,expectedBytes:Long?,expectedDurationMs:Long?,validator:String?)`; runtime-only redacted `MediaRequest(uri:String,headers:Map<String,String>)`; `ResolvedPlayback(candidateId:CandidateId,representation:Representation,transport:TransportKind,request:MediaRequest,expiresAtMs:Long?,networkGeneration:Long)`; `PlaybackCapabilities(transports:Set<TransportKind>)`; `PlaybackResolver.resolve(candidate:PlaybackCandidate,capabilities:PlaybackCapabilities,network:NetworkContext):AppResult<ResolvedPlayback>` (suspend); `StreamLeaseCache.get(candidateId:CandidateId,representationId:String,networkGeneration:Long,nowMs:Long):ResolvedPlayback?`, `put(lease:ResolvedPlayback)`, `clear()`.

- [ ] До implementation написать expiry/capability tests с injected clock; fake extractor возвращает direct/HLS/SABR/range/error.
- [ ] Запустить RED-проверку: `./gradlew :core:desktopTest --tests 'app.musicplayer.playback.StreamLeaseCacheTest' :shared:desktopTest --tests 'app.musicplayer.playback.ResolverMappingTest'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertNull(cache.get(id, "140", generation, expiresAtMs - 29_999))
assertNotNull(cache.get(id, "140", generation, expiresAtMs - 30_001))
assertNull(cache.get(id, "140", generation + 1, nowMs))
assertNull(unknownExpiryCachedForNewSession)
assertEquals(ErrorCode.UNSUPPORTED_TRANSPORT, sabrOnly.errorOrNull()?.code)
assertFalse(lease.toString().contains("signature=test-secret"))
```

- [ ] Создать tagged InnerTubeX extractor/config/cipher wiring; Runtime DTO mapping не сохраняет signed URLs. Host token provider — отдельный Android component, public anonymous flow без cookies/Premium discovery. System connection применяется сейчас; полная proxy configuration в задаче 10. AndroidTokenProvider capabilities не объявляются, пока corresponding component не реализован; если token нужен и путь отсутствует, вернуть диагностируемую причину, не fake success.
- [ ] Поддержать только реально читаемые player transports; BOUNDED_RANGE объявить capability лишь после adapter/read verification задачи 6. Слишком ранний expiry заново resolve; unknown expiry не reuse новой session. Прогнать targeted tests и `./gradlew projectCheck`.

## Task 6. Android Media3 player, session и callbacks

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/model/Queue.kt`; modify `core/src/commonMain/kotlin/app/musicplayer/model/Playback.kt`; create `core/src/commonMain/kotlin/app/musicplayer/playback/PlayerEngine.kt`, `PlaybackCoordinator.kt`; `androidApp/src/main/kotlin/app/musicplayer/playback/AndroidPlayerEngine.kt`, `PlaybackService.kt`, `MediaSourceFactory.kt`, `PlayerConnection.kt`; `androidApp/src/main/kotlin/app/musicplayer/MusicApplication.kt`, `AppGraph.kt`; tests `core/src/commonTest/kotlin/app/musicplayer/playback/PlaybackCoordinatorTest.kt`, `androidApp/src/androidTest/kotlin/app/musicplayer/playback/PlayerIntegrationTest.kt`, `GeneratedMedia.kt`; modify AndroidManifest.

**Consumes:** Resolver/capabilities; models task 1. **Produces:** `QueueEntry(id:QueueEntryId,trackId:TrackId)`; `PlayableSource.Remote(lease:ResolvedPlayback,track:Track)` / `Local(assetId:AssetId,stableUri:String,mimeType:String,cacheKey:String,track:Track)` с общей normalized metadata property `track`; `PlaybackSnapshot(status:PlayerStatus,current:QueueEntry?,track:Track?,positionMs:Long,durationMs:Long?,playWhenReady:Boolean,error:AppError?)`, status `IDLE/RESOLVING/BUFFERING/PLAYING/PAUSED/ENDED/FAILED`; `PlayerEngine.snapshot:StateFlow<PlaybackSnapshot>`, suspend `load(entry:QueueEntry,source:PlayableSource,positionMs:Long,playWhenReady:Boolean):AppResult<Unit>`, `play():Unit`, `pause():Unit`, `seek(positionMs:Long):Unit`, `stop():Unit`, `release():Unit`; `PlaybackCoordinator.select(track:Track,entry:QueueEntry,positionMs:Long,playWhenReady:Boolean):AppResult<Unit>` (suspend). Engine получает metadata из source.track; current Track для Now Playing/lock screen следует snapshot.track.

- [ ] Написать common tests late resolve A after B, pause during resolve, seek clamp и неуспешный resolve при сохранённой metadata. Instrumented test использует generated WAV 10 секунд через local test HTTP, не YouTube.
- [ ] Запустить RED-проверку: `./gradlew :core:desktopTest --tests 'app.musicplayer.playback.PlaybackCoordinatorTest'`; device: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.musicplayer.playback.PlayerIntegrationTest`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(entryB, snapshot.current)
assertFalse(snapshot.playWhenReady) // pause до завершения resolve
assertEquals(10_000L, clampedSeekMs)
assertTrue(actualPlayerDurationMs in 9_900L..10_100L)
assertTrue(actualPositionAfterSeekMs >= 5_000L)
```

- [ ] Implement coordinator generation/cancellation и Media3 calls на main dispatcher; service владеет player, Activity — controller connection. Manifest: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK; mediaPlayback service. Настроить MediaSession и metadata title/artists/artwork из Track. Pause/resume/seek отражают actual callbacks, не optimistic UI timer. Если range-capable источник требует отдельного reader, реализовать его здесь с range/read tests, иначе не объявлять capability.
- [ ] Прогнать `projectCheck`, device test при доступном устройстве. Ни positive resolver fixture, ни generated WAV не доказывают реальный полный YouTube playback.

## Task 7. Технический vertical slice и live evidence

**Files:** Create `shared/src/commonMain/kotlin/app/musicplayer/ui/TechnicalApp.kt`, `TechnicalState.kt`, `TechnicalScreenModel.kt`; `androidApp/src/main/kotlin/app/musicplayer/MainActivity.kt`; modify `MusicApplication.kt`, `AppGraph.kt`, AndroidManifest; tests `shared/src/commonTest/kotlin/app/musicplayer/ui/TechnicalFlowTest.kt`; `docs/validation/core-playback.md`, `core-playback-results.md`.

**Consumes:** Tasks 1–6; manual constructor injection в AppGraph, без DI framework. **Produces:** Технический экран search/results/artwork, выбранный Track, play/pause/seek/duration/progress и открываемые lyrics. UI imports только app models/commands, не InnerTubeX/Media3 DTO.

`TechnicalState(query:String,results:List<Track>,selected:Track?,playback:PlaybackSnapshot,lyrics:LyricsLookup?,loadingLyrics:Boolean)`; `TechnicalScreenModel.state:StateFlow<TechnicalState>`, suspend `search(query:String)`, `select(track:Track)`, `loadLyrics()`, `playPause()`, `seek(positionMs:Long)`, `close()`. Model получает SearchCoordinator/PlaybackCoordinator/PlayerEngine/LyricsLoader; close отменяет только UI-owned jobs, не service/player.

- [ ] Написать `TechnicalFlowTest.ignoresStaleSearch`, `showsMetadataWhenPlaybackFails`, `lyricsFailureDoesNotStopAudio`.
- [ ] Запустить RED-проверку: `./gradlew :shared:desktopTest --tests 'app.musicplayer.ui.TechnicalFlowTest'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(queryBTracks, state.results)
assertEquals(selectedTrack, state.selected)
assertEquals(ErrorCode.URL_REJECTED, state.playback.error?.code)
assertEquals(PlayerStatus.PLAYING, state.playback.status) // lyrics failure независим
```

- [ ] Связать реальные adapters/engine, lifecycle-aware collection и технический UI без полировки, animation/design system и final navigation. `./gradlew projectCheck`; APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- [ ] Выполнить explicit device/live procedure spec §17 через доступный системный VPN: representative categories, полный трек до конца, forward/backward seek, корректные metadata/artwork, plain/synced/unavailable lyrics. Results фиксируют source/version/network и фактический outcome; lyrics/audio/signatures не сохраняются в repository. Проверить конец полной записи, чтобы обнаружить успешный HTTP с ограниченным начальным окном. Отсутствие доступа/устройства отмечается как незавершённый live gate, не PASS.
- [ ] Gate milestone 1: реальная музыкальная цепочка подтверждена, tests/build проходят, failures классифицированы. Если нет полного audio или basic catalog/artwork, вернуться к integration decision до следующего visual этапа; не подменять live flow fixtures.
