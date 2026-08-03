package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Контракт GET /v1/egress-check (2026-08-03): используется авто-включением РФ-пресета split-туннеля
 * (MayakActivity.maybeAutoEnableRuPreset) — там страна выхода читается по полю `country` из этого
 * ответа. Здесь фиксируем ровно то, что молча ломается при рефакторинге: имя JSON-поля и что
 * незнакомые ключи/пустая строка не роняют разбор.
 */
class EgressCheckTest {

    private val json = MayakBackend.defaultJson

    @Test
    fun parsesRuAnswer() {
        val r = json.decodeFromString(EgressCheck.serializer(), """{"ip":"5.44.1.2","country":"RU"}""")
        assertEquals("5.44.1.2", r.ip)
        assertEquals("RU", r.country)
    }

    @Test
    fun emptyCountry_meansUnknown_notCrash() {
        val r = json.decodeFromString(EgressCheck.serializer(), """{"ip":"1.2.3.4","country":""}""")
        assertEquals("", r.country)
    }

    @Test
    fun unknownKeys_dontBreakParsing() {
        val r = json.decodeFromString(
            EgressCheck.serializer(),
            """{"ip":"1.2.3.4","country":"NL","asn":"AS1234"}""",
        )
        assertEquals("NL", r.country)
    }
}
