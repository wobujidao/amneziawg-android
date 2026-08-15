package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Приглашения (SPEC-0049): чистка кода и разбор отказов.
 *
 * Проверяем ровно то, что ломается молча: код диктуют голосом и перепечатывают с чужого экрана,
 * поэтому канон обязан совпадать с серверным (`NormalizeReferralCode`), иначе человек получит
 * «такого кода нет» на верный код. И причины отказа: под 409 у ядра ЧЕТЫРЕ разные беды, и
 * перепутать их значит посоветовать человеку не то действие.
 */
class ReferralOutcomeTest {

    private val json = MayakBackend.defaultJson

    @Test
    fun кодЧиститсяКакНаЯдре_регистрПробелыДефисы() {
        assertEquals("NP5EM3D2", normalizeReferralCode(" np5em-3d2 "))
        assertEquals("NP5EM3D2", normalizeReferralCode("NP5EM3D2"))
        assertEquals("NP5EM3D2", normalizeReferralCode("np5 em 3d2"))
    }

    @Test
    fun похожНаКод_толькоВосемьСимволовИзНашегоАлфавита() {
        assertTrue(looksLikeReferralCode("np5em-3d2"))
        assertFalse("короткий", looksLikeReferralCode("NP5EM3D"))
        assertFalse("длинный", looksLikeReferralCode("NP5EM3D22"))
        // 0, O, 1, I, L в алфавит НЕ входят намеренно: их путают на экране телефона.
        assertFalse("нулей в алфавите нет", looksLikeReferralCode("NP5EM3D0"))
        assertFalse("пустой", looksLikeReferralCode(""))
    }

    @Test
    fun подОдним409_четыреРазныеПричины() {
        assertEquals(ReferralFailure.DISABLED, referralFailure(409, "referral_disabled"))
        assertEquals(ReferralFailure.OWN_CODE, referralFailure(409, "referral_own_code"))
        assertEquals(ReferralFailure.ALREADY_INVITED, referralFailure(409, "referral_already_invited"))
        assertEquals(ReferralFailure.WINDOW_CLOSED, referralFailure(409, "referral_window_closed"))
    }

    @Test
    fun остальныеОтказы() {
        assertEquals(ReferralFailure.NOT_FOUND, referralFailure(404, "referral_code_not_found"))
        assertEquals(ReferralFailure.EMPTY_OR_MALFORMED, referralFailure(400, ""))
        assertEquals(ReferralFailure.NEED_LOGIN, referralFailure(401, ""))
        assertEquals(ReferralFailure.RATE_LIMITED, referralFailure(429, "rate_limited"))
        assertEquals(ReferralFailure.NO_CONNECTION, referralFailure(0, ""))
        assertEquals(ReferralFailure.RETRY_LATER, referralFailure(503, ""))
        // Незнакомая пара не притворяется знакомой: экран покажет текст ядра и не подскажет действие.
        assertEquals(ReferralFailure.UNKNOWN, referralFailure(418, "teapot"))
    }

    @Test
    fun ответЯдраРазбирается_иОтсутствующиеПоляНеРоняют() {
        val full = """
            {"enabled":true,"code":"NP5EM3D2","link":"https://mayaknetworks.com/?ref=NP5EM3D2",
             "invited":2,"rewarded":1,"earned_kopecks":10000,"inviter_kopecks":10000,
             "invitee_kopecks":5000,"hold_days":31,"applied_code":false,"apply_window_days":14}
        """.trimIndent()
        val info = json.decodeFromString(ReferralInfo.serializer(), full)
        assertTrue(info.enabled)
        assertEquals("NP5EM3D2", info.code)
        assertEquals(10000L, info.inviterKopecks)
        assertEquals(31, info.holdDays)
        assertFalse(info.appliedCode)

        // Выключенная программа приходит короче — и это НЕ ошибка разбора.
        val off = json.decodeFromString(ReferralInfo.serializer(), """{"enabled":false}""")
        assertFalse(off.enabled)
        assertEquals("", off.code)

        val applied = json.decodeFromString(ReferralApplied.serializer(), """{"applied":true,"promised_kopecks":5000}""")
        assertTrue(applied.applied)
        assertEquals(5000L, applied.promisedKopecks)
    }
}
