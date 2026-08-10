package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNudgeTest {

    @Test
    fun `в день выхода показываем нулевую ступень`() {
        assertEquals(0, UpdateNudge.stepToShow(daysSinceFirstSeen = 0, lastShownStep = UpdateNudge.NO_STEP))
    }

    @Test
    fun `после отказа молчим до следующего порога`() {
        // Именно этой тишины и не хватало наоборот: раньше «Позже» выключало напоминание навсегда.
        for (d in 0L..13L) {
            assertEquals("день $d", UpdateNudge.NONE, UpdateNudge.stepToShow(d, lastShownStep = 0))
        }
        assertEquals(1, UpdateNudge.stepToShow(14, lastShownStep = 0))
    }

    @Test
    fun `ступени идут по порядку и не перепрыгивают`() {
        assertEquals(1, UpdateNudge.stepToShow(20, lastShownStep = 0))
        assertEquals(2, UpdateNudge.stepToShow(21, lastShownStep = 1))
        assertEquals(UpdateNudge.NONE, UpdateNudge.stepToShow(30, lastShownStep = 2))
    }

    @Test
    fun `человек поставивший приложение поздно сразу попадает на верхнюю ступень`() {
        // Отсчёт идёт от первой ВСТРЕЧИ с версией, но если он уже 40 дней её игнорирует — это
        // последняя ступень, а не путешествие по всем предыдущим.
        assertEquals(3, UpdateNudge.stepToShow(40, lastShownStep = UpdateNudge.NO_STEP))
    }

    @Test
    fun `последняя ступень повторяется каждый раз`() {
        // Сборке больше 36 дней — молчать вредно: в ней могут быть починки протокола.
        assertEquals(3, UpdateNudge.stepToShow(36, lastShownStep = 3))
        assertEquals(3, UpdateNudge.stepToShow(100, lastShownStep = 3))
    }

    @Test
    fun `смена версии на сервере начинает историю заново`() {
        assertTrue(UpdateNudge.isNewVersion(latestCode = 153, trackedCode = 152))
        assertFalse(UpdateNudge.isNewVersion(latestCode = 152, trackedCode = 152))
    }

    @Test
    fun `переведённые назад часы не дают отрицательных дней`() {
        val now = 1_000_000_000L
        assertEquals(0L, UpdateNudge.daysBetween(firstSeenMs = now + 86_400_000L, nowMs = now))
        assertEquals(2L, UpdateNudge.daysBetween(firstSeenMs = now, nowMs = now + 2 * 86_400_000L))
    }
}
