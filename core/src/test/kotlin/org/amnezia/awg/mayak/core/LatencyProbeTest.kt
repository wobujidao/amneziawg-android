package org.amnezia.awg.mayak.core

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тихий замер близости выходов (LatencyProbe) — правила, ради которых замер переделан 15-08
 * (директива владельца: прежний ICMP мерил ЧЕРЕЗ поднятый туннель и показывал 129 мс до России):
 *  • ЛЮБОЙ активный VPN — не мерим, и признак проверяется перед КАЖДОЙ попыткой, не раз на старте;
 *  • «Авто» ставит быстрейший выход первым ТОЛЬКО по свежим замерам; замеров нет → порядок сервера
 *    как есть (прежнее поведение не ломается);
 *  • прерванный VPN'ом замер отличим от «узел не ответил» — прерванное нельзя кэшировать как провал.
 */
class LatencyProbeTest {

    /** Момент «сейчас» для сортировки. Опыта подключения в этих тестах нет — проверяется ярус замера. */
    private val NOW = 1_700_000_000_000L

    private fun dir(id: Long, health: String = "ok") =
        Direction(id = id, code = "d$id", name = "Страна $id", health = health)

    // ── Гейт «VPN включён → не мерим» ─────────────────────────────────────────────

    @Test
    fun `VPN активен с самого начала — ни одной попытки соединения`() {
        var connects = 0
        val r = LatencyProbe.measure("1.2.3.4", vpnActive = { true }, connectMs = { connects++; 10 })
        assertTrue("замер обязан быть помечен прерванным", r.aborted)
        assertNull(r.rttMs)
        assertEquals("при активном VPN сокет не открывается вовсе", 0, connects)
    }

    @Test
    fun `VPN поднялся посреди серии — замер прерван, частичные пробы выброшены`() {
        var attempt = 0
        // первая попытка проходит без VPN, перед второй VPN уже поднят
        val r = LatencyProbe.measure(
            "1.2.3.4",
            vpnActive = { attempt > 0 },
            connectMs = { attempt++; 10 },
        )
        assertTrue(r.aborted)
        assertNull("частичная выборка не выдаётся за результат", r.rttMs)
        assertEquals("до подъёма VPN успела пройти ровно одна попытка", 1, attempt)
    }

    @Test
    fun `решение мерить - только без VPN, с сетью и когда есть протухшие`() {
        assertTrue(LatencyProbe.shouldMeasure(vpnActive = false, hasNetwork = true, staleCount = 2))
        assertFalse("любой VPN — сразу нет", LatencyProbe.shouldMeasure(vpnActive = true, hasNetwork = true, staleCount = 2))
        assertFalse(LatencyProbe.shouldMeasure(vpnActive = false, hasNetwork = false, staleCount = 2))
        assertFalse("всё свежо — мерить нечего", LatencyProbe.shouldMeasure(vpnActive = false, hasNetwork = true, staleCount = 0))
    }

    // ── Сам замер: медиана трёх попыток ───────────────────────────────────────────

    @Test
    fun `медиана трёх попыток, выброс не портит результат`() {
        val probes = ArrayDeque(listOf(30, 10, 900)) // 900 — ретрансмит SYN
        val r = LatencyProbe.measure("1.2.3.4", vpnActive = { false }, connectMs = { probes.removeFirst() })
        assertEquals(30, r.rttMs)
        assertFalse(r.aborted)
    }

    @Test
    fun `неудачные попытки не участвуют в медиане`() {
        val probes = ArrayDeque(listOf<Int?>(null, 20, 40))
        val r = LatencyProbe.measure("1.2.3.4", vpnActive = { false }, connectMs = { probes.removeFirst() })
        assertEquals("медиана двух удачных — их среднее", 30, r.rttMs)
    }

    @Test
    fun `все попытки мимо — узел недоступен, и это НЕ прерванный замер`() {
        val r = LatencyProbe.measure("1.2.3.4", vpnActive = { false }, connectMs = { null })
        assertNull(r.rttMs)
        assertFalse("недоступность кэшируется, прерывание нет — путать их нельзя", r.aborted)
    }

    @Test
    fun `пустой host не меряется`() {
        var connects = 0
        val r = LatencyProbe.measure("", vpnActive = { false }, connectMs = { connects++; 10 })
        assertNull(r.rttMs)
        assertEquals(0, connects)
    }

