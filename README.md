# Mayak Networks — Android-клиент

Это **форк [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android)** (официального
Android-клиента AmneziaWG), доработанный для сервиса **Mayak Networks**. Не официальный клиент
AmneziaWG и не приложение WireGuard — самостоятельный продукт на их основе.

- Лицензия: **Apache-2.0** (файл [COPYING](COPYING)), как у апстрима.
- Точка форка: апстрим v2.0.1 (коммит `fb64e74b`, июнь 2026).
- Полный перечень отличий от апстрима: **[CHANGES](CHANGES)**.
- Что приложение собирает о работе: [TELEMETRY-DISCLOSURE.md](TELEMETRY-DISCLOSURE.md).
- История версий: [CHANGELOG.md](CHANGELOG.md).

## Приложение Маяка

Готовое приложение и сам сервис — на [mayaknetworks.com](https://mayaknetworks.com)
(раздел «Скачать приложение»). Личный кабинет — [cabinet.mayaknetworks.com](https://cabinet.mayaknetworks.com).
Ставить сборки из этого репозитория самостоятельно не нужно: без аккаунта Mayak Networks
приложение не подключается.

## Что изменено относительно апстрима

Кратко (подробно и по фактам — в [CHANGES](CHANGES)):

- **Свой клиент сервиса вместо ручных конфигов**: вход по аккаунту, выбор страны, аренда доступа
  с ядра Mayak Networks; ручной импорт `.conf`/QR как основной сценарий убран.
- **Лестница подключения**: прямой AWG → транзит через РФ → TCP-мост поверх :443, с честной
  диагностикой каждой ступени.
- **Split-туннель с пресетами** («РФ напрямую» и свои), OTA-обновление списка.
- **Свой брендинг**: имя, иконка-маяк, тёмная тема, русская локализация интерфейса.
- **Безопасность**: пиннинг своего CA, kill-switch IPv6-утечки, кэши в `noBackupFilesDir`,
  самообновление с проверкой подписи.
- Телеметрия описана в [TELEMETRY-DISCLOSURE.md](TELEMETRY-DISCLOSURE.md).

## Сборка

```
$ git clone --recurse-submodules https://github.com/wobujidao/amneziawg-android
$ cd amneziawg-android
$ ./gradlew :ui:assembleRelease
```

Боевой вариант (прод-хосты и пиннинг прод-CA) — `./gradlew :ui:assembleProdRelease`.

## Лицензии и атрибуция

- Основа — [amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0),
  который в свою очередь основан на [wireguard-android](https://git.zx2c4.com/wireguard-android/)
  (Apache-2.0, © 2017–2023 WireGuard LLC).
- Изменённые файлы апстрима несут пометку `Modified 2026 by Mayak Networks` (Apache-2.0 §4).
- Движок туннеля — [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) (MIT).
- Сабмодуль [amneziawg-tools](https://github.com/amnezia-vpn/amneziawg-tools) (GPL-2.0) — унаследован
  от апстрима; из него собираются `libwg.so`/`libwg-quick.so` для kernel-режима, которым это
  приложение не пользуется (бэкенд — только userspace GoBackend). Нового GPL-кода форк не добавляет.
- [termux-elf-cleaner](https://github.com/termux/termux-elf-cleaner) используется только на этапе
  сборки, в приложение не попадает.

**WireGuard** — зарегистрированный товарный знак Jason A. Donenfeld. **AmneziaWG** — проект команды
Amnezia. Mayak Networks не аффилирован ни с WireGuard LLC, ни с Amnezia и ими не одобрен; названия
упоминаются только для указания происхождения кода и протокола.
