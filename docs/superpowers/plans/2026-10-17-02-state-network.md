# Milestone 2 — State and Network Implementation Plan

**Goal:** Durable library/queue, сквозной HTTP/SOCKS5 proxy, background controls и диагностируемое ограниченное восстановление playback.

**Architecture:** Pure queue/recovery policies живут в core; Room repository и network construction — в shared; MediaSessionService и platform secrets/WebView — в androidApp.

**Tech Stack:** [Основной план](2026-10-17-android-mvp.md); [одобренный spec](../specs/2026-10-17-music-player-design.md), разделы 8–16.

Предусловие — live gate milestone 1 из [плана 01](2026-10-17-01-core-playback.md). Global Constraints/Review Focus основного плана сохраняются. Current Track/Playback/Catalog contracts не переименовываются между планами.

## Task 8. Queue reducer и service-owned queue

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/queue/QueueReducer.kt`, `QueueController.kt`; tests `core/src/commonTest/kotlin/app/musicplayer/queue/QueueReducerTest.kt`, `QueueControllerTest.kt`; modify `core/src/commonMain/kotlin/app/musicplayer/model/Queue.kt`, `androidApp/src/main/kotlin/app/musicplayer/playback/PlaybackService.kt`.

**Consumes:** QueueEntry, PlaybackCoordinator, PlayerEngine из плана 01. **Produces:** `QueueSnapshot(entries:List<QueueEntry>,currentEntryId:QueueEntryId?,positionMs:Long,playWhenReady:Boolean)`; sealed `QueueCommand.Add(entries:List<QueueEntry>)`, `Remove(id:QueueEntryId)`, `Select(id:QueueEntryId)`, `Next`, `Previous`, `Ended(entryId:QueueEntryId)`; `QueueTransition(snapshot:QueueSnapshot,seekToMs:Long?,stopPlayback:Boolean)`; `QueueReducer.reduce(snapshot:QueueSnapshot,command:QueueCommand):QueueTransition`; `QueueController(engine:PlayerEngine,coordinator:PlaybackCoordinator,lookup:suspend (TrackId)->Track?)` с `state:StateFlow<QueueSnapshot>` и suspend `dispatch(command:QueueCommand):Unit`. До задачи 9 lookup использует session map выбранных Tracks в AppGraph, затем `repository::getTrack`; missing Track даёт SOURCE_UNAVAILABLE, не автоматический skip всей queue.

- [ ] Написать parameterized tests для duplicate Track IDs, previous на 2999/3000/3001 ms, boundaries первого/последнего элемента, remove-current, empty queue и stale callback старого entry ID.
- [ ] Запустить RED-проверку: `./gradlew :core:desktopTest --tests 'app.musicplayer.queue.*'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertNotEquals(queue.entries[0].id, queue.entries[1].id)
assertEquals(previousEntry.id, at3000.currentEntryId)
assertEquals(currentEntry.id, at3001.currentEntryId)
assertEquals(0L, at3001.positionMs)
assertTrue(afterLast.stopPlayback)
assertFalse(emptyTransition.snapshot.playWhenReady)
assertEquals(entryB.id, afterLateEndedForA.currentEntryId)
```

- [ ] Реализовать reducer: remove-current выбирает следующий с position 0 и сохраняет intent, отсутствие следующего останавливает; Ended переходит вперёд без автопропуска error items. QueueController сериализует команды, связывает переход с coordinator/engine, владеет playback generation и не живёт в Activity.
- [ ] Прогнать targeted tests и `./gradlew projectCheck`. Media buttons и technical UI должны пользоваться одним QueueController, не отдельными очередями.

## Task 9. Room schema 1, identity transactions и cache retention

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/library/LibraryRepository.kt`, `CacheRetention.kt`; `shared/src/commonMain/kotlin/app/musicplayer/storage/MusicDatabase.kt`, `Records.kt`, `MusicDao.kt`, `RoomLibraryRepository.kt`, `RecordMapper.kt`; `shared/src/commonMain/kotlin/app/musicplayer/catalog/CachedCatalog.kt`; `shared/src/desktopMain/kotlin/app/musicplayer/storage/DatabaseFactory.kt`; `shared/src/androidMain/kotlin/app/musicplayer/storage/DatabaseFactory.kt`; tests `shared/src/desktopTest/kotlin/app/musicplayer/storage/LibraryPersistenceTest.kt`, `RetentionTest.kt`; exported schema `shared/schemas/`; modify AppGraph.

