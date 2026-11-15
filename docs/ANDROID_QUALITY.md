# Android quality — початок етапу 0.4

Java-only зміни, 2026-11-15. Це опис перевірок і review; актуальні результати запусків належать `VALIDATION.md` та окремим device JSON artifacts.

## Локалізація й доступність

Додано англійські та українські string/plural resources для навігації, загальних дій, WebSocket UI та числових статусів Scanner/Intruder/Repeater. Count phrases використовують Android plurals (включно з українськими one/few/many), а багатовимірні summaries — незалежні labels на кшталт «Запитів: N · помилок: M». Вони не повинні вибирати граматику одного лічильника для інших незалежних чисел.

Protocol templates, IP/URL defaults, hexadecimal payloads та значення числових полів залишаються машинними даними; переклад не змінює wire bytes. Числові поля налаштувань зберігають ASCII для відповідних parser-ів. Dynamic display text більше не збирається з англомовних фрагментів у раніше виявлених SetTextI18n sites.

WebSocket та Scanner мають зовнішню прокрутку і bounded-height RecyclerView. Це дозволяє дістатися дій на короткому landscape viewport та з великим шрифтом без вимірювання всіх записів як wrap-content list. WebSocket action rows переходять у вертикальні при fontScale > 1.3; важливі touch targets мають мінімум 48dp. Додані labels для session/mode selection та interception controls. Решта старих діалогів і core error/finding descriptions ще потребують локалізації; повну Ukrainian UX локалізацію не заявлено.

`AdaptiveWorkspaceTest` перевіряє українські plurals і стабільні navigation IDs, fontScale 2, IME padding/reduced viewport, досяжність дій у landscape та tablet recreation. API28 не може round-trip synthetic IME type overrides через native WindowInsets; тест викликає той самий product listener path напряму. Реальна клавіатура, TalkBack і OEM geometry потребують device checks.

## Review lint warnings

Початкова 0.3.0 мала 56 warnings, з яких 45 — SetTextI18n. Нові resources усувають їх причини. Сім PluralsCandidate, виявлених після винесення summaries у XML, переглянуто: багатовимірні summaries переписано як labels; справжні count phrases мають plurals. Фактичне фінальне число warnings визначає останній lint run, а не цей опис.

Інші review categories:

- **QueryPermissionsNeeded:** app picker показує доступні launcher apps; manifest queries відповідає MAIN/LAUNCHER. Це не повний список прихованих/system packages. QUERY_ALL_PACKAGES автоматично не додається.
- **TrustAllX509TrustManager у сторонніх JARs:** dependency містить класи з відповідними методами. Це не дозвіл використовувати їх для origin TLS; product transport зберігає chain/hostname verification, а device fixture використовує лише явно створений локальний CA. Перевірку шляху довіри не підміняє suppression.
- **InsecureBaseConfiguration:** plaintext HTTP/ws потрібні для явно вибраних targets та fixtures. Власний management Collaborator API залишається HTTPS-вимогою; cleartext не вмикає довіру до TLS-сертифікатів.
- **NotifyDataSetChanged:** legacy HTTP-history adapter ще потребує окремого профілювання/оновлення. Це лишається видимим performance warning.
- **Dependency/Gradle updates:** оновлення версій не є автоматично частиною локалізації; потрібні compatibility/license checks і відповідні тести.

Blanket suppression та lint baseline не додаються.

## UI work queues

MainActivity, payload replay, Scanner і Collaborator використовують один worker на owner з малими bounded queues та explicit rejection feedback. Idle workers завершуються; CallerRunsPolicy не використовується. Rejected Repeater/Scanner запуск відновлює кнопку/busy state до відправлення мережевого запиту. Collaborator export rejection не скидає busy іншої активної операції. CA fingerprint не залишається назавжди в loading state, якщо завдання не прийнято.

`UiWorkQueueTest` та `WorkspaceRejectionTest` перевіряють saturation/shutdown, відсутність виконання rejected work на caller thread і відновлення UI без мережевої відправки.

