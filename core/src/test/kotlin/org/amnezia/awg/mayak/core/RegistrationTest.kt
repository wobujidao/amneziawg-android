// Сторожа регистрации в приложении (SPEC-0048). Три вещи, каждая из которых ломается МОЛЧА.
//
// 1️⃣ ПОЛИТИКА ПАРОЛЯ = СЕРВЕРНАЯ. Таблица ниже — дословно примеры из `internal/accounts/validate_test.go`
//    ядра. Разъедется — человек нажмёт «Создать аккаунт», подождёт, пройдёт проверку «не робот» и
//    получит `weak_password` с потраченным одноразовым токеном капчи. Никакого падения, просто
//    воронка теряет людей на последнем шаге.
//
// 2️⃣ ИМЕНА ПОЛЕЙ ЗАПРОСА. Это стык с ДРУГИМ репозиторием: серверную половину пишет ядро. 12-08 у
//    нас уже было ровно это (приложение спрашивало `days`, сервер клал `grace_days`) — поэтому имена
//    проверяются сериализацией НАСТОЯЩЕГО запроса, а не чтением глазами. Здесь цена ошибки выше:
//    неузнанное `consent` сервер прочтёт как «согласия нет».
//
// 3️⃣ ОТВЕТ БЕЗ ТОКЕНА — НЕ ОШИБКА. У сервера есть ветка «учётка создана, а сессию выдать не смогли»:
//    приходят только номер и message. Разбор обязан её переживать, иначе экран покажет неудачу на
//    уже существующем аккаунте и человек нажмёт «повторить» — заведя второй.
package org.amnezia.awg.mayak.core

import java.security.SecureRandom
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationTest {

    // ===== 1. Пароль: та же политика, что на сервере =====

    @Test
    fun `годные пароли — те же, что принимает сервер`() {
        // internal/accounts/validate_test.go, список ok
        for (p in listOf("abc12345", "Pa\$\$w0rd", "longenough1!", "паролькириллица1")) {
            assertTrue("сервер принимает «$p», а приложение отвергает", PasswordPolicy.isStrong(p))
        }
    }

    @Test
    fun `негодные пароли — те же, что отвергает сервер`() {
        // internal/accounts/validate_test.go, список bad. Каждый — своя причина:
        // пусто, коротко, один символ, только цифры, только буквы.
        for (p in listOf("", "short1", "aaaaaaaa", "11111111", "onlyletters", "12345678")) {
            assertFalse("сервер отвергает «$p», а приложение пропускает", PasswordPolicy.isStrong(p))
        }
    }

    @Test
    fun `длина считается В БАЙТАХ, как в Go`() {
        // Go считает len(pw) — это UTF-8-байты. Семь кириллических букв с цифрой = 15 байт: сервер
        // такой пароль ПРИМЕТ, хотя символов в нём меньше восьми. Считали бы символы — приложение
        // отвергало бы то, что сервер разрешает (и наоборот на границе).
        assertTrue(PasswordPolicy.isStrong("парол1"))
        assertEquals(11, "парол1".toByteArray(Charsets.UTF_8).size)
        // Потолок: 200 байт можно, 201 — нельзя.
        assertTrue(PasswordPolicy.isStrong("a1".repeat(100)))
        assertFalse(PasswordPolicy.isStrong("a1".repeat(100) + "b"))
    }

    // ===== 2. Генератор =====

    @Test
    fun `придуманный пароль всегда проходит нашу же политику`() {
        val random = SecureRandom()
        val seen = HashSet<String>()
        repeat(500) {
            val pw = PasswordPolicy.generate(random)
            assertEquals(PasswordPolicy.GENERATED_LENGTH, pw.length)
            assertTrue("генератор выдал пароль, который наш экран отвергнет: $pw", PasswordPolicy.isStrong(pw))
            assertTrue("нет цифры: $pw", pw.any { it.isDigit() })
            assertTrue("нет буквы: $pw", pw.any { it.isLetter() })
            seen.add(pw)
        }
        // Совпадений быть не должно: одинаковые пароли означали бы, что источник случайности мёртв
        // (например SecureRandom подменили заглушкой) — а это тихая катастрофа, не опечатка.
        assertEquals(500, seen.size)
    }

    @Test
    fun `в придуманном пароле нет знаков, которые путают при переписывании`() {
        val random = SecureRandom()
        repeat(200) {
            for (c in PasswordPolicy.generate(random)) {
                assertFalse("похожий на другой знак «$c» в пароле, который переписывают руками", c in "0Oo1lI")
            }
        }
    }

    // ===== 3. Кнопка =====

    @Test
    fun `без согласия кнопка не активна`() {
        assertFalse(RegisterForm.canSubmit(consentChecked = false, requestInFlight = false))
        assertTrue(RegisterForm.canSubmit(consentChecked = true, requestInFlight = false))
    }

    @Test
    fun `во время запроса кнопка не активна — иначе второй аккаунт`() {
        assertFalse(RegisterForm.canSubmit(consentChecked = true, requestInFlight = true))
    }

    // ===== 4. Контракт с ядром =====

    /**
     * Тем же кодировщиком, каким запрос уходит НА САМОМ ДЕЛЕ (MayakBackend.defaultJson), и вторым —
     * голым `Json`: у `captcha_token` есть значение по умолчанию, и обычный `Json` умолчания не
     * сериализует. Расхождение здесь означало бы, что при выключенной капче поле вообще не уезжает.
     */
    @Test
    fun `запрос регистрации несёт РОВНО поля контракта`() {
        val request = RegisterAnonRequest(password = "abc12345", consent = true, captchaToken = "0.тест")
        assertEquals(
            """{"password":"abc12345","consent":true,"captcha_token":"0.тест"}""",
            MayakBackend.defaultJson.encodeToString(RegisterAnonRequest.serializer(), request),
        )
        assertEquals(
            """{"password":"abc12345","consent":true,"captcha_token":"0.тест"}""",
            Json { encodeDefaults = true }.encodeToString(RegisterAnonRequest.serializer(), request),
        )
    }

    @Test
    fun `ответ 201 разбирается и с токеном, и без него`() {
        val json = MayakBackend.defaultJson
        val full = json.decodeFromString(
            RegisterAnonResponse.serializer(),
            """{"account_number":"019-785-686","token":"живой-токен","trial_days":7}""",
        )
        assertEquals("019-785-686", full.accountNumber)
        assertEquals("живой-токен", full.token)
        assertEquals(7, full.trialDays)

        // Ветка «аккаунт создан, сессия не выдана»: токена нет, зато есть message. Аккаунт УЖЕ есть.
        val noSession = json.decodeFromString(
            RegisterAnonResponse.serializer(),
            """{"account_number":"019-785-686","message":"аккаунт создан — войдите по номеру и паролю"}""",
        )
        assertEquals("019-785-686", noSession.accountNumber)
        assertTrue("токена нет — экран обязан это увидеть, а не упасть", noSession.token.isEmpty())
        assertEquals(0, noSession.trialDays)
        assertTrue(noSession.message.isNotBlank())
    }

    @Test
    fun `ответ ручки капчи разбирается в оба состояния`() {
        val json = MayakBackend.defaultJson
        val on = json.decodeFromString(CaptchaInfo.serializer(), """{"enabled":true,"sitekey":"0xAAAA1111"}""")
        assertTrue(on.enabled)
        assertEquals("0xAAAA1111", on.sitekey)
        val off = json.decodeFromString(CaptchaInfo.serializer(), """{"enabled":false,"sitekey":""}""")
        assertFalse(off.enabled)
        assertTrue(off.sitekey.isEmpty())
    }
}
