# Molpy — спецификация Android MVP

Дата: 2026-10-17. Статус: письменный документ и implementation plan одобрены пользователем; начата реализация. Название продукта — Molpy.

Разделы дизайна согласованы в разговоре. Этот документ объединяет решения с учётом добавленных офлайн-загрузок и proxy. Он не является implementation plan и не разрешает начинать реализацию до завершения следующих согласованных стадий Superpowers.

## 1. Продукт и критерий успеха

Локальный музыкальный плеер с каталогом YouTube Music и воспроизведением из YouTube ecosystem. Пользователь работает с музыкой: Track, исполнители, релиз, обложка, очередь и lyrics. Video/channel объекты и ответы внешних API не являются моделями UI.

Первый инженерный результат: найти обычную коммерческую песню по исполнителю/названию, получить чистые метаданные и подходящую обложку, воспроизвести правильную полную запись, использовать pause/resume и seek, видеть progress/duration и открыть lyrics при наличии. Это происходит через нормализованный Track.

MVP дополнительно позволяет явно скачать выбранный трек, затем воспроизвести его и перемотать без сети вместе с сохранёнными метаданными и доступными сопутствующими ресурсами.

Приоритеты: работающее полное аудио → правильная identity → устойчивость интеграции → метаданные/artwork → lyrics → простота архитектуры → UX → визуальная отделка.

## 2. Платформы, распространение и границы

- Первый MVP: Android. Основной пользовательский регион — Россия.
- Следующий платформенный этап: Windows и Linux, с общими Kotlin-моделями, логикой и Compose UI, но отдельными player/system adapters.
- Web рассматривается позднее. Перенос UI не доказывает пригодность браузерного playback, resolver, CORS или proxy. Backend ради web сейчас не создаётся.
- iOS исключён из текущего проекта.
- Android baseline — API 23, нижняя граница проверенных выбранных Room/InnerTubeX зависимостей. Требования отдельных возможностей WebView проверяются отдельно; minSdk сам по себе не гарантирует их наличие.
- Целевое распространение — APK через GitHub Releases. Это не разрешение на push, публикацию или создание remote repository.

В MVP входят search, Track results/artwork, streaming playback, pause/resume/seek/progress, background/media controls, простая очередь с next/previous, избранное, последние воспроизведённые треки, lyrics, явные офлайн-загрузки, настройки сети/proxy, управление местом и понятные ошибки.

В MVP не входят собственные пользовательские плейлисты и страницы Artist/Album, Home recommendations, внешние playlist importers, другие streaming ecosystems, произвольная локальная медиатека, аккаунт приложения, собственный backend, sync, социальные функции, casting/AirPlay/remote control, EQ/visualizer, themes/plugins и AI/ML matching. Связи artist/album сохраняются в данных без обязательства строить эти страницы сейчас.

Нет автоматического скачивания каталога, экспорта аудио в общую папку, MP3-конвертера, управления VPN-подписками, VLESS/VMess/Shadowsocks-клиента или собственного сетевого посредника.

## 3. Что подтверждено и что остаётся риском

Исходные исследовательские наблюдения приведены ниже. Фактические проверки собственной цепочки фиксируются в [результатах device/live validation](../../validation/core-playback-results.md).

| Наблюдение | Допустимый вывод |
| --- | --- |
| Google отмечает РФ как временно недоступную для YouTube Music | Прямую доступность каталога в РФ обещать нельзя; это не замер каждого неофициального endpoint |
| У пользователя без VPN не загружались Metrolist, NewPipe и обычный YouTube в браузере | Прямой путь в проверенной сети не подтверждён; причина и другие российские сети не установлены |
| С системным VPN пользователь подтвердил Metrolist search/playback/pause/resume/seek для Numb | Есть предварительно рабочий способ доступа на его устройстве; собственный pipeline и полная длительность не проверены |
| В cloud CONNECT к YouTube/YTM отклонял proxy среды с 403 | Эти ошибки не доказывают неисправность extractor или региональный ответ YouTube |
| LRCLIB вернул plain и synced для Numb и Группы крови | Проверена форма двух ответов из cloud, не точность синхронизации на слух и не доступность из всех сетей |

Пользователь разрешил VPN и настройку пользовательского proxy. Затем явно отменил дальнейший отдельный feasibility spike. Throwaway Android APK не создавался. Это не отменяет обычные проверки реализации и не означает успешную валидацию собственной цепочки.

Критические непроверенные предположения: качество music catalog adapter, доступность полных аудиопотоков, совместимость форматов с Media3, expiration/recovery, дополнительные token/WebView запросы через proxy, offline completeness/resume и packaging конкретной dependency matrix. Их проверка начинается в инженерных milestones до Product Design и финального UI.

## 4. Рассмотренные подходы и выбранный стек

