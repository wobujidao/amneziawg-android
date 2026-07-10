// Рендер структурированного конфига ядра (ClientConfig) в текст wg-quick .conf, который понимает
// парсер форка (org.amnezia.awg.config.Config.parse). Чистая функция — основной юнит-тестируемый
// кусок core. Приватный ключ подставляется ЛОКАЛЬНО здесь, в ядро он не уходит (ADR-0004).
//
// Правила AmneziaWG 2.0 (сверено с research §2, 2026-06-25):
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
            // S3/S4 пишем только если ненулевые: userspace amneziawg-go (v1.x) ОТВЕРГАЕТ ключи
            // s3/s4 в UAPI даже со значением 0 (errno -22). На мобиле MVP S3=S4=0 → просто не шлём
            // их (паддинга нет — поведение эквивалентно). Когда S3/S4 заработают в форке (T9) — пишем.
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
     * Port-hopping (SPEC-0029): если у конфига задан диапазон [portHopLo, portHopHi] (lo>0, hi>=lo),
     * возвращает КОПИЮ с dst-портом endpoint (и endpointFqdn), заменённым на выбранный из диапазона.
     * Сервер DNAT'ит весь диапазон → любой порт валиден; так у соединения нет единственного IP:port для
     * блока. Диапазон не задан (0/0, старые выдачи) → конфиг БЕЗ изменений (обратная совместимость).
     * Чистая функция: выбор порта инъектируется (`pick`) — по умолчанию равномерно случайный.
     * Замечание: это выбор порта НА КОННЕКТ (не смена порта посреди сессии — та требует рантайм-мутации
     * туннеля, отдельная задача). Уже даёт разброс IP:port по переподключениям.
     */
    fun applyPortHop(
        cfg: ClientConfig,
        pick: (Int, Int) -> Int = { lo, hi -> lo + kotlin.random.Random.nextInt(hi - lo + 1) },
    ): ClientConfig {
        if (cfg.portHopLo <= 0 || cfg.portHopHi < cfg.portHopLo) return cfg
        val port = pick(cfg.portHopLo, cfg.portHopHi)
        return cfg.copy(
            endpoint = replaceEndpointPort(cfg.endpoint, port),
            endpointFqdn = if (cfg.endpointFqdn.isNotBlank()) replaceEndpointPort(cfg.endpointFqdn, port) else cfg.endpointFqdn,
        )
    }

    /**
     * Заменяет порт в endpoint вида `host:port`. Поддерживает IPv4/hostname (`1.2.3.4:51820`) и
     * IPv6 в скобках (`[2001:db8::1]:51820`). Если порт не распознан — строку не трогаем (fail-safe).
     */
    fun replaceEndpointPort(endpoint: String, port: Int): String {
        val colon = endpoint.lastIndexOf(':')
        if (colon <= 0 || colon == endpoint.length - 1) return endpoint
        // порт — только цифры после последнего ':' (у IPv6 в скобках последний ':' идёт после ']')
        val portPart = endpoint.substring(colon + 1)
        if (portPart.toIntOrNull() == null) return endpoint
        return endpoint.substring(0, colon + 1) + port
    }

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
