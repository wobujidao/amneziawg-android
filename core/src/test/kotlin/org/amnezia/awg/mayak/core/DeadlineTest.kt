package org.amnezia.awg.mayak.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тест на ЭФФЕКТ, а не на намерение.
 *
 * Порог перехода на следующую ступень лестницы считался правильно и был покрыт зелёным тестом
 * ([FallbackDecisionTest]) — а на живом Мегафоне решение принималось через 29 с вместо 5, потому что
 * ожидание пробы стояло поверх БЛОКИРУЮЩЕГО резолва и таймаут молча ждал вместе с ним
 * (диаг-лог владельца #71, 2026-07-28). Здесь проверяется именно то, что тогда не проверил никто:
 * что срок соблюдается на блокирующем теле.
 */
class DeadlineTest {

    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun сдаётсяВСрок_дажеЕслиТелоБлокируетПоток() = runBlocking {
        val started = System.currentTimeMillis()
        val result = awaitAtMost(scope(), ms = 300) {
            // Ровно тот случай из жизни: блокирующий вызов (у нас — системный резолвер), который
            // не умеет отменяться. Раньше `withTimeoutOrNull` вокруг такого ждал полные 3 секунды.
            withContext(Dispatchers.IO) { Thread.sleep(3_000); "поздний ответ" }
        }
        val took = System.currentTimeMillis() - started
        assertNull("ответ пришёл позже срока — принимать его нельзя", result)
        assertTrue("ждали $took мс при сроке 300 мс — срок не соблюдён", took < 1_500)
    }

    @Test
    fun отдаётРезультат_еслиУспелВСрок() = runBlocking {
        val result = awaitAtMost(scope(), ms = 2_000) { "вовремя" }
        assertEquals("вовремя", result)
    }
}
