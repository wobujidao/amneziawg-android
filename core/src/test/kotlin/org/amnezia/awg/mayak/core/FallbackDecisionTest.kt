package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackDecisionTest {

    @Test
    fun noHandshake_waitsUntilItsDeadline() {
        // Пока не вышел срок — ждём: хендшейк на медленной соте может встать не мгновенно.
        assertFalse(FallbackDecision.shouldSwitch(0, handshakeAtMs = null))
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS - 1, handshakeAtMs = null))
    }

    @Test
    fun noHandshake_switchesAtDeadline() {
        // Ни одного хендшейка за срок = наши UDP-пакеты не доходят вовсе. Это и есть случай SPEC-0039.
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS, handshakeAtMs = null))
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS + 5_000, handshakeAtMs = null))
    }

    @Test
    fun withHandshake_egressBudgetStartsAtHandshake() {
        // Хендшейк на 0 мс: полные NO_EGRESS_MS на подтверждение выхода.
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.NO_EGRESS_MS - 1, handshakeAtMs = 0))
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_EGRESS_MS, handshakeAtMs = 0))
    }

    // ГЛАВНЫЙ тест этого файла: ровно этот случай увёл живого пользователя с рабочего пути
    // (диаг-лог #63, 2026-07-28). Рукопожатие на 6,5 с — раньше на проверку выхода оставалось 3,5 с
    // вместо десяти, и приложение уходило на транзит через Москву при исправном прямом пути.
    @Test
    fun slowHandshake_doesNotEatEgressBudget() {
        val handshakeAt = 6_500L
        // на 10-й секунде (старое поведение переключилось бы) — ещё ждём
        assertFalse(FallbackDecision.shouldSwitch(10_000, handshakeAtMs = handshakeAt))
        // ждём ровно NO_EGRESS_MS ПОСЛЕ рукопожатия, не раньше
        assertFalse(FallbackDecision.shouldSwitch(handshakeAt + FallbackDecision.NO_EGRESS_MS - 1, handshakeAtMs = handshakeAt))
        assertTrue(FallbackDecision.shouldSwitch(handshakeAt + FallbackDecision.NO_EGRESS_MS, handshakeAtMs = handshakeAt))
    }

    @Test
    fun deadlines_areOrdered_andWorstCaseIsBounded() {
        // Порог «нет хендшейка» ДОЛЖЕН быть строго меньше порога «хендшейк есть»: иначе первый никогда
        // не сработает раньше второго и быстрый случай (весь UDP задавлен) ждал бы столько же.
        assertTrue(FallbackDecision.NO_HANDSHAKE_MS < FallbackDecision.NO_EGRESS_MS)
        // Худший случай ограничен сам собой: нет хендшейка к NO_HANDSHAKE_MS — уходим по первому порогу,
        // значит дольше NO_HANDSHAKE_MS + NO_EGRESS_MS на ступени не просидим. Это должно остаться
        // заметно короче полного набора egress-проб (~34 с), иначе сторож бессмыслен.
        assertTrue(FallbackDecision.NO_HANDSHAKE_MS + FallbackDecision.NO_EGRESS_MS < 20_000)
    }
}

// msLeft — остаток до порога: им ограничивают пробу, иначе порог фикция (проба на мёртвом туннеле
// спотыкается о DNS и возвращается через десятки секунд — разбор 2026-07-27).
class FallbackDecisionMsLeftTest {

    @Test
    fun `остаток считается от нужного порога`() {
        assertEquals(6_000L, FallbackDecision.msLeft(0, null))
        assertEquals(10_000L, FallbackDecision.msLeft(0, 0))
        assertEquals(1_000L, FallbackDecision.msLeft(5_000, null))
    }

    @Test
    fun `после медленного рукопожатия остаток полный, а не обрезанный`() {
        // Было: на 6,5 с с хендшейком оставалось 3,5 с. Стало: сразу после рукопожатия — все десять.
        assertEquals(10_000L, FallbackDecision.msLeft(6_500, 6_500))
        assertEquals(6_000L, FallbackDecision.msLeft(10_500, 6_500))
    }

    @Test
    fun `порог пройден — остаток положительный, но минимальный`() {
        // Ноль или минус в withTimeoutOrNull означал бы «не ждать вовсе»: проба не успевала бы даже
        // начаться, а решение всё равно принимается снаружи по shouldSwitch.
        assertEquals(1L, FallbackDecision.msLeft(6_000, null))
        assertEquals(1L, FallbackDecision.msLeft(99_000, 0))
    }
}
