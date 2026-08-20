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

    // ===== Что говорим о живости после СМЕНЫ СЕТИ =====
    //
    // Повод: правило «рукопожатие свежее 150 с → Защищено» написано для покоя, а в момент смены
    // сети врёт: рукопожатие состоялось на СТАРОЙ сети, сокет движка остался там же. Человек с
    // мёртвым туннелем видел «Защищено» ещё две с половиной минуты.

    private val OK = LivenessDecision.LIVE_OK
    private val UNKNOWN = LivenessDecision.LIVE_UNKNOWN
    private val NO_TRAFFIC = LivenessDecision.LIVE_NO_TRAFFIC
    private val NO_NETWORK = LivenessDecision.LIVE_NO_NETWORK

    private fun verdict(
        hasNetwork: Boolean = true,
        rxGrew: Boolean = false,
        handshakeAgeMs: Long = 10_000L,
        msSinceNetworkChange: Long? = null,
        proofPending: Boolean = false,
        warmingUp: Boolean = false,
    ) = LivenessDecision.verdict(hasNetwork, rxGrew, handshakeAgeMs, msSinceNetworkChange, proofPending, warmingUp)

    @Test
    fun `сети нет — это видно сразу и перевешивает всё`() {
        assertEquals(NO_NETWORK, verdict(hasNetwork = false, rxGrew = true))
    }

    @Test
    fun `в покое свежее рукопожатие доказывает жизнь`() {
        assertEquals(OK, verdict(handshakeAgeMs = 100_000L))
        assertEquals(NO_TRAFFIC, verdict(handshakeAgeMs = 151_000L))
    }

    @Test
    fun `рукопожатие со СТАРОЙ сети жизнь не доказывает`() {
        // Сеть сменилась 3 с назад, рукопожатию 40 с — оно из прошлой сети.
        assertFalse(LivenessDecision.handshakeProvesLife(handshakeAgeMs = 40_000L, msSinceNetworkChange = 3_000L))
    }

    @Test
    fun `рукопожатие, случившееся уже на НОВОЙ сети, жизнь доказывает`() {
        assertTrue(LivenessDecision.handshakeProvesLife(handshakeAgeMs = 5_000L, msSinceNetworkChange = 30_000L))
        assertEquals(OK, verdict(handshakeAgeMs = 5_000L, msSinceNetworkChange = 30_000L))
    }

    @Test
    fun `пока проверка после смены сети идёт — честное слово «проверяем», а не «Защищено»`() {
        assertEquals(UNKNOWN, verdict(handshakeAgeMs = 40_000L, msSinceNetworkChange = 3_000L, proofPending = true))
    }

    @Test
    fun `проверка не нашла жизни — говорим «трафика нет»`() {
        assertEquals(NO_TRAFFIC, verdict(handshakeAgeMs = 40_000L, msSinceNetworkChange = 9_000L, proofPending = false))
    }

    /**
     * Главный сторож этой правки. В первой версии режим «сеть только что сменилась» гасился по
     * таймеру, и вердикт «трафика нет» жил ПОЛСЕКУНДЫ: следующий такт снова верил старому
     * рукопожатию и возвращал «Защищено» (видно в живом логе эмулятора 21-08, строки
     * «сервер не ответил → трафика нет» и через 0,45 с «живость: 2 → 1»).
     *
     * Поэтому: сколько бы времени ни прошло, пока жизнь не доказана — слово не меняется.
     */
    @Test
    fun `вердикт «трафика нет» держится, пока жизнь не доказана`() {
        for (passed in listOf(10_000L, 60_000L, 600_000L, 3_600_000L)) {
            assertEquals(
                "прошло ${passed}мс после смены сети, рукопожатие всё то же старое",
                NO_TRAFFIC,
                verdict(handshakeAgeMs = 40_000L + passed, msSinceNetworkChange = passed),
            )
        }
    }

    @Test
    fun `рост rx закрывает вопрос немедленно`() {
        assertEquals(OK, verdict(rxGrew = true, handshakeAgeMs = 999_000L, msSinceNetworkChange = 1_000L))
    }

    @Test
    fun `только что поднятому туннелю даём фору, а не приговор`() {
        assertEquals(UNKNOWN, verdict(handshakeAgeMs = 999_000L, warmingUp = true))
    }
}
