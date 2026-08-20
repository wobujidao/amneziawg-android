package org.amnezia.awg.mayak.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гонка проб выхода. Тесты НАРОЧНО на настоящих задержках, а не на виртуальном времени: весь смысл
 * EgressRace.first — не ждать блокирующих участников, а виртуальное время именно такое ожидание и
 * скрыло бы (оно двигает часы, а поток остаётся занят).
 */
class EgressRaceTest {

    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Проба, отвечающая [ip] через [ms]; [blocking] — занять ПОТОК (как настоящий HttpURLConnection). */
    private class Slow(
        private val ms: Long,
        private val ip: String?,
        private val blocking: Boolean = false,
    ) : EgressProbe {
        override suspend fun externalIp(): String? =
            if (blocking) withContext(Dispatchers.IO) { Thread.sleep(ms); ip }
            else { delay(ms); ip }
    }

    @Test
    fun победитель_не_обязан_быть_первым_в_списке() = runBlocking {
        val ip = EgressRace.first(scope(), listOf(Slow(1_500, "1.1.1.1"), Slow(20, "2.2.2.2")))
        assertEquals("2.2.2.2", ip)
    }

    /**
     * ГЛАВНЫЙ тест файла: ответ отдаётся СРАЗУ, а не после самого медленного участника.
     *
     * Медленный участник тут БЛОКИРУЮЩИЙ (Thread.sleep в Dispatchers.IO) — именно такой живёт внутри
     * настоящей пробы, и отменить его нельзя. Если однажды кто-то перепишет гонку на
     * `coroutineScope { async … }` (выглядит чище), структурная конкурентность обяжет дождаться этого
     * участника — и тест покраснеет, а не «просто станет медленнее незаметно».
     */
    @Test
    fun медленный_участник_не_задерживает_ответ() = runBlocking {
        val started = System.currentTimeMillis()
        val ip = EgressRace.first(
            scope(),
            listOf(Slow(4_000, "9.9.9.9", blocking = true), Slow(30, "2.2.2.2")),
        )
        val took = System.currentTimeMillis() - started
        assertEquals("2.2.2.2", ip)
        assertTrue("гонка ждала проигравшего: $took мс", took < 1_500)
    }

    @Test
    fun проба_вернувшая_null_не_заканчивает_гонку() = runBlocking {
        // Первый ответ — «выход не подтвердился». Это НЕ повод объявить провал: остальные ещё бегут.
        val ip = EgressRace.first(scope(), listOf(Slow(10, null), Slow(300, "3.3.3.3")))
        assertEquals("3.3.3.3", ip)
    }

    @Test
    fun пустая_строка_считается_неудачей() = runBlocking {
        // Ядро/сервис может отдать 200 с пустым ip — это не адрес и не доказательство выхода.
        val ip = EgressRace.first(scope(), listOf(Slow(10, ""), Slow(200, "4.4.4.4")))
        assertEquals("4.4.4.4", ip)
    }

    @Test
    fun никто_не_подтвердил_выход() = runBlocking {
        assertNull(EgressRace.first(scope(), listOf(Slow(10, null), Slow(20, null))))
    }

    @Test
    fun звать_некого() = runBlocking {
        assertNull(EgressRace.first(scope(), emptyList()))
    }

    @Test
    fun паузы_растут_а_суммарное_окно_прежнее() {
        // Первый повтор — быстрый: провал сразу после подъёма туннеля лечится повтором, а не ожиданием.
        assertTrue(EgressRace.retryGapMs(0) <= 500)
        // Дальше паузы только растут (там уже ждём, пока сервер заведёт пира).
        val gaps = EgressRace.RETRY_GAPS_MS
        for (i in 1 until gaps.size) assertTrue("пауза $i не выросла", gaps[i] > gaps[i - 1])
        // Суммарное окно ожидания НЕ сократилось против прежних 5 × 2000 мс — иначе мы начали бы
        // бросать путь раньше, чем на нём появляется пир (FallbackDecision.PEER_SYNC_MS).
        assertEquals(10_000L, gaps.sum())
        // Выход за конец списка не падает и не обнуляет паузу.
        assertEquals(gaps.last(), EgressRace.retryGapMs(99))
    }
}
