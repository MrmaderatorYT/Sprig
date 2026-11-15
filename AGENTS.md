# Правила роботи зі Sprig

Ці інструкції діють для всього репозиторію. Явні вказівки користувача мають пріоритет. Не застосовувати цей файл ретроактивно як доказ, що старі перевірки виконані.

## Продукт і джерела фактів

- Назва: **Sprig**. Android namespace/applicationId: **com.ccs.sprig**.
- Починати з `README.md`, `ROADMAP.md`, `docs/ARCHITECTURE.md` і `docs/VALIDATION.md`; для інструментів читати відповідні docs.
- Відрізняти реалізацію, JVM/UI verification, device verification і виміряну продуктивність.
- Не заявляти повний паритет із Burp Pro, підтверджену вразливість чи максимальну оптимізацію без доказів.
- Не розширювати задачу автоматично до всього roadmap. Завершувати погоджений обсяг і оновлювати фактичний статус.

## Мова й архітектура

- Код продукту й серверів — **Java**, Gradle — **Groovy**. Не додавати Kotlin-файли, Kotlin plugin або Compose.
- Нативний UI — **Material Design 3**. Зберігати єдину тему, accessibility, window insets і коректну роботу клавіатури/rotation.
- NDK дозволений лише для виміряного bottleneck із benchmark і описаним tradeoff; Java залишається основною реалізацією.
- Наявність Kotlin всередині транзитивних AndroidX/Material binaries не приховувати; це не дозвіл писати Kotlin у проєкті.
- Не реалізовувати криптографічні алгоритми вручну. Використовувати перевірені бібліотеки та platform APIs.
- Не змінювати package, min/target SDK, залежності чи версію релізу без потреби задачі.

## Модулі

| Модуль | Відповідальність |
| --- | --- |
| `app` | Android lifecycle, VpnService, Java Material UI, SAF export |
| `core` | TUN/proxy, HTTP1/2/3, WebSocket, Intruder, Scanner, capture/cancellation |
| `quic` | Vendored Java Kwik source port |
| `qpack` | Vendored Java QPACK source port |
| `oast` | Android-compatible Collaborator client/models |
| `collaborator-server` | Окремий Java17 OAST server |

Не переносити Android dependencies у portable core/server без обґрунтування. Для `quic`/`qpack` зберігати upstream headers, provenance й опис кожної локальної зміни.

## Мережа, scope і секрети

- Розробляти fixtures локально; не запускати активні перевірки публічних/сторонніх цілей без явного дозволу.
- Active scans/Intruder запускаються явною дією. Нові state-changing або складні active checks мають окремий opt-in/profile.
- Origin TLS завжди перевіряє certificate chain і hostname. Не додавати trust-all, вимкнення hostname validation чи прихований insecure fallback.
- URL/оригінальне призначення визначає маршрут і TLS identity. Зміна Host/authority не повинна змінювати їх непомітно.
- Captured credentials не переносяться на інший origin; redirects не обходять scope.
- Upstream sockets виключати з VPN належним protect-шляхом. Не створювати routing loops.
- Cancellation має закривати лише власні запити, а не VPN чи інші інструменти.
- Не додавати зовнішній OAST endpoint, telemetry, analytics або автоматичне надсилання captured data.
- Не комітити CA private keys, production signing/TLS keys, credentials, captures або `local.properties`. Наявний публічний test-only PKCS12 fixture є документованим винятком.
- Export може містити секрети: зберігати explicit action, truncation flags і можливість redaction. HTML/CSV/JSON повинні правильно екранувати недовірені дані.

## Продуктивність і concurrency

- Жодної мережі, DNS, генерації CA, великого parsing чи file I/O на Android main thread.
- Черги, workers, bodies, decompression, history, payloads і results повинні мати явні byte/count/time limits.
- Не додавати unbounded executors/queues, Cartesian materialization або масове `split` перед перевіркою розміру.
- Використовувати shared admission, backpressure, request-local `CallCancellation` і bounded `HostResolver`.
- Скасовані scheduled tasks видаляти з черг; cleanup не повинен блокувати UI.
- Довгі WebSocket-сесії не повинні займати весь HTTP worker/admission budget.
- Підтримувати порядок даних, close barriers, framing і binary/UTF-8 semantics.
- UI updates об’єднувати; великі списки — RecyclerView/DiffUtil, великі drafts — поза Bundle/Intent.
- Оптимізацію підтверджувати baseline/benchmark. Не замінювати коректність на припущення про швидкість.

## Робочий процес

1. Прочитати поточні файли й applicable інструкції; зберегти сторонні незавершені зміни.
2. Вибрати мінімальний цілісний обсяг; не підміняти функцію placeholder або silent fallback.
3. Додавати meaningful regression tests для protocol/security/concurrency behavior; не писати дзеркальні тести для косметичних правок.
4. Виконати відповідні перевірки. Не запускати кілька Gradle builds одночасно в одному workspace.
5. Оновити docs/roadmap за фактичними змінами. Checkbox закривати лише з evidence.
6. Повідомити результат, перевірки й суттєві обмеження; якщо перевірка неможлива, вказати точну причину.

## Команди перевірки

Потрібні JDK17 та Android SDK, зазначені у README. Використовувати Gradle Wrapper.

```sh
./gradlew :core:test :oast:test :collaborator-server:test :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease
./gradlew :collaborator-server:distZip
```

Для вузької зміни спершу запускати потрібний module/test subset. Повний набір потрібний для інтеграційних змін і release. Не повторювати успішні перевірки без нової зміни чи невирішеного ризику. Robolectric не замінює VpnService/device tests; для них використовувати `docs/DEVICE_TESTING.md`.

## Release і залежності

- Debug-підписаний preview чітко називати preview; не видавати його за production release.
- Перед видачею APK перевірити package/version/minSdk, підпис, zip alignment, потрібні runtime resources і склад бібліотек.
- Зберігати R8 resource-dependent names QPACK/QUIC; перевіряти packaged tables/version resources після змін shrinker rules.
- Не приховувати lint errors широким baseline чи blanket suppression. Виправляти причину, вузькі винятки пояснювати.
- Нові dependencies перевіряти за офіційними джерелами на Android compatibility, license і footprint; фіксувати конкретні версії.
- Оновлювати `docs/THIRD_PARTY.md`, APK notices та LGPL source/relinking instructions відповідно до змін.
- Артефакти класти в `dist/`, зберігати попередні версії; source ZIP не включає caches/build outputs/private keys/local SDK paths.
- `docs/VALIDATION.md` містить фактичні результати конкретної версії, а не очікування. Не переносити старі цифри тестів як свіжі без пояснення.
