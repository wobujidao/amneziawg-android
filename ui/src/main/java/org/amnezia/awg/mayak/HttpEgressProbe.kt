// Android-реализация EgressProbe (:core): сквозная проба «реально ли вышли в интернет» — тянем
// внешний IP через поднятый туннель. Не факт хендшейка, а настоящий egress (ADR-0012): при блоке
// по AS хендшейк живёт, а трафика нет — здесь это и ловим (externalIp() вернёт null).
//
// 📛 Класс назывался IpifyProbe и был приколочен к api.ipify.org. Переименован 2026-08-20: участником
// пробы теперь бывает и НАШ /v1/egress-check (см. MayakEgressProbes), а имя, называющее чужой сервис,
// врало бы про то, куда ходит приложение. Разбор здесь один и тот же — оба ответа это {"ip":"…"}.
//
// 🔴 Соединение НАМЕРЕННО обычное (URL.openConnection), БЕЗ обхода туннеля. У запросов к ядру такой
// обход есть (OutsideTunnel — чтобы поддержка и диагностика работали при мёртвом туннеле), и соблазн
// переиспользовать MayakBackend для /v1/egress-check велик. Делать этого НЕЛЬЗЯ: обход вернул бы
// собственный адрес телефона, приложение сочло бы выход подтверждённым и показало «Защищено» при
// мёртвом туннеле — ровно ложь версии 0.3.x, ради которой вся эта проба и существует.
package org.amnezia.awg.mayak

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.EgressProbe
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpEgressProbe(
    private val url: String = "https://api.ipify.org?format=json",
    // перф-2026-07-07: 8с→4с — быстрее ловим появление пира (синхронизация нод теперь 5с), ложные фейлы
    // страхуются ретраями (probeWithRetry). 4с хватает на HTTP-GET через живой туннель даже на сотовой.
    private val timeoutMs: Int = 4_000,
    /** Короткое имя для лога («ядро», «ipify»): в строке отчёта важно, КТО из гонки ответил. */
    private val label: String = "",
) : EgressProbe {
    override suspend fun externalIp(): String? = withContext(Dispatchers.IO) {
        // Диагностика (тег содержит «AmneziaWG» → попадает в диаг-лог DiagCollector): при провале пробы
        // пишем ПРИЧИНУ (UnknownHostException=не резолвится DNS; SocketTimeout=таймаут; ConnectException/
        // NoRouteToHost=нет маршрута). Нужно, чтобы понять, почему значок IPv6 не горит на конкретной сети.
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // 🔴 ТОЛЬКО СВЕЖЕЕ СОЕДИНЕНИЕ. Сокет из общего пула помнит маршрут, по которому его
            // открыли: до туннеля — значит мимо туннеля. Такой сокет либо мёртв (запрос уходит в
            // никуда и умирает по таймауту — 4 с из пяти у владельца, 21-08), либо жив в обход и
            // вернул бы адрес самого телефона, то есть подтвердил бы выход, которого нет.
            val conn = FreshConnection.open(URL(url))
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                val code = conn.responseCode
                if (code != 200) {
                    // 429 сюда попадает штатно: у нашего /v1/egress-check лимит по IP, а через туннель
                    // все люди одной ноды приходят под ОДНИМ адресом (адресом выхода). Для гонки это
                    // просто «этот участник не ответил» — победит второй.
                    Log.i(PROBE_TAG, "проба $name: HTTP $code (не 200) за ${took(startedAt)}мс")
                    return@withContext null
                }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val ip = JSONObject(body).optString("ip").takeIf { it.isNotBlank() }
                // 📏 Цифра, по которой можно действовать (урок 20-08): раньше время УСПЕШНОЙ пробы не
                // мерил никто, и «половина подключения уходит не на туннель» пришлось раскапывать по
                // косвенным признакам. Теперь в каждом логе видно, кто из гонки победил и за сколько.
                Log.i(PROBE_TAG, "проба $name OK за ${took(startedAt)}мс: $ip")
                ip
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Отдельно резолвим хост: если вернулись адреса (в т.ч. IPv6) — DNS ОК, значит спотыкается
            // маршрут/выход; если резолв провалился — проблема в DNS (не отдал AAAA/недоступен через туннель).
            val host = runCatching { URL(url).host }.getOrDefault(url)
            val resolved = resolveForLog(host)
            Log.i(PROBE_TAG, "проба $name ПРОВАЛ за ${took(startedAt)}мс: ${e.javaClass.simpleName}: ${e.message}; DNS($host)=$resolved")
            null
        }
    }

    /** Как эта проба зовётся в логе: метка, если задана (в гонке их несколько), иначе сам адрес. */
    private val name: String get() = if (label.isBlank()) url else "$label ($url)"

    private fun took(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

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
        // Сырой IP резолвить незачем — участник гонки «наше ядро по IP» ходит без DNS осознанно,
        // и строчка «DNS(2.26.77.243)=2.26.77.243» только мешала бы читать лог.
        if (host.none { it.isLetter() }) return "адрес, резолв не нужен"
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
