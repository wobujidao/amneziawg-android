// Замер задержки (ICMP) до серверов направлений — для показа пинга в списке и сортировки по нему
// (директива владельца 2026-07-02). Наши экзиты отвечают на ICMP (проверено), а WG/UDP-порт без
// хендшейка молчит — поэтому меряем именно ICMP-эхо. InetAddress.isReachable на Android использует
// непривилегированный ICMP, когда доступен (иначе TCP-порт 7 → скорее timeout). Меряем на экране
// выбора (ДО подключения) = честная задержка устройство→сервер напрямую (не через туннель).
package org.amnezia.awg.mayak

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

object MayakPing {
    private const val TIMEOUT_MS = 1500
    private const val TRIES = 2 // берём минимум из нескольких проб (сглаживаем джиттер)

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
}
