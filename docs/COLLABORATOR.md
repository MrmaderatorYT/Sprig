# Sprig Collaborator — власний OAST-сервер

Сервер написаний на Java 17 і постачається окремо від APK. Android-клієнт: Tools → Collaborator. Він генерує унікальну адресу, копіює її для Repeater / Payload runner, отримує події та експортує JSON. Ключі API й секрет сесії залишаються у пам'яті процесу; URL сервера зберігається окремо.

## Локальна перевірка

```sh
./gradlew :oast:test :collaborator-server:test :collaborator-server:installDist
export SPRIG_COLLABORATOR_KEY="$(openssl rand -hex 32)"
collaborator-server/build/install/collaborator-server/bin/collaborator-server collaborator-server/server.example.properties
```

Спочатку скопіюйте приклад конфігурації у власний файл і задайте `domain`, `publicAddress`, адреси прив'язки та порти. Приклад використовує документаційну IP-адресу та не є готовим публічним доменом.

Локальна management API: `http://127.0.0.1:18081/api/v1`. Android-клієнт дозволяє HTTP тільки для loopback, віддалені API потребують HTTPS. `127.0.0.1` на телефоні — це сам телефон: для доступу до локального комп'ютера потрібний HTTPS endpoint або USB-перенаправлення порту, налаштоване користувачем.

## Публічне розгортання

1. Підготуйте VPS із публічною IPv4-адресою й власний домен, наприклад `oast.example.com`.
2. Делегуйте DNS цієї зони на `ns.oast.example.com`, задайте glue / A-запис із IP сервера. Сервер приймає DNS UDP **та** TCP. Публічний DNS має бути доступним на порту 53; високий порт у прикладі призначений для локальних тестів.
3. Налаштуйте мережеві порти: DNS 53 → `dnsPort`, HTTP 80 → `httpPort`, SMTP 25 → `smtpPort`, HTTPS 443 → `httpsPort`, SMTPS 465 → `smtpsPort`. Можна прив'язати процес безпосередньо до стандартних портів з відповідними правами ОС або використати мережеве перенаправлення.
4. Для HTTPS / SMTPS / STARTTLS потрібен чинний wildcard-сертифікат `*.oast.example.com` і його приватний ключ у PKCS12. Задайте `tlsKeyStore` та `SPRIG_TLS_PASSWORD`. Без TLS-сховища ці можливості не анонсуються клієнту.
5. Management API за замовчуванням слухає тільки loopback. Розмістіть перед ним HTTPS reverse proxy на окремому імені, наприклад `api.oast.example.com`, і передавайте шлях `/api/v1`. Або задайте `apiTls=true` із відповідним сертифікатом. Не публікуйте admin key у DNS чи URL.
6. У Sprig введіть HTTPS URL API та `SPRIG_COLLABORATOR_KEY`, натисніть Generate callback address. Вставте отриманий hostname у власний тестовий запит і натисніть Poll now. Автоматичне опитування працює тільки поки екран відкритий.

Цей проєкт не розгортає сервіс в обліковому записі користувача автоматично. Публічну досяжність, делегування DNS, сертифікати, NAT/firewall та дозволений SMTP-трафік перевіряють на конкретному VPS.

## Протоколи й API

- Authoritative DNS UDP/TCP: A, NS, SOA, MX, TXT; решта підтримуваних імен дає NODATA з SOA. Сторонні зони отримують REFUSED. Рекурсивного DNS немає.
- HTTP/HTTPS: запис методу, URI, обмеженого набору заголовків і до 8 KiB даних. `Authorization` та `Cookie` вилучаються. Сесія визначається за hostname або `/<session-id>/...` для локальної перевірки.
- SMTP, STARTTLS і SMTPS: приймання адреси RCPT та повідомлення до 8 KiB. Сервер ніколи не пересилає пошту.
- `POST /api/v1/sessions`, admin Bearer key → новий payload hostname, ID і окремий session secret.
- `GET /api/v1/sessions/{id}/events?after={cursor}`, session Bearer secret → події та наступний cursor.
- `DELETE /api/v1/sessions/{id}`, session Bearer secret → відкликання адреси.

Межі: 128 сесій, 24 години життя, 256 подій / 2 MiB на сесію, 16 MiB загалом, сторінка подій до 256 KiB. Втрата старих подій позначається `truncated`. Дані живуть тільки в пам'яті сервера; перезапуск очищає сесії. Це власний протокол Sprig, сумісність із закритим API PortSwigger не заявляється.

Опис ролі публічного сервера в Burp: [PortSwigger Collaborator server](https://portswigger.net/burp/documentation/collaborator/server).
