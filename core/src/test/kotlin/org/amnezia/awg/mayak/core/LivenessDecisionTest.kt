package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── Отложенная заливка диагностики по «трафика нет» (20-08) ────────────────────────────────
    // Живой случай: человек с МТС прислал два авто-лога за два дня, оба на короткой потере сети.
    // Туннель в таком случае оживает сам за ~2 с, разбирать в логе нечего, а 6-часовой лимит
    // авто-заливки уже потрачен.

    @Test
    fun `только что объявили «трафика нет» — лог не льём`() {
        assertFalse(LivenessDecision.shouldReportNoTraffic(noTrafficSinceMs = 1_000L, nowMs = 1_000L))
    }

    @Test
    fun `трафик вернулся через две секунды — лога не будет`() {
        // Замер 20-08 на эмуляторе: сеть вернули, туннель ожил через ~2 с. Отсчёт при этом
        // обнуляется вызывающим (noTrafficSinceMs = 0), и это состояние обязано молчать.
        assertFalse(LivenessDecision.shouldReportNoTraffic(noTrafficSinceMs = 0L, nowMs = 3_000L))
    }

    @Test
    fun `трафика нет полминуты — вот теперь льём`() {
        assertTrue(LivenessDecision.shouldReportNoTraffic(noTrafficSinceMs = 1_000L, nowMs = 31_000L))
    }

    /** Порог должен пережить и оживление (2 с), и пару тактов пинга (5 с), но не заставлять ждать минуту. */
    @Test
    fun `порог между десятью и тридцатью секундами`() {
        assertTrue(LivenessDecision.NO_TRAFFIC_DIAG_DELAY_MS in 10_000L..30_000L)
    }

    /** Экономия должна быть заметной: иначе правка не стоила бы риска. 4 → 1 при такте 5 с = −15 с. */
    @Test
    fun `выигрыш не меньше пятнадцати секунд`() {
        val tickMs = 5_000
        val saved = (LivenessDecision.MISSES_NORMAL - LivenessDecision.MISSES_WHEN_WATCHDOG_SURE) * tickMs
        assertTrue("выигрыш $saved мс — меньше обещанного", saved >= 15_000)
    }
}
