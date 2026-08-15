package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Второй шаг «Авто»: порядок по СОБСТВЕННОМУ опыту подключения.
 *
 * Проверяем то, что ломается молча: смешение ярусов (секунды подъёма против миллисекунд TCP),
 * вечная память о провале и всплытие мёртвого направления с хорошей историей.
 */
class ConnectHistoryTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun dir(id: Long, health: String = "ok") =
        Direction(id = id, code = "d$id", name = "Страна $id", health = health)

    // ===== Накопление опыта =====

    @Test
    fun перваяЗаписьРавнаЗамеру() {
        val s = ConnectHistory.noteSuccess(null, 4_000, now)
        assertEquals(4_000, s.setupMs)
        assertFalse(s.lastFailed)
    }

    @Test
    fun среднееСглаживаетВыброс() {
        var s = ConnectHistory.noteSuccess(null, 3_000, now)
        s = ConnectHistory.noteSuccess(s, 30_000, now) // один провальный по времени подъём
        // Новая попытка весит треть: 3000*2/3 + 30000/3 = 12000, а не 30000.
        assertEquals(12_000, s.setupMs)
        s = ConnectHistory.noteSuccess(s, 3_000, now)
        assertTrue("возвращается к норме", s.setupMs < 10_000)
    }

    @Test
    fun протухшийОпытНеСмешиваетсяСоСвежим() {
        val old = ConnectStat(setupMs = 3_000, lastAtMs = now - ConnectHistory.TTL_MS - 1)
        val s = ConnectHistory.noteSuccess(old, 9_000, now)
        assertEquals("начинаем заново, а не усредняем с прошлогодним", 9_000, s.setupMs)
    }

    @Test
    fun мусорныйЗамерНеПортитСреднее_ноУспехСнимаетПометкуПровала() {
        val prev = ConnectStat(setupMs = 3_000, lastAtMs = now - 1000, lastFailed = true)
        val s = ConnectHistory.noteSuccess(prev, ConnectHistory.MAX_SETUP_MS + 1, now)
        assertEquals(3_000, s.setupMs)
        assertFalse(s.lastFailed)
    }

    @Test
    fun провалНеТрогаетСреднее() {
        val prev = ConnectStat(setupMs = 3_000, lastAtMs = now - 1000)
        val s = ConnectHistory.noteFailure(prev, now)
        assertEquals(3_000, s.setupMs)
        assertTrue(s.lastFailed)
    }

    @Test
    fun провалЗабываетсяЧерезЧас() {
        val fresh = ConnectStat(lastAtMs = now - 5 * 60 * 1000, lastFailed = true)
        val stale = ConnectStat(lastAtMs = now - 2 * hour, lastFailed = true)
        assertTrue(ConnectHistory.recentlyFailed(fresh, now))
        assertFalse(ConnectHistory.recentlyFailed(stale, now))
    }

    @Test
    fun опытИзБудущегоНеСчитаетсяСвежим() {
        // Часы перевели назад — верить отметке «ещё не наступило» нечем.
        assertNull(ConnectHistory.usableSetup(ConnectStat(setupMs = 1_000, lastAtMs = now + hour), now))
    }

    // ===== Порядок списка =====

    @Test
    fun личныйОпытСильнееЗамераБлизости() {
        val dirs = listOf(dir(1), dir(2))
        // У 1 TCP-замер лучше, но реальный подъём вдвое дольше — человеку важно второе.
        val order = orderForAutoWithHistory(
            dirs,
            rttOf = { id -> if (id == 1L) 20 else 90 },
            statOf = { id -> ConnectStat(setupMs = if (id == 1L) 20_000 else 4_000, lastAtMs = now - 1000) },
            nowMs = now,
        )
        assertEquals(listOf(2L, 1L), order.map { it.id })
    }

    @Test
    fun ярусыНеСмешиваются_опытВышеЗамера() {
        val dirs = listOf(dir(1), dir(2))
        // У 1 нет опыта, но отличный TCP-замер; у 2 опыт есть, пусть и небыстрый.
        val order = orderForAutoWithHistory(
            dirs,
            rttOf = { id -> if (id == 1L) 5 else null },
            statOf = { id -> if (id == 2L) ConnectStat(setupMs = 15_000, lastAtMs = now - 1000) else null },
            nowMs = now,
        )
        assertEquals("проверенное своим опытом идёт впереди приближения", listOf(2L, 1L), order.map { it.id })
    }

    @Test
    fun свежийПровалУходитВниз_дажеСХорошимОпытом() {
        val dirs = listOf(dir(1), dir(2), dir(3))
        val order = orderForAutoWithHistory(
            dirs,
            rttOf = { null },
            statOf = { id ->
                when (id) {
                    1L -> ConnectStat(setupMs = 2_000, lastAtMs = now - 60_000, lastFailed = true)
                    2L -> ConnectStat(setupMs = 9_000, lastAtMs = now - 60_000)
                    else -> null
                }
            },
            nowMs = now,
        )
        assertEquals(listOf(2L, 3L, 1L), order.map { it.id })
    }

    @Test
    fun мёртвоеНаправлениеНеПоднимаетсяДажеСОпытом() {
        // 1 объявлено сервером мёртвым, но у человека по нему прекрасная история. Наверх оно НЕ
        // всплывает: сервер знает про узел то, чего телефон не видит. Ниже, чем поставил сервер,
        // мы его тоже не двигаем — этим занимается сам сервер, а не порядок «Авто».
        val dirs = listOf(dir(2), dir(1, health = "down"))
        val order = orderForAutoWithHistory(
            dirs,
            rttOf = { null },
            statOf = { id -> if (id == 1L) ConnectStat(setupMs = 1_000, lastAtMs = now - 1000) else null },
            nowMs = now,
        )
        assertEquals(listOf(2L, 1L), order.map { it.id })
    }

    @Test
    fun безОпытаИЗамеров_порядокСервераНеТрогаем() {
        val dirs = listOf(dir(7), dir(3), dir(5))
        val order = orderForAutoWithHistory(dirs, rttOf = { null }, statOf = { null }, nowMs = now)
        assertEquals(listOf(7L, 3L, 5L), order.map { it.id })
    }
}