**Consumes:** IdentityStore, LyricsCache, Track, QueueSnapshot, NormalizedLyrics. **Produces:** `LibraryRepository : IdentityStore, LyricsCache` с suspend `upsert(track:Track):Unit`, `getTrack(id:TrackId):Track?`, `setFavorite(id:TrackId,favorite:Boolean):Unit`, `saveQueue(snapshot:QueueSnapshot):Unit`, `restoreQueue():QueueSnapshot`, `recordStarted(id:TrackId,atMs:Long):Unit`, `getCachedSearch(query:String,musicOnly:Boolean,nowMs:Long):List<Track>?`, `cacheSearch(query:String,musicOnly:Boolean,tracks:List<Track>,atMs:Long):Unit`, `prune(nowMs:Long):Unit`; `favorites:Flow<List<Track>>`, `recent:Flow<List<Track>>`; inherited getLyrics/saveLyrics имеют сигнатуры задачи 4. `CachedCatalog(delegate:CatalogPort,repository:LibraryRepository,nowMs:()->Long):CatalogPort` cache только successful normalized search results и metadata; failures не становятся empty success. Offline mapping добавляется schema 2 в задаче 13, не заранее.

- [ ] Написать real BundledSQLiteDriver desktop tests: close/reopen, transaction race двух source upserts, две записи с одинаковым title, queue duplicates/current identity, unknown restore entry, lyrics selection key и retention.
- [ ] Запустить RED-проверку: `./gradlew :shared:desktopTest --tests 'app.musicplayer.storage.*'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(savedId, reopened.getOrCreate(source))
assertEquals(savedQueue.entries, reopened.restoreQueue().entries)
assertFalse(reopened.restoreQueue().playWhenReady)
assertNotEquals(sameTitleA.id, sameTitleB.id)
assertEquals(100, reopened.recent.first().size)
assertNotNull(repository.getTrack(favoriteOlderThan7Days))
assertNull(expiredSearchResultAfter15Minutes)
assertNull(failureCachedAsUnavailable)
```

- [ ] Реализовать schema 1 tables: tracks (нормализованный versioned app record), external_identity (unique provider/kind/id), candidates, favorites, recent_history, queue_entries, queue_snapshot, lyrics_cache, catalog_cache, settings. Ordered artists и optional AlbumRef сериализуются как app fields, не raw provider body; отдельных artist/album screen tables сейчас нет. `getOrCreate`/upsert и queue snapshots атомарны. На restart missing current выбирает первый существующий entry paused либо empty; position неотрицательна.
- [ ] Реализовать retention values spec: 100 recent, metadata 7 дней/1000 unprotected tracks, search 15 минут, unavailable lyrics 24 часа; positive offline-linked content защищается задачей 13. Смена selected candidate не отдаёт чужие timed lyrics. История обновляется один раз после реального старта playback session, не при каждом BUFFERING/PLAYING callback. Без destructive fallback. Session identity/lyrics caches заменить repository, CatalogPort обернуть CachedCatalog. Прогнать tests и `projectCheck`.

