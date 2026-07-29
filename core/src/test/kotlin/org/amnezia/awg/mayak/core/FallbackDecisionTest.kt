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

    /**
     * САМЫЙ ВАЖНЫЙ инвариант файла, и его легко нарушить из лучших побуждений.
     *
     * «Рукопожатие занимает полторы секунды, зачем ему шесть» — рассуждение верное ровно до первой
     * потери пакета. Движок повторяет инициацию раз в ~5 с (REKEY_TIMEOUT самого WireGuard), поэтому
     * при потере ПЕРВОГО пакета ответ физически не может прийти раньше пятой секунды. Порог меньше
     * этого превращает уход на транзит из исключения в норму — ровно то, на что пожаловался владелец
     * 2026-07-28 (диаг-лог #63: отправка 55.898, повтор 00.936, ответ 02.372).
     *
     * Если этот тест упал — значит кто-то (возможно, я) сократил порог, не вспомнив про повторы.
     */
    @Test
    fun handshakeBudget_survivesOneLostPacket() {
        val engineRetryMs = 5_000L // REKEY_TIMEOUT WireGuard — не наша настройка, изменить не можем
        assertTrue(
            "порог рукопожатия (${FallbackDecision.NO_HANDSHAKE_MS} мс) должен переживать один повтор движка ($engineRetryMs мс)",
            FallbackDecision.NO_HANDSHAKE_MS > engineRetryMs
        )
        // и при этом не превращаться в «ждём вечно»: два повтора уже не ждём, это явно блокировка
        assertTrue(FallbackDecision.NO_HANDSHAKE_MS < 2 * engineRetryMs)
    }

    @Test
    fun worstCasePerRung_isBounded() {
        // Худший случай ограничен сам собой: нет рукопожатия к NO_HANDSHAKE_MS — уходим по первому
        // порогу, значит дольше суммы порогов на ступени не просидим. Должно остаться заметно короче
        // полного набора egress-проб (~34 с), иначе сторож бессмыслен.
        //
        // NB: порядок порогов между собой больше НЕ важен (раньше требовалось NO_HANDSHAKE < NO_EGRESS):
        // второй отсчитывается от рукопожатия, а не от подъёма туннеля, поэтому пересечься они не могут.
        assertTrue(FallbackDecision.NO_HANDSHAKE_MS + FallbackDecision.NO_EGRESS_MS <= 12_000)
    }
}

// msLeft — остаток до порога: им ограничивают пробу, иначе порог фикция (проба на мёртвом туннеле
// спотыкается о DNS и возвращается через десятки секунд — разбор 2026-07-27).
class FallbackDecisionMsLeftTest {

    @Test
    fun `остаток считается от нужного порога`() {
        assertEquals(6_000L, FallbackDecision.msLeft(0, null))
        assertEquals(FallbackDecision.NO_EGRESS_MS, FallbackDecision.msLeft(0, 0))
        assertEquals(1_000L, FallbackDecision.msLeft(5_000, null))
    }

    @Test
    fun `после медленного рукопожатия остаток полный, а не обрезанный`() {
        // Было: на 6,5 с с хендшейком оставалось 3,5 с. Стало: сразу после рукопожатия — весь бюджет.
        assertEquals(FallbackDecision.NO_EGRESS_MS, FallbackDecision.msLeft(6_500, 6_500))
        assertEquals(FallbackDecision.NO_EGRESS_MS - 4_000, FallbackDecision.msLeft(10_500, 6_500))
    }

    @Test
    fun `порог пройден — остаток положительный, но минимальный`() {
        // Ноль или минус в withTimeoutOrNull означал бы «не ждать вовсе»: проба не успевала бы даже
        // начаться, а решение всё равно принимается снаружи по shouldSwitch.
        assertEquals(1L, FallbackDecision.msLeft(6_000, null))
        assertEquals(1L, FallbackDecision.msLeft(99_000, 0))
    }
}

/**
 * Ожидание пира на ноде (разбор 2026-07-29).
 *
 * Пир заводится на выходе не в момент выдачи конфига, а на следующем поллинге агента (15 с). Пока
 * его нет, сервер молчит на инициацию — рукопожатия не будет ни на первой секунде, ни на пятой.
 * Прежняя логика бросала прямой путь на 6-й секунде, уходила на мост (тот же wg0 без пира!) и
 * заканчивала «нет выхода» там, где надо было просто подождать.
 */
class FallbackDecisionPeerSyncTest {

    @Test
    fun `свежий конфиг — не сдаёмся раньше, чем пир доедет`() {
        val slack = FallbackDecision.peerSyncSlackMs(0) // конфиг выдан только что
        assertEquals(FallbackDecision.PEER_SYNC_MS, slack)
        // 6 с без рукопожатия: раньше уходили на следующую ступень, теперь ждём — пира ещё нет
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS, null, slack))
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.PEER_SYNC_MS - 1, null, slack))
        // но не ждём вечно: дождались срока — обычные пороги снова главные
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.PEER_SYNC_MS, null, slack))
    }

    @Test
    fun `тёплый конфиг не стоит ничего`() {
        // Обычный случай: конфиг предзагрузили, пока человек выбирал страну. Пир давно на ноде —
        // ждать нечего, пороги работают как прежде (иначе починка замедлила бы КАЖДОЕ подключение).
        assertEquals(0L, FallbackDecision.peerSyncSlackMs(FallbackDecision.PEER_SYNC_MS))
        assertEquals(0L, FallbackDecision.peerSyncSlackMs(60_000))
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS, null, minWaitMs = 0))
    }

    @Test
    fun `наполовину тёплый конфиг ждёт только недостающее`() {
        val slack = FallbackDecision.peerSyncSlackMs(10_000) // выдан 10 с назад
        assertEquals(5_000L, slack)
        assertFalse(FallbackDecision.shouldSwitch(4_999, null, slack))
        assertTrue(FallbackDecision.shouldSwitch(6_000, null, slack)) // 5 с ожидания прошли, порог 6 с достигнут
    }

    @Test
    fun `подтверждённый выход ожиданием не задерживается`() {
        // Ожидание запрещает только СДАВАТЬСЯ. Успех проверяется отдельно и возвращается сразу:
        // если рукопожатие прошло и egress подтвердился на 900 мс, никто не ждёт пятнадцати секунд.
        // Здесь фиксируем сам инвариант: shouldSwitch — про уход, а не про удержание.
        assertFalse(FallbackDecision.shouldSwitch(900, handshakeAtMs = 400, minWaitMs = FallbackDecision.PEER_SYNC_MS))
    }

    @Test
    fun `остаток на пробу учитывает ожидание пира`() {
        // msLeft ограничивает пробу. Пока ждём пира, остаток обязан считаться по ожиданию, иначе
        // проба обрывалась бы по «истёкшему» порогу ровно тогда, когда мы намеренно ещё ждём.
        assertEquals(9_000L, FallbackDecision.msLeft(6_000, null, minWaitMs = 15_000))
        assertEquals(1L, FallbackDecision.msLeft(15_000, null, minWaitMs = 15_000))
    }
}
