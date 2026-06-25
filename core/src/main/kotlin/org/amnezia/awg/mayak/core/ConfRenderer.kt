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
        sb.appendLine("Address = ${cfg.address}")
        if (cfg.dns.isNotBlank()) sb.appendLine("DNS = ${cfg.dns}")
        if (cfg.mtu > 0) sb.appendLine("MTU = ${cfg.mtu}")

        cfg.obfuscation?.let { o ->
            sb.appendLine("Jc = ${o.jc}")
            sb.appendLine("Jmin = ${o.jmin}")
            sb.appendLine("Jmax = ${o.jmax}")
            sb.appendLine("S1 = ${o.s1}")
            sb.appendLine("S2 = ${o.s2}")
            sb.appendLine("S3 = ${o.s3}")
            sb.appendLine("S4 = ${o.s4}")
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
}
