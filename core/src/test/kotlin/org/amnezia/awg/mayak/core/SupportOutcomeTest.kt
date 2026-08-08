package org.amnezia.awg.mayak.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форма обращения в поддержку: что человек видит по ответу ядра.
 *
 * Почему это стоит тестов. Ядро отдаёт СЕМЬ разных отказов под тремя статусами: 400 — это и «тема
 * разъехалась», и «текст короткий», и «текст длинный»; 503 — и «канала отправки нет вовсе», и
 * «письмо сейчас не ушло». Советы человеку в этих парах ПРОТИВОПОЛОЖНЫЕ: в одном случае надо править
 * текст, в другом ждать, в третьем нажать «Повторить», в четвёртом уйти на запасной путь письмом.
 * Ветка «по статусу» дала бы один совет на всё — ровно тот дефект, что был с 402 (AccessDenialTest).
 */
class SupportOutcomeTest {
    private val json = MayakBackend.defaultJson

    @Test
    fun `под одним 400 живут три разные беды — различает только code`() {
        val topic = apiError(400, """{"error":"выберите тему обращения из списка","code":"unknown_topic"}""", json)
        val short = apiError(400, """{"error":"опишите проблему хотя бы одной фразой","code":"message_too_short"}""", json)
        val long = apiError(400, """{"error":"обращение длиннее 4000 символов","code":"message_too_long"}""", json)

        // По статусу они НЕРАЗЛИЧИМЫ — вот почему ветвиться по нему нельзя.
        assertEquals(topic.status, short.status)
        assertEquals(short.status, long.status)

        assertEquals(SupportFailure.TOPIC_REJECTED, supportFailure(topic))
        assertEquals(SupportFailure.TOO_SHORT, supportFailure(short))
        assertEquals(SupportFailure.TOO_LONG, supportFailure(long))
        assertNotEquals(supportFailure(topic), supportFailure(short))
    }

    @Test
    fun `под одним 503 живут «канала нет» и «сейчас не ушло» — и советы разные`() {
        val off = apiError(503, """{"error":"отправка обращений сейчас недоступна","code":"support_unavailable"}""", json)
        val failed = apiError(503, """{"error":"не удалось отправить обращение","code":"send_failed"}""", json)
        val overloaded = apiError(503, """{"error":"сервис перегружен","code":"overloaded"}""", json)

        assertEquals(SupportFailure.CHANNEL_OFF, supportFailure(off))
        assertEquals(SupportFailure.RETRY_LATER, supportFailure(failed))
        assertEquals(SupportFailure.RETRY_LATER, supportFailure(overloaded))

        // «Повторить» имеет смысл только там, где повтор может сработать.
        assertFalse(supportFailure(off).canRetrySameText)
        assertTrue(supportFailure(failed).canRetrySameText)
    }

    @Test
    fun `лимит, чужая сессия и чужое обращение — свои исходы`() {
        val acct = apiError(429, """{"error":"вы уже отправили несколько обращений","code":"support_rate_limited"}""", json)
        val byIp = apiError(429, """{"error":"слишком много запросов, попробуйте позже","code":"rate_limited"}""", json)
        val unauth = apiError(401, """{"error":"требуется авторизация","code":"unauthorized"}""", json)
        val alien = apiError(404, """{"error":"обращение не найдено","code":"not_found"}""", json)

        // Оба лимита ядра (по аккаунту и по адресу) человеку означают одно: подождать.
        assertEquals(SupportFailure.RATE_LIMITED, supportFailure(acct))
        assertEquals(SupportFailure.RATE_LIMITED, supportFailure(byIp))
        assertEquals(SupportFailure.NEED_LOGIN, supportFailure(unauth))
        assertEquals(SupportFailure.NOT_FOUND, supportFailure(alien))
        assertFalse(supportFailure(acct).canRetrySameText)
    }

    @Test
    fun `нет связи — это не отказ ядра`() {
        assertEquals(SupportFailure.NO_CONNECTION, supportFailure(IOException("сеть недоступна")))
        assertEquals(
            SupportFailure.NO_CONNECTION,
            supportFailure(NoReachableHostException("ни один домен ядра недоступен (2)")),
        )
        // status = 0 — «ответа не было вовсе», отдельно от любого HTTP-кода.
        assertEquals(SupportFailure.NO_CONNECTION, supportFailure(0, ""))
        // Повторить осмысленно: сеть могла появиться.
        assertTrue(SupportFailure.NO_CONNECTION.canRetrySameText)
    }

    @Test
    fun `незнакомый признак не выдумывает причину`() {
        // Ядро завело новый код, приложение о нём не знает: показываем текст ядра, действие не врём.
        assertEquals(SupportFailure.UNKNOWN, supportFailure(400, "some_new_code"))
        assertEquals(SupportFailure.UNKNOWN, supportFailure(403, "account_blocked"))
        assertEquals(SupportFailure.UNKNOWN, supportFailure(Throwable("что-то своё")))
    }

    @Test
    fun `тема разъехалась — переотправляем «Другое», а не спорим с ядром`() {
        // Списки тем живут в трёх местах (ядро, кабинет, приложение). Разъедутся — человек упирается
        // в отказ, который ему нечем исправить: других тем на экране нет. Клиент уходит на «Другое».
        assertTrue(supportResendAsOther(SupportFailure.TOPIC_REJECTED))
        assertFalse(supportResendAsOther(SupportFailure.TOO_SHORT))
        assertFalse(supportResendAsOther(SupportFailure.RATE_LIMITED))
        // Приют обязан быть в списке тем, иначе переотправлять некуда.
        assertTrue(SupportTopics.OTHER in SupportTopics.CODES)
    }

    @Test
    fun `«когда снова» округляем вверх и не обещаем ноль минут`() {
        assertEquals(60, retryAfterMinutes(3600)) // Retry-After ядра для лимита обращений — час
        assertEquals(1, retryAfterMinutes(1))     // «через 0 минут» — обещание, которое мы не сдержим
        assertEquals(2, retryAfterMinutes(61))
        assertEquals(0, retryAfterMinutes(0))     // 0 = ядро не сказало; экран не выдумает число
        assertEquals(0, retryAfterMinutes(-5))
    }

    @Test
    fun `Retry-After читается из заголовка ответа, а не из тела`() {
        assertEquals(3600, parseRetryAfter("3600"))
        assertEquals(3600, parseRetryAfter(" 3600 "))
        // HTTP-дата (RFC 7231 разрешает и её) числом не притворяется: 0 = «не сказали».
        assertEquals(0, parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(0, parseRetryAfter(null))
        assertEquals(0, parseRetryAfter(""))
        assertEquals(0, parseRetryAfter("-10"))
    }

    @Test
    fun `отказ ядра доносит до клиента и код, и Retry-After`() {
        val e = apiError(
            429,
            """{"error":"вы уже отправили несколько обращений","code":"support_rate_limited"}""",
            json,
            retryAfterSec = 3600,
        )
        assertEquals("support_rate_limited", e.code)
        assertEquals(3600, e.retryAfterSec)
        assertEquals(60, retryAfterMinutes(e.retryAfterSec))
    }
}
