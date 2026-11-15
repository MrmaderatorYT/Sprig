# Sprig — roadmap

Оновлено: 2026-11-15. Базова версія: 0.3.0 preview. Пакет: `com.ccs.sprig`.

Мета — самостійна Android-платформа для інспекції й авторизованого тестування вебзастосунків із функціональністю, порівнюваною з Burp Suite Professional. Це орієнтир розвитку, а не заява про поточний паритет чи сумісність із закритими API PortSwigger.

## Правила плану

- `[x]` означає реалізовану можливість; перевірка на пристрої зазначається окремо.
- `[ ]` означає заплановане або не завершене. Пункт закривається лише разом із доказами перевірки.
- P0 — блокер стабільності/випуску; P1 — основні можливості; P2 — розширення; P3 — дослідження.
- Версії нижче — пропоновані етапи, без обіцянок календарних дат. Змінювати порядок можна за результатами вимірювань.
- Java для коду продукту, Groovy для Gradle; Kotlin і Compose не використовувати. NDK допустимий лише після обґрунтування вимірюваннями.

## Поточна база

- [x] Назва Sprig, namespace/applicationId `com.ccs.sprig`, нативний Material Design 3.
- [x] IPv4 VPN TCP/UDP, вибір застосунків, HTTPS MITM із локальним CA та перевіркою upstream TLS.
- [x] HTTP/1, HTTP/2, HTTP/3, Intercept, історія, Repeater, HAR, Decoder, Comparer.
- [x] WebSocket ws/wss через HTTP/1 Upgrade: text/binary, fragmentation, interception і replay.
- [x] Intruder: чотири режими, lazy payloads, processors, pacing, pause/resume/cancel, grep/extract, експорт.
- [x] Scanner: 16 категорій, query/header/form/JSON-string insertion, POST opt-in, evidence, звіти.
- [x] Власні Android OAST-клієнт і Java Collaborator server.
- [x] Обмежені черги/пам’ять, scoped cancellation, bounded DNS, R8 release.
- [x] Зафіксований результат 0.3.0: 164 тести, lint 0 errors / 56 warnings.
- [ ] Підтвердження VPN, release runtime та продуктивності на фізичних Android-пристроях.

Детальні факти й обмеження: [README](README.md), [validation](docs/VALIDATION.md), [архітектура](docs/ARCHITECTURE.md).

## Етап 0.4 — стабільність і вимірювана продуктивність · P0

**Статус: у роботі.** Реалізація почата 2026-11-15; [журнал і докази](docs/STAGE_0_4.md). Базова версія/package не змінені. Підключений Pixel 7a API37 додано окремо; він не підміняє заплановану API/OEM matrix.

### Android і життєвий цикл

- [ ] Матриця Android API 26, 29, 33 і 36; щонайменше два OEM та пристрій із малим heap.
- [ ] Наскрізні HTTP/HTTPS/H2/H3/WS fixtures через справжній VpnService.
  - [x] Pixel7a/API37: HTTP/IPv4, 20 GET/binary POST +4 після Stop/Restart; незалежний fixture-client і перевірка capture bytes. [Device artifact](dist/stage04/pixel7a-vpn-latest.json).
  - [x] Pixel native direct-loopback: HTTP1, TLS/H2, QUIC/H3+wrong-hostname rejection, WS; це не їх VPN-path acceptance.
  - [ ] VPN HTTPS/H2/H3/WS та інші API/OEM.
- [ ] Release APK на пристрої: R8, QPACK resources, crypto providers, CA export/install.
  - [x] R8 build/signature/alignment/resources, Pixel cold launch і CA fingerprint loading. [Smoke](dist/stage04/pixel7a-r8-smoke.json).
  - [ ] Повний release VPN/protocol runtime і системне встановлення CA.
- [ ] Start/Stop/revoke, process death, background/foreground, rotation, notifications, інший VPN.
  - [x] Bounded/coalesced lifecycle queue, stale START/error ownership guards і шість regression fixtures; bounded UI queues з rejection feedback.
  - [x] Pixel короткий Start/Stop/Restart/Stop із повторною перевіркою трафіку; п’ять Activity launch/recreate.
  - [ ] Повна device matrix усіх lifecycle сценаріїв.
- [ ] Wi-Fi/mobile handover, offline/reconnect, captive portal, помилки DNS і TLS.
- [ ] Тривалий soak із паралельними capture, WebSocket та Intruder/Scanner.
- [ ] Перевірка великих шрифтів, клавіатури, landscape, TalkBack і планшетів.

