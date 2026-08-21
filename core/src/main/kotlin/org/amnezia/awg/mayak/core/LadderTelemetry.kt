// Исход ЛЕСТНИЦЫ ПОДКЛЮЧЕНИЯ для недельного телеметри-бикона (2026-08-09).
//
// Лестница — три ступени: AWG напрямую → транзит РФ (relay) → мост поверх :443 (fallback).
// Бикон до сих пор не знал, КАКОЙ ступенью человек в итоге подключился и какие до неё провалились, —
// «у скольких людей режут прямой путь» узнавали из жалоб в поддержку, а не из данных.
//
// Здесь ЧИСТАЯ логика (без Android): дельта счётчиков по итогам одной попытки + свёртка в поля
// бикона. Накопление на диске — MayakPrefs.noteLadder (:ui), запись исхода — MayakActivity.doConnect.
// Приватность: только техника (ступень/успех/миллисекунды), никаких адресов и содержимого — Политика
// обещает не собирать содержимое трафика.
package org.amnezia.awg.mayak.core

/**
 * Счётчики исходов лестницы. Одна форма на две роли: дельта одной попытки ([LadderTelemetry.attemptOutcome])
 * и накопленная сумма с установки (MayakPrefs) — складываются оператором [plus].
 *
 * ok/fail НЕ взаимоисключающие по попытке: «успех со второй ступени» даёт directFail=1 И relayOk=1.
 * [successMsSum] — сумма миллисекунд до подтверждённого выхода ПО УСПЕШНЫМ попыткам (для среднего).
 */
data class LadderCounters(
    val directOk: Int = 0,
    val relayOk: Int = 0,
    val fallbackOk: Int = 0,
    val directFail: Int = 0,
    val relayFail: Int = 0,
    val fallbackFail: Int = 0,
    /** Попытки, где не вышла НИ ОДНА ступень (при живой сети). */
    val none: Int = 0,
    val successMsSum: Long = 0L,
) {
    operator fun plus(d: LadderCounters): LadderCounters = LadderCounters(
        directOk = directOk + d.directOk,
        relayOk = relayOk + d.relayOk,
        fallbackOk = fallbackOk + d.fallbackOk,
        directFail = directFail + d.directFail,
        relayFail = relayFail + d.relayFail,
        fallbackFail = fallbackFail + d.fallbackFail,
        none = none + d.none,
        successMsSum = successMsSum + d.successMsSum,
    )

    /** Всего успешных попыток (какой бы ступенью ни вышли). */
    val successes: Int get() = directOk + relayOk + fallbackOk

    /** Среднее время до подтверждённого выхода (мс) по успешным попыткам; 0 — успехов не было. */
    val avgSuccessMs: Int
        get() = if (successes <= 0) 0 else (successMsSum / successes).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

object LadderTelemetry {
    // Имена ступеней — ЕДИНСТВЕННЫЙ источник (GoTunnel.ROUTE_* в :ui ссылается сюда, чтобы строки
    // в состоянии туннеля и в телеметрии не разъехались молча).
    const val ROUTE_DIRECT = "direct"
    const val ROUTE_RELAY = "relay"
    const val ROUTE_FALLBACK = "fallback"

    /**
     * Дельта счётчиков по итогам ОДНОЙ попытки подключения.
     *
     * @param failedRungs ступени, которые ПРОБОВАЛИ и они не подтвердили выход (в порядке лестницы).
     *   Непопробованная ступень сюда не попадает: пропуск (нет relay-плеча, тумблер «всегда запасной
     *   канал») — не провал, и врать «провалилась» о том, что не пробовали, нельзя.
     * @param successRung ступень, подтвердившая выход, или null — не вышла ни одна.
     * @param elapsedMs сколько заняла попытка от старта до подтверждённого выхода; учитывается
     *   только при успехе (время до «сдались» — другая величина, её не смешиваем).
     */
    /**
     * След лестницы одной строкой для ЖУРНАЛА ПОДКЛЮЧЕНИЙ в панели: «прямая ✗ · РФ ✓».
     *
     * Собирается на КЛИЕНТЕ, потому что только он знает, какие ступени пробовал и в каком порядке:
     * на сервере видна лишь выдача конфига, где перечислены все доступные плечи, а не пройденный путь.
     *
     * Порядок сохраняем как в лестнице: сперва то, что не вышло, потом то, что довезло. Ступень,
     * которую НЕ пробовали (нет плеча, включён «всегда запасной канал»), в след не попадает вовсе —
     * врать «провалилась» о непопробованном нельзя, тот же принцип, что в [attemptOutcome].
     *
     * Подписи русские: строка идёт в панель, а панель русская. Незнакомое имя ступени показываем как
     * есть — в журнале честная сырая строка лучше ровной лжи.
     */
    fun trace(failedRungs: List<String>, successRung: String?): String {
        fun имя(rung: String) = when (rung) {
            ROUTE_DIRECT -> "прямая"
            ROUTE_RELAY -> "РФ"
            ROUTE_FALLBACK -> "мост"
            else -> rung
        }
        return buildList {
            failedRungs.forEach { add("${имя(it)} ✗") }
            successRung?.let { add("${имя(it)} ✓") }
        }.joinToString(" · ")
    }

    fun attemptOutcome(failedRungs: List<String>, successRung: String?, elapsedMs: Long): LadderCounters =
        LadderCounters(
            directOk = if (successRung == ROUTE_DIRECT) 1 else 0,
            relayOk = if (successRung == ROUTE_RELAY) 1 else 0,
            fallbackOk = if (successRung == ROUTE_FALLBACK) 1 else 0,
            directFail = if (ROUTE_DIRECT in failedRungs) 1 else 0,
            relayFail = if (ROUTE_RELAY in failedRungs) 1 else 0,
            fallbackFail = if (ROUTE_FALLBACK in failedRungs) 1 else 0,
            none = if (successRung == null) 1 else 0,
            successMsSum = if (successRung != null) elapsedMs.coerceAtLeast(0L) else 0L,
        )
}

/** Полный бикон: накопленные счётчики лестницы → поля ladder_*. Понимает только НОВОЕ ядро. */
fun TelemetryRequest.withLadder(c: LadderCounters): TelemetryRequest = copy(
    ladderDirectOk = c.directOk,
    ladderRelayOk = c.relayOk,
    ladderFallbackOk = c.fallbackOk,
    ladderDirectFail = c.directFail,
    ladderRelayFail = c.relayFail,
    ladderFallbackFail = c.fallbackFail,
    ladderNone = c.none,
    ladderMsAvg = c.avgSuccessMs,
)

/**
 * Урезанный бикон СТАРОГО контракта: все ladder-поля null → их ключи не сериализуются вовсе.
 * Нужен, потому что ядро парсит тело строго (DisallowUnknownFields) и до своего обновления отвечает
 * на новые ключи 400 — тогда шлём этот вариант, чтобы старые данные доехали (MayakTelemetryWorker).
 */
fun TelemetryRequest.withoutLadder(): TelemetryRequest = copy(
    ladderDirectOk = null,
    ladderRelayOk = null,
    ladderFallbackOk = null,
    ladderDirectFail = null,
    ladderRelayFail = null,
    ladderFallbackFail = null,
    ladderNone = null,
    ladderMsAvg = null,
)
