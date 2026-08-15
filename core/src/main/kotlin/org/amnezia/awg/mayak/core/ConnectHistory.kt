// Второй шаг «Авто»: порядок стран по СОБСТВЕННОМУ опыту человека, а не только по замеру близости.
//
// Что уже было (первый шаг, LatencyProbe): тихий TCP-замер до легенды узла :443, когда не поднят ни
// один VPN. Он отвечает на вопрос «какой узел ближе», и это ХОРОШЕЕ приближение — но приближение:
// легенда на :443 отвечает и там, где наш UDP-путь у ЭТОГО оператора режется, и человек с быстрым
// TCP до узла может минуту смотреть на «подключаемся», пока лестница перебирает ступени.
//
// Что добавляет этот файл. Приложение и так знает ПРАВДУ: сколько заняло реальное поднятие туннеля
// (лестница уже меряет это для недельного бикона — LadderTelemetry) и вышло ли оно вообще. Правда
// сильнее приближения, поэтому:
//   • направление, которое у ЭТОГО человека поднималось за 3 с, идёт выше того, что поднималось 20 с,
//     даже если TCP-замер говорит обратное;
//   • направление, где ПОСЛЕДНЯЯ попытка не дала выхода в интернет, опускается ВНИЗ на час: у него
//     сейчас не «медленно», у него «не работает», и предлагать его первым — обман.
//
// Почему усреднение, а не последний замер: одна попытка попадает в мигнувшую сеть, метро, лифт.
// Держим скользящее среднее (EMA) — новая попытка весит треть, прошлое две трети: так один выброс
// не переворачивает порядок, а устойчивое ухудшение доезжает за две-три попытки.
//
// Всё здесь — чистые функции и данные, проверяемые на JVM (ConnectHistoryTest). Хранение (prefs,
// запись из лестницы) — в :ui, MayakConnectStats.
package org.amnezia.awg.mayak.core

/**
 * Собственный опыт подключения к одному направлению.
 *
 * @property setupMs скользящее среднее времени подъёма туннеля, мс; 0 — удачных подъёмов не было.
 * @property lastAtMs когда записан последний исход (стеночные часы, мс эпохи).
 * @property lastFailed true — ПОСЛЕДНЯЯ попытка кончилась ничем (ни одна ступень не дала выхода).
 */
data class ConnectStat(
    val setupMs: Int = 0,
    val lastAtMs: Long = 0L,
    val lastFailed: Boolean = false,
)

object ConnectHistory {
    /** Вес новой попытки в скользящем среднем: 1/3. Прошлое весит 2/3 — см. заголовок файла. */
    const val NEW_WEIGHT_NUM = 1
    const val NEW_WEIGHT_DEN = 3

    /**
     * Сколько живёт собственный опыт: неделя. Условия у оператора меняются (палитра мимикрии,
     * блокировки, переезд узла), и месячной давности «было быстро» — уже не про сегодня.
     */
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Насколько долго помним ПРОВАЛ: час. Провал — самый скоропортящийся факт из всех: у оператора
     * это лечится сменой сети, а у нас — новой палитрой с ядра. Держать направление внизу сутками
     * из-за одной неудачи значит спрятать рабочий выход.
     */
    const val FAIL_PENALTY_MS = 60L * 60 * 1000

    /** Верхняя граница разумного времени подъёма, мс. Всё, что дольше, — это уже не «подключение». */
    const val MAX_SETUP_MS = 120_000

