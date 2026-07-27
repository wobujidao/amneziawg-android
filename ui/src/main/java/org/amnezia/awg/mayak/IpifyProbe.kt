// Android-реализация EgressProbe (:core): сквозная проба «реально ли вышли в интернет» — тянем
// внешний IP через поднятый туннель. Не факт хендшейка, а настоящий egress (ADR-0012): при блоке
// по AS хендшейк живёт, а трафика нет — здесь это и ловим (externalIp() вернёт null).
package org.amnezia.awg.mayak

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.EgressProbe
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IpifyProbe(
    private val url: String = "https://api.ipify.org?format=json",
    // перф-2026-07-07: 8с→4с — быстрее ловим появление пира (синхронизация нод теперь 5с), ложные фейлы
    // страхуются ретраями (probeWithRetry). 4с хватает на HTTP-GET через живой туннель даже на сотовой.
    private val timeoutMs: Int = 4_000,
) : EgressProbe {
    override suspend fun externalIp(): String? = withContext(Dispatchers.IO) {
        // Диагностика (тег содержит «AmneziaWG» → попадает в диаг-лог DiagCollector): при провале пробы
        // пишем ПРИЧИНУ (UnknownHostException=не резолвится DNS; SocketTimeout=таймаут; ConnectException/
        // NoRouteToHost=нет маршрута). Нужно, чтобы понять, почему значок IPv6 не горит на конкретной сети.
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                val code = conn.responseCode
                if (code != 200) {
                    Log.i(PROBE_TAG, "проба $url: HTTP $code (не 200)")
                    return@withContext null
                }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                JSONObject(body).optString("ip").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Отдельно резолвим хост: если вернулись адреса (в т.ч. IPv6) — DNS ОК, значит спотыкается
            // маршрут/выход; если резолв провалился — проблема в DNS (не отдал AAAA/недоступен через туннель).
            val host = runCatching { URL(url).host }.getOrDefault(url)
            val resolved = resolveForLog(host)
            Log.i(PROBE_TAG, "проба $url ПРОВАЛ: ${e.javaClass.simpleName}: ${e.message}; DNS($host)=$resolved")
            null
        }
    }

    /**
     * Резолв ХОСТА РАДИ СТРОЧКИ В ЛОГЕ — с жёстким потолком в секунду.
     *
     * Раньше здесь стоял голый `getAllByName`, и на мёртвом туннеле он сам ждал системный резолвер
     * (тот уходит В туннель и перебирает серверы) — диагностика добавляла к КАЖДОЙ провалившейся
     * пробе ещё десятки секунд. Разбор 2026-07-27: один вызов externalIp() занимал ~37 с, из них
     * бо́льшая часть — вот это. Диагностика не имеет права быть дороже того, что диагностирует.
     *
     * Поток демонский и брошенный: если резолвер ответит позже, результат просто никому не нужен.
     */
    private fun resolveForLog(host: String): String {
        val out = java.util.concurrent.atomic.AtomicReference("резолв не ответил за ${RESOLVE_LOG_MS}мс")
        val t = Thread {
            out.set(
                runCatching {
                    java.net.InetAddress.getAllByName(host).joinToString(",") { it.hostAddress ?: "?" }
                }.getOrElse { "резолв провал: ${it.javaClass.simpleName}" },
            )
        }
        t.isDaemon = true
        t.start()
        t.join(RESOLVE_LOG_MS)
        return out.get()
    }

    private companion object {
        /** Потолок диагностического резолва: строка в логе не стоит секунды ожидания человека. */
        const val RESOLVE_LOG_MS = 1_000L

        // Диагностика egress-пробы ОСТАВЛЕНА НАМЕРЕННО (решение владельца 2026-07-07): ~неск. строк/подключение,
        // без ПДн, полезно для дебага сети. Опционально позже — за скрытый тумблер «Диагностика». (docs/APP-BACKLOG.md)
        // Тег содержит «AmneziaWG» → DiagCollector.logcat включает эти строки в присланный лог.
        const val PROBE_TAG = "AmneziaWG/mayak-probe"
    }
}
