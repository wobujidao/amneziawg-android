// Замер задержки до сервера направления. РАНЬШЕ был ICMP через InetAddress.isReachable — на Android
// ненадёжно (непривилегированный ICMP часто недоступен → молчит/подменяется TCP-порт-7) + сокет не был
// защищён, поэтому при подключении пинг мерил путь ЧЕРЕЗ туннель и все направления показывали ~одинаково.
// ТЕПЕРЬ (директива владельца 2026-07-19): TCP-connect RTT к :22 (SSH открыт на всех наших нодах; голый
// connect без авторизации — fail2ban не триггерит), сокет ЗАЩИЩЁН (GoBackend.protectSocket → VpnService.protect)
// → замер идёт МИМО туннеля = честная близость сервера, подключён ты или нет. Первую пробу (прогрев радио/
// стека — всегда завышена) отбрасываем, усредняем следующие 5.
package org.amnezia.awg.mayak
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.GoBackend
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
object MayakPing {
    private const val PING_PORT = 22   // TCP-порт замера (SSH открыт на ядре и всех нодах — UFW ALLOW anywhere)
    private const val TIMEOUT_MS = 1500
    private const val AVG_PROBES = 5   // усредняем столько замеров ПОСЛЕ прогревочного (директива владельца)

    /**
     * Средний RTT (мс) TCP-connect до host:port или null, если сервер недоступен. Первая проба —
     * прогревочная (и проверка доступности): её RTT отбрасываем (всегда завышен), дальше усредняем [AVG_PROBES].
     * Блокирующие сетевые вызовы → на IO.
     */
    suspend fun ping(host: String, port: Int = PING_PORT, timeoutMs: Int = TIMEOUT_MS): Int? = withContext(Dispatchers.IO) {
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext null // DNS вне таймингов
        // Прогрев + проверка доступности: если даже он не прошёл — сервер недоступен, дальше не мучаем (без 5 таймаутов).
        val warmup = tcpConnectRtt(addr, port, timeoutMs) ?: return@withContext null
        val samples = ArrayList<Int>(AVG_PROBES)
        repeat(AVG_PROBES) { tcpConnectRtt(addr, port, timeoutMs)?.let { samples.add(it) } }
        if (samples.isEmpty()) warmup // все замеры после прогрева отвалились — берём прогрев (лучше, чем прочерк)
        else (samples.sum().toDouble() / samples.size).toInt()
    }

    /** Одна проба: время установки TCP-соединения (мс). Сокет защищаем от туннеля (protect) → замер напрямую. */
    private fun tcpConnectRtt(addr: InetAddress, port: Int, timeoutMs: Int): Int? {
        val sock = Socket()
        return try {
            GoBackend.protectSocket(sock) // мимо туннеля (best-effort; VPN выкл. → no-op, соединение и так прямое)
            val start = SystemClock.elapsedRealtime()
            sock.connect(InetSocketAddress(addr, port), timeoutMs)
            (SystemClock.elapsedRealtime() - start).toInt()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Хост из endpoint "IP:port" (или "host:port") — для пинга сервера. null, если пусто/не распарсить. */
    fun hostOf(endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return null
        val h = endpoint.substringBeforeLast(':', endpoint).trim()
        return h.ifBlank { null }
    }
}
