package org.amnezia.awg.mayak.core

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт списка устройств с ядром (GET /v1/client/devices → internal/cprepo.DeviceInfo).
 *
 * Экран «Мои устройства» — единственное место, где человек может освободить занятый слот, не уходя
 * во внешний браузер. Молча разъехавшиеся имена полей превратили бы его в пустой список, то есть
 * вернули бы ровно тот тупик, ради которого он сделан.
 */
class DeviceListTest {

    private val json = MayakBackend.defaultJson

    @Test
    fun parsesCoreFieldNames() {
        val list = json.decodeFromString(
            ListSerializer(DeviceItem.serializer()),
            """[{"id":42,"label":"Pixel 5","pubkey":"abcd…wxyz",
                 "created_at":"2026-08-01T10:00:00Z","last_seen":"2026-08-07T04:00:00Z"}]""",
        )
        assertEquals(1, list.size)
        assertEquals(42L, list[0].id)
        assertEquals("Pixel 5", list[0].label)
        assertEquals(
            java.time.OffsetDateTime.parse("2026-08-07T04:00:00Z").toInstant().toEpochMilli(),
            list[0].lastSeenMs(),
        )
    }

    @Test
    fun deviceThatNeverConnected_hasNoLastSeen_andDoesNotBreakParsing() {
        // Ядро опускает last_seen (omitempty на nil-указателе) — у только что заведённого устройства
        // поля в ответе НЕТ вовсе. Экран должен показать «ни разу не подключалось», а не упасть.
        val list = json.decodeFromString(
            ListSerializer(DeviceItem.serializer()),
            """[{"id":7,"label":"Роутер","pubkey":"qwer…tyui","created_at":"2026-08-06T09:30:00+03:00"}]""",
        )
        assertNull(list[0].lastSeenMs())
        assertEquals(
            java.time.OffsetDateTime.parse("2026-08-06T09:30:00+03:00").toInstant().toEpochMilli(),
            list[0].createdAtMs(),
        )
    }

    @Test
    fun explicitNullAndGarbageDates_doNotBreakParsing() {
        val list = json.decodeFromString(
            ListSerializer(DeviceItem.serializer()),
            """[{"id":1,"label":"","last_seen":null,"created_at":"позавчера","новое_поле":true}]""",
        )
        assertNull(list[0].lastSeenMs())
        assertNull(list[0].createdAtMs())
        assertEquals("", list[0].label) // пустое имя — UI подставит «Устройство без имени»
    }
}
