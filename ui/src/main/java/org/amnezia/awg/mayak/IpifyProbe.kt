// Android-реализация EgressProbe (:core): сквозная проба «реально ли вышли в интернет» — тянем
// внешний IP через поднятый туннель. Не факт хендшейка, а настоящий egress (ADR-0012): при блоке
// по AS хендшейк живёт, а трафика нет — здесь это и ловим (externalIp() вернёт null).
package org.amnezia.awg.mayak

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
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                JSONObject(body).optString("ip").takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
