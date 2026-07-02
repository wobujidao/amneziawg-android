// Замер задержки (ICMP) до сервера ТЕКУЩЕГО подключения — показываем пинг на главном экране, пока
// подключены (директива владельца 2026-07-02: пинг только для текущего подключения). Наши серверы
// отвечают на ICMP (проверено); WG/UDP-порт без хендшейка молчит. InetAddress.isReachable на Android
// использует непривилегированный ICMP, когда доступен.
package org.amnezia.awg.mayak

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

object MayakPing {
    private const val TIMEOUT_MS = 1500
    private const val TRIES = 2 // минимум из нескольких проб (сглаживаем джиттер)

    /** RTT в мс до host, или null если недоступен/таймаут. Блокирующий сетевой вызов → на IO. */
    suspend fun ping(host: String, timeoutMs: Int = TIMEOUT_MS): Int? = withContext(Dispatchers.IO) {
        var best: Int? = null
        repeat(TRIES) {
            val rtt = runCatching {
                val addr = InetAddress.getByName(host)
                val start = SystemClock.elapsedRealtime()
                if (addr.isReachable(timeoutMs)) (SystemClock.elapsedRealtime() - start).toInt() else null
            }.getOrNull()
            if (rtt != null && (best == null || rtt < best!!)) best = rtt
        }
        best
    }

    /** Хост из endpoint "IP:port" (или "host:port") — для пинга сервера. null, если пусто/не распарсить. */
    fun hostOf(endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return null
        val h = endpoint.substringBeforeLast(':', endpoint).trim()
        return h.ifBlank { null }
    }
}
