// Замер задержки до сервера направления — НАСТОЯЩИЙ ICMP через СИСТЕМНЫЙ ping (как `ping` на ПК).
// История: (1) InetAddress.isReachable — на Android НЕ настоящий ICMP, часто мусор (давал ~211мс там, где
// реально 6мс); (2) TCP-connect к :22 — некоторые сети режут/шейпят :22 → завышало. Директива владельца
// 2026-07-19: делать именно ICMP. Берём /system/bin/ping — реальный ICMP-эхо, совпадает с ping на ПК.
// 6 проб (ping -c 6), ПЕРВУЮ (прогрев радио/стека — завышена) отбрасываем, усредняем остальные 5.
//
// ⚠️ Ограничение: подпроцесс ping нельзя увести мимо туннеля (VpnService.protect работает только для сокетов
// в нашем процессе, не для внешнего процесса). Поэтому: ОТКЛЮЧЁН → замер прямой и точный (реальная близость
// сервера); ПОДКЛЮЧЁН → ICMP идёт через туннель (пинги других стран завышены). Точный замер под туннелем
// потребовал бы нативного ICMP-сокета с protect — отдельная задача.
package org.amnezia.awg.mayak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
object MayakPing {
    private const val PROBES = 6                 // ping -c 6: 6 эхо; первое (прогрев) отбрасываем → усредняем 5
    private const val PER_PING_TIMEOUT_S = 1     // ping -W: таймаут одного эхо, сек
    private val TIME_RE = Regex("""time[=<]\s*([0-9.]+)""") // "... time=6.12 ms" / "time<1 ms"

    /** Средний ICMP-RTT (мс) до host или null (недоступен). Первую пробу-прогрев отбрасываем. Блокирующий → IO. */
    suspend fun ping(host: String): Int? = withContext(Dispatchers.IO) {
        val proc = runCatching {
            ProcessBuilder("/system/bin/ping", "-c", PROBES.toString(), "-W", PER_PING_TIMEOUT_S.toString(), host)
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return@withContext null
        val out = try {
            val text = proc.inputStream.bufferedReader().readText()
            // ждём завершения с запасом: 6 эхо × (таймаут + ~1с интервал) + 2с
            proc.waitFor(PROBES.toLong() * (PER_PING_TIMEOUT_S + 1) + 2, TimeUnit.SECONDS)
            text
        } catch (e: Exception) {
            runCatching { proc.destroy() }
            return@withContext null
        } finally {
            runCatching { proc.destroy() }
        }
        val times = TIME_RE.findAll(out).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
        if (times.isEmpty()) return@withContext null // сервер не ответил на ICMP / ping недоступен
        val measured = if (times.size > 1) times.drop(1) else times // прогревочный первый — за борт
        (measured.sum() / measured.size).toInt()
    }

    /** Хост из endpoint "IP:port" (или "host:port"). null, если пусто/не распарсить. */
    fun hostOf(endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return null
        val h = endpoint.substringBeforeLast(':', endpoint).trim()
        return h.ifBlank { null }
    }
}
