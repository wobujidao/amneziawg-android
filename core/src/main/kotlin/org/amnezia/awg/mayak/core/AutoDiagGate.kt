// Решение «пора ли пробовать авто-заливку диаг-лога» (см. MayakActivity.maybeAutoSendDiag). Чистая
// арифметика времени — вынесена из MayakPrefs (там SharedPreferences, Android), чтобы само ПРАВИЛО
// можно было проверить юнит-тестом на обычном JVM, без эмулятора.
package org.amnezia.awg.mayak.core

object AutoDiagGate {
    /**
     * Короткий зазор МЕЖДУ ПОПЫТКАМИ (не путать с окном между УСПЕХАМИ ниже) — анти-шквал: без него
     * серия отказов подряд (каждая ступень лестницы, повторные ручные тапы) долбила бы сеть и
     * logcat без остановки, даже когда ни одна попытка не имеет шанса дойти до сервера.
     */
    const val MIN_ATTEMPT_INTERVAL_MS = 5L * 60 * 1000 // 5 минут

    /** Не чаще раза в это время беспокоим сервер УСПЕШНОЙ заливкой (исходный лимит 0.3.48). */
    const val MIN_SUCCESS_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 часов

    /**
     * Можно ли сейчас пробовать авто-заливку. 0 в lastAttemptMs/lastSuccessMs — события ещё не
     * было. now < lastX — часы устройства перевели назад: не блокируем навсегда, разрешаем.
     *
     * До 0.3.99 была ОДНА метка, которую ставили ДО попытки, — то есть провальная попытка сжигала
     * весь 6-часовой лимит наравне с успешной: человек с постоянно ломающейся сетью не получал ни
     * одной диагностики за все 6 часов, хотя каждая следующая попытка была бы так же уместна — сеть
     * могла ожить в любой момент. Теперь два независимых зазора: короткий не даёт шквалу отказов
     * долбить сеть без толку, длинный не даёт РЕАЛЬНО доставленному логу дублироваться.
     */
    fun dueForAttempt(lastAttemptMs: Long, lastSuccessMs: Long, nowMs: Long): Boolean {
        val attemptOk = lastAttemptMs == 0L || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= MIN_ATTEMPT_INTERVAL_MS
        val successOk = lastSuccessMs == 0L || nowMs < lastSuccessMs || nowMs - lastSuccessMs >= MIN_SUCCESS_INTERVAL_MS
        return attemptOk && successOk
    }
}