| Подход | Плюсы для проекта | Ограничения и стоимость сопровождения |
| --- | --- | --- |
| Kotlin + Compose Multiplatform — выбран | Прямой доступ к Android Media3/services, Kotlin/JVM интеграциям и сетевому стеку; общие модели/логика/UI для desktop | Desktop требует другого player и system controls; JVM/WebView зависимости не переносятся автоматически в browser; upstream extraction остаётся нестабильным |
| Flutter | Общий UI Android/desktop, подходящая графика и audio ecosystem | Extractor/proxy/background детали могут потребовать Kotlin bridge; player и media controls зависят от platform packages; изменения upstream затрагивают Dart/native границы |
| Tauri + web frontend | Удобен для desktop и отдельного resolver process, повторное использование web UI | Android-first потребует Kotlin plugins и native media service наряду с JS/Rust; desktop sidecar модель не переносится на Android автоматически |

Выбранные составляющие:

| Назначение | Решение |
| --- | --- |
| Язык и UI | Kotlin + Compose Multiplatform; visual direction ещё не выбрана |
| Асинхронность/состояние | Kotlin coroutines и Flow/StateFlow; без дополнительного state-management framework |
| YouTube интеграция | Встраиваемый InnerTubeX за отдельными catalog и resolver adapters |
| Android player/session | Media3 ExoPlayer и MediaSessionService |
| HTTP | Ktor с OkHttp engine для интеграции; Media3 OkHttpDataSource и согласованная конфигурация транспорта |
| Android downloads | Media3 DownloadService/DownloadManager, отдельное постоянное хранилище и DownloadIndex |
| Данные приложения | SQLite, Room KMP stable 2.8.5 и BundledSQLiteDriver |
| Lyrics | YouTube Music и LRCLIB за нормализующими adapters |

Для InnerTubeX исходная фиксируемая release — v0.10.0, опубликованная 2026-10-15. README содержит более старый install example 0.7.2: примеры нельзя смешивать без сверки с выбранным tag. Библиотека экспериментальная 0.x. Её tag указывает minSdk 23, compileSdk 37, Kotlin 2.4.20 и Ktor 3.6.0. Проверка опубликованных artifacts в задаче 1 обнаружила разные bytecode targets: desktop JAR — Java 17, Android AAR — Java 21. Поэтому прежнее общее утверждение «JVM 17» недостаточно. Android toolchain должен проверяться на фактической сборке; его consumer JDK и bytecode dependency — разные параметры.

Version catalog проекта должен закрепить совместимые выпущенные версии Kotlin/Compose/AGP/Media3/KSP/Room/InnerTubeX. Конкретная build matrix и доступность опубликованных artifacts проверяются при настройке сборки в implementation plan. Недоступность artifact или конфликт metadata/compiler — явный blocker, а не повод молча переключить integration library или взять случайную ветку. Room 3 alpha API из новой документации не смешиваются с выбранной stable 2.8.5.

## 5. Границы компонентов

Общее ядро содержит application models, matching/search orchestration, queue rules, normalized lyrics и repository contracts. Оно не зависит от Android Context, Media3, InnerTubeX DTO или Room entities.

| Граница | Ответственность |
| --- | --- |
| Catalog adapter | YTM music search, track/release metadata, artist/album identifiers и обычный YouTube fallback; нормализация на выходе |
| PlaybackResolver | Stable source identity → временное описание playable transport с учётом возможностей player и сетевого режима |
| PlayerEngine | Play/pause/seek и фактические состояние, position/duration; platform implementation |
| Lyrics adapters + выбор результата | Получение, matching и нормализация lyrics; выбор по качеству |
| LibraryRepository | Durable metadata, source identities, favorites/recent history, queue snapshot и связи offline resources |
| Download coordinator | Пользовательские команды загрузки, стабильная identity задания и перевод состояния platform downloader в app model |

Catalog и resolver могут использовать один InnerTubeX client, но имеют разные contracts. Metadata provenance никогда не выводится из выбранного playback candidate. Замена resolver не меняет Track/UI/storage identity.

На Android MediaSessionService владеет player. UI посылает команды и наблюдает нормализованное состояние через platform connection; закрытие или пересоздание Activity не уничтожает playback.

Нужны несколько понятных логических границ, а не отдельный Gradle module на каждый interface. Минимальное физическое разбиение уточняется планом. Artwork не требует отдельной provider framework: catalog отдаёт подходящие изображения, image loader занимается доставкой и caching.

## 6. Нормализованные модели и identity

