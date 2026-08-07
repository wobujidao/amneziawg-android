package org.amnezia.awg.mayak.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDiagGateTest {

    // 0 в lastAttemptMs/lastSuccessMs — зарезервированный смысл «события ещё не было» (см. doc
    // AutoDiagGate.dueForAttempt), поэтому в тестах, где событие РЕАЛЬНО произошло, берём метку
    // времени подальше от нуля — иначе тест случайно проверял бы ветку «событий не было».
    private val base = 1_000_000L

    @Test
    fun `чистая установка — первая попытка разрешена сразу`() {
        assertTrue(AutoDiagGate.dueForAttempt(lastAttemptMs = 0, lastSuccessMs = 0, nowMs = 1_000))
    }

    @Test
    fun `подряд идущие попытки внутри короткого зазора блокируются — анти-шквал жив`() {
        val now = base + AutoDiagGate.MIN_ATTEMPT_INTERVAL_MS - 1
        assertFalse(AutoDiagGate.dueForAttempt(lastAttemptMs = base, lastSuccessMs = 0, nowMs = now))
    }

    /**
     * ГЛАВНЫЙ тест файла — сам баг 2026-08-07. lastSuccessMs здесь ВСЁ ЕЩЁ 0: попытка на t=base
     * была, а до сервера лог не дошёл (сеть/туннель мертвы). До 0.3.99 единая метка ставилась ДО
     * попытки и заперла бы авто-заливку до t=base+6ч, даже если сеть ожила на 10-й минуте. Теперь
     * как только прошёл короткий анти-шквальный зазор — новую попытку разрешаем.
     */
    @Test
    fun `неудачная попытка НЕ жжёт 6-часовой лимит`() {
        val now = base + AutoDiagGate.MIN_ATTEMPT_INTERVAL_MS + 1
        assertTrue(AutoDiagGate.dueForAttempt(lastAttemptMs = base, lastSuccessMs = 0, nowMs = now))
    }

    @Test
    fun `успешная отправка запирает авто-заливку на полные 6 часов`() {
        val justBefore = base + AutoDiagGate.MIN_SUCCESS_INTERVAL_MS - 1
        assertFalse(AutoDiagGate.dueForAttempt(lastAttemptMs = base, lastSuccessMs = base, nowMs = justBefore))
        val justAfter = base + AutoDiagGate.MIN_SUCCESS_INTERVAL_MS + 1
        assertTrue(AutoDiagGate.dueForAttempt(lastAttemptMs = base, lastSuccessMs = base, nowMs = justAfter))
    }

    @Test
    fun `успех блокирует даже когда короткий зазор давно прошёл`() {
        // Попытка была очень давно (короткий зазор точно пройден), но она УДАЛАСЬ — держим полные 6ч,
        // а не открываем дверь заново просто потому, что "с последней попытки" прошло много времени.
        val lastAttempt = base
        val lastSuccess = base
        val now = lastSuccess + AutoDiagGate.MIN_ATTEMPT_INTERVAL_MS * 10
        assertTrue("тест бессмыслен, если это уже за пределами 6ч", now - lastSuccess < AutoDiagGate.MIN_SUCCESS_INTERVAL_MS)
        assertFalse(AutoDiagGate.dueForAttempt(lastAttempt, lastSuccess, now))
    }

    @Test
    fun `перевод часов назад не запирает лимит навсегда`() {
        assertTrue(AutoDiagGate.dueForAttempt(lastAttemptMs = 10_000, lastSuccessMs = 10_000, nowMs = 500))
    }
}