    @Test
    fun `медиана - нечётное, чётное, пусто`() {
        assertEquals(20, LatencyProbe.median(listOf(30, 10, 20)))
        assertEquals(25, LatencyProbe.median(listOf(30, 20)))
        assertEquals(7, LatencyProbe.median(listOf(7)))
        assertNull(LatencyProbe.median(emptyList()))
    }

    // ── Порядок «Авто» ────────────────────────────────────────────────────────────

    @Test
    fun `свежие замеры есть — быстрейший выход первым`() {
        val dirs = listOf(dir(1), dir(2), dir(3)) // порядок сервера: 1, 2, 3
        val rtt = mapOf(1L to 80, 2L to 5, 3L to 40)
        assertEquals(listOf(2L, 3L, 1L), orderForAutoWithHistory(dirs, { rtt[it] }, { null }, NOW).map { it.id })
    }

    @Test
    fun `замеров нет — порядок сервера как есть`() {
        val dirs = listOf(dir(3), dir(1), dir(2))
        assertEquals(dirs, orderForAutoWithHistory(dirs, { null }, { null }, NOW))
    }

    @Test
    fun `замер есть у части — измеренные первыми, прочие следом в порядке сервера`() {
        val dirs = listOf(dir(1), dir(2), dir(3), dir(4))
        val rtt = mapOf(3L to 50, 2L to 90)
        assertEquals(listOf(3L, 2L, 1L, 4L), orderForAutoWithHistory(dirs, { rtt[it] }, { null }, NOW).map { it.id })
    }

    @Test
    fun `мёртвое направление не поднимается наверх даже с быстрым замером`() {
        // Легенда узла на :443 отвечает и у направления с мёртвым VPN-путём — быстрый TCP там
        // не означает «сюда стоит подключаться».
        val dirs = listOf(dir(1), dir(2, health = "down"), dir(3))
        val rtt = mapOf(1L to 80, 2L to 1, 3L to 40)
        assertEquals(listOf(3L, 1L, 2L), orderForAutoWithHistory(dirs, { rtt[it] }, { null }, NOW).map { it.id })
    }

    @Test
    fun `равный RTT — порядок сервера (сортировка стабильна)`() {
        val dirs = listOf(dir(1), dir(2), dir(3))
        assertEquals(listOf(1L, 2L, 3L), orderForAutoWithHistory(dirs, { 25 }, { null }, NOW).map { it.id })
    }

    // ── Срок жизни замера ─────────────────────────────────────────────────────────

    @Test
    fun `замер живёт сутки`() {
        val now = 1_700_000_000_000L
        assertTrue(LatencyProbe.isFresh(now - 1, now))
        assertTrue(LatencyProbe.isFresh(now - LatencyProbe.TTL_MS + 1, now))
        assertFalse("ровно сутки — уже протух", LatencyProbe.isFresh(now - LatencyProbe.TTL_MS, now))
        assertFalse("нулевая отметка — замера не было", LatencyProbe.isFresh(0, now))
        assertFalse("отметка из будущего (часы перевели) — перемерить", LatencyProbe.isFresh(now + 60_000, now))
    }

    // ── Живой сокет (loopback — быстро и детерминированно) ───────────────────────

    @Test
    fun `TCP-подключение к живому порту меряется`() {
        ServerSocket(0).use { srv ->
            val ms = LatencyProbe.tcpConnectMs("127.0.0.1", srv.localPort, timeoutMs = 1_000)
            assertNotNull("loopback обязан открыться", ms)
            assertTrue("время неотрицательно и явно меньше таймаута", ms!! in 0..999)
        }
    }

    @Test
    fun `закрытый порт — null, а не выдуманное время`() {
        val port = ServerSocket(0).use { it.localPort } // порт свободен: сокет уже закрыт
        assertNull(LatencyProbe.tcpConnectMs("127.0.0.1", port, timeoutMs = 1_000))
    }

    @Test
    fun `неразрешимое имя — null (резолв ДО секундомера, время DNS в замер не въезжает)`() {
        assertNull(LatencyProbe.tcpConnectMs("no-such-host.invalid", 443, timeoutMs = 1_000))
    }
}