| Модель | Данные и инварианты |
| --- | --- |
| Track | Собственный постоянный app ID; title; упорядоченные artists; optional album и duration; artwork; external identities; metadata provenance; playback candidates; lyrics lookup state |
| ArtistRef / AlbumRef | Отображаемые имена и optional durable provider IDs; отсутствие catalog ID не заменяется ID канала произвольно |
| ExternalIdentity | Provider/namespace + kind + source ID; unique mapping не зависит от CDN URL |
| MetadataProvenance | Источник, его entity ID, время получения и способ получения: catalog либо tentative fallback parsing; источник artwork учитывается отдельно |
| PlaybackCandidate | Provider, stable source ID, версия записи, известная duration, evidence/confidence соответствия; без media URL |
| Artwork | Изображения/размеры, происхождение, kind: release cover, video thumbnail или placeholder; optional local resource reference |
| Lyrics | Plain text, lines с startTime и optional endTime, synchronization type, provenance; привязка к выбранной записи |
| ResolvedPlayback | Source/candidate identity, transport kind, временные адреса и headers, format/codec, expiry сведения, range/segment requirements; только runtime |
| QueueEntry | Собственный ID + Track ID; одинаковый Track может встречаться несколько раз |
| OfflineAsset | Stable asset/download ID, Track и playback identity, representation descriptor, storage key, размер и наличие локальных ресурсов; не CDN URL |

Duration каталога — metadata. Фактическая playback duration и progress принадлежат player. При несовпадении их не подменяют одним полем: это влияет на matching, lyrics и проверку полноты.

App ID не создаёт глобальную базу канонических музыкальных записей. Треки объединяются по надёжной source identity, а не только одинаковым title/artist. Live/remaster/cover и разные записи не склеиваются автоматически. Чужой playback source можно связать с Track только с достаточными evidence соответствия.

## 7. Search, metadata и matching

Основной путь: query → YTM songs search → normalized Track results → выбор пользователя. Альбомные metadata/artwork догружаются при необходимости; отдельные artist/album screens не требуются.

Если подходящих music results нет, выполняется обычный YouTube search. Его результат проходит нормализацию и попытку enrichment через YTM. Сетевой/proxy/provider failure отличается от пустой выдачи; fallback можно попробовать, но исходная причина остаётся в диагностике. Одну и ту же операцию не запускают бесконечно.

Для fallback:

1. Разобрать title/artist и version markers с явным признаком предварительности.
2. Найти возможное соответствие в музыкальном каталоге.
3. Ранжировать по normalized title/artist, duration, album при наличии, music result type и official/topic hints.
4. Автоматически присоединять metadata только при однозначном соответствии; при недостаточной уверенности оставить partial metadata или предложить пользователю выбрать запись.

Очищаем известный служебный мусор вроде `[Official Video]`, `(Lyrics)` и технических quality labels в соответствующих позициях. Не удаляем произвольные скобки, слова из настоящего названия, featured artists или обозначение версии. Normalization для matching не переписывает исходное корректное имя из catalog.

Для первого deterministic matcher:

- Version compatibility — обязательное условие автоматического enrichment. Live/cover/remix/nightcore/slowed/reverb/karaoke/reaction/tutorial/full-album penalize или исключаются в зависимости от намерения query; явно запрошенная версия не штрафуется за само название версии.
- Title и artist — основные сигналы. Duration — независимая проверка; album и official/topic hints не могут компенсировать явно другую композицию.
- Консервативный auto-link допускается при совпадении normalized title/artist, совместимой версии и известной duration с разницей не более `max(2 секунды, 2% длительности)`, если нет противоречащего album и второго равнозначного кандидата. Это heuristic, не доказательство одинаковой фонограммы.
- Более слабые/fuzzy совпадения можно показать в ранжированной выдаче, но не выдавать автоматически за подтверждённое enrichment.
- Отсутствующие album/duration остаются неизвестными. Другой релиз или изображение не подставляются ради полноты карточки.

Пороговые случаи покрываются разнообразными fixtures. AI/ML matching, waveform fingerprinting и сложная scoring platform не нужны.

## 8. Playback resolution и восстановление

UI передаёт Track ID и команду. Application выбирает candidate, сначала проверяя готовый OfflineAsset. Для online playback resolver вызывается непосредственно перед запуском текущего трека; очередь заранее не разрешается.

Resolver выбирает поддерживаемые player варианты и возвращает ResolvedPlayback. URL сам по себе недостаточен: headers, codec/container, диапазоны чтения, segment/range requirements и protocol capabilities передаются через границу.

MVP использует поддерживаемые Media3 аудиоисточники. SABR/UMP нельзя передать как обычный media URL; если для выбранного source нет поддерживаемого пути, возвращается `UnsupportedTransport`. Специальный protocol adapter не добавляется без установленной необходимости. Если это препятствует основному тестовому набору, integration decision пересматривается до дальнейшего UI.

Временные источники:

- Только memory cache; ключ включает candidate/representation, resolver version и network configuration generation.
- Срок берётся из доступных expiry данных. Для повторного использования требуется запас не менее 30 секунд; уже истекающий источник разрешается заново.
- Неизвестный expiry допускает немедленное использование в текущей playback session, но не повторный запуск из cache новой session.
- Даже известный expiry не гарантирует доступность до указанного времени: CDN может отказать раньше.
- Stable identities, а не временные URL, переживают restart.

При CDN rejection или исчезновении формата application допускает одну recovery operation для того же candidate: fresh resolve с выбором поддерживаемого формата и восстановлением позиции в пределах фактической duration. Повторная неудача даёт пользователю «Повторить» и «Следующий». Бюджет recovery не сбрасывается каждым новым callback до успешного восстановления.

