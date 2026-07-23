// DohResolver — резолв имён через DNS-over-HTTPS (Cloudflare), В ОБХОД системного DNS оператора.
// Зачем: на РФ-сотовой (Билайн) оператор подменяет/режет DNS нашего домена mayakvpn.ru, и endpoint по
// FQDN не срабатывает (см. memory mobile-dpi-api-domain-leak). DoH-запрос идёт к 1.1.1.1 ПО IP (без
// системного DNS), шифрованно — оператор не видит имя и не может подменить ответ. При любой ошибке
// возвращаем null → вызывающий откатывается на IP-endpoint из /connect (связь не ломается никогда).
package org.amnezia.awg.mayak

import java.net.URL
import javax.net.ssl.HttpsURLConnection

object DohResolver {
    private val IPV4 = Regex("\"data\"\\s*:\\s*\"(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\"")

    // DoH-эндпоинты ПО IP (без системного DNS), порт 443 (прячется в HTTPS — оператору не вырезать).
    // Несколько провайдеров для стойкости: если один придушили/заблокировали — берём следующий. У всех
    // сертификат покрывает IP (IP-SAN), поэтому работают по голому адресу. Все на 443 (не 853, который РФ режет).
    private val DOH = arrayOf(
        "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query", // Cloudflare
        "https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query", // Google
        "https://9.9.9.9/dns-query",                              // Quad9
    )

    /** Резолвит hostname в первый IPv4 через DoH (мультипровайдер, по IP, мимо системного DNS). null при ошибке. */
    fun resolve(hostname: String): String? {
        for (doh in DOH) {
            try {
                val url = URL("$doh?name=$hostname&type=A")
                val conn = (url.openConnection() as HttpsURLConnection).apply {
                    setRequestProperty("accept", "application/dns-json")
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode != 200) { conn.disconnect(); continue }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val ip = IPV4.find(body)?.groupValues?.get(1)
                if (ip != null) return ip
            } catch (_: Exception) { /* пробуем следующий DoH / откат на IP */ }
        }
        return null
    }

    /**
     * host → IPv4 через DoH; если host УЖЕ IPv4 (резолвить нечего) или DoH не вышел — возвращает host как есть.
     * Для ICMP-пробы RTT (MayakPing): /system/bin/ping — ВНЕШНИЙ процесс, резолвит имя СИСТЕМНЫМ резолвером
     * оператора (мимо app-DoH), поэтому проба ПО ИМЕНИ падает на подменённом/зарезанном DNS (напр. свежая нода
     * → «...» в списке). Резолвим домен здесь через DoH (мимо оператора) и пингуем УЖЕ IP — как endpoint туннеля
     * (resolveEndpoint). Фоллбэк на сырой host: caller (MayakPing) всё равно попробует системный резолв.
     */
    fun resolveHost(host: String): String {
        if (host.isBlank()) return host
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return host // уже IPv4 — DoH не нужен
        return resolve(host) ?: host
    }

    /** "host:port" → "ip:port" через DoH; если host уже IP или DoH недоступен — возвращает как есть (фоллбэк). */
    fun resolveEndpoint(hostPort: String): String {
        val i = hostPort.lastIndexOf(':')
        if (i <= 0) return hostPort
        val host = hostPort.substring(0, i)
        val port = hostPort.substring(i + 1)
        // уже IPv4 — резолвить нечего
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return hostPort
        val ip = resolve(host) ?: return hostPort // DoH не вышел → отдаём как было (caller имеет IP-фоллбэк)
        return "$ip:$port"
    }
}