### Продуктивність

- [x] Відтворюваний Java benchmark harness: host runtime/build/config/fixture, bounded mixed load, allocation comparison; Android runner і окремий fixture-app. Команди й межі: [BENCHMARKS](docs/BENCHMARKS.md), [Android](docs/ANDROID_QUALITY.md).
- [ ] Виміряти throughput, p50/p95 latency, CPU, RSS/heap, GC, кількість потоків/FD, UI jank і батарею.
  - [x] Host paced mixed60s та allocation comparison; Pixel короткі latency/throughput і CPU/RSS/PSS/heap/GC/thread/FD snapshots. [Значення та межі](docs/STAGE_0_4.md).
  - [ ] Репрезентативний Android steady-state, UI jank, radio/battery без USB charging.
- [ ] Зафіксувати бюджети для малих/середніх/великих пристроїв на підставі baseline.
- [ ] Глобальний allocator/reservation для байтів між history, bodies, WS та інструментами.
  - [x] Спільний retained-memory budget: HTTP history, WS metadata/history/queues/intercept, Intruder config/results; contention/admission/cleanup tests. [Облік і прогалини](docs/MEMORY_BUDGET.md).
  - [ ] Transient protocol bodies/frame assembly, native/TLS buffers та зовнішні snapshots; виміряні device budgets.
- [ ] Перевірити backpressure й cleanup після timeout/cancel у всіх протоколах.
  - [x] JVM fixtures: shared admission/memory pressure, H2/H3 scoped cancellation/reuse, WS queue/hold cleanup, lifecycle generation races.
  - [ ] Усі device/protocol overload paths і тривалий mixed soak.
- [ ] Прибрати зайві копії/allocations лише після профілювання; додати regression benchmarks.
  - [x] Owned capture path + allocation benchmark: у host JVM 307.57 проти 65874.92 allocated bytes/record для двох тіл по32KiB (≈99.53% менше); це не вимірювання Android. [Артефакт](dist/stage04/store-allocation.json).
  - [ ] Повне профілювання Android transport/UI і наступні виміряні оптимізації.
- [ ] Переглянути 56 lint warnings, локалізувати рядки, пояснити залишені попередження.
  - [x] Перенести 45 dynamic-text місць у English/Ukrainian resources/plurals; localized navigation/WebSocket, виправлені plural candidates. [Review](docs/ANDROID_QUALITY.md).
  - [ ] Локалізувати всі решта legacy dialogs/core descriptions і завершити remaining lint improvements.

**Критерій завершення:** опубліковані результати матриці й профілювання; немає відомих критичних втрат трафіку, ANR чи необмеженого росту ресурсів. Числові performance gates визначені за вимірюваннями, а не вигадані наперед.

## Етап 0.5 — мережевий рушій · P0/P1

- [ ] IPv6 TCP/UDP, IPv6 DNS, dual stack, IPv6-only/NAT64; явна політика unsupported packets.
- [ ] IPv4 fragmentation/reassembly із byte/time/fragment limits.
- [ ] Connection pooling із розділенням origin, TLS identity, route, credentials і protocol settings.
- [ ] H2 multiplexing між запитами інструментів, fairness, GOAWAY draining і stream-local cancellation.
- [ ] TLS/QUIC resumption; 0-RTT лише окремим opt-in з контролем повторного виконання.
- [ ] Потокові bodies/spooling, streaming export, cancellation і backpressure без повного завантаження в heap.
- [ ] SSE та довгі streaming responses з окремими idle/absolute deadlines.
- [ ] Brotli/gzip/deflate inspection із лімітами розпакування та збереженням оригіналу.
- [ ] Client TLS certificates із захищеним керуванням ключами.
- [ ] Тести certificate chains, hostname, ALPN, нестандартних портів і DNS failures.
- [ ] Диференційні/interoperability тести з незалежними H1/H2/H3 реалізаціями.
- [ ] Документована поведінка ECH, pinning, unsupported TLS та passthrough; не заявляти автоматичного обходу.

**Критерій завершення:** незалежні клієнти/сервери проходять protocol matrix; повторне використання з’єднань не змішує сесії, маршрути та сертифікати.

## Етап 0.6 — збереження проєктів і щоденна робота · P1

