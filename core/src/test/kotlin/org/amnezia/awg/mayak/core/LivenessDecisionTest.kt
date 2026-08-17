package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сколько ждать до лечения туннеля, у которого не идёт трафик.
 *
 * Повод — диаг-лог #28 (15-08): фоновый сторож понял, что трафика нет, в 07:50:22, а чинить начали
 * в 07:50:58. Тридцать шесть секунд человек смотрел на «Защищено» и на страницу, которая не грузится,
 * потому что пинг-цикл экрана считал свои четыре промаха с нуля.
 */
class LivenessDecisionTest {

    @Test
    fun `сторож молчит — считаем полные четыре промаха`() {
        assertEquals(4, LivenessDecision.missesBeforeSelfHeal(watchdogSaysNoTraffic = false))
    }

    @Test
    fun `сторож уже видит, что трафика нет — хватает одного промаха`() {
        assertEquals(1, LivenessDecision.missesBeforeSelfHeal(watchdogSaysNoTraffic = true))
    }

    /**
     * Ноль ставить НЕЛЬЗЯ, и это не придирка к числу.
     *
     * Лечение — это опустить и заново поднять туннель, то есть заведомый обрыв. Сторож и пинг судят
     * по разным признакам (рост rx и возраст рукопожатия против ответа сервера), и вся ценность
     * порога в том, что нужно совпадение ДВУХ независимых. При нуле мы чинили бы по одному только
     * вердикту сторожа — а он ошибается на простаивающем туннеле, где rx честно не растёт.
     */
    @Test
    fun `хотя бы один свой промах нужен всегда`() {
        assertTrue(LivenessDecision.missesBeforeSelfHeal(true) >= 1)
        assertTrue(LivenessDecision.missesBeforeSelfHeal(false) > LivenessDecision.missesBeforeSelfHeal(true))
    }

    /** Экономия должна быть заметной: иначе правка не стоила бы риска. 4 → 1 при такте 5 с = −15 с. */
    @Test
    fun `выигрыш не меньше пятнадцати секунд`() {
        val tickMs = 5_000
        val saved = (LivenessDecision.MISSES_NORMAL - LivenessDecision.MISSES_WHEN_WATCHDOG_SURE) * tickMs
        assertTrue("выигрыш $saved мс — меньше обещанного", saved >= 15_000)
    }
}
