// DohResolver — резолв имён через DNS-over-HTTPS, В ОБХОД системного DNS оператора.
//
// Зачем: на РФ-сотовой оператор подменяет/режет DNS нашего домена, и endpoint по FQDN не срабатывает
// (memory mobile-dpi-api-domain-leak). DoH-запрос идёт К IP резолвера (без системного DNS), внутри
// HTTPS — оператор не видит имя и не может подменить ответ. Любая ошибка → null, вызывающий
// откатывается на IP из /connect: связь не ломается никогда.
//
// ⚠️ Почему НЕ JSON-API (?name=&type=), как было до 2026-07-25: JSON-расширение поддерживают ТОЛЬКО
// Cloudflare и Google — то есть ровно те два резолвера, которые в РФ и блокируют (DoH/DoT Google и
// Cloudflare режут TCP-RST'ом после ClientHello). Quad9 и AdGuard, которые как раз НЕ блокируют,
// на JSON-запрос отвечают HTTP 400 — они говорят только на стандартном RFC 8484 (wire-format).
// Проверено вживую: 9.9.9.9 и 94.140.14.14 на JSON → 400, на wire-format → 200 с корректным A.
// Итог: список «нескольких провайдеров для стойкости» на деле состоял из двух блокируемых, а
// остальные были балластом. Поэтому здесь — RFC 8484, на нём говорят ВСЕ.
package org.amnezia.awg.mayak.core

