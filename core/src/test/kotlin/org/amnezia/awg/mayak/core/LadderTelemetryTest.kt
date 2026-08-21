package org.amnezia.awg.mayak.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Исход лестницы в телеметри-биконе: дельты одной попытки, накопление и СБОРКА ПОЛЕЗНОЙ НАГРУЗКИ —
 * то есть какие JSON-ключи реально уйдут на ядро. Сериализуем тем же Json, что и боевой код
 * (MayakBackend.defaultJson с encodeDefaults=true), — проверяем эффект, а не намерение.
 */
class LadderTelemetryTest {

    private val json = MayakBackend.defaultJson

    private fun baseBeacon() = TelemetryRequest(
        appVersion = "0.4.2",
        versionCode = 149,
        deviceModel = "Test Phone",
        osVersion = "android 15",
        locale = "ru-RU",
        installSource = "Play",
        connectCount = 10,
        activeDays = 3,
        fallbackConnects = 1,
        ruDirectEnabled = true,
    )

    private fun payload(req: TelemetryRequest) =
        json.parseToJsonElement(json.encodeToString(TelemetryRequest.serializer(), req)).jsonObject

    // ── Три канонических сценария лестницы ────────────────────────────────────────────────────────

    @Test
    fun успехСПервойСтупени_толькоDirectOkИВремя() {
        val delta = LadderTelemetry.attemptOutcome(
            failedRungs = emptyList(),
            successRung = LadderTelemetry.ROUTE_DIRECT,
            elapsedMs = 3_200,
        )
        assertEquals(1, delta.directOk)
        assertEquals(0, delta.relayOk + delta.fallbackOk)
        assertEquals(0, delta.directFail + delta.relayFail + delta.fallbackFail)
        assertEquals(0, delta.none)
        assertEquals(3_200L, delta.successMsSum)

        val p = payload(baseBeacon().withLadder(delta))
        assertEquals(1, p.getValue("ladder_direct_ok").jsonPrimitive.int)
        assertEquals(0, p.getValue("ladder_direct_fail").jsonPrimitive.int)
        assertEquals(0, p.getValue("ladder_none").jsonPrimitive.int)
        assertEquals(3_200, p.getValue("ladder_ms_avg").jsonPrimitive.int)
    }

    @Test
    fun успехСоВторойПослеПровалаПервой_directFailИRelayOk() {
        val delta = LadderTelemetry.attemptOutcome(
            failedRungs = listOf(LadderTelemetry.ROUTE_DIRECT),
            successRung = LadderTelemetry.ROUTE_RELAY,
            elapsedMs = 9_500,
        )
        assertEquals(0, delta.directOk)
        assertEquals(1, delta.relayOk)
        assertEquals(1, delta.directFail)
        assertEquals(0, delta.relayFail + delta.fallbackFail)
        assertEquals(0, delta.none)
        assertEquals(9_500L, delta.successMsSum)

        val p = payload(baseBeacon().withLadder(delta))
        assertEquals(1, p.getValue("ladder_relay_ok").jsonPrimitive.int)
        assertEquals(1, p.getValue("ladder_direct_fail").jsonPrimitive.int)
        assertEquals(0, p.getValue("ladder_direct_ok").jsonPrimitive.int)
        assertEquals(9_500, p.getValue("ladder_ms_avg").jsonPrimitive.int)
    }

    @Test
    fun полныйПровал_всеFailПлюсNone_иВремяНеУчтено() {
        val delta = LadderTelemetry.attemptOutcome(
            failedRungs = listOf(
                LadderTelemetry.ROUTE_DIRECT,
                LadderTelemetry.ROUTE_RELAY,
                LadderTelemetry.ROUTE_FALLBACK,
            ),
            successRung = null,
            elapsedMs = 40_000, // время до «сдались» в среднее успеха попадать НЕ должно
        )
        assertEquals(0, delta.successes)
        assertEquals(1, delta.directFail)
        assertEquals(1, delta.relayFail)
        assertEquals(1, delta.fallbackFail)
        assertEquals(1, delta.none)
        assertEquals(0L, delta.successMsSum)

        val p = payload(baseBeacon().withLadder(delta))
        assertEquals(1, p.getValue("ladder_none").jsonPrimitive.int)
        assertEquals(1, p.getValue("ladder_fallback_fail").jsonPrimitive.int)
        assertEquals(0, p.getValue("ladder_ms_avg").jsonPrimitive.int)
    }

    // ── Накопление и среднее ──────────────────────────────────────────────────────────────────────

