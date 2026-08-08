package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Отказ 402 значит ДВЕ разные вещи, и приложение обязано их различать.
 *
 * Разбор 2026-08-08: ядро отдаёт `no_plan` (тарифа не выдавали) и `no_subscription` (срок прошёл)
 * под одним статусом 402. Приложение ветвилось только по статусу и обоим показывало «Доступ
 * закончился — продлите». Человек, который ни разу не платил, читал совет продлить то, чего у него
 * не было, и шёл искать кнопку продления, которой для него не существует.
 *
 * Тест держит ровно то, на чём стоит починка: причина различается по `code`, а не по статусу, и при
 * этом неизвестный признак не ломает поведение (fail-safe в прежнюю ветку).
 */
class AccessDenialTest {
    private val json = MayakBackend.defaultJson

    @Test
    fun `тарифа нет и срок кончился — разные причины при одном статусе`() {
        val noPlan = apiError(
            402,
            """{"error":"доступ пока не открыт — выберите тариф","code":"no_plan"}""",
            json,
        )
        val expired = apiError(
            402,
            """{"error":"доступ закончился — продлите его","code":"no_subscription"}""",
            json,
        )

        // Признак доезжает до клиента из тела — без этого различать нечем.
        assertEquals("no_plan", noPlan.code)
        assertEquals("no_subscription", expired.code)
        // По статусу они НЕРАЗЛИЧИМЫ — вот почему ветвиться по нему было ошибкой.
        assertEquals(noPlan.status, expired.status)

        assertEquals(AccessDenial.NO_PLAN, accessDenial(noPlan.status, noPlan.code))
        assertEquals(AccessDenial.EXPIRED, accessDenial(expired.status, expired.code))
        assertNotEquals(
            "тому, кому тариф не выдавали, нельзя показывать «продлите»",
            accessDenial(noPlan.status, noPlan.code),
            accessDenial(expired.status, expired.code),
        )
    }

    @Test
    fun `402 без признака и с незнакомым признаком остаётся прежним поведением`() {
        // Старое ядро признака не слало вовсе.
        assertEquals(AccessDenial.EXPIRED, accessDenial(402, ""))
        // Появится третья причина — клиент не должен провалиться в «ошибка ядра (402)».
        assertEquals(AccessDenial.EXPIRED, accessDenial(402, "plan_suspended"))
    }

    @Test
    fun `чужие статусы не попадают в ветку доступа`() {
        assertEquals(AccessDenial.NONE, accessDenial(409, "device_limit_reached"))
        assertEquals(AccessDenial.NONE, accessDenial(401, "unauthorized"))
        // Даже если признак тот же самый — ветку выбирает пара «статус + код».
        assertEquals(AccessDenial.NONE, accessDenial(403, "no_plan"))
    }
}
