package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Плитка «⚡ Рекомендуем» показывает ТОЛЬКО серверную рекомендацию (SPEC-0031 T3).
 *
 * Тест держит три вещи, каждая из которых молча ломала бы плитку:
 *  • клиент не выдумывает рекомендацию сам — нет пометки от ядра, нет плитки (иначе приложение и
 *    сервер советовали бы разное);
 *  • помеченное мёртвое направление не рекомендуется — совет «подключайся сюда» на down хуже
 *    отсутствия совета;
 *  • кривое ядро с двумя пометками не прячет плитку и не роняет клиента — берём первую.
 */
class RecommendationTest {

    private fun dir(id: Long, recommended: Boolean = false, health: String = "ok") =
        Direction(id = id, code = "d$id", name = "Страна $id", recommended = recommended, health = health)

    @Test
    fun `помеченное сервером направление находится`() {
        val dirs = listOf(dir(1), dir(2, recommended = true), dir(3))
        assertEquals(2L, recommendedDirection(dirs)?.id)
    }

    @Test
    fun `без пометки сервера плитки нет — клиент не выдумывает рекомендацию сам`() {
        // Направления «хорошие» (ok, есть из чего выбрать) — но раз ядро не пометило, плитки нет.
        assertNull(recommendedDirection(listOf(dir(1), dir(2), dir(3))))
        assertNull(recommendedDirection(emptyList()))
    }

    @Test
    fun `мёртвое направление не рекомендуется даже с пометкой`() {
        // Сервер обещает помечать только живые; если пометка и down всё же пришли вместе
        // (рассинхрон обогащения), совет подключаться к мёртвому — хуже отсутствия совета.
        assertNull(recommendedDirection(listOf(dir(1), dir(2, recommended = true, health = "down"))))
    }

    @Test
    fun `degraded и без сигнала — доверяем пометке`() {
        // health="" (старое ядро без сигнала) и "degraded" — не повод прятать плитку: гейт живости
        // делает сервер, клиент отсекает только заведомо мёртвое.
        assertEquals(1L, recommendedDirection(listOf(dir(1, recommended = true, health = ""))) ?.id)
        assertEquals(2L, recommendedDirection(listOf(dir(1), dir(2, recommended = true, health = "degraded")))?.id)
    }

    @Test
    fun `две пометки (кривое ядро) — берём первую по порядку сервера`() {
        val dirs = listOf(dir(1), dir(2, recommended = true), dir(3, recommended = true))
        assertEquals(2L, recommendedDirection(dirs)?.id)
    }

    @Test
    fun `признак recommended доезжает из JSON ядра`() {
        // Контракт /v1/client/directions: и recommended, и ipv6 — omitempty. Разбор полного ответа
        // с обоими полями и ответа старого ядра без них не должен ни падать, ни путать дефолты.
        val dirs = MayakBackend.defaultJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Direction.serializer()),
            """[
              {"id":1,"code":"nl","name":"Нидерланды","p2p":true,"health":"ok","load_hint":40},
              {"id":2,"code":"pl","name":"Польша","p2p":true,"health":"ok","load_hint":10,"recommended":true,"ipv6":true}
            ]""",
        )
        assertEquals(2L, recommendedDirection(dirs)?.id)
        assertEquals(false, dirs[0].recommended)
    }
}
