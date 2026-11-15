# Sprig — Android web security workspace

**Sprig 0.1.1 Preview** (`versionCode=2`), пакет **`com.ccs.sprig`**. Увесь код застосунку, протокольних модулів, Scanner і серверної частини написаний на **Java**. Gradle — Groovy; немає Kotlin compiler plugin, Compose чи NDK-модуля. Інтерфейс — нативний Material Design 3.

Це самостійний інструмент для інспекції й тестування, а не ліцензійна копія продукту PortSwigger. HTTP/2, HTTP/3, WebSocket, Intruder, Scanner і Collaborator реалізовані; повна функціональна паритетність із Burp Suite Professional ще не заявляється.

## Реалізовано

| Можливість | Реалізація |
| --- | --- |
| VPN capture | Локальний `VpnService`, IPv4 TCP/UDP, вибір застосунків, системна згода й сповіщення |
| HTTPS MITM | CA на інсталяцію, SNI, перевірка ланцюжка та hostname справжнього сервера |
| HTTP/1 | HTTP/1.0/1.1, CONNECT, коректний framing і chunked, редагування та повтор |
| HTTP/2 | TLS ALPN на Android 10+/API 29+, h2c prior knowledge, multiplexing, HPACK/Huffman, flow control, RESET/GOAWAY |
| HTTP/3 | Справжній QUIC v1/v2 + TLS 1.3 + QPACK, UDP MITM, збереження IP/порту призначення, окремий Repeater client |
| Intercept | Forward / Drop / редагування HTTP/1, HTTP/2 і HTTP/3 через спільний шлях обробки |
| Repeater | AUTO / HTTP/1.1 / HTTP/2 / HTTP/3; явний протокол не маскується fallback-ом |
| WebSocket | ws/wss MITM через HTTP/1 Upgrade, text/binary, fragmentation, live history, hold/edit/drop, replay і ручний клієнт |
| Intruder | Sniper, Battering ram, Pitchfork, Cluster bomb; lazy payloads, per-set processing, pacing, pause/resume/cancel, grep/extract, bounded results, CSV/JSON |
| Scanner | 16 категорій, query/header/form/JSON insertion points, контрольовані captured POST, evidence/control, confidence, скасування, JSON/HTML/CSV |
| Collaborator | Власний Java OAST-сервер DNS UDP/TCP, HTTP/HTTPS, SMTP/STARTTLS/SMTPS; Android-клієнт, приватні payload-и, polling, revoke, JSON |
| Інші інструменти | HTTP history і пошук, HAR, Decoder, Comparer, до 500 payload replay із затримкою |

HTTP/2 використовує Netty для HPACK; HTTP/3 — Java Kwik/Agent15 та QPACK. Android-порти `quic` і `qpack` прибирають настільний JMX-monitor та несумісний із ранніми Android `readNBytes`. Вихідні коди, точні зміни та LGPL-тексти включені. Деталі — [third-party notices](docs/THIRD_PARTY.md).

## Збірка

Потрібні **JDK 17**, Android SDK Platform 36, Build Tools 35+ та доступ до Maven/Google repositories. Gradle Wrapper включений.

```sh
./gradlew :core:test :oast:test :collaborator-server:test :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
./gradlew :collaborator-server:distZip :core:collectThirdPartySources
```