import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object DohResolver {
    /** DoH-эндпоинты ПО IP (без системного DNS), порт 443 — прячется в общем HTTPS, не вырезать по порту.
     *  Порядок: сперва те, что в РФ НЕ блокируют (Quad9, AdGuard), затем Cloudflare/Google как резерв
     *  (они блокируются в РФ, но живы в остальном мире). У всех сертификат покрывает IP (IP-SAN). */
    private val DOH = arrayOf(
        "https://9.9.9.9/dns-query", "https://149.112.112.112/dns-query", // Quad9
        "https://94.140.14.14/dns-query", "https://94.140.15.15/dns-query", // AdGuard
        "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query", // Cloudflare
        "https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query", // Google
    )

    private const val PER_TRY_MS = 3_000
    private const val TOTAL_MS = 6_000L
    private const val CACHE_TTL_MS = 60_000L

    /** Доп. DoH-эндпоинты из ПОДПИСАННОГО delivery-документа (F-T8): «https://<bootstrap-IP><путь>»,
     *  собирает Delivery.dohEndpoints, кладёт MayakDelivery после успешной проверки подписи.
     *  Пробуются ВМЕСТЕ со вшитыми (та же параллельная гонка); вшитый список не заменяют — документ
     *  может протухнуть, а связь ломаться не должна никогда. */
    @Volatile private var extra: Array<String> = emptyArray()

    fun setExtraEndpoints(endpoints: List<String>) {
        // только https и без дублей со вшитыми; мусор молча отбрасываем — источник уже проверен подписью,
        // но формат мог собрать не тот слой
        extra = endpoints.filter { it.startsWith("https://") && it !in DOH }.distinct().toTypedArray()
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mayak-doh").apply { isDaemon = true } // демоны: не держат процесс живым
    }

    private class Entry(val ip: String, val at: Long)

    private val cache = HashMap<String, Entry>()

    /** Резолвит hostname в первый IPv4 через DoH. null — ни один резолвер не ответил.
     *  Резолверы опрашиваются ПАРАЛЛЕЛЬНО, побеждает первый ответивший: под DPI «плохие» эндпоинты не
     *  отвечают отказом, а МОЛЧАТ (пакеты дропают) — последовательный перебор упирался бы в таймаут на
     *  каждом и складывался в десятки секунд ожидания на экране. */
    fun resolve(hostname: String): String? {
        synchronized(cache) {
            val e = cache[hostname]
            if (e != null && System.currentTimeMillis() - e.at < CACHE_TTL_MS) return e.ip
        }
        val query = buildQuery(hostname) ?: return null
        val cs = ExecutorCompletionService<String?>(pool)
        val endpoints = DOH + extra // вшитые + из подписанного delivery-документа
        val tasks = ArrayList<Future<String?>>(endpoints.size)
        for (url in endpoints) tasks.add(cs.submit { queryOne(url, query) })
        val deadline = System.nanoTime() + TOTAL_MS * 1_000_000
        try {
            var pending = tasks.size
            while (pending > 0) {
                val leftMs = (deadline - System.nanoTime()) / 1_000_000
                if (leftMs <= 0) break
                val done = cs.poll(leftMs, TimeUnit.MILLISECONDS) ?: break
                pending--
                val ip = try {
                    done.get()
                } catch (_: Exception) {
                    null
                }
                if (ip != null) {
                    synchronized(cache) { cache[hostname] = Entry(ip, System.currentTimeMillis()) }
                    return ip
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            for (t in tasks) t.cancel(true) // отстающих не ждём
        }
        return null
    }

    /**
     * host → IPv4 через DoH; если host УЖЕ IPv4 (резолвить нечего) или DoH не вышел — возвращает host как есть.
     * Для ICMP-пробы RTT (MayakPing): /system/bin/ping — ВНЕШНИЙ процесс, резолвит имя СИСТЕМНЫМ резолвером
     * оператора (мимо app-DoH), поэтому проба ПО ИМЕНИ падает на подменённом/зарезанном DNS. Резолвим домен
     * здесь и пингуем УЖЕ IP — как endpoint туннеля (resolveEndpoint).
     */
    fun resolveHost(host: String): String {
        if (host.isBlank()) return host
        if (isIPv4(host)) return host
        return resolve(host) ?: host
    }

    /** "host:port" → "ip:port" через DoH; host уже IP или DoH недоступен → возвращает как есть (фоллбэк). */
    fun resolveEndpoint(hostPort: String): String {
        val i = hostPort.lastIndexOf(':')
        if (i <= 0) return hostPort
        val host = hostPort.substring(0, i)
        if (isIPv4(host)) return hostPort
        val ip = resolve(host) ?: return hostPort // caller имеет IP-фоллбэк из /connect
        return "$ip:${hostPort.substring(i + 1)}"
    }

    private fun isIPv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
    }

    /** Один резолвер: GET /dns-query?dns=<base64url(запрос)> с Accept: application/dns-message (RFC 8484). */
    private fun queryOne(endpoint: String, query: ByteArray): String? = try {
        val url = URL(endpoint + "?dns=" + base64Url(query))
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            setRequestProperty("accept", "application/dns-message")
            connectTimeout = PER_TRY_MS
            readTimeout = PER_TRY_MS
        }
        try {
            if (conn.responseCode != 200) null else firstARecord(conn.inputStream.readBytes())
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null // резолвер молчит/зарезан/TLS не встал — соседи в гонке ответят
    }

    // --- RFC 1035 / RFC 8484: сборка запроса и разбор ответа ---

    /** Стандартный DNS-запрос A-записи: заголовок (id=0, RD=1) + QNAME + QTYPE=A + QCLASS=IN.
     *  id=0 намеренно: по RFC 8484 для GET он обязан быть нулевым (иначе ломается HTTP-кэширование). */
    internal fun buildQuery(hostname: String): ByteArray? {
        val name = hostname.trim().trim('.')
        if (name.isEmpty() || name.length > 253) return null
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id=0, flags=0x0100 (RD), QDCOUNT=1
        for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            if (b.isEmpty() || b.size > 63) return null
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        out.write(byteArrayOf(0, 1, 0, 1)) // QTYPE=A(1), QCLASS=IN(1)
        return out.toByteArray()
    }

    /** Первая A-запись из ответа (CNAME-цепочки пропускаем, идём дальше по секции ответов). null — нет A. */
    internal fun firstARecord(resp: ByteArray): String? {
        if (resp.size < 12) return null
        val qd = u16(resp, 4)
        val an = u16(resp, 6)
        if (an == 0) return null
        var p = 12
        repeat(qd) {
            p = skipName(resp, p) ?: return null
            p += 4 // QTYPE + QCLASS
            if (p > resp.size) return null
        }
        repeat(an) {
            p = skipName(resp, p) ?: return null
            if (p + 10 > resp.size) return null
            val type = u16(resp, p)
            val rdLen = u16(resp, p + 8)
            p += 10
            if (p + rdLen > resp.size) return null
            if (type == 1 && rdLen == 4) {
                return "${resp[p].toInt() and 0xff}.${resp[p + 1].toInt() and 0xff}." +
                    "${resp[p + 2].toInt() and 0xff}.${resp[p + 3].toInt() and 0xff}"
            }
            p += rdLen
        }
        return null
    }

    /** Сдвигает позицию за DNS-имя: последовательность меток до 0x00 либо указатель сжатия (0xC0…). */
    private fun skipName(b: ByteArray, start: Int): Int? {
        var p = start
        while (true) {
            if (p >= b.size) return null
            val len = b[p].toInt() and 0xff
            when {
                len == 0 -> return p + 1
                len and 0xC0 == 0xC0 -> return if (p + 2 <= b.size) p + 2 else null // указатель — 2 байта
                else -> p += 1 + len
            }
        }
    }

    private fun u16(b: ByteArray, i: Int): Int =
        if (i + 2 > b.size) 0 else ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    /** base64url без padding (RFC 8484 требует именно такой параметр dns=). */
    private fun base64Url(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or
                (data[i + 2].toInt() and 0xff)
            sb.append(alphabet[n ushr 18 and 63]).append(alphabet[n ushr 12 and 63])
                .append(alphabet[n ushr 6 and 63]).append(alphabet[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                sb.append(alphabet[n ushr 18 and 63]).append(alphabet[n ushr 12 and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                sb.append(alphabet[n ushr 18 and 63]).append(alphabet[n ushr 12 and 63])
                    .append(alphabet[n ushr 6 and 63])
            }
        }
        return sb.toString()
    }
}