Не делаем неограниченных retries, не заменяем незаметно запись другой версией и не обходим region/login/protected-content ограничения. Rate limit учитывает Retry-After при наличии и не превращается в цикл запросов.

## 9. Сеть, VPN и proxy

Поддерживаем два режима: системное подключение, включая внешний VPN, либо один явно заданный пользовательский proxy. Автоматическое определение и ротация списка proxy не нужны.

MVP proxy:

- HTTP proxy с HTTPS CONNECT; без авторизации или с username/password.
- SOCKS5 без авторизации, включая локальный listener внешнего VPN/proxy client.
- SOCKS5 username/password, TLS-соединение к самому HTTPS proxy, VPN-ссылки/подписки и собственный tunnel client не входят в этот MVP.

Catalog, resolver, audio, artwork, lyrics и downloads используют согласованный режим. У Ktor/OkHttp, Media3 и дополнительных token/WebView запросов есть platform-specific configuration, поэтому наличие `OkHttpClient.proxy` не считается готовой сквозной поддержкой.

Для WebView проверяется `PROXY_OVERRIDE` и применяются соответствующие правила без direct fallback. Неподдерживаемая конфигурация даёт понятную ошибку и возможность использовать системный VPN. В Chromium отсутствует штатная SOCKS5 authentication; это причина согласованного ограничения MVP.

DNS назначения для SOCKS5 передаётся proxy, где это поддерживает выбранный transport. Не обещаем системную VPN-level изоляцию всего устройства; приложение контролирует собственные запросы и не делает скрытого direct fallback при отказе явно выбранного proxy.

Смена proxy:

1. Увеличить network generation, отменить старые операции и не принимать их поздние результаты.
2. Очистить временные источники и session-bound token state, который нельзя переносить между сетевыми контекстами.
3. Применить конфигурацию всех transport components, затем заново разрешить текущий трек с сохранением позиции и намерения play/pause.
4. Downloads переподключить через тот же режим с правилами безопасного resume.

Обычный переход Wi-Fi/mobile может изменить доступность или IP binding источника. Ошибка открытия нового диапазона проходит через ограниченный recovery, а не считается постоянной поломкой трека.

TLS-проверка origin остаётся включённой. Proxy-Authorization не передаётся origin server. Пароль хранится за platform secret-storage boundary; на Android — ciphertext с ключом Android Keystore, не plaintext в Room/settings. Credentials не используются для Google login.

## 10. Lyrics

Lookup states: not requested, loading, available, instrumental, unavailable и failed. Ошибка не записывается как отсутствие текста. Эти состояния принадлежат app model, не LRCLIB response.

При открытии lyrics или подготовке offline asset сначала ищем подходящий сохранённый результат; затем при необходимости запрашиваем YTM и LRCLIB с ограниченным временем ожидания. Не запрашиваем lyrics всей выдачи или очереди заранее.

Выбор: соответствие записи/версии → валидность timing → synced вместо plain среди подходящих результатов. Provider order сам по себе не определяет качество. Адаптеры можно заменить или переставить без изменения UI; пользовательский редактор приоритетов не нужен.

Normalizer обрабатывает plain и line-timed lyrics. Времена — в миллисекундах; строки сохраняют порядок и повторения. EndTime можно вывести из следующей строки и границы duration. Некорректные timestamps не становятся валидной синхронизацией. Word-level режим в MVP отсутствует, даже если provider добавил такие поля.

Для LRCLIB используются title/artist и известные album/duration. Его matching tolerance и наличие synced ответа не заменяют проверку identity. Для YouTube fallback с intro/outro или иной структурой lyrics показываются как plain, если пригодность синхронизации не подтверждена.

Highlight следует фактической позиции player, включая seek. Качественный сохранённый результат не перезаписывается более слабым. Сохранённые lyrics связаны с Track и evidence playback version, а не только строками названия.

## 11. Artwork

Порядок: proper YTM release artwork → artwork надёжно связанного альбома → video thumbnail → placeholder. Наличие квадратного изображения не доказывает, что это release cover.

Храним происхождение и kind; не приклеиваем к одному релизу обложку другого из-за слабого matching. Для fallback catalog enrichment имеет те же требования к identity, что и title/album.

Обычный image cache ограничен размером и может очищаться. Artwork, сохранённый для offline asset, имеет постоянную ссылку и не вытесняется как временный cache. Ошибка изображения даёт placeholder и не останавливает playback/download аудио.

MusicBrainz/CAA не подключаем: пока нет измерений, показывающих их необходимость для базового плеера.

## 12. Явные скачивания и offline playback

Выбран управляемый приложением постоянный app-specific каталог. Это не временный cache и не shared Music folder. После uninstall/clear app data загрузки теряются; это объясняется пользователю в управлении хранилищем. Экспорт/теги для сторонних плееров откладываются.

