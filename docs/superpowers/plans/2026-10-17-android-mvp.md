# Molpy — Android MVP Implementation Plan

**Goal:** Получить проверенный Android music player с normalized Track, YouTube playback, локальной библиотекой и управляемыми offline downloads до разработки финального UI.

**Architecture:** Три модуля: независимое Kotlin-ядро `core`, общие adapters/storage/Compose UI в `shared`, Android lifecycle/Media3/downloads в `androidApp`. Catalog и resolver используют закреплённый InnerTubeX за разными contracts; SQLite и media files имеют разные обязанности.

**Tech Stack:** Kotlin/Compose Multiplatform, coroutines/Flow, InnerTubeX, Ktor/OkHttp, Android Media3, Room KMP/BundledSQLiteDriver.

**Spec:** [Одобренная спецификация](../specs/2026-10-17-music-player-design.md).

Статус: архитектура и план одобрены. Реализован технический Android slice задач 1–7; автоматические проверки пройдены, device/live gate milestone 1 ожидает проверки. Актуальные наблюдения — в [результатах проверки](../../validation/core-playback-results.md).

## Границы и состав плана

Engineering milestones реализуются последовательно тремя связанными планами. Каждый завершается runnable APK и отдельным результатом проверки. Общий spec не дробится: playback, persistence и downloads связаны одной identity и одной сетевой политикой.

| Порядок | План | Результат |
| --- | --- | --- |
| 1 | [Музыкальная цепочка, задачи 1–7](2026-10-17-01-core-playback.md) | Реальные search/metadata/artwork/audio/lyrics через Track, технический UI |
| 2 | [Состояние и сеть, задачи 8–12](2026-10-17-02-state-network.md) | Queue/library persistence, proxy, background controls и bounded recovery |
| 3 | [Офлайн, задачи 13–17](2026-10-17-03-offline.md) | Полные downloads и локальный playback после restart в airplane mode |

После инженерных milestones находится отдельный Product Design gate. Финальный visual UI нельзя подробно распланировать до выбора визуального направления. Действие gate: показать несколько разных направлений, получить выбранный reference, обновить UI-раздел spec и составить самостоятельный UI implementation plan. Это отдельная стадия с определённым входом и результатом, не незавершённая задача текущего engineering plan.

Windows/Linux и web не входят в исполнение этих трёх планов. JVM compilation/tests общего кода сохраняют возможность desktop reuse; desktop player/release не выдаются за готовые.

## Global Constraints

- «Первый MVP: Android. Основной пользовательский регион — Россия.»
- «Android baseline — API 23»; дополнительные WebView capabilities проверяются отдельно.
- «SOCKS5 без авторизации»; HTTP CONNECT — без авторизации или username/password.
- «Сетевой адрес разрешается just-in-time»; signed media URLs не попадают в постоянные app/Media3 indexes.
- «В MVP одна одновременная загрузка, одно стандартное качество и отсутствие перекодирования.»
- «Room KMP stable 2.8.5»; InnerTubeX v0.10.0; Room 3 alpha не используется.
- «Previous при position > 3 секунд возвращает к началу текущего элемента»; после полного process restart восстановление paused.
- Auto-enrichment tolerance: `max(2 секунды, 2% длительности)` при однозначном title/artist/version match; неизвестные данные не выдумываются.
- Expiry reuse safety margin — 30 секунд; recovery — одна операция для текущего сбоя, не бесконечный retry.
- History — 100 различных Track; metadata cache — 7 дней/1000 Track; search cache — 15 минут; lyrics unavailable — 24 часа; playback snapshot — примерно раз в 5 секунд.
- Другие playback ecosystems, accounts/backend/sync, playlist import, DRM/paid-access обход и финальный visual design исключены.

## Review Focus

1. Поздний callback предыдущего трека после seek/select/recreation не должен запускать неверное аудио: задачи 6, 8, 11.
2. CDN отдаёт только начальное окно аудио при успешном HTTP статусе: полный playback проверяется в задаче 7, offline completeness — в задачах 15, 17.
3. Redirect/proxy/служебный WebView запрос не должен утечь в direct route, передать Proxy-Authorization origin или сохранить signed URL: задачи 10, 11, 14.
4. Одинаковые title и повторяющиеся queue entries не должны склеить разные recordings или назначить чужие synced lyrics: задачи 2–4, 8, 9.
5. Restart/resume после смены representation, нехватки места или partial file не должен дать available либо испортить существующие downloads: задачи 13–17.

## Файловая структура

| Путь | Ответственность |
| --- | --- |
| `core/src/commonMain/kotlin/app/musicplayer/` | Models, contracts, normalization/matching, orchestration, queue и download policies; без Android/Room/InnerTubeX |
| `core/src/commonTest/kotlin/app/musicplayer/` | Pure tests и test-only builders/fakes |
| `shared/src/commonMain/kotlin/app/musicplayer/` | Catalog/lyrics adapters, Room entities/repositories, shared screen state и технический Compose UI |
| `shared/src/jvmCommonMain/kotlin/app/musicplayer/` | Android/JVM HTTP construction, InnerTubeX wiring и artifact-specific code |
| `shared/src/desktopTest/kotlin/app/musicplayer/` | Provider fixtures/MockEngine, реальные SQLite tests и JVM transport tests, без live YouTube |
| `shared/src/commonTest/kotlin/app/musicplayer/` | Platform-independent UI state tests |
| `androidApp/src/main/kotlin/app/musicplayer/` | Composition root, Activity, Media3/session, WebView/token integration, DownloadService, secrets/files |
| `androidApp/src/androidTest/kotlin/app/musicplayer/` | Device integration tests с generated media и локальными test servers |
| `docs/validation/` | Dependency matrix и явные live/device procedures/results без secrets и media bodies |

