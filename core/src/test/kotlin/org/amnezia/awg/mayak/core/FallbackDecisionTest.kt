package org.amnezia.awg.mayak.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackDecisionTest {

    @Test
    fun noHandshake_waitsUntilItsDeadline() {
        // Пока не вышел срок — ждём: хендшейк на медленной соте может встать не мгновенно.
        assertFalse(FallbackDecision.shouldSwitch(0, hasHandshake = false))
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS - 1, hasHandshake = false))
    }

    @Test
    fun noHandshake_switchesAtDeadline() {
        // Ни одного хендшейка за срок = наши UDP-пакеты не доходят вовсе. Это и есть случай SPEC-0039.
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS, hasHandshake = false))
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS + 5_000, hasHandshake = false))
    }

    @Test
    fun withHandshake_getsMoreTimeThanWithout() {
        // Хендшейк есть, но egress не подтвердился — ждём дольше: пир на сервере появляется не мгновенно
        // (sync-таймер), и рвать живой UDP-путь раньше времени нельзя.
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.NO_HANDSHAKE_MS, hasHandshake = true))
        assertFalse(FallbackDecision.shouldSwitch(FallbackDecision.NO_EGRESS_MS - 1, hasHandshake = true))
        assertTrue(FallbackDecision.shouldSwitch(FallbackDecision.NO_EGRESS_MS, hasHandshake = true))
    }

    @Test
    fun deadlines_areOrdered_andShorterThanFullProbe() {
        // Порог «нет хендшейка» ДОЛЖЕН быть строго меньше порога «хендшейк есть»: иначе первый никогда
        // не сработает раньше второго и быстрый случай (весь UDP задавлен) ждал бы столько же.
        assertTrue(FallbackDecision.NO_HANDSHAKE_MS < FallbackDecision.NO_EGRESS_MS)
        // И оба должны быть заметно короче полного набора egress-проб (6 × (4с таймаут + 2с пауза) ≈ 34с) —
        // иначе сторож бессмыслен: пользователь всё равно увидит полминуты «Подключаюсь».
        assertTrue(FallbackDecision.NO_EGRESS_MS < 20_000)
    }
}

// msLeft — остаток до порога: им ограничивают пробу, иначе порог фикция (проба на мёртвом туннеле
// спотыкается о DNS и возвращается через десятки секунд — разбор 2026-07-27).
class FallbackDecisionMsLeftTest {
    @org.junit.Test
    fun `остаток считается от нужного порога`() {
        org.junit.Assert.assertEquals(6_000L, FallbackDecision.msLeft(0, false))
        org.junit.Assert.assertEquals(10_000L, FallbackDecision.msLeft(0, true))
        org.junit.Assert.assertEquals(1_000L, FallbackDecision.msLeft(5_000, false))
        org.junit.Assert.assertEquals(4_000L, FallbackDecision.msLeft(6_000, true))
    }

    @org.junit.Test
    fun `порог пройден — остаток положительный, но минимальный`() {
        // Ноль или минус в withTimeoutOrNull означал бы «не ждать вовсе»: проба не успевала бы даже
        // начаться, а решение всё равно принимается снаружи по shouldSwitch.
        org.junit.Assert.assertEquals(1L, FallbackDecision.msLeft(6_000, false))
        org.junit.Assert.assertEquals(1L, FallbackDecision.msLeft(99_000, true))
    }
}
