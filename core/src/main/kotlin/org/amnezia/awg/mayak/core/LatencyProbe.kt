// Тихий замер близости выходов — время установления TCP-соединения до легенды узла (host:443).
//
// История и ПОЧЕМУ именно так (директива владельца 15-08). Прежний замер (ICMP через /system/bin/ping,
// снят коммитом 038616ee) шёл при ЛЮБОМ состоянии сети — в том числе при ПОДНЯТОМ туннеле, когда эхо
// идёт через выбранную страну: «пинг до России» мерялся через Польшу и показывал 129 мс вместо
// реальных единиц. Числа были бессмысленны ровно тогда, когда человек на них смотрел. Владелец:
// «эти замеры должно делать приложение при условии что VPN не включен не только наш но и другой».
//
// Отсюда три правила этого модуля:
//  • МЕРИМ ТОЛЬКО БЕЗ VPN — вообще без какого-либо (наш или чужой): признак берётся у системы
//    (TRANSPORT_VPN активной сети, MayakNet.vpnActive в :ui) и проверяется ПЕРЕД КАЖДОЙ попыткой,
//    а не один раз на старте замера — VPN может подняться посреди серии;
//  • ЧТО мерим: ICMP из приложения недоступен без root, наши узлы на UDP молчат по построению, зато
//    у каждого узла есть легенда на TCP :443 (Reality-фронт отвечает всем) — меряем время
//    установления TCP-соединения до pool_host:443. Три попытки, медиана, таймаут попытки 1,5 с.
//    DNS-резолв — ЗАРАНЕЕ, вне секундомера (иначе в «RTT» въезжает время резолва);
//  • ЦИФР НА ЭКРАНЕ НЕТ — владелец убрал их сознательно. Замер живёт молча и кормит только порядок
//    «Авто» (orderForAuto ниже): свежие замеры есть → быстрейший выход первым; нет — порядок сервера.
//    Серверную плитку «⚡ Рекомендуем» замер НЕ трогает: её ставит сервер.
//
// Хранение (отметка времени, TTL сутки, повтор на холодном старте) — в :ui (MayakLatency): здесь
// только переносимая логика, проверяемая на JVM (LatencyProbeTest).
package org.amnezia.awg.mayak.core

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Исход замера одного узла.
 *
 * @property rttMs медиана удачных попыток, мс; null — узел не ответил ни разу ЛИБО замер прерван.
 * @property aborted true — посреди серии поднялся VPN и замер ПРЕРВАН (это не «узел недоступен»:
 *  провал кэшировать нельзя, следующая возможность без VPN должна перемерить).
 */
data class ProbeResult(val rttMs: Int?, val aborted: Boolean) {
    companion object {
        val ABORTED = ProbeResult(rttMs = null, aborted = true)
        val UNREACHABLE = ProbeResult(rttMs = null, aborted = false)
    }
}

object LatencyProbe {
    /** Порт легенды узла: Reality-фронт на :443 отвечает на TCP всем (наши узлы на UDP молчат). */
    const val PORT = 443

    /** Попыток на узел; берём медиану — одиночный выброс (ретрансмит SYN) не портит результат. */
    const val ATTEMPTS = 3

    /** Таймаут ОДНОЙ попытки. Не встало за 1,5 с — попытка не в счёт (живой узел отвечает быстрее). */
    const val ATTEMPT_TIMEOUT_MS = 1_500

    /** Сколько живёт замер: сутки. Протух → перемерить на холодном старте (и только без VPN).
     *  Никаких будильников/таймеров — радио будить нельзя. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /**
     * Свеж ли замер с отметкой [measuredAtMs] (стеночные часы, мс эпохи) на момент [nowMs].
     * Отметка из будущего (часы перевели назад) — протухла: честнее перемерить, чем верить
     * отметке, которой «ещё не было».
     */
    fun isFresh(measuredAtMs: Long, nowMs: Long): Boolean =
        measuredAtMs in 1..nowMs && nowMs - measuredAtMs < TTL_MS

