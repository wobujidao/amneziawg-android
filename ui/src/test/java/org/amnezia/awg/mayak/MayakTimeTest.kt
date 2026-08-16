// Относительное время не показывает будущее.
//
// Повод (эмулятор, 16-08): сразу после отправки обращения экран писал «Принято · In 0 minutes».
// Время события приходит с СЕРВЕРА, а сравнивается с часами ТЕЛЕФОНА — они расходятся всегда, и
// свежая запись оказывается «в будущем». Человек читает «через 0 минут» как ошибку приложения.
package org.amnezia.awg.mayak

import org.junit.Assert.assertEquals
import org.junit.Test

class MayakTimeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `будущее подтягивается к сейчас`() {
        assertEquals(now, MayakTime.clampToPast(now + 1_000, now))
        assertEquals(now, MayakTime.clampToPast(now + 60_000, now))
    }

    @Test
    fun `прошлое не трогаем`() {
        assertEquals(now - 1, MayakTime.clampToPast(now - 1, now))
        assertEquals(now - 86_400_000, MayakTime.clampToPast(now - 86_400_000, now))
        assertEquals(now, MayakTime.clampToPast(now, now))
    }
}