В MVP одна одновременная загрузка, одно стандартное качество и отсутствие перекодирования. Выбирается поддерживаемый целиком сохраняемый audio representation. Если offline transport выбранного source ещё не поддержан, возвращается явная ошибка; streaming success не выдаётся за offline availability.

Состояния: not downloaded, queued, downloading, paused, available, failed. Cancel удаляет задачу и её незавершённые данные; delete удаляет готовый offline asset. Команды и progress нормализованы, без raw Media3 objects в UI. Percent показывается только при известном общем размере.

Android DownloadService/DownloadManager управляет фоновыми заданиями с уведомлением и ограничениями ОС. Download cache имеет NoOpCacheEvictor и отдельный постоянный каталог. Не обещаем неограниченную работу после force-stop, отсутствия сети или системного time budget; состояние сохраняется для продолжения.

Persistent DownloadRequest/DownloadIndex содержат stable app URI/identity и representation descriptor. Сетевой адрес разрешается just-in-time. Требование «не сохранять подписанные CDN URLs» распространяется и на служебные cache metadata/redirect state: adapter не должен позволять библиотеке незаметно закрепить их в persistent index. Cache keys основаны на asset/source/representation identity, а не query-параметрах URL.

Resume допустим только при подтверждении той же byte representation и корректного range response. Надёжный validator/версия ресурса, позиция Content-Range и общий размер должны быть совместимы. Если подтвердить identity невозможно, изменился format/resource либо на Range пришёл полный ответ, частичные данные не дописываются: загрузка начинается заново. После expiry сначала fresh resolve; другой candidate не продолжает старый partial asset.

Available выставляется после полного завершения выбранного transport и проверки сохранённых данных. При известном expected length требуется полный размер; дополнительно проверяются читаемость media и согласованность duration с выбранным source. Если нет достаточных признаков полноты, частичный/неопределённый результат нельзя объявлять готовым. Верификация доставки полного трека обязательна на live test set.

Сохраняем metadata snapshot, имеющееся artwork и подходящие lyrics. Отсутствие lyrics или ошибка artwork не мешают получить playable offline asset; сопутствующие ресурсы имеют собственную availability. Сохранённые ссылки на Track не меняются при обновлении временной сети.

При готовом offline asset playback предпочитает локальный источник без resolver/account/network validation. В airplane/offline режиме data source не имеет сетевого upstream для восполнения cache holes. Не хватает данных — понятная ошибка локального ресурса, а не скрытая попытка streaming.

Нехватка места останавливает загрузку с причиной; существующие загрузки автоматически не удаляются. Пользователь видит занятое место, удаляет отдельные assets или все загрузки. Favorite/Track/history не удаляются вместе с аудио. Общие ресурсы очищаются только когда на них больше нет ссылок.

## 13. Persistent data и cache policy

Room хранит Track records, source identity mappings/candidates, metadata provenance, favorite membership, recent playback timestamps, queue snapshot, сохранённые lyrics, ordinary settings и связи на offline resources. Минимальная schema следует этим данным; не строим распределённую canonical music database.

Artists/album refs сохраняют provider relationships, не требуют готовых экранов. Уникальность external IDs и порядок artists/queue entries имеют явные ограничения. Provider DTO не сериализуются как единственный источник app schema; расширение unknown provider fields не ломает repository.

Audio/artwork — файлы. Platform DownloadIndex — владелец transfer state; app repository хранит связь на asset и не ведёт независимый конкурирующий download state machine. При запуске выполняется reconciliation индекса и файлов; отсутствующий asset теряет offline availability, Track остаётся.

Виды хранения:

- Favorites, queue, downloaded metadata/resources и lyrics сохраняются до явного удаления соответствующих данных.
- Recent history для MVP — последние 100 различных Track с lastPlayedAt, обновляемым после фактического начала воспроизведения. Это локальная функция, не telemetry.
- Не сохранённые пользователем metadata/search cache ограничены: до 7 дней и до 1000 Track; cached search results до 15 минут. Library/offline references защищают связанные записи от eviction.
- Lyrics unavailable можно cache на 24 часа; network/provider failures не становятся negative cache. Сохранённые для offline lyrics не истекают только из-за TTL.
- Stream cache — runtime only по правилам раздела 8. Обычный image/playback cache не заменяет явные downloads.

Миграции базы сохраняют library/queue/offline mappings; destructive fallback запрещён. Отсутствующий migration path не вызывает молчаливую очистку пользовательских данных. Transaction boundaries защищают identity mapping, queue snapshot и library updates.

Скачанные media и proxy credentials исключаются из системного backup/переноса, где Android позволяет приложению управлять этим. Restore не должен делать несуществующий asset доступным или использовать ciphertext без соответствующего ключа. Собственный cloud backup/sync не реализуется.

## 14. Player state и queue

Нормализованное состояние включает idle, resolving, buffering, playing, paused, ended и failed, отдельно от play intent, current QueueEntry, actual position/duration и выбранного source. UI не выводит playing из завершения resolve или собственного таймера.