    /** Медиана выборки, мс; пусто → null. Чётный размер — среднее двух средних. */
    fun median(samples: List<Int>): Int? {
        if (samples.isEmpty()) return null
        val s = samples.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /**
     * Замер одного узла: до [ATTEMPTS] TCP-подключений подряд, итог — медиана удачных.
     *
     * ПЕРЕД КАЖДОЙ попыткой зовётся [vpnActive]; true → [ProbeResult.ABORTED] немедленно, частичные
     * пробы выбрасываются (они могли уйти уже через туннель — ровно та ложь, ради которой замер
     * переделан). Все попытки мимо → [ProbeResult.UNREACHABLE].
     *
     * [connectMs] — одна попытка (по умолчанию [tcpConnectMs]); подставная в тестах.
     */
    fun measure(
        host: String,
        vpnActive: () -> Boolean,
        connectMs: (String) -> Int? = { tcpConnectMs(it) },
    ): ProbeResult {
        if (host.isBlank()) return ProbeResult.UNREACHABLE
        val samples = ArrayList<Int>(ATTEMPTS)
        repeat(ATTEMPTS) {
            if (vpnActive()) return ProbeResult.ABORTED
            connectMs(host)?.let { samples.add(it) }
        }
        val rtt = median(samples) ?: return ProbeResult.UNREACHABLE
        return ProbeResult(rttMs = rtt, aborted = false)
    }

    /**
     * Одна попытка: время установления TCP до host:[port], мс; не встало за [timeoutMs] или любая
     * ошибка → null. Имя резолвится ДО запуска секундомера (конструктор InetSocketAddress) — время
     * DNS не въезжает в замер; не разрешилось → null. Вызывающий (:ui) и так резолвит pool_host
     * через DoH заранее и передаёт сюда уже IP — эта ветка лишь страховка на случай, когда DoH
     * не вышел и host остался именем.
     */
    fun tcpConnectMs(host: String, port: Int = PORT, timeoutMs: Int = ATTEMPT_TIMEOUT_MS): Int? {
        val addr = try {
            InetSocketAddress(host, port)
        } catch (_: Exception) {
            return null
        }
        if (addr.isUnresolved) return null
        return try {
            Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(addr, timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000).toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Можно ли сейчас НАЧИНАТЬ замер. Чистое решение для юнит-теста; сами признаки (TRANSPORT_VPN
     * активной сети, наличие не-VPN-сети) добывает :ui у системы. Порядок важен ровно один:
     * ЛЮБОЙ активный VPN — сразу нет, что бы ни говорили остальные признаки.
     */
    fun shouldMeasure(vpnActive: Boolean, hasNetwork: Boolean, staleCount: Int): Boolean =
        !vpnActive && hasNetwork && staleCount > 0
}

/**
 * Порядок списка для режима «Авто» (SPEC-0031): живые направления со СВЕЖИМ замером — первыми, по
 * возрастанию RTT (быстрейший выход сверху); остальные — следом, в порядке сервера. Замеров нет ни
 * у одного → список как отдал сервер, без изменений (прежнее поведение «Авто»).
 *
 * `health=down` наверх не поднимается даже с замером: легенда на :443 отвечает и у направления, чей
 * VPN-путь мёртв, — быстрый TCP там не означает «сюда стоит подключаться».
 *
 * Плитку «⚡ Рекомендуем» функция не знает и не трогает: разбор на плитку/список (splitRecommended)
 * происходит ПОСЛЕ и сам вынимает серверную рекомендацию из любого места списка.
 */
fun orderForAuto(dirs: List<Direction>, rttOf: (Long) -> Int?): List<Direction> {
    val rtt = dirs.associate { it.id to rttOf(it.id) }
    val measured = dirs.filter { it.health != "down" && rtt[it.id] != null }
    if (measured.isEmpty()) return dirs
    val head = measured.sortedBy { rtt[it.id] } // sortedBy стабильна: равный RTT — порядок сервера
    val headIds = head.mapTo(HashSet()) { it.id }
    return head + dirs.filter { it.id !in headIds }
}
