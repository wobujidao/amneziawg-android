package org.amnezia.awg.mayak.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вход при включённой двухфакторной аутентификации.
 *
 * Разбор 2026-07-27: человек включал 2FA в кабинете и терял вход в приложение. Ядро отвечало
 * 401 {"code":"totp_required"} — «пароль верен, дайте код», — но клиент выбрасывал поле `code` и
 * показывал ЛЮБОЙ 401 как «Неверный email или пароль». Человек шёл сбрасывать пароль, сброс
 * проходил, вход всё равно не работал.
 *
 * Тест держит две вещи, на которых стоит починка: причина отказа доезжает до клиента машинно, и
 * код уходит на сервер в поле totp_code.
 */
class LoginTotpTest {
    private val json = MayakBackend.defaultJson

    @Test
    fun `нужен код 2FA отличим от неверного пароля`() {
        val needCode = apiError(401, """{"error":"нужен код двухфакторной аутентификации","code":"totp_required"}""", json)
        val badPassword = apiError(401, """{"error":"неверный логин или пароль"}""", json)

        assertEquals("totp_required", needCode.code)
        assertEquals(401, needCode.status)
        // Ключевое: по одному лишь HTTP-коду эти два случая неразличимы — различает именно code.
        assertEquals(needCode.status, badPassword.status)
        assertTrue("отказ по паролю не должен маскироваться под 2FA", badPassword.code.isEmpty())
    }

    @Test
    fun `неверный код 2FA приходит отдельным признаком`() {
        val e = apiError(401, """{"error":"неверный код двухфакторной аутентификации","code":"totp_invalid"}""", json)
        assertEquals("totp_invalid", e.code)
    }

    @Test
    fun `тело без признака и мусор не роняют разбор`() {
        assertEquals("", apiError(500, """{"error":"внутренняя ошибка"}""", json).code)
        assertEquals("внутренняя ошибка", apiError(500, """{"error":"внутренняя ошибка"}""", json).message)
        // не-JSON (прокси/заглушка провайдера) → остаётся понятный фолбэк, а не падение
        assertEquals("HTTP 502", apiError(502, "<html>bad gateway</html>", json).message)
    }

    @Test
    fun `код уходит в теле запроса полем totp_code`() {
        val body = json.encodeToString(
            LoginRequest.serializer(),
            LoginRequest("a@b.ru", "pw", "123456"),
        )
        assertTrue("ожидали поле totp_code в теле, got: $body", body.contains(""""totp_code":"123456""""))
    }

    @Test
    fun `без кода поле остаётся пустым и ядро само решает нужен ли код`() {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest("a@b.ru", "pw"))
        assertTrue("ожидали пустой totp_code, got: $body", body.contains(""""totp_code":""""))
    }
}
