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
    private val timeoutMs: Int = 8_000,
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
            Log.i(PROBE_TAG, "проба $url ПРОВАЛ: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private companion object {
        // TODO(prod): ВРЕМЕННАЯ диагностика (0.3.15) — СНЯТЬ или спрятать за скрытый тумблер перед прод-
        // релизом (см. docs/APP-BACKLOG.md «диагностика IPv6-пробы»). Держим, пока ловим баг значка IPv6.
        // Тег содержит «AmneziaWG» → DiagCollector.logcat включает эти строки в присланный лог.
        const val PROBE_TAG = "AmneziaWG/mayak-probe"
    }
}