Название продукта и репозитория — `Molpy`, выбрано пользователем. Package `app.musicplayer` и applicationId `app.musicplayer.android` пока остаются техническими идентификаторами. Публичный репозиторий: [resassance/Molpy](https://github.com/resassance/Molpy). Технические Android-сборки публикуются как prerelease для проверки на устройстве.

## Сборка и общие проверки

Исходная матрица для задачи 1: JDK 17, JVM bytecode 17, Gradle 9.6.0, AGP 9.4.1, Kotlin/Compose compiler 2.4.21, Compose Multiplatform 1.12.2, KSP 2.3.12, compile/target SDK 37, Build Tools 36.0.0, minSdk 23. InnerTubeX 0.10.0, Ktor 3.6.0, coroutines/serialization 1.11.0, Room 2.8.5, bundled SQLite 2.7.1, Media3 1.11.1. Эта исходная матрица подтверждена интегрированной технической сборкой; текущие pins и уточнения находятся в `gradle/libs.versions.toml` и [dependency validation](../../validation/dependencies.md).

`core` и `shared` объявляют Android и `jvm("desktop")`. Для AGP 9.4 используется `kotlin.android {}`, не устаревший `androidLibrary {}`. Android app использует `com.android.application` и его built-in Kotlin. Compose compiler plugin совпадает с Kotlin. Source set `jvmCommonMain` явно объединяет Android и desktop только там, где нужны JVM APIs. Native/iOS/web targets не объявляются.

Compose 1.12.2 документирует 64-bit platforms. Начальные APK ABI — arm64-v8a и x86_64; поддержка 32-bit не обещается. Native libraries выбранных dependencies должны реально присутствовать для этих ABI. Недоступность SDK/artifact/JDK или конфликт matrix фиксируется как build blocker до provider implementation; номера нельзя считать совместимыми только по этой таблице.

Root Gradle task `projectCheck` зависит от `:core:desktopTest`, `:shared:desktopTest`, `:shared:compileKotlinDesktop`, `:androidApp:testDebugUnitTest`, `:androidApp:lintDebug`, `:androidApp:assembleDebug`. Локальные Android JVM tests добавлены в задаче 6 для regression coverage controller retry и transport selection без device runtime. Общая проверка: `./gradlew projectCheck`. Успех: exit 0/`BUILD SUCCESSFUL`, реально выполненные соответствующие tests, отсутствие необъяснённых lint failures. `UP-TO-DATE` допустим только при проверенном актуальном входе.

Device suite: `./gradlew :androidApp:connectedDebugAndroidTest`; запуск только при видимом `adb devices` устройстве/emulator. Отсутствие устройства отмечается как невыполненная проверка. Live YouTube никогда не входит в `projectCheck` или обычный connected test suite.

В RED шагах нового contract допустим ожидаемый missing symbol до появления этого contract; build/tooling failure или неверный импорт не являются полезным RED. Остальные шаги требуют теста, падающего на нужном поведении. Зелёный отдельный test не заменяет `projectCheck` в конце задачи.

## Handoff и следующие gates

Покрытие spec: §1–3 → задачи 1, 7 и live gates; §4–6 → 1, 2, 4–6, 8, 13; §7 → 2–3; §8–9 → 5–6, 10–11, 14–16; §10–11 → 2, 4, 9, 16; §12 → 13–17; §13 → 9, 13–14; §14 → 6, 8, 11; §15–16 → 1, 5, 10–12, 14–17; §17 → RED/GREEN steps всех задач и acceptance 7/12/17; §18 → текущие milestone gates и последующий Product Design/UI plan; §19 → статус review; §20 → sources в spec и build matrix. Специальные протоколы и поздние платформы остаются явными gates, не молчаливыми implementation tasks.

После review этого пакета планов выбирается способ исполнения. Сначала исполняется план 01; milestone не считается готовым по mocks или запуску Activity. Отсутствие реального полного playback останавливает переход к visual UI.

Product Design начинается после инженерной цепочки и offline milestone: вход — работающие модели/сценарии и проверенные ограничения; выход — выбранная visual direction. Реализация production UI требует нового конкретного плана с выбранными references и отдельного review. Перед Android release следуют regression/device checks, проверка runtime licenses/native ABI и локальная release сборка; GitHub upload не выполняется автоматически.

## Источники build matrix

[Kotlin releases](https://kotlinlang.org/docs/releases.html), [Kotlin 2.4.20 Gradle compatibility](https://kotlinlang.org/docs/whatsnew2420.html), [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [Android KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin), [KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12), [Media3 stable](https://developer.android.com/jetpack/androidx/releases/media3). Provider, storage, networking и Terms references находятся в spec.