## Device harness

`app/src/androidTest/.../Stage04Instrumentation` — framework Instrumentation без AndroidX test runner dependency. `fixture-app` — окремий Java Android application `com.ccs.sprig.fixture`, який приймає лише explicit private IPv4 HTTP URL `/sprig-fixture`, перевіряє GET body та 16KiB binary POST echo і записує bounded report у private files.

Потрібні debug APK, androidTest APK, fixture APK і власний LAN fixture зі [BENCHMARKS.md](BENCHMARKS.md). Поточна validation-конфігурація використовує `192.168.0.113:8080`, token `sprig040fixture`; при зміні мережі узгоджено змінити fixture address/scope у harness. Public endpoints не використовуються.

Приклади після встановлення APK:

```sh
adb shell am instrument -w -e mode runtime -e idleSeconds 35 com.ccs.sprig.test/com.ccs.sprig.validation.Stage04Instrumentation
adb shell am instrument -w -e mode prepare-vpn -e autoFixture true com.ccs.sprig.test/com.ccs.sprig.validation.Stage04Instrumentation
adb shell am instrument -w -e mode vpn -e autoFixture true com.ccs.sprig.test/com.ccs.sprig.validation.Stage04Instrumentation
adb shell am instrument -w -e mode cleanup com.ccs.sprig.test/com.ccs.sprig.validation.Stage04Instrumentation
```

- `runtime` запускає/recreates п’ять Activity та виконує справжні local HTTP1, native TLS/H2, QUIC/H3 і WebSocket exchanges. Wrong H3 hostname повинен відхилятися. Це direct loopback/native-provider перевірки, а не HTTPS/H2/H3 через VpnService.
- `idleSeconds` — 0–45, початково 0. `afterImmediate` і `afterIdle` дозволяють побачити завершення idle workers/timers без forced GC. Cold class/provider loading не вважається автоматично витоком; нульовий ріст ресурсів не стверджується.
- `prepare-vpn` відкриває стандартну Android згоду; користувач має її прийняти. Немає app-op/consent bypass. `vpn` потребує вже наданої згоди.
- VPN тимчасово вибирає лише fixture package. `autoFixture` початково false; true запускає explicit fixture Activity з fresh task і випадковим run ID. Runner чекає nonce-correlated completion, перевіряє captured status/body, виконує Stop/Restart, запускає ще чотири запити й знову Stop.
- Отримання client completion перед Stop запобігає гонці між записом HTTP history та останньою доставкою response через TUN. Client summary обох запусків включено у runner report.
- Native device CPU/RSS/PSS/heap/GC/thread/FD snapshots та fixture latency/throughput — вимірювання конкретного run, а не універсальні performance gates. Radio/battery/jank цей runner не вимірює.
- Capture preferences зберігаються у private durable backup перед змінами й відновлюються в finally. Існуюча history не очищається. `cleanup` відновлює backup після перерваного запуску; для legacy запуску без backup прибирає лише точні fixture selection/scope, не інші preferences.
- Усі Activity launches bounded; existing task не може залишити `startActivitySync` в нескінченному очікуванні. Watchdog: 120s runtime / 360s VPN, з cleanup/report при timeout.
- Reports: `files/stage04-runtime.json`, `files/stage04-prepare-vpn.json`, `files/stage04-vpn.json`, `files/stage04-cleanup.json`; fixture app має `files/fixture-result.json`.

Debug instrumentation явно встановлює `r8ReleaseRuntimeValidated=false`. API37 результат, якщо отриманий, не підміняє заплановану API26/29/33/36 чи multi-OEM matrix. Результат harness не закриває повністю етап 0.4.

Fixture workload має110s deadline, 10s socket timeouts, bounded lifecycle/request-error trace. Configuration changes у fixture обробляються in-place без повторної відправки; справжнє знищення Activity лишається failed/cancelled run.