- [ ] Проєкти: створення, відкриття, перейменування, архівування, видалення.
- [ ] Версійована схема локального сховища та перевірені міграції.
- [ ] Дискові квоти, retention, crash recovery, індекси, пагінація та фоновий пошук.
- [ ] Політика шифрування секретів із Android Keystore, explicit export/import і backup policy.
- [ ] Відновлення drafts/results після process death; network jobs не відновлювати автоматично без визначеної політики.
- [ ] Site map: host/path/method, параметри, content types, findings, scope include/exclude.
- [ ] Нотатки, tags, bookmarks, пошук у request/response, фільтри, збережені представлення.
- [ ] Repeater tabs, історія змін, request groups, comparison, binary/hex editor.
- [ ] Match/replace rules із preview, порядком виконання, scope і можливістю вимкнення.
- [ ] Імпорт HAR/cURL, експорт відтворюваних запитів із redaction options.

**Критерій завершення:** великий проєкт відкривається без завантаження всієї історії в RAM; відновлення/міграції/видалення перевірені fixtures.

## Етап 0.7 — WebSocket і Intruder · P1

### WebSocket

- [ ] `permessage-deflate`: negotiation, context takeover, fragmented compression, inflation limits.
- [ ] RFC 8441 extended CONNECT через H2 і RFC 9220 через H3.
- [ ] Message search/filter/export, напрямки, timestamps, annotations і connection details.
- [ ] Окремі правила interception/match/replace для сесій і напрямків.
- [ ] Збережені ручні клієнти й керований reconnect без автоматичного повтору stateful messages.
- [ ] Великі повідомлення через spooling/streaming зі збереженням коректної UTF-8/framing семантики.

### Intruder

- [ ] Генератори: combinatorial strings, character ranges, dates, UUID, permutations і file-backed lists.
- [ ] Recursive/response-derived payloads із лімітами глибини та кількості.
- [ ] Умовні pipelines, payload deduplication, preview та per-position encoding.
- [ ] Resource pools, adaptive throttling/backoff, правила для 429/Retry-After.
- [ ] Session rules, cookie jar, token extraction, macro hooks і оновлення CSRF.
- [ ] Multiple configurations, persisted results, pause/checkpoint/resume без дублювання запитів.
- [ ] Розширений grep/extract, response comparison, clustering і result annotations.
- [ ] Планування запусків у межах foreground lifecycle; явні battery/network policies.
- [ ] Окремий експертний режим raw/malformed requests з чіткою сумісністю протоколів і fixture tests.

**Критерій завершення:** послідовності payloads детерміновані, відновлення не дублює виконане, session rules не переносять секрети між origin.

## Етап 0.8 — Scanner і авторизовані сесії · P1

### Crawl та авторизація

- [ ] Ізольований browser-assisted crawl із Java-керуванням, контрольованим JS runtime і origin boundaries.
- [ ] SPA navigation, forms, DOM links, API discovery й endpoint deduplication.
- [ ] Cookie jar, recorded login workflows, macros, session health і reauthentication.
- [ ] OpenAPI імпорт; XML, multipart, GraphQL і typed JSON insertion points.
- [ ] Crawl/audit окремими фазами; scan profiles, exclusions, checkpoints і incremental scans.

### Перевірки та якість результатів

- [ ] Розширювана Java check API із metadata, budgets, applicability й evidence contract.
- [ ] Browser-confirmed reflected/stored/DOM XSS у контрольованих тестових сценаріях.
- [ ] Розширення injection/auth/access-control/request-handling checks із незалежними позитивними й негативними fixtures.
- [ ] Розділити passive, low-impact active та state-changing checks; запускати відповідно до явного профілю.
- [ ] Підтвердження blind findings через власний OAST, а не за HTTP-відповіддю на payload.
- [ ] Baseline/control/retest, стабільність контенту, false-positive suppression із поясненнями.
- [ ] Дедуплікація findings між сторінками/запусками, issue lifecycle і ручне підтвердження.
- [ ] CWE/OWASP references, remediation, redacted reproduction steps, report templates.
- [ ] Зафіксований fixture corpus і метрики precision/recall за категоріями; не зводити якість до числа checks.

**Критерій завершення:** заявлені результати відтворюються з evidence; евристики не видаються за доведені exploit paths; coverage і false positives виміряні.

## Етап 0.9 — Collaborator і розширення · P1/P2

