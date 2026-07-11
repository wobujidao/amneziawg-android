package org.amnezia.awg.mayak

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Кэш RTT «телефон→сервер» по направлению (SPEC-0031 «быстрейший вверху»). Процесс-скоупный (переживает
 * пересоздание Activity при смене темы). Замер делается ТОЛЬКО по открытию экрана со списком, результат
 * кэшируется на [TTL_MS] → серверы НЕ пингуются постоянно/таймером (constraint владельца: куча клиентов
 * не должна спамить серверы пингами). Провал пинга (null) тоже кэшируется, чтобы не долбить сеть повторно.
 */
object MayakPingCache {
    private const val TTL_MS = 180_000L // 3 минуты

    // dirId → (rttMs или null при провале ICMP, момент замера в elapsedRealtime)
    private val cache = ConcurrentHashMap<Long, Pair<Int?, Long>>()

    private fun freshEntry(id: Long): Pair<Int?, Long>? =
        cache[id]?.takeIf { SystemClock.elapsedRealtime() - it.second < TTL_MS }

    /** Есть ли свежий (в пределах TTL) замер — если да, перепинговывать не нужно. */
    fun isFresh(id: Long): Boolean = freshEntry(id) != null

    /** Свежий RTT в мс, или null (не мерян/провал/протух). */
    fun rtt(id: Long): Int? = freshEntry(id)?.first

    fun put(id: Long, rttMs: Int?) {
        cache[id] = rttMs to SystemClock.elapsedRealtime()
    }

    /** Сброс (смена пользователя/логаут — чужие замеры неактуальны). */
    fun clear() = cache.clear()
}