MediaSessionService/player — источник истины runtime. User commands, callbacks и async results имеют playback generation: поздний resolve/lyrics/result предыдущего трека не переключает текущий. Seek ограничивается реальной duration, если она известна.

Queue поддерживает duplicate Track через отдельные QueueEntry IDs, добавление, удаление, выбор элемента, next и previous. Repeat/shuffle и рекомендации не входят в этот MVP.

- Previous при position > 3 секунд возвращает к началу текущего элемента; иначе выбирает предыдущий. На первом элементе возвращает к его началу.
- Next выбирает следующий элемент. На последнем останавливает playback.
- Завершение трека автоматически выбирает следующий; в конце очереди останавливается.
- Удаление текущего элемента выбирает следующий, сохраняя play/pause intent; если следующего нет, playback останавливается. Остальные элементы не меняют identity при переиндексации.
- Ошибка текущего трека не запускает цикл автоматического пропуска всей очереди: доступны retry/next.

При пересоздании UI подключаемся к работающему service. После полного restart процесса восстанавливаем queue/current position в paused состоянии; автоматического старта музыки нет. Позиция сохраняется при существенных переходах и примерно раз в 5 секунд во время playback, не на каждом UI frame. Android audio focus, noisy/headset disconnect и media buttons обслуживаются player/session integration; отключение наушников не должно неожиданно продолжить музыку через динамик.

## 15. Ошибки и пользовательское поведение

| Ситуация | Поведение |
| --- | --- |
| Search работает, resolve не работает | Metadata остаются; playback error и retry, без выдачи неуспеха за пустой catalog |
| Нет сети / proxy connection failure | Понятная причина; ready offline assets доступны; direct fallback при explicit proxy запрещён |
| Proxy 407 / неверный password | Предложить исправить настройки; не повторять авторизацию бесконечно |
| Region restriction / removed content / login required | Отдельная unavailable причина; без обхода account/paid/DRM ограничений |
| Token/cipher/parser/provider change | Resolver/provider error со stage/version для диагностики; возможное обновление app integration |
| CDN expiry / исчез format | Одна fresh resolve recovery для того же candidate с восстановлением позиции |
| Unsupported transport | Явный результат, не передача неподдерживаемых данных в player |
| Неверная/неоднозначная fallback version | Partial metadata или выбор пользователя, без скрытого присоединения другого релиза |
| Lyrics failed/unavailable | Независимое состояние lyrics; аудио продолжает играть |
| Artwork failure | Placeholder; playback продолжается |
| Нет места / partial download / missing offline asset | Не выставлять available; сохранить Track, дать retry/delete/storage action |

Из UI можно понять действие, не читать stacktrace. Детальные сведения локальны и обезличены. Нельзя оборачивать все перечисленные причины одной строкой «что-то пошло не так».

## 16. Устойчивость upstream, безопасность и лицензии

InnerTubeX version и adapter fixtures закреплены. Изменения upstream локализуются в integration layer. Обычное обновление dependency не должно менять persisted Track identity. В MVP нет собственного hot-update engine для исполняемого кода и второго extractor «на всякий случай».

YouTube player scripts, cipher/PO-token mechanisms и remote configuration требуют работы поддерживаемой библиотеки и platform-specific token components. InnerTubeX не гарантирует готовое token minting только созданием client: host contract и сетевой маршрут этих компонентов — часть первого milestone. Не добавляем Google login/cookies, authenticated Premium discovery или paid access, чтобы скрыть неудачу anonymous public-content path.

Structured logging содержит operation ID, stage, provider/resolver version, source ID при необходимости, transport kind, error category и duration операции. По умолчанию не пишет raw search queries, lyrics, полные provider bodies, signed URLs, headers, cookies, tokens, proxy credentials или session objects. Нет аналитического backend и сбора чужих credentials. Логи ограничены локальным размером; перед экспортом выполняется redaction, автоматической отправки нет.

Предлагаемая лицензия приложения — GPL-3.0-only с учётом согласованной GPLv3 InnerTubeX dependency и желания публиковать исходники. Финальное одобрение этого документа включает этот выбор; LICENSE будет создан только при разрешённой реализации. Перед распределением binaries проверяются licenses реально включённых native/transitive components. Open source не предоставляет прав на музыку и не заменяет условия источников.

Техническая работоспособность рассматривается отдельно от YouTube Terms. Terms ограничивают автоматизированный доступ и использование/копирование контента вне разрешённых механизмов; public availability и наличие downloader API не означают разрешения на download или распространение. Добавление явных офлайн-загрузок — отдельное согласованное расширение продукта с этим риском. Никаких DRM bypass, protected-content defeat, paid-service auth bypass и встроенного каталога чужих аудиофайлов.

## 17. Тестирование и критерии доказательств

TDD применяется к normalization, matching, provider fallback, expiration/recovery budgets, lyrics normalization/selection, queue rules, network-generation cancellation и offline state/resume. Tests проверяют наблюдаемое поведение, а не повторяют структуру реализации.

