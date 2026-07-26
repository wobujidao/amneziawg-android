package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт настроек аккаунта и статуса доступа с ядром (internal/clientapi/settings.go, handleSync).
 * Тут проверяем ровно то, что молча ломается при рефакторинге: имена JSON-полей и различие
 * «не трогай адреса» (null) vs «сотри адреса» ("").
 */
class AccountSettingsTest {

    private val json = MayakBackend.defaultJson

    @Test
    fun settingsResponse_parsesCoreFieldNames() {
        val s = json.decodeFromString(
            AccountSettings.serializer(),
            """{"dns_mode":"family","dns_custom":"1.1.1.1, 9.9.9.9"}""",
        )
        assertEquals(AccountSettings.DNS_FAMILY, s.dnsMode)
        assertEquals("1.1.1.1, 9.9.9.9", s.dnsCustom)
    }

    @Test
    fun settingsUpdate_withoutAddresses_sendsNull_soCoreKeepsThem() {
        // Ядро отличает отсутствующее/null-поле («переключи профиль») от пустой строки («сотри мои
        // адреса»). Пришлём "" при простом переключении — у человека молча пропадёт свой резолвер.
        val body = json.encodeToString(
            SettingsUpdate.serializer(),
            SettingsUpdate(AccountSettings.DNS_ADBLOCK),
        )
        assertTrue(body, body.contains("\"dns_mode\":\"adblock\""))
        assertTrue(body, body.contains("\"dns_custom\":null"))
    }

    @Test
    fun settingsUpdate_withAddresses_sendsThem() {
        val body = json.encodeToString(
            SettingsUpdate.serializer(),
            SettingsUpdate(AccountSettings.DNS_CUSTOM, "8.8.8.8"),
        )
        assertTrue(body, body.contains("\"dns_custom\":\"8.8.8.8\""))
    }

    @Test
    fun accountStatus_parsesSyncResponse_withUnknownKeys() {
        // В ответе /v1/client/sync бывает ещё и список доменов — незнакомые ключи не должны ронять разбор.
        val st = json.decodeFromString(
            AccountStatus.serializer(),
            """{"access":"active","devices_used":2,"device_limit":5,
               "valid_until":"2026-08-02T10:00:00Z","domains":["a.example"]}""",
        )
        assertTrue(st.active())
        assertEquals(2, st.devicesUsed)
        assertEquals(5, st.deviceLimit)
        assertEquals("2026-08-02T10:00:00Z", st.validUntil)
    }

    @Test
    fun daysLeft_roundsUp_soLastDayIsNotZero() {
        val st = AccountStatus(access = "active", validUntil = "2026-08-02T10:00:00Z")
        val now = java.time.OffsetDateTime.parse("2026-08-01T22:00:00Z").toInstant().toEpochMilli()
        assertEquals(1, st.daysLeft(now)) // осталось 12 часов — это «1 день», а не «0»
    }

    @Test
    fun daysLeft_wholeDays() {
        val st = AccountStatus(access = "active", validUntil = "2026-08-08T10:00:00Z")
        val now = java.time.OffsetDateTime.parse("2026-08-01T10:00:00Z").toInstant().toEpochMilli()
        assertEquals(7, st.daysLeft(now))
    }

    @Test
    fun daysLeft_expired_isZero_andMissingDateIsNull() {
        val past = AccountStatus(access = "expired", validUntil = "2026-07-01T10:00:00Z")
        val now = java.time.OffsetDateTime.parse("2026-08-01T10:00:00Z").toInstant().toEpochMilli()
        assertEquals(0, past.daysLeft(now))

        // Доступ без срока (выдан админом бессрочно) — «дней» не существует, а не «0 дней».
        assertNull(AccountStatus(access = "active").daysLeft(now))
        // Мусор вместо даты не должен ронять экран.
        assertNull(AccountStatus(access = "active", validUntil = "позавчера").daysLeft(now))
    }

    @Test
    fun offsetDate_isParsed_notOnlyZulu() {
        // PG отдаёт timestamptz со смещением — форма «+03:00» встречается в ответе ядра наравне с Z.
        val st = AccountStatus(access = "active", validUntil = "2026-08-02T13:00:00+03:00")
        val now = java.time.OffsetDateTime.parse("2026-08-01T10:00:00Z").toInstant().toEpochMilli()
        assertEquals(1, st.daysLeft(now))
    }
}
