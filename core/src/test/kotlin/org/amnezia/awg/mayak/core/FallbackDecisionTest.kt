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