Основной suite — offline unit tests и разрешённые sanitized fixtures через provider boundaries. Fixture не содержит secrets/signed URLs и не требует хранения copyrighted lyrics/audio в repository; для parser cases подходят синтетические данные. Live YouTube не вызывается обычным test run.

Persistence tests используют реальную SQLite/Room schema: migrations, external identity uniqueness, duplicate queue entries, restart snapshots и reconciliation missing assets. Download checks включают отказ Range, смену representation/validator, expiry, insufficient space, process restart и запрет available для partial content. Отдельная проверка persisted DownloadIndex/cache metadata ищет signed URLs.

Явная manual/live процедура в ходе реализации:

1. Зафиксировать app/dependency versions, устройство/Android, режим сети/VPN/proxy, query, выбранную Track/source/version и ожидаемую запись без secrets.
2. Проверить metadata/artwork и прослушать полную запись, включая конец, а не только первые секунды или HEAD/HTTP 206.
3. Проверить pause/resume, forward/backward seek, duration/progress, background/lock screen/media buttons, lyrics и bounded recovery.
4. Проверить системный VPN, HTTP proxy без/с авторизацией и SOCKS5 без авторизации. Нерабочая конфигурация не должна уходить напрямую.
5. Скачать, перезапустить приложение и включить airplane mode: play/seek/next по скачанным трекам работают без resolver/network; partial/missing assets корректно недоступны.

Представительный набор:

| Случай | Начальный пример / правило отбора |
| --- | --- |
| Mainstream international | Linkin Park — Numb |
| Русскоязычная запись | Кино — Группа крови |
| Старый релиз | Queen — Bohemian Rhapsody |
| Недавний релиз | При live проверке выбрать официальный релиз последних 12 месяцев; записать точную версию и source ID |
| Proper YTM release | Daft Punk — Get Lucky; наличие нужного release подтвердить фактической выдачей |
| Обычный YouTube fallback | Найти музыкальную запись, для которой текущий YTM search не даёт подходящего результата, а обычный YouTube даёт; зафиксировать запрос и evidence, не предполагать этого по памяти |
| Synced lyrics | Numb и Группа крови как начальные кандидаты с измеренными LRCLIB synced responses; timing проверить на реальном audio |
| Plain-only / unavailable lyrics | Отдельные реальные случаи подбираются по ответам providers; состояния также проверяются synthetic fixtures |
| Instrumental | Aphex Twin — Avril 14th; provider instrumental state не предполагать заранее |
| Неоднозначное название | Hello: отличать Adele от Lionel Richie |
| Много live/cover/remix | Radiohead — Creep, отдельно исходная версия и явно запрошенная live/cover |

Это категории проверки, не обещание доступности перечисленных source IDs навсегда. Итоговый manifest фиксируется по живой выдаче. Mocks не доказывают реальный playback; успешный готовый сторонний client не доказывает наш pipeline.

## 18. Milestones и Product Design

Это последовательность результатов дизайна, не детальный implementation plan.

| Milestone | Definition of done |
| --- | --- |
| 1 — музыкальная цепочка | Android технический UI через normalized models; YTM search/metadata/artwork; правильная полная запись из source; pause/resume/seek/progress; lyrics при наличии. Dependency packaging и выбранный transport действительно проверены на устройстве |
| 2 — устойчивость и локальное состояние | Queue/favorites/recent history переживают restart; background/media controls работают; HTTP/SOCKS5 modes сквозные; stale async results не меняют новый Track; expiry/failure recovery ограничен и диагностируем |
| 3 — offline | Явные downloads/pause/resume/cancel/delete; completeness и безопасное возобновление; управление местом; ready assets играют и перематываются после restart в airplane mode с доступными metadata/artwork/lyrics |
| 4 — Product Design | После рабочей существенной цепочки явно подключён Product Design; исследованы несколько действительно разных направлений, выбрано одно и согласованы основные surfaces |
| 5 — production UI и стабилизация | Реализован выбранный дизайн; проверены основные сценарии, migrations, network/offline failures, full playback и packaging Android APK |

Внутри первых milestones приоритет у playback/identity. Если основная интеграция не обеспечивает полный трек, нельзя продолжать визуальный этап и считать техническую цель выполненной. Смена существенной интеграции/transport scope обсуждается и отражается в spec, а не маскируется mock data.

Product Design не вызывается сейчас. Технический UI milestones 1–3 намеренно грубый и не становится визуальным образцом production. Будущие направления должны различаться композицией и взаимодействиями; artwork, Now Playing, lyrics, typography и queue оцениваются после проверки основы, без заранее выбранного Spotify clone.

Windows/Linux идут отдельным последующим платформенным milestone: desktop PlayerEngine, packaging и media controls с проверкой reuse общего ядра. MediaMP/mpv — изученный кандидат, не добавляемая сейчас dependency; его pre-1.0 API и native distribution требуют отдельного выбора. Web остаётся отдельной feasibility задачей, не скрытым обязательством Android MVP.