Задайте SDK у `local.properties`: `sdk.dir=/ваш/шлях/Android/sdk`, або відкрийте проєкт в Android Studio.

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- R8 release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`; для розповсюдження потрібен ваш ключ підпису.
- Java-сервер: `collaborator-server/build/distributions/collaborator-server-0.3.0.zip`.
- Старі локальні preview-артефакти підписані debug-ключем. GitHub Release **0.1.1 Preview** містить APK, підписаний власним release-ключем розробника. Ключ і пароль не включені у вихідні коди.

## Перший запуск і протоколи

1. Встановіть Sprig на Android 8.0+; для TLS HTTP/2 потрібен Android 10+. HTTP/3 перевіряє доступність потрібних JCA-алгоритмів під час запуску.
2. Settings → Export CA certificate. Встановіть CA через Android Security → Install a certificate → CA certificate. Нова назва пакета означає окрему інсталяцію й окремий CA від попереднього Canopy.
3. Задайте Scope і застосунки. Порожній Scope означає всі домени. Для зміни вибору застосунків / HTTP/3 / QUIC перезапустіть capture.
4. Start capture → стандартна згода Android VPN. Клієнт, який довіряє CA, зможе передавати HTTP/1, HTTP/2 або HTTP/3 через MITM.
5. HTTP history показує протокол запиту; деталі також показують відмінний upstream-протокол. Редактор працює з HTTP/1-текстовим представленням; Sprig кодує його у відповідний wire format.
6. Для повтору: деталі → Send to → Repeater. У списку Protocol виберіть AUTO або потрібний протокол. AUTO погоджує HTTP/2 чи HTTP/1.1; HTTP/3 вибирається явно.

CA-довіра залежить від клієнта. Для власного debug Android-застосунку можна використати `network-security-config` з `debug-overrides` і `<certificates src="user" />`. Це не змінює політику інших програм. [Android CA configuration](https://developer.android.com/privacy-and-security/security-config).

## WebSocket

Tools → WebSocket. Захоплені ws/wss Upgrade-запити з’являються як сесії; для WSS клієнт має довіряти Sprig CA. Ручне Connect відкриває окрему ws/wss-сесію. Виберіть сесію, щоб надсилати UTF-8 text або binary hex. Hold data messages → черга → редагування, Forward або Drop. Ping/Pong обробляються під час перехоплення. Повні повідомлення можна повторити до вибраного сервера.

Після handshake WebSocket передає сокети окремому обмеженому набору workers і звільняє HTTP admission. Історія — до 512 повідомлень / 8 MiB; capture до 64 KiB, editing до 256 KiB, повне повідомлення до 1 MiB. Скорочені captures явно позначені й не повторюються як повні. Компресія не погоджується. [Поведінка та межі WebSocket](docs/WEBSOCKET.md).

## Intruder

HTTP history → Send to → Intruder, Repeater → Send to Intruder або Tools → Intruder. Позначте позиції парами `§value§`, виберіть режим і payload sets, перевірте кількість комбінацій, потім Start attack. Sniper/Battering ram використовують один набір; Pitchfork/Cluster bomb — окремий набір на кожну позицію.

Набори: UTF-8 списки, binary hex, числові діапазони; імпорт файлу й окремі впорядковані processing rules. Rate, limits and matching задає concurrency, pacing, deadline, request budget, retries, literal grep та RE2 extract. Results підтримує пошук, сортування, фільтри, evidence і повернення повного відновленого запиту в Repeater. Пауза зупиняє нові запити; Cancel закриває лише з’єднання цього запуску. Експорт JSON/CSV містить збережені результати та позначки скорочення/видалення.

До 32 позицій, 1–4 workers, до 100 000 спроб; комбінації генеруються поступово без створення повного декартового добутку. Retained results обмежені кількістю та пам’яттю. Запуск один на процес, попередні результати й чернетки зберігаються в пам’яті. [Режими, payloads і runtime Intruder](docs/INTRUDER.md).

## Scanner

Tools → Scanner → seed URL → Categories / Input types / Limits → Start scan. Capture не запускає Scanner автоматично. Для авторизованої сесії передайте повний GET/POST через Send to → Scanner; Cookie/Authorization залишаються в пам’яті та не передаються на інший origin. POST вимагає окремого Enable stateful testing і збереження початкового URL.

П’ять базових категорій увімкнено початково: headers/cookies, contextual reflection, SQL error differential, external redirect і credentialed CORS. Додаткові категорії: template arithmetic, CRLF header injection, forwarded-host reflection, OPTIONS/TRACE, повторюваний boolean differential, cache policy, disclosures, directory listing, CSRF forms, DOM observations і submission власного OAST callback. Query — початковий тип insertion; header/form/JSON-string налаштовуються явно. Евристики й відбитий текст не видаються за доведене виконання JavaScript, SQL чи підтверджений OAST callback.

Початкові межі: 20 сторінок, 150 запитів, 2 workers, інтервал 250 ms, 6 insertion points, 24 probes на категорію за весь scan. Crawl і redirects залишаються в одному origin, є загальний і окремі бюджети. Cancel закриває власні активні запити без зупинки VPN. JSON/HTML/CSV-звіти містять confidence та всі evidence exchange IDs. [Повний перелік і межі Scanner](docs/SCANNER.md).

## Collaborator

Tools → Collaborator. Потрібен власний HTTPS management API, публічний DNS-домен і доступний сервер. Готова Java-серверна частина включена; публічне розгортання не виконувалося без даних VPS/домену.

[Інструкція сервера, DNS, TLS та API](docs/COLLABORATOR.md). Це власний протокол Sprig, не клієнт закритого сервісу PortSwigger.

## Оптимізація та межі

- NIO selector для VPN, bounded queues/windows, coalesced UI updates, R8/resource shrinking.
- Спільний бюджет upstream-запитів для capture, Scanner, Repeater, H2 та H3: 2 / 4 / 8 за розміром heap; переповнення повертає явну помилку.
- HTTP/2: до 8 streams на connection, 4 MiB aggregate pending bodies, 64 KiB headers, 16 KiB frames, 4 KiB HPACK table.
- HTTP/3: 2 / 4 VPN flows, обмежені buffers/workers; QPACK dynamic capacity 0 — стандартне оголошення без dynamic indexing, Huffman підтримується.
- Тіла інспектуються до 4 MiB; history capture — до 512 KiB на тіло; редактор — до 1 MiB. Історія: до 1000 записів і 10% heap, від 4 до 24 MiB.
- Історія, чернетки й звіти — в пам'яті; для збереження потрібен явний HAR/JSON/HTML/CSV export. CA зберігається у private no-backup storage.
- Certificate pinning не обходиться автоматично. IPv6 у capture блокується; IPv4 fragmentation / IPv6-only/NAT64 не підтримуються.
- Немає WebSocket extended CONNECT через HTTP/2/3, permessage-deflate, ECH, client TLS authentication, WebTransport, необмежених streaming bodies, BApp API чи всіх генераторів і перевірок Burp Professional.
- Нове upstream TLS/QUIC-з'єднання створюється для кожного exchange. Origin pooling, resumption і 0-RTT — наступна оптимізація; максимальна швидкодія та батарея не виміряні на телефоні.

Документація: [архітектура](docs/ARCHITECTURE.md), [HTTP/3](docs/HTTP3.md), [перевірка на пристрої](docs/DEVICE_TESTING.md), [результати перевірок](docs/VALIDATION.md).

## Розвиток і внески

[Повний roadmap](ROADMAP.md) · [Правила для агентів і розробників](AGENTS.md).

Робота над етапом **0.4**: [журнал перевірок](docs/STAGE_0_4.md), [benchmarks](docs/BENCHMARKS.md), [спільний бюджет пам’яті](docs/MEMORY_BUDGET.md), [Android quality](docs/ANDROID_QUALITY.md). Поточні зміни не оголошують завершення етапу чи нового production-релізу.

## GitHub Release 0.1 Preview

[Репозиторій](https://github.com/MrmaderatorYT/Sprig) · [Releases](https://github.com/MrmaderatorYT/Sprig/releases). Android application versionName — `0.1 Preview`, versionCode — `1`; server/library build versions і попередні validation reports мають власну історію версій.

APK підписаний release-сертифікатом, а не debug-сертифікатом старих preview. Android не приймає звичайне оновлення поверх збірки з іншим сертифікатом; попередні локальні versionCode=3 також вищі за поточний1. Новий APK не слід видавати за in-place update таких інсталяцій.

SHA-256 та fingerprint підпису наведено в release assets/notes. `stage04-validation-evidence.zip` містить історичні результати локальних fixtures, на які посилаються roadmap/docs через `dist/stage04/`; для локальних посилань розпакуйте архів у корінь проєкту. Звіт release-перевірок: [docs/RELEASE_0_1_PREVIEW.md](docs/RELEASE_0_1_PREVIEW.md).

Приватні ключі, паролі, `local.properties`, кеші, captures і build outputs виключені з Git. `fixture-test-only.p12` — навмисно публічний fixture, описаний у third-party notices; це не ключ підпису застосунку.

## Open beta 0.1.1 Preview

Інтерфейс має англійські та українські ресурси. Android 13+ дозволяє вибрати мову Sprig у системних налаштуваннях застосунку; старіші Android використовують мову пристрою. Налаштування → Про Sprig показують фактичні package/version/versionCode, вихідний код і перехід до налаштувань мови. Протокольні дані, назви стандартів, первинні повідомлення transport/core та технічні описи findings у звітах зберігаються без автоматичного перекладу.

При першому запуску з’являється попередження про відкриту бету. Підтвердження зберігається після «Зрозуміло»; перезапуск або поворот перед підтвердженням не приховує попередження. Його можна відкрити повторно в налаштуваннях. Відгуки: **cybercraftstudiopro@gmail.com**. Кнопка відкриває поштову чернетку лише з адресою й темою: жодних captures, вкладень або діагностики автоматично не додається і не надсилається.

[Поточний реліз](https://github.com/MrmaderatorYT/Sprig/releases/tag/v0.1.1-preview) · [Сторінка EN/UK](https://mrmaderatoryt.github.io/Sprig/) · [Перевірки 0.1.1](docs/RELEASE_0_1_1_PREVIEW.md). Попередні release-звіти нижче/вище зберігають власні версії та факти.