- [ ] Документоване VPS deployment, DNS delegation/glue, TLS renewal, service supervision і upgrades.
- [ ] End-to-end HTTP/HTTPS/DNS UDP/TCP/SMTP/STARTTLS/SMTPS перевірка на власному домені.
- [ ] Автоматична прив’язка OAST token → scan/issue/request/insertion point.
- [ ] Polling із backoff, expiration/revoke, deduplication, offline handling і контрольовані сповіщення.
- [ ] Server persistence, quotas, rate limits, abuse controls, redaction, cleanup та observability.
- [ ] Версійований API, client/server compatibility tests і migration policy.
- [ ] Java extension API для checks, payload sources/processors, editors та exporters.
- [ ] Модель довіри й дозволів extensions, resource quotas, crash isolation та compatibility versioning.
- [ ] Визначити межі сумісності з Burp extensions; не обіцяти виконання BApps без реалізованого compatibility layer.

**Критерій завершення:** реальні callback-події правильно корелюються, сесії ізольовані, extension failures не руйнують capture.

## Етап 1.0 — стабільний випуск · P0

- [ ] Формальна capability matrix Sprig ↔ цільові workflows Burp із позначенням supported/partial/unsupported.
- [ ] Закриті критичні дефекти; відтворюваний release checklist і підтримувана Android matrix.
- [ ] Власний production signing key поза репозиторієм, перевірка оновлення зі старої версії та rollback/data policy.
- [ ] CI: JVM/UI checks, lint, release build, dependency/security/license review, artifact hashes.
- [ ] SBOM, повні third-party notices, актуальні LGPL sources/relinking instructions.
- [ ] Перевірка permissions, backup/export, secret handling, logs і мережевої ізоляції.
- [ ] Українська/англійська локалізація, accessibility, onboarding і зрозумілі повідомлення unsupported features.
- [ ] Керівництва користувача, приклади локальних fixtures, troubleshooting і політика підтримки.
- [ ] Канал розповсюдження та оновлень, release notes, integrity verification і crash reporting лише за explicit opt-in.

**Критерій завершення:** стабільність і заявлені workflows підтверджені доказами. Номер 1.0 сам по собі не означає повного паритету з Burp.

## Дослідження після основних етапів · P2/P3

- [ ] WebTransport/gRPC/protobuf-aware editors, browser automation і додаткові API schemas.
- [ ] Desktop companion для великих проєктів без обов’язкової хмарної залежності.
- [ ] NDK лише для підтверджених CPU bottlenecks; спершу benchmark, потім prototype і порівняння з Java.
- [ ] За потреби окреме рішення щодо привілейованих/root workflows; не робити root обов’язковим для основного продукту.

## Залежності та порядок

`0.4 вимірювання → 0.5 transport → 0.6 storage → 0.7 advanced tools → 0.8 browser/session Scanner → 0.9 OAST/extensions → 1.0 release`.

Storage та session management потрібні для довгих/відновлюваних запусків. Browser crawl залежить від transport isolation і session rules. OAST correlation можна робити до повного browser Scanner. Нові transport features не повинні обходити admission, cancellation, scope чи TLS validation.

## Definition of done для кожного пункту

1. Реалізований і доступний користувачу сценарій, без placeholders чи прихованого fallback.
2. Перевірені success/error/cancel/overload paths і відповідні resource bounds.
3. Для мережевої зміни — локальні protocol fixtures та незалежний interop, де це потрібно.
4. Для Android behavior/performance — окремі device evidence; JVM не підміняє телефон.
5. Оновлені документація, відомі обмеження, roadmap checkbox і release notes.
6. Пройдені відповідні checks; production artifacts мають перевірений підпис і hashes.

## Ризики й відкриті рішення

| Ризик / рішення | Як вирішувати |
| --- | --- |
| Обсяг Burp значно більший за мобільний preview | Підтримувати capability matrix і перевіряти workflows поетапно |
| OEM crypto/VPN відмінності | Device matrix, capability preflight, явні помилки |
| Heap/батарея/радіо | Вимірювані budgets, fairness, spooling, профілювання |
| Секрети в історії та звітах | Keystore, retention, redaction, explicit exports |
| Active checks змінюють стан | Явні profiles/opt-in, fixture coverage, точний scope |
| LGPL та native dependencies | Provenance, sources/notices/relinking, review перед новою залежністю |
| Persisted jobs після process death | Визначити checkpoint/idempotency policy до автоматичного resume |
| Browser runtime та extensions | Окремо оцінити ізоляцію, footprint, API і lifecycle |

План переглядати після кожного milestone. Не закривати checkbox на підставі лише компіляції чи існування класу.