## 19. Статус проекта и переход к реализации

После одобрения implementation plan начата задача 1: build harness, проверка dependency matrix и независимые domain models. Готовность приложения и реальная музыкальная цепочка пока не подтверждены. Python-пакеты предварительного probe установлены только в `/tmp`, не являются зависимостями проекта.

Публичный репозиторий проекта: [resassance/Molpy](https://github.com/resassance/Molpy). Первая техническая Android-сборка распространяется как prerelease для проверки на устройстве. APK signing secrets и source credentials не являются частью проектных артефактов.

Архитектура, письменная спецификация и implementation plan одобрены. Технический slice задач 1–7 реализован и прошёл автоматические проверки; device/live gate milestone 1 остаётся открытым. Дальнейшие результаты проверяются по milestone gates; Product Design остаётся после инженерной проверки основы.

## 20. Основные первичные источники исследования

Документация сверялась напрямую и через Context7. Repository activity и latest releases проверялись через GitHub API на 2026-10-17; это не доказательство live reliability.

- [YouTube Music availability](https://support.google.com/youtubemusic/answer/6313540?hl=ru), [YouTube Terms](https://www.youtube.com/static?template=terms).
- [InnerTubeX](https://github.com/MetrolistGroup/innertubex), [v0.10.0 release](https://github.com/MetrolistGroup/innertubex/releases/tag/v0.10.0), [tagged build versions](https://github.com/MetrolistGroup/innertubex/blob/v0.10.0/gradle/libs.versions.toml), [token provider contract](https://github.com/MetrolistGroup/innertubex/blob/v0.10.0/src/commonMain/kotlin/com/metrolist/innertubex/extraction/TokenProvider.kt).
- [ytmusicapi search](https://ytmusicapi.readthedocs.io/en/stable/reference/search.html), [browsing/lyrics](https://ytmusicapi.readthedocs.io/en/stable/reference/browsing.html), [unauthenticated usage](https://ytmusicapi.readthedocs.io/en/stable/usage.html): полезный поддерживаемый metadata reference, но Python packaging не выбран для Android.
- [yt-dlp](https://github.com/yt-dlp/yt-dlp), [YouTube restrictions](https://github.com/yt-dlp/yt-dlp/wiki/Extractors), [EJS](https://github.com/yt-dlp/yt-dlp/wiki/EJS), [PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide): активная альтернатива; desktop subprocess удобен, Android добавляет Python/JS runtime и update/ABI вопросы. Не ставится вторым extractor в MVP.
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor): поддерживаемая Java/GPLv3 альтернатива и пример separate extraction; типизированный video result сам по себе не заменяет наш music metadata model.
- [Metrolist](https://github.com/MetrolistGroup/Metrolist), [extraction boundary](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt), [v13.7.0](https://github.com/MetrolistGroup/Metrolist/releases/tag/v13.7.0): примеры metadata/extraction разделения, expiry/headers/range capability, локальных данных и lyrics adapters; не копируем весь app и его многочисленные features.
- [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [core modules](https://github.com/maxrave-dev/core): примеры разделения cross-platform UI/domain/platform playback; наличие desktop исходников не доказывает готовность нашего desktop release.
- [zemer-cipher](https://github.com/ZemerTeam/zemer-cipher): узкая Android cipher/token библиотека, не полный catalog/player; пример того, что decipher success не доказывает доставку полного аудио.
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback), [network stacks](https://developer.android.com/media/media3/exoplayer/network-stacks), [customization](https://developer.android.com/media/media3/exoplayer/customization), [downloads](https://developer.android.com/media/media3/exoplayer/downloading-media).
- [OkHttp proxy](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/proxy.html), [proxyAuthenticator](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/proxy-authenticator.html), [WebView ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController), [Chromium SOCKS5 limitation](https://chromium.googlesource.com/chromium/src/+/HEAD/net/docs/proxy.md#SOCKSv5-proxy-scheme).
- [LRCLIB API](https://lrclib.net/docs): title/artist/album/duration matching, plain/synced/instrumental responses. Два live response проверены отдельно от выбора provider priority.
- [Room KMP](https://developer.android.com/kotlin/multiplatform/room), [Room stable releases](https://developer.android.com/jetpack/androidx/releases/room), [app-specific storage](https://developer.android.com/training/data-storage/app-specific), [shared media](https://developer.android.com/training/data-storage/shared/media), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [Auto Backup](https://developer.android.com/identity/data/autobackup).
- [KMP source sets](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html), [platform stability](https://kotlinlang.org/docs/multiplatform/supported-platforms.html), [Flutter platform channels](https://docs.flutter.dev/platform-integration/platform-channels), [just_audio](https://pub.dev/packages/just_audio), [audio_service](https://pub.dev/packages/audio_service), [Tauri mobile plugins](https://v2.tauri.app/develop/plugins/develop-mobile/), [MediaMP](https://github.com/open-ani/mediamp), [browser CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS).