    /**
     * Учесть удачный подъём. [prev] = null — первая запись, среднее равно самому замеру.
     * Значения вне разумного диапазона отбрасываем: в среднее не должен въезжать мусор от часов
     * или от подвисшего экрана.
     */
    fun noteSuccess(prev: ConnectStat?, setupMs: Int, nowMs: Long): ConnectStat {
        if (setupMs <= 0 || setupMs > MAX_SETUP_MS) {
            // Замер не годится, но САМ ФАКТ успеха годится: снимаем пометку провала.
            return (prev ?: ConnectStat()).copy(lastAtMs = nowMs, lastFailed = false)
        }
        val old = prev?.setupMs ?: 0
        val avg = if (prev == null || old <= 0 || !isFresh(prev.lastAtMs, nowMs)) {
            setupMs // опыта нет или он протух — начинаем заново с сегодняшнего замера
        } else {
            (old * (NEW_WEIGHT_DEN - NEW_WEIGHT_NUM) + setupMs * NEW_WEIGHT_NUM) / NEW_WEIGHT_DEN
        }
        return ConnectStat(setupMs = avg, lastAtMs = nowMs, lastFailed = false)
    }

    /**
     * Учесть провал лестницы (ни одна ступень не дала выхода). Среднее НЕ трогаем: провал — это не
     * «стало медленно», а «сейчас не работает», и лечится он отдельным правилом сортировки.
     */
    fun noteFailure(prev: ConnectStat?, nowMs: Long): ConnectStat =
        (prev ?: ConnectStat()).copy(lastAtMs = nowMs, lastFailed = true)

    /** Свеж ли опыт: отметка не старше недели и не из будущего (часы перевели — верить нечему). */
    fun isFresh(lastAtMs: Long, nowMs: Long): Boolean =
        lastAtMs in 1..nowMs && nowMs - lastAtMs <= TTL_MS

    /** Годится ли среднее время подъёма для сортировки. */
    fun usableSetup(stat: ConnectStat?, nowMs: Long): Int? =
        stat?.takeIf { it.setupMs > 0 && isFresh(it.lastAtMs, nowMs) }?.setupMs

    /** Свежий ли это провал — направление опускается вниз списка на [FAIL_PENALTY_MS]. */
    fun recentlyFailed(stat: ConnectStat?, nowMs: Long): Boolean =
        stat != null && stat.lastFailed && stat.lastAtMs in 1..nowMs &&
            nowMs - stat.lastAtMs <= FAIL_PENALTY_MS
}

/**
 * Порядок списка для «Авто» с учётом собственного опыта — второй шаг (первый — [orderForAuto]).
 *
 * Тремя ярусами, и ярусы не смешиваются: секунды реального подъёма и миллисекунды TCP-замера — это
 * разные величины, и складывать их в одну формулу значит выдумать вес, которого мы не мерили.
 *   1. свой опыт есть и он свежий → по возрастанию времени подъёма;
 *   2. опыта нет, но есть свежий TCP-замер → по возрастанию RTT;
 *   3. остальные — в порядке сервера.
 * И поверх всего: направление со СВЕЖИМ провалом уходит в самый низ (внутри — порядок сервера), а
 * `health=down` наверх не поднимается никогда, даже с прекрасным личным опытом: сервер знает про
 * узел то, чего телефон не видит.
 */
fun orderForAutoWithHistory(
    dirs: List<Direction>,
    rttOf: (Long) -> Int?,
    statOf: (Long) -> ConnectStat?,
    nowMs: Long,
): List<Direction> {
    val failed = dirs.filter { ConnectHistory.recentlyFailed(statOf(it.id), nowMs) }
    val failedIds = failed.mapTo(HashSet()) { it.id }
    val rest = dirs.filter { it.id !in failedIds }

    val byExperience = rest
        .filter { it.health != "down" && ConnectHistory.usableSetup(statOf(it.id), nowMs) != null }
        .sortedBy { ConnectHistory.usableSetup(statOf(it.id), nowMs) } // sortedBy стабильна
    val experiencedIds = byExperience.mapTo(HashSet()) { it.id }

    val byProbe = rest
        .filter { it.id !in experiencedIds && it.health != "down" && rttOf(it.id) != null }
        .sortedBy { rttOf(it.id) }
    val probedIds = byProbe.mapTo(HashSet()) { it.id }

    val tail = rest.filter { it.id !in experiencedIds && it.id !in probedIds }
    return byExperience + byProbe + tail + failed
}
