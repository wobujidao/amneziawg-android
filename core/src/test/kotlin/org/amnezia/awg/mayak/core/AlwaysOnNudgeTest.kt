package org.amnezia.awg.mayak.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило показа разговора про постоянное подключение. Проверяем ровно то, что ломается на людях:
 * заговорили не вовремя (до первого подключения), заговорили повторно после «Позже», заговорили с
 * тем, у кого настройка уже стоит.
 */
class AlwaysOnNudgeTest {

    @Test
    fun `после первого успешного подключения разговор заводим`() {
        assertTrue(
            AlwaysOnNudge.shouldShow(
                decision = AlwaysOnNudge.NOT_ASKED,
                connectCount = 1,
                alwaysOnProven = false,
            )
        )
    }

    @Test
    fun `до первого успешного подключения молчим`() {
        // Свежая установка, человек ещё ни разу не подключился: объяснять «связь рвётся во сне»
        // не о чем, а окно поверх пустого экрана читается как реклама настроек.
        assertFalse(
            AlwaysOnNudge.shouldShow(
                decision = AlwaysOnNudge.NOT_ASKED,
                connectCount = 0,
                alwaysOnProven = false,
            )
        )
    }

    /**
     * ГЛАВНЫЙ тест файла. «Позже» — это не «спроси меня на следующем подключении»: карточка
     * всплывала бы после КАЖДОГО коннекта, а подключается человек по многу раз в день.
     */
    @Test
    fun `после «Позже» больше не показываем — ни сейчас, ни через сотню подключений`() {
        assertFalse(AlwaysOnNudge.shouldShow(AlwaysOnNudge.POSTPONED, connectCount = 1, alwaysOnProven = false))
        assertFalse(AlwaysOnNudge.shouldShow(AlwaysOnNudge.POSTPONED, connectCount = 100, alwaysOnProven = false))
    }

    @Test
    fun `повторный запуск приложения решение не забывает`() {
        // Флаг живёт в SharedPreferences, то есть переживает перезапуск процесса. Здесь это
        // выражено тем, что правило смотрит ТОЛЬКО на сохранённое решение и счётчик — никакого
        // состояния «в этой сессии уже показывали» в нём нет, забыть решение попросту нечему.
        val afterRestart = AlwaysOnNudge.shouldShow(AlwaysOnNudge.POSTPONED, connectCount = 7, alwaysOnProven = false)
        assertFalse(afterRestart)
    }

    @Test
    fun `сказавшему «уже включено» не напоминаем`() {
        assertFalse(AlwaysOnNudge.shouldShow(AlwaysOnNudge.CONFIRMED, connectCount = 1, alwaysOnProven = false))
    }

    @Test
    fun `ушедшему в системные настройки не напоминаем`() {
        assertFalse(AlwaysOnNudge.shouldShow(AlwaysOnNudge.SENT_TO_SETTINGS, connectCount = 1, alwaysOnProven = false))
    }

    /**
     * У кого система ПОДТВЕРДИЛА постоянное подключение — не заводим разговор вовсе, даже на чистой
     * установке. Обратное (не подтвердила) значением не является: «прочитать не дали» и «выключено»
     * для приложения неразличимы, поэтому в остальных тестах alwaysOnProven=false означает
     * «неизвестно», и при неизвестности мы показываем.
     */
    @Test
    fun `подтверждённое системой постоянное подключение закрывает разговор`() {
        assertFalse(AlwaysOnNudge.shouldShow(AlwaysOnNudge.NOT_ASKED, connectCount = 1, alwaysOnProven = true))
    }
}
