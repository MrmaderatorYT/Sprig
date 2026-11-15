# Sprig 0.3.0 — перевірка

Це збережений звіт для 0.3.0. Нові результати робочого етапу 0.4 наведено окремо в [STAGE_0_4.md](STAGE_0_4.md).

Дата: 15 листопада 2026 року (Europe/Kyiv). Пакет: `com.ccs.sprig`. JDK17, Gradle8.13, Android compile/targetSDK36, minSDK26.

## Автоматичні перевірки

| Набір | Пройдено | Помилки / пропуски |
| --- | ---: | ---: |
| JVM core / HTTP1/2/3 / WebSocket / Intruder / Scanner / TUN / DNS | 142 | 0 / 0 |
| Android UI через Robolectric API28 | 14 | 0 / 0 |
| OAST client / JSON | 2 | 0 / 0 |
| Java Collaborator server | 6 | 0 / 0 |
| **Разом** | **164** | **0 / 0** |

Завдання Gradle: `:core:test :oast:test :collaborator-server:test :collaborator-server:distZip :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`, окремо `:app:assembleRelease`. Незмінені OAST/server suites можуть бути UP-TO-DATE; їхні останні результати включено до таблиці. Lint: **0 errors, 56 warnings**, без загального suppression baseline. 45 попереджень стосуються англомовних динамічних UI-рядків; решта — залежності, встановлені застосунки, явна підтримка cleartext, сторонні trust-manager класи та старий HTTP-history adapter. Origin TLS використовує власний перевірений шлях із hostname/chain validation. R8 також повідомляє про необов’язковий Netty BlockHound service; це не помилка складання.

## Перевірені сценарії

- HTTP/2: незалежний JDK HTTP2 client через CONNECT/TLS ALPN MITM, multiplexing, HPACK/Huffman, flow control, reset isolation, framing errors, redirect без переходу, raw-Host authority з фіксованим мережевим призначенням.
- HTTP/3: реальні QUIC TLS1.3 client/server, hostname/CA rejection, simulated-TUN MITM із початковим IP/портом, Intercept і passthrough. Новий тест підтверджує, що змінений `:authority` доходить до вибраного origin і не змінює перевірку TLS hostname. Source modules `quic` та `qpack` використовуються в тестах і APK.
- Окремий curl8.23/ngtcp2/nghttp3 `--http3-only` interop-check пройшов для 0.2.0. Для 0.3.0 цей зовнішній interop-check повторно не запускався; актуальні QUIC-регресії наведено вище.
- WebSocket: незалежний JDK WSS client → MITM → fixture, 101 разом із першим frame, 180 KiB binary, fragmented UTF-8/Ping/Pong, edit/drop/hold timeout/stop, закриття окремої сесії, неправильні handshake/frame/close encodings, WSS hostname rejection, IPv6 authority formatting. Фінальне 220 KiB text повідомлення перед Close доходить повністю. Закриття не може додати новий hold; idle дозволено, неповний frame має абсолютний deadline. Під повним WS-бюджетом звичайні HTTP capture/replay працюють.
- Intruder: точні послідовності й counts усіх чотирьох режимів, lazy trillion-combination plan, overflow, independent/global processors, numeric/UTF-8/binary boundaries, pause/resume, загальний pacing/concurrency, scoped cancel, deadlines, retries/attempt budget, RE2 grep/evidence/eviction/export. Реальний proxy fixture перевіряє binary POST, Cookie/Authorization, перерахований Content-Length і закриття власного origin socket. Newline-flood payload import відхиляється до масових allocations; aggregate memory рахує також array overhead.
- Scanner: 16 categories, per-check/global budgets, query/header/form/JSON-string insertion, captured POST opt-in/target pinning, scope/credentials, contextual reflection, repeated baseline/control differential, confidence, redacted disclosures і JSON/HTML/CSV escaping. Повільний Closeable підтверджує, що Cancel не закриває транспорт на UI-потоці.
- DNS/cancellation: numeric literals без DNS, strict malformed-literal rejection, platform order/IPv4 preference, cancellation під час resolver, який ігнорує interrupt, timeout, два workers/черга вісім, overload і видалення скасованої queued task. Тести не використовують публічний DNS або публічні цілі.
- Collaborator: авторизація/ізоляція/revoke, HTTP, DNS UDP/TCP, SOA/MX/NXDOMAIN, compression-loop rejection, SMTP sink, локальні HTTPS/SMTPS і bounded history.
- UI: головні вкладки, Scanner, Collaborator, Intruder і WebSocket; нові екрани рендеряться нативною графікою Robolectric. Чернетка900KiB й WebSocket composer200k не входять у великий saved-state parcel; Intruder переживає recreation. Перевірено queued-start Cancel, bounded UI queue, збереження повного512KiB request при скороченій response, POST opt-in після rotation. Intent handoff містить URL+ID або локальний process handoff; великі тіла/секрети не серіалізуються в Intent.

## Артефакти та практичні межі

Debug і R8 release збираються окремо. Preview APK підписується стандартним локальним Android debug-ключем для встановлення й перевірки; production-реліз потребує власного ключа. Перевіряються підпис, zip alignment, package/version, QPACK/version resources та включені GPL/LGPL/RE2J notices. Native `.so` не постачаються. Server distribution не містить тестового TLS-ключа.

У середовищі немає підключеного Android-пристрою чи підготовленого емулятора. Справжній VpnService, R8 runtime на телефоні, OEM JCA, браузери, CPU/RSS/GC/радіо/батарея й публічний DNS/VPS deployment потребують окремої перевірки. Інструкція: `DEVICE_TESTING.md`.

Повний паритет із Burp Professional і максимальна швидкодія не доведені. WebSocket extended CONNECT через H2/H3, permessage-deflate, BApp API, повний browser-driven Scanner, усі Intruder generators і streaming/pooling/resumption не реалізовані. Точні межі — `WEBSOCKET.md`, `INTRUDER.md`, `SCANNER.md`, `ARCHITECTURE.md`.
