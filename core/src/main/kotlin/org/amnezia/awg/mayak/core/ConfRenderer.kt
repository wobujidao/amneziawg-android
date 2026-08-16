// Рендер структурированного конфига ядра (ClientConfig) в текст wg-quick .conf, который понимает
// парсер форка (org.amnezia.awg.config.Config.parse). Чистая функция — основной юнит-тестируемый
// кусок core. Приватный ключ подставляется ЛОКАЛЬНО здесь, в ядро он не уходит (ADR-0004).
//
// Почему 3.0, а не 2.0, как значилось здесь раньше: движок клиента — amneziawg-go/v3
// (tunnel/tools/libwg-go/go.mod), боевые выходы держат модуль ядра той же линии. Набор полей
// Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5 перешёл из 2.0 БЕЗ изменений — поэтому правила ниже про них
// остались дословно верными; 3.0 добавил СВЕРХУ защиту заголовка (HeaderProtectionKey — он и
// рендерится ниже), добивку размера пакета и настраиваемые тайминги. Менять пришлось название
// линии, а не логику рендера.
//
// Правила AmneziaWG 3.0 (база сверена с research §2, 2026-06-25):
//  - Если obfuscation задана: пишем Jc/Jmin/Jmax/S1..S4 как числа (на мобиле MVP S3=S4=0),
//    H1..H4 и I1..I5 — ТОЛЬКО если непустые. Пустые I-поля НЕ пишем: пустой `I2=` ломает парсеры
//    клиентов (issue android #56). Itime не пишем нигде (нигде не поддержан).
//  - Если obfuscation == null (релейное плечо без AWG): пишем чистый WireGuard без обфускации.
package org.amnezia.awg.mayak.core

object ConfRenderer {

    /**
     * Собирает .conf для одного плеча.
     * @param cfg конфиг плеча из ответа connect ядра.
     * @param privateKeyBase64 приватный ключ устройства (base64, 44 симв.) — остаётся на устройстве.
     */
    fun render(cfg: ClientConfig, privateKeyBase64: String): String {
        val sb = StringBuilder()
        sb.appendLine("[Interface]")
        sb.appendLine("PrivateKey = $privateKeyBase64")
        // dual-stack (SPEC-0014): при наличии address_v6 кладём v4 и v6 в одну строку Address;
        // форк парсит IPv6 сам. DNS/AllowedIPs (IPv6-резолверы + ::/0) ядро уже складывает в свои поля.
        val address = if (cfg.addressV6.isNotBlank()) "${cfg.address}, ${cfg.addressV6}" else cfg.address
        sb.appendLine("Address = $address")
        if (cfg.dns.isNotBlank()) sb.appendLine("DNS = ${cfg.dns}")
        if (cfg.mtu > 0) sb.appendLine("MTU = ${cfg.mtu}")

        cfg.obfuscation?.let { o ->
            sb.appendLine("Jc = ${o.jc}")
            sb.appendLine("Jmin = ${o.jmin}")
            sb.appendLine("Jmax = ${o.jmax}")
            sb.appendLine("S1 = ${o.s1}")
            sb.appendLine("S2 = ${o.s2}")
            // S3/S4 пишем только если ненулевые: userspace amneziawg-go (v1.x) ОТВЕРГАЛ ключи
            // s3/s4 в UAPI даже со значением 0 (errno -22). Движок v3 их принимает, но привычка
            // не слать нули остаётся верной: 0 = «паддинга нет», поведение эквивалентно.
            // При включённой защите заголовка сервер сам поднимает S1–S4 до ≥12 — тогда S3/S4
            // ненулевые и честно уезжают в конфиг (движок ТРЕБУЕТ ≥12 при заданном ключе; если
            // сервер прислал ключ с S<12, движок откажет на IpcSet — это тоже fail-closed, а не тихо).
            if (o.s3 != 0) sb.appendLine("S3 = ${o.s3}")
            if (o.s4 != 0) sb.appendLine("S4 = ${o.s4}")
            appendIfPresent(sb, "H1", o.h1)
            appendIfPresent(sb, "H2", o.h2)
            appendIfPresent(sb, "H3", o.h3)
            appendIfPresent(sb, "H4", o.h4)
            appendIfPresent(sb, "I1", o.i1)
            appendIfPresent(sb, "I2", o.i2)
            appendIfPresent(sb, "I3", o.i3)
            appendIfPresent(sb, "I4", o.i4)
            appendIfPresent(sb, "I5", o.i5)
            // AWG 3.0: ключ защиты заголовка. Fail-closed НАМЕРЕННО: кривой ключ роняет рендер
            // исключением, а не выбрасывается молча — сервер, задавший ключ, шифрует заголовки,
            // и туннель без ключа «поднялся бы и не работал» (хуже честного отказа подключения:
            // человек видит ошибку сразу, а не гадает, почему нет интернета при значке VPN).
            // Строгий формат заодно отсекает перевод строки в недоверенном значении — иначе им
            // можно было бы дописать в .conf произвольную директиву. Пустая строка = ключа нет,
            // директиву не пишем — байт-в-байт как до появления поля.
            if (o.headerProtectionKey.isNotEmpty()) {
                require(isValidHeaderProtectionKey(o.headerProtectionKey)) {
                    // Само значение в текст НЕ кладём: ключ — секрет, а сообщение уйдёт в лог/диаг-лог.
                    "кривой header_protection_key: ждём 64 hex-символа в нижнем регистре"
                }
                sb.appendLine("HeaderProtectionKey = ${o.headerProtectionKey}")
            }
            // AWG 3.1: случайная длина пакетов рукопожатия. Пишем ТОЛЬКО когда сервер сказал «да» —
            // настройка парная, и включённая в одностороннем порядке она ломает связь совсем.
            // false = директивы нет вовсе, .conf байт-в-байт как раньше.
            if (o.randomTrailers) {
                sb.appendLine("RandomTrailers = true")
            }
        }

        sb.appendLine()
        sb.appendLine("[Peer]")
        sb.appendLine("PublicKey = ${cfg.serverPubkey}")
        sb.appendLine("Endpoint = ${cfg.endpoint}")
        sb.appendLine("AllowedIPs = ${cfg.allowedIps}")
        if (cfg.persistentKeepalive > 0) {
            sb.appendLine("PersistentKeepalive = ${cfg.persistentKeepalive}")
        }
        return sb.toString()
    }

