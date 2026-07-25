package org.amnezia.awg.mayak.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Проверка клиента против ЖИВОГО моста на ядре. Фейковый сервер в остальных тестах ходит без TLS,
 * поэтому именно тут проверяется то, что он не покрывает: настоящий сертификат, проверка имени
 * хоста и апгрейд через Caddy.
 *
 * Гейт по переменным окружения — в CI и на обычном прогоне тест пропускается (секрет в репо не кладём):
 *   MAYAK_WS_URL=wss://mayakvpn.ru/v1/stream MAYAK_WS_TOKEN=<токен моста> ./gradlew :core:test
 */
class WsLiveBridgeTest {

    private val url = System.getenv("MAYAK_WS_URL").orEmpty()
    private val token = System.getenv("MAYAK_WS_TOKEN").orEmpty()

    @Test
    fun `живой мост принимает апгрейд по TLS`() {
        assumeTrue("нет MAYAK_WS_URL/MAYAK_WS_TOKEN — пропускаю живую пробу", url.isNotEmpty() && token.isNotEmpty())
        WsDatagramClient(url, token).use { it.connect() } // не бросил — значит 101 и accept сошёлся
    }

    /**
     * Без токена мост обязан выглядеть как обычная страница-404 сайта: в этом весь смысл маскировки,
     * и это же проверяется на серверной стороне (scripts/smoke.sh). Здесь смотрим глазами клиента.
     */
    @Test
    fun `без токена живой мост отвечает как обычный сайт`() {
        assumeTrue("нет MAYAK_WS_URL — пропускаю живую пробу", url.isNotEmpty())
        try {
            WsDatagramClient(url, "net-takogo-tokena").use { it.connect() }
            throw AssertionError("мост не должен пускать без токена")
        } catch (e: java.io.IOException) {
            assertTrue("ждали 404, получили: ${e.message}", e.message!!.contains("404"))
        }
    }
}
