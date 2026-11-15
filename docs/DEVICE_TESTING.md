# Перевірка на Android-пристрої

JVM-тести не доводять правильність Android VPN, життєвого циклу чи роботи різних TLS-провайдерів. Перед використанням потрібна ця перевірка на реальному пристрої або емуляторі.

1. Встановити Sprig preview APK на Android 8, 10, 13 та 16; перевірити запуск, тему, системні відступи, великий шрифт і поворот екрана.
2. У власній локальній тестовій мережі підняти HTTP/1.1 fixture: GET, POST з бінарним тілом, chunked response, 204/304, redirect, keep-alive, повільна відповідь і тіло, що перевищує ліміт.
3. Запустити capture, дати згоду VPN, виконати HTTP-запит із вибраного застосунку. Перевірити тіло, заголовки, статус і завершення з'єднання.
4. Експортувати та встановити CA. У тестовому HTTPS-клієнті з довірою до CA перевірити SNI, TLS 1.2 і 1.3, правильний SAN листового сертифіката та HTTP/1 fallback.
5. У клієнті без довіри до CA перевірити очікувану TLS-помилку; вимкнути MITM / виключити домен і перевірити звичайний TLS tunnel.
6. Сервер із простроченим, самопідписаним або невідповідним hostname сертифікатом має бути відхилений upstream-перевіркою.
7. Увімкнути Intercept, редагувати POST та довжину тіла, Forward; інший запит Drop. Перевірити 120-секундний timeout і поведінку черги з 16 запитів.
8. У Repeater повторити захоплений запит. Додатково перевірити Decoder та Comparer з Unicode і некоректним входом.
9. Зберегти HAR, розібрати JSON стороннім інструментом; звірити Base64 тіла і `_truncated`, `_error`. Public CA export не має містити приватного ключа.
10. Перевірити DNS/UDP, блокування QUIC, відсутність IPv6 bypass, вибір застосунків та виключення самого Sprig з VPN.
11. Зупинити capture, відкликати VPN у системі, вбити процес, переключити Wi-Fi / mobile, запустити інший VPN, швидко повторити Start/Stop. Інтерфейс і сповіщення мають прибиратися, звичайний інтернет — відновлюватися.
12. Навантажити 100 паралельними короткими HTTP-з'єднаннями й великими файлами. Через Android Studio Profiler / Perfetto виміряти CPU, RSS, GC, frames, батарею та пропускну здатність. Перевірити ліміти flow, пам'яті та кількості потоків.

## Додаткові перевірки 0.2.0

13. HTTP/2 на Android10+: клієнт з ALPN h2 → MITM → h2 origin, паралельні streams, POST240KiB+, відміна одного stream, HEAD/304, помилки HPACK/CONTINUATION, великі заголовки. На API26–28 перевірити заявлений HTTP1 TLS fallback і явну помилку forced TLS HTTP2.
14. HTTP/3: Chrome/curl/власний клієнт із довірою до CA; QUICv1/v2, UDP443 та нестандартний порт, правильний оригінальний IP/порт, Intercept Forward/Drop, host Scope, MITM-off passthrough, недійсний origin certificate. Перевірити release APK, щоб таблиці QPACK збереглися після R8.
15. HTTP/3 crypto preflight на різних OEM/API: P256/ECDH, RSA-PSS mapping; нездатний runtime має дати явну помилку. За потреби вимкнути HTTP3 у Settings для TCP capture.
16. Scanner на власному fixture: anonymous URL і captured GET із Cookie/Authorization, зміна origin має відхиляти credential template, redirect/crawl не має виходити за scope. Перевірити budgets, Cancel, ротацію, confidence/evidence та JSON export.
17. Collaborator на власному VPS: DNS delegation/glue, UDP/TCP53, SOA/MX, wildcardTLS, HTTP80/HTTPS443, SMTP25/STARTTLS/SMTPS465, приватний HTTPS API. Перевірити payload generation, polling/cursor, isolated secrets, expiration, revoke і JSON export.
18. Навантажити public callback listeners повільними клієнтами; API polling/revoke має залишатися доступним, SMTP/TCP DNS повинні закриватися за абсолютним deadline.

## Додаткові перевірки 0.3.0

19. WebSocket ws/wss: браузер через VPN → локальний echo fixture; custom subprotocol, text, 180 KiB binary, UTF-8 fragments і Ping між fragments. Hold/Edit/Forward/Drop в обидва боки, Forward original >256 KiB, timeout, маскування й закриття. Останнє повідомлення перед Close має надходити повністю. Під максимальним числом відкритих WS-сесій звичайний HTTP і Repeater мають працювати.
20. WebSocket lifecycle: закриття однієї сесії не зачіпає іншу; Stop capture прибирає всі сесії й pending holds. Некоректний Accept/subprotocol/extensions має відхилятись. Перевірити довгий idle, повільний неповний frame, величезний frame та явні truncation-позначки. H2/H3 extended CONNECT не підтримується.
21. Intruder: усі чотири режими з двома позиціями й перевіреними вручну послідовностями; UTF-8, binary hex, числові межі, per-set rules, імпорт newline-only oversized list, великий lazy Cluster bomb. Budget має рахувати retries, Pause не запускає нових запитів, Cancel не перериває іншу сесію чи VPN. POST не повторюється автоматично при помилці.
22. Intruder results: grep/extract RE2, status/length/time sorting, evidence limits/evictions, відновлення повного запиту в Repeater, JSON/CSV. Чернетка 900 KiB й активний запуск переживають rotation; UI не має накопичувати необмежені Send/Validate/Export завдання. Окремо перевірити Cancel під час підготовки та DNS-пошуку.
23. Scanner: усі 16 категорій на власних позитивних/негативних fixtures, baseline/control evidence, повторювані boolean/template probes, context-only DOM/CSRF observations. Captured POST залишається заблокованим до opt-in. Query/header/form/JSON-string змінюють лише вибране поле; header auth/framing заборонені, captured credentials не виходять за origin. Перевірити per-check/global budgets, негайний Cancel, JSON/HTML/CSV escaping і redaction.
24. На телефоні порівняти heap/CPU/плавність/батарею під HTTP capture + відкритими WS + Intruder/Scanner. Перевірити R8 release, низький heap і часті Start/Pause/Resume/Cancel без росту потоків/черг.

## Статус у цьому середовищі

На початку 0.4 знайдено фізичний Google Pixel7a/API37. Виконано native HTTP1/TLS-H2/QUIC-H3/WS loopback та п’ять Activity launch/recreate; окремий fixture-app перевіряє справжній HTTP/IPv4 VpnService. Результати й обмеження: [STAGE_0_4.md](STAGE_0_4.md), [Android runner](ANDROID_QUALITY.md). Це не повна запланована API26/29/33/36/multi-OEM matrix. Емулятор/AVD у середовищі не встановлені.

Robolectric API28 залишається окремим JVM UI рівнем перевірки. Debug instrumentation не підміняє R8 protocol runtime. Для повторення потрібні явно вибраний локальний fixture, standard VPN consent та збереження/відновлення capture preferences; команди наведено в ANDROID_QUALITY.md.
