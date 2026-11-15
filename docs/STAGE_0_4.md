# Sprig 0.4 — журнал реалізації та перевірок

Дата: 2026-11-15. Це робота над етапом 0.4 на базі 0.3.0, а не оголошення завершеного релізу. Package/SDK/version залишені без зміни. Старі артефакти 0.3.0 та їхній validation report збережені.

## Реалізовано

- Спільний MemoryBudget для HTTP history, WebSocket metadata/history/queues/interception та Intruder configuration/results; fail-fast admission, peak/rejection metrics, release на eviction/clear/forget/cancel. Це ліміт облікованої власності, а не всього heap/native memory. [Межі обліку](MEMORY_BUDGET.md).
- Owned capture path прибирає повторні body clones у ProxyServer → SessionStore; загальний record API залишається defensive. [Порівняння allocations](BENCHMARKS.md).
- Серіалізована bounded/coalesced черга lifecycle VPN; захист proxy accept loop від передачі старого socket у нове покоління workers.
- Обмежені UI queues з явною відмовою, відновленням busy/button state, без роботи на UI при переповненні.
- Англійські й українські resources/plurals для основної навігації, WebSocket та dynamic displays; adaptive scrolling, 48dp targets, великі шрифти/landscape/tablet tests. Старі англомовні діалоги ще залишаються.
- Java host benchmark/mixed load, allocation microbenchmark, обмежений LAN HTTP/WS fixture, окремий Android fixture-app та framework-only Java instrumentation. [Команди й семантика метрик](BENCHMARKS.md).

## Матриця пристроїв

| Пристрій / API | Статус |
| --- | --- |
| Google Pixel 7a, API 37 | Native runtime 5/5, HTTP/IPv4 VPN20+4 після restart, R8 launch/CA smoke виконані; докази нижче |
| API 26 / 29 / 33 / 36 | Фізичні пристрої/емулятори відсутні в поточному середовищі; не перевірено |
| Другий OEM / малий heap | Не перевірено |

Robolectric API28 — окремі JVM UI tests, а не виконана device matrix. API37 доданий за фактично підключеним Pixel; він не підміняє заплановані чотири API.

## Значення доказів

Артефакти знаходяться в `dist/stage04/`. Debug instrumentation використовує локальні fixture CA лише для власних loopback endpoints; системний upstream trust застосунку не послаблюється. HTTP/2 та HTTP/3 native-loopback results не доводять їх проходження через VpnService.

Справжній VPN workload вибирає тільки пакет com.ccs.sprig.fixture, звертається до приватного LAN HTTP endpoint на порту8080, перевіряє GET і binary POST та зберігає/відновлює settings. Згода VPN — стандартна Android, без appops bypass. Production captures/history не очищаються тестом.

Перший непейсований host run не пройшов через вичерпання TCP ephemeral ports/TIME_WAIT: нове origin connection на exchange є відомою межею рушія. Невдалий JSON збережено як host-mixed-60s-unpaced-failed.json. Pacing додає керовану offered rate; це не приховане pooling-виправлення й не доказ максимальної швидкості.

Pixel підключений через USB і заряджається. Короткі CPU/PSS/heap/GC/thread/FD snapshots не є вимірюванням енергії/батареї або довготривалого steady-state. Примусовий GC для поліпшення цифр не викликається.

## Отримані вимірювання

- Host mixed, 3s warmup +60s, загальна offered rate100/s, два основні workers, тіло4096B: 3715 успішних основних HTTP exchanges, 0 errors, ≈61.92 основних HTTP ops/s; додатково1142 WS echo,591 Intruder,404 Scanner exchanges. p50≤2.786ms, p95≤14.418ms — верхні межі histogram для service latency без pacing wait. Це не максимальна throughput і не Android latency. Усі обліковані резерви після cleanup:0, peak≈2.20MB, відмов admission:0. JSON: `host-mixed-60s-paced.json`.
- Host allocation comparison, 5 alternating rounds ×5000 records/variant, тіла по32KiB: defensive≈65874.92 allocated bytes/record, owned≈307.57; ≈99.53% менше. Показники стосуються current-thread JVM allocations, не Android RSS чи загальної швидкості програми. JSON: `store-allocation.json`.
- Pixel API37 debug runtime: п’ять Activity launch/recreate і чотири local protocol checks пройдено. Після35s idle без forcedGC threads41→30, PSS139515→139105KiB, FD128→128. Це коротка cold-loading session, а не доказ відсутності витоків чи довгий soak. JSON: `pixel7a-runtime-idle35.json`.
- Справжній VPN: перші20 GET/binary POST перевірені клієнтом і capture, bytesUp185466 / bytesDown183090. Початковий автоматичний restart виявив cancelled fixture client; невдалий результат збережено у `pixel7a-vpn-restart-client-cancelled.json`. Після bounded lifecycle diagnostics/config handling фінальний повтор `pixel7a-vpn-latest.json` пройшов: 20 початкових та4 після restart, client errors0, captured GET/POST bytes підтверджені, Stop/Restart/Stop завершено, settings відновлені. Це один короткий HTTP/IPv4 сценарій, а не повний lifecycle acceptance.

## Ще потрібно

- Повна API/OEM/low-heap matrix; VPN HTTPS/H2/H3/WS, user CA installation і різні trust policies.
- Wi-Fi/mobile handover, revoke/process-death/інший VPN, offline/captive portal.
- Тривалий Android mixed soak, Perfetto/UI jank, батарея/радіо без USB-підзарядки.
- Device-derived performance budgets; universal reservation для transient bodies/frame assembly/native buffers і зовнішніх snapshots.
- Повна локалізація всіх старих діалогів і справжні keyboard/TalkBack accessibility sessions.

Етап 0.4 залишається відкритим до виконання цих перевірок. Закриті підпункти ROADMAP мають посилатися на конкретні результати, а не тільки на наявність коду.

## Фінальні автоматичні перевірки робочих змін

| Набір | Пройдено | Failures / errors / skips |
| --- | ---: | --- |
| core | 161 | 0 / 0 / 0 |
| benchmark | 9 | 0 / 0 / 0 |
| app Robolectric/UI/lifecycle | 27 | 0 / 0 / 0 |
| **Актуальні змінені модулі** | **197** | **0 / 0 / 0** |

Незмінені OAST/server suites не повторювалися; їхні8 старих результатів не включені до197. Фінальний lint: **0 errors /11 warnings**, без suppression baseline; причини залишених попереджень у ANDROID_QUALITY.md. Debug, androidTest, fixture та R8 release APK зібрані. Preview APK підписаний стандартним debug-ключем, v2/v3 signature та zip alignment перевірені; QPACK/license resources збережені, bundled `.so` немає. Версія всередині APK залишається0.3.0: це snapshot роботи0.4, не production release.

Фінальний native runner із RSS: `pixel7a-runtime-final.json`, 5/5 checks; після35s idle threads 42→31, FD 130→128, RSS 296431616→251547648 bytes, PSS 169032→124840KiB. Це samples конкретного debug process із природним GC; не порівняння release performance.

На Pixel також встановлено підписаний R8 preview: cold MainActivity launch222ms, Settings і завантаження CA fingerprint перевірені (`pixel7a-r8-smoke.json`). Це launch/CA smoke, не повна R8 VPN/protocol acceptance.

Причину першого fixture-client cancellation не встановлено постфактум; успішний повтор із diagnostics не видаляє невдалий artifact. Універсальний висновок про всі lifecycle paths не робиться. Legacy UI/footer branding і решта англомовних текстів залишаються частиною незавершеної локалізації.