    @Test
    fun накоплениеСкладываетДельты_иСреднееСчитаетсяПоУспехам() {
        val sum = LadderCounters() +
            LadderTelemetry.attemptOutcome(emptyList(), LadderTelemetry.ROUTE_DIRECT, 2_000) +
            LadderTelemetry.attemptOutcome(listOf(LadderTelemetry.ROUTE_DIRECT), LadderTelemetry.ROUTE_RELAY, 10_000) +
            LadderTelemetry.attemptOutcome(
                listOf(LadderTelemetry.ROUTE_DIRECT, LadderTelemetry.ROUTE_RELAY, LadderTelemetry.ROUTE_FALLBACK),
                null,
                40_000,
            )
        assertEquals(1, sum.directOk)
        assertEquals(1, sum.relayOk)
        assertEquals(2, sum.directFail)
        assertEquals(1, sum.relayFail)
        assertEquals(1, sum.fallbackFail)
        assertEquals(1, sum.none)
        assertEquals(2, sum.successes)
        assertEquals(6_000, sum.avgSuccessMs) // (2000+10000)/2 — полный провал среднее не размывает
    }

    @Test
    fun отрицательноеВремя_неЛомаетСчётчик() {
        // Отрицательный elapsed невозможен на SystemClock.elapsedRealtime, но чужим значениям не верим.
        val delta = LadderTelemetry.attemptOutcome(emptyList(), LadderTelemetry.ROUTE_DIRECT, -5)
        assertEquals(0L, delta.successMsSum)
    }

    // ── Совместимость со СТАРЫМ ядром (DisallowUnknownFields → 400 на незнакомый ключ) ────────────

    @Test
    fun урезанныйБикон_вообщеБезLadderКлючей_ключиСтарогоКонтрактаНаМесте() {
        val p = payload(baseBeacon().withLadder(LadderCounters(directOk = 7)).withoutLadder())
        assertFalse("после withoutLadder не должно остаться ни одного ladder-ключа",
            p.keys.any { it.startsWith("ladder_") })
        // Ровно 10 ключей старого контракта — ни лишних (400 от старого ядра), ни пропущенных.
        assertEquals(
            setOf(
                "app_version", "version_code", "device_model", "os_version", "locale",
                "install_source", "connect_count", "active_days", "fallback_connects", "ru_direct_enabled",
            ),
            p.keys,
        )
    }

    @Test
    fun полныйБикон_несётВсеВосемьLadderКлючей() {
        val p = payload(baseBeacon().withLadder(LadderCounters()))
        val expected = setOf(
            "ladder_direct_ok", "ladder_relay_ok", "ladder_fallback_ok",
            "ladder_direct_fail", "ladder_relay_fail", "ladder_fallback_fail",
            "ladder_none", "ladder_ms_avg",
        )
        assertTrue("в полном биконе не хватает ladder-ключей: ${expected - p.keys}",
            p.keys.containsAll(expected))
    }

    @Test
    fun биконБезВызоваWithLadder_остаётсяСтарымКонтрактом() {
        // Пути, где счётчики не приложили (напр. будущий вызов из другого места), не должны
        // случайно отправить null-ключи и споткнуться о строгий парсер ядра.
        val p = payload(baseBeacon())
        assertFalse(p.keys.any { it.startsWith("ladder_") })
    }
}

// След лестницы для ЖУРНАЛА ПОДКЛЮЧЕНИЙ в панели (ядро 0167). Строка уезжает на сервер и там
// показывается человеку как есть, поэтому формат держим в одном месте и под тестом.
class LadderTraceTest {

    @Test
    fun `успех с первой ступени — одна отметка`() {
        assertEquals("прямая ✓", LadderTelemetry.trace(emptyList(), LadderTelemetry.ROUTE_DIRECT))
    }

    @Test
    fun `порядок следа тот же, что у лестницы — сперва провалы, потом успех`() {
        val след = LadderTelemetry.trace(
            listOf(LadderTelemetry.ROUTE_DIRECT, LadderTelemetry.ROUTE_RELAY),
            LadderTelemetry.ROUTE_FALLBACK,
        )
        assertEquals("прямая ✗ · РФ ✗ · мост ✓", след)
    }

    // 🔴 Не вышла ни одна — след обязан остаться ЧЕСТНЫМ перечислением провалов, без выдуманной
    // галочки в конце: журнал читают как доказательство, а не как пересказ.
    @Test
    fun `не вышла ни одна ступень — только провалы`() {
        val след = LadderTelemetry.trace(
            listOf(LadderTelemetry.ROUTE_DIRECT, LadderTelemetry.ROUTE_RELAY, LadderTelemetry.ROUTE_FALLBACK),
            null,
        )
        assertEquals("прямая ✗ · РФ ✗ · мост ✗", след)
    }

    // Ступень, которую НЕ пробовали (нет плеча, включён «всегда запасной канал»), в след не попадает
    // вовсе — врать «провалилась» о непопробованном нельзя. Тот же принцип, что в attemptOutcome.
    @Test
    fun `непопробованная ступень в след не попадает`() {
        val след = LadderTelemetry.trace(listOf(LadderTelemetry.ROUTE_DIRECT), LadderTelemetry.ROUTE_FALLBACK)
        assertEquals("прямая ✗ · мост ✓", след)
        assertFalse("РФ" in след)
    }
}