## Task 10. Единый сетевой режим, proxy credentials и WebView

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/network/NetworkController.kt`, `SecretStore.kt`; `shared/src/jvmCommonMain/kotlin/app/musicplayer/network/NetworkClients.kt`, `NetworkConfigValidator.kt`; `androidApp/src/main/kotlin/app/musicplayer/network/AndroidNetworkController.kt`, `AndroidSecretStore.kt`, `WebViewProxyRoute.kt`; tests `shared/src/desktopTest/kotlin/app/musicplayer/network/ProxyTransportTest.kt`, `ProxyTestServers.kt`; `androidApp/src/androidTest/kotlin/app/musicplayer/network/ProxyRouteTest.kt`, `SecretStoreTest.kt`; modify AndroidTokenProvider, MediaSourceFactory, ArtworkLoader, AppGraph и `androidApp/src/main/AndroidManifest.xml`.

**Consumes:** NetworkConfig/NetworkContext из задачи 1. **Produces:** `NetworkController.context:StateFlow<NetworkContext>`, suspend `apply(config:NetworkConfig):AppResult<NetworkContext>`; `SecretStore.put(username:String,password:CharArray):SecretId`, `read(id:SecretId):ProxyCredentials?`, `remove(id:SecretId)` — suspend, `ProxyCredentials` runtime-only/redacted; `NetworkClients` создаёт Ktor/OkHttp clients одной config/generation и отдаёт actual OkHttp Call.Factory Media3/Coil adapters. Tokens/cipher/WebView получают тот же network context.

- [ ] Написать tests local HTTP CONNECT proxy без/с auth, SOCKS5 no-auth с proxy-side DNS и unreachable proxy. Fake proxy/server находится только в test sources.
- [ ] Запустить RED-проверку: `./gradlew :shared:desktopTest --tests 'app.musicplayer.network.ProxyTransportTest'`; device tests: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=app.musicplayer.network`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(0, directOriginConnectionsWhenProxyFails)
assertNull(originRequest.getHeader("Proxy-Authorization"))
assertEquals("unresolved.test.invalid", socksHandshake.destinationHost)
assertEquals(ErrorCode.PROXY_AUTH, badPassword.errorOrNull()?.code)
assertEquals(ErrorCode.PROXY_UNSUPPORTED, socksWithCredentials.errorOrNull()?.code)
assertEquals(oldGeneration + 1, switched.generation)
assertFalse(exportedSettings.contains("test-password"))
```

- [ ] Implement config validation, one-profile settings и KeyStore-backed encryption; plaintext username/password не сериализовать в NetworkConfig/Room. В системном режиме использовать inherited OS route/VPN. Для explicit proxy не добавлять DIRECT в selection/bypass fallback. HTTP(S) origin verification остаётся включённой. Не поддерживать TLS-to-proxy или SOCKS authentication. Включить WebView `PROXY_OVERRIDE` только если capability есть, дождаться apply callback до запуска служебных запросов; отсутствие capability возвращает PROXY_UNSUPPORTED, не direct success.
- [ ] Для Android 13+ определить runtime POST_NOTIFICATIONS handling: отказ не ломает playback и не объявляет download завершённым; UI остаётся источником progress, foreground service следует требованиям ОС. Если HTTP-auth служебный WebView путь не маршрутизируется, задача не считается поддержкой этого режима: нужен исправленный transport либо явное пересогласование ограничения, не молчаливое исключение feature.
- [ ] `apply` сначала увеличивает generation и публикует context с ready=false, отменяет старые requests и очищает leases/network-bound tokens. После успешной активации всех transport components публикуется тот же generation с ready=true. Ошибка оставляет ready=false и не возвращает приложение незаметно в direct mode. Platform connectivity обновляет online; этот флаг не доказывает доступность YouTube. Сохранить track/position/intent при reconnect. Instrumented test проверяет фактический служебный WebView route, а не только поле config.
- [ ] Прогнать `projectCheck` и device package tests; затем manual sweep для catalog, audio seek, artwork и lyrics через HTTP/SOCKS5. Не объявлять «proxy работает» только по успешному search или основному OkHttp request.

## Task 11. Bounded recovery, diagnostics и lifecycle persistence

**Files:** Create `core/src/commonMain/kotlin/app/musicplayer/playback/RecoveryPolicy.kt`, `SnapshotPersistence.kt`; `core/src/commonMain/kotlin/app/musicplayer/diagnostics/DiagnosticEvent.kt`; `shared/src/jvmCommonMain/kotlin/app/musicplayer/diagnostics/RedactedDiagnostics.kt`; tests `core/src/commonTest/kotlin/app/musicplayer/playback/RecoveryPolicyTest.kt`, `SnapshotPersistenceTest.kt`, `shared/src/desktopTest/kotlin/app/musicplayer/diagnostics/DiagnosticsTest.kt`; modify PlaybackCoordinator, PlaybackService, PlayerConnection, MusicDao.

**Consumes:** Runtime generation/coordinator/engine из задач 5–6, service-owned QueueController задачи 8, LibraryRepository задачи 9 и NetworkController задачи 10.

**Produces:** `RecoveryPolicy.shouldResolveAgain(error:AppError,attemptId:String):Boolean`, `resetForUserAttempt(attemptId:String)`; suspend `SnapshotPersistence.start(queue:StateFlow<QueueSnapshot>)`, `flush(snapshot:QueueSnapshot)`; `DiagnosticEvent(operationId:String,stage:String,providerVersion:String?,sourceId:String?,transport:String?,errorCode:ErrorCode?,elapsedMs:Long)`; `RedactedDiagnostics.record(event:DiagnosticEvent)` / `exportRedacted():String`.

- [ ] Написать tests initial resolve + один fresh resolve максимум, повторный 403/format disappearance, queued stale error, restored position/intent, сохранение раз в 5000 ms и на pause/track change; redirect error с marker secret не входит в export.
- [ ] Запустить RED-проверку: `./gradlew :core:desktopTest --tests 'app.musicplayer.playback.RecoveryPolicyTest' --tests 'app.musicplayer.playback.SnapshotPersistenceTest' :shared:desktopTest --tests 'app.musicplayer.diagnostics.DiagnosticsTest'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertEquals(2, resolverCallsIncludingInitial)
assertEquals(PlayerStatus.FAILED, repeatedRejection.status)
assertEquals(savedPositionMs, recoveryLoadPositionMs)
assertFalse(pausedDuringRecovery.playWhenReady)
assertEquals(1, periodicSavesAfter4999Then5000Ms)
assertFalse(export.contains("signature=test-secret"))
assertFalse(export.contains("test-password"))
```

- [ ] Реализовать один auto-recovery на playback attempt; callback BUFFERING/PLAYING не обнуляет budget, явный user retry/new selection создаёт новый attempt. Не считать 407, region/login/rate-limit тем же expired URL. Ошибки связывать с stage/version/category; raw Throwable/provider bodies не писать. Диагностический ring ограничить 1 MiB локально, без telemetry; export повторно проходит redaction.
- [ ] Persistence пишет actual position/queue на переходах и каждые 5 секунд во время playing. Service/session продолжает playback после Activity recreation, корректно обрабатывает audio focus, noisy/headset disconnect и media commands. Расширить `PlayerIntegrationTest` на recreation, disconnect pause и controller reconnect. Прогнать `projectCheck` и device test; process death не выдавать за Activity recreation.

## Task 12. Engineering surfaces и milestone 2 acceptance

**Files:** Create `shared/src/commonMain/kotlin/app/musicplayer/ui/QueueScreen.kt`, `LibraryScreen.kt`, `NetworkSettingsScreen.kt`, `ErrorPresentation.kt`; tests `shared/src/commonTest/kotlin/app/musicplayer/ui/LibraryQueueFlowTest.kt`, `ErrorPresentationTest.kt`; modify TechnicalApp/TechnicalScreenModel; `docs/validation/state-network.md`, `state-network-results.md`.

**Consumes:** QueueController, LibraryRepository, NetworkController; reusable UI принимает app models/commands. **Produces:** Технические screens для queue, favorites/recent, одного proxy profile и normalized error states. Только flows/features этого milestone, без final visual navigation/design.

- [ ] Написать tests removal сохраняет favorite, restart paused, error action соответствует category, смена settings не возобновляет paused track.
- [ ] Запустить RED-проверку: `./gradlew :shared:desktopTest --tests 'app.musicplayer.ui.LibraryQueueFlowTest' --tests 'app.musicplayer.ui.ErrorPresentationTest'`. Ожидается failure по указанному поведению/отсутствующему contract, не по ошибке окружения.

```kotlin
assertTrue(state.favoriteIds.contains(trackId))
assertFalse(restored.playback.playWhenReady)
assertEquals(ErrorAction.EDIT_NETWORK, present(proxyAuthError).primaryAction)
assertEquals(ErrorAction.RETRY, present(expiredUrlFailure).primaryAction)
```

- [ ] Implement `present(error:AppError):ErrorPresentation` в ErrorPresentation.kt; `ErrorAction(RETRY,NEXT,EDIT_NETWORK,MANAGE_STORAGE,NONE)`, `ErrorPresentation(message:String,primaryAction:ErrorAction)`; сообщения человеческие, без stacktraces и секретов. UI mutations делегируют существующим controllers/repository, не дублируют state machine.
- [ ] `./gradlew projectCheck`, затем device/live acceptance: queue duplicate/next/previous, cold restart paused, background/lock screen/headset, system VPN, HTTP auth/no-auth, SOCKS5 no-auth, wrong credentials, unreachable proxy и switch while paused/playing. Результаты каждого режима отдельно; внешние ограничения не отмечаются как pass.
- [ ] Gate milestone 2: durable data сохраняются, сквозные supported network modes проверены, failures bounded, diagnostics redacted. Затем переход к [offline plan](2026-10-17-03-offline.md).