    private fun appendIfPresent(sb: StringBuilder, key: String, value: String) {
        if (value.isNotBlank()) sb.appendLine("$key = $value")
    }

    /**
     * Ключ защиты заголовка годен: ровно 64 hex-символа в НИЖНЕМ регистре (32 байта ChaCha20,
     * ровно как их шлёт ядро). Верхний регистр движок бы съел, но у нас его быть не может —
     * значит это признак порчи по дороге, и честнее отказать, чем «поправить» значение за сервер.
     */
    fun isValidHeaderProtectionKey(key: String): Boolean =
        key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Убирает IPv6 из готового .conf — для тумблера настроек «Не использовать IPv6» (SPEC-0014 T5).
     * Из строк Address/DNS/AllowedIPs выкидывает токены с ':' (IPv6-адреса/подсети), в т.ч. `::/0`.
     * Транспорт (Endpoint) не трогаем — он по IPv4. Строку целиком выкидываем, если после чистки
     * значение пустое (напр. DNS был только IPv6). Так туннель поднимается чисто по IPv4, без `::/0` →
     * IPv6-трафик идёт мимо (как будто фичи нет), значок «IPv6» не зажигается (проба не пройдёт).
     */
    fun stripIpv6(conf: String): String {
        val keys = setOf("Address", "DNS", "AllowedIPs")
        return buildString {
            for (line in conf.lineSequence()) {
                val eq = line.indexOf('=')
                val key = if (eq > 0) line.substring(0, eq).trim() else ""
                if (key in keys) {
                    val v4 = line.substring(eq + 1).split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.contains(':') }
                    if (v4.isEmpty()) continue // всё было IPv6 → строку убираем целиком
                    appendLine("$key = ${v4.joinToString(", ")}")
                } else {
                    appendLine(line)
                }
            }
        }.trimEnd('\n') + "\n"
    }

    /**
     * Запасной канал (SPEC-0039 T5): подменяет `Endpoint` в готовом .conf на локальный шим
     * (`127.0.0.1:<порт>`). Движок AmneziaWG остаётся нетронутым — он просто шлёт датаграммы на
     * loopback, а в WSS их перекладывает [WsUdpShim]. Остальные строки не трогаем: ключи, адреса,
     * маршруты и keepalive у запасного пути ровно те же, что у прямого.
     *
     * Ключ ищем по имени, а не подстрокой: `EndpointFoo` — не наш ключ. Пробелы вокруг `=` бывают
     * любые (конфиг мог прийти не от нашего рендерера — например, last-good с диска).
     */
    fun withEndpoint(conf: String, endpoint: String): String = buildString {
        for (line in conf.lineSequence()) {
            val eq = line.indexOf('=')
            val key = if (eq > 0) line.substring(0, eq).trim() else ""
            if (key == "Endpoint") appendLine("Endpoint = $endpoint") else appendLine(line)
        }
    }.trimEnd('\n') + "\n"

    /**
     * Split-туннель (SPEC-0018 F1): добавляет строку `ExcludedApplications`/`IncludedApplications` в
     * секцию [Interface] готового .conf. Режимы:
     *  - excluded=true (по умолч.): перечисленные приложения идут МИМО туннеля (напр. банки/госуслуги,
     *    которые режут загран-IP); остальной трафик — в туннеле.
     *  - excluded=false: в туннель идут ТОЛЬКО перечисленные, всё остальное — напрямую (обратный режим).
     * Оба ключа известны парсеру форка (BadConfigException.EXCLUDED/INCLUDED_APPLICATIONS) и применяются
     * GoBackend через VpnService.Builder.{exclude,include}Applications. Пустой список — конфиг НЕ трогаем
     * (весь трафик в туннеле — безопасно by default). Вставляем строку сразу после [Interface] — так
     * гарантированно в нужной секции (не в [Peer]). Пустые/дублирующиеся package-имена отбрасываем.
     */
    fun withSplitTunnel(conf: String, packages: List<String>, excluded: Boolean = true): String {
        val pkgs = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (pkgs.isEmpty()) return conf
        val key = if (excluded) "ExcludedApplications" else "IncludedApplications"
        return buildString {
            for (line in conf.lineSequence()) {
                appendLine(line)
                if (line.trim() == "[Interface]") {
                    appendLine("$key = ${pkgs.joinToString(", ")}")
                }
            }
        }.trimEnd('\n') + "\n"
    }
}
