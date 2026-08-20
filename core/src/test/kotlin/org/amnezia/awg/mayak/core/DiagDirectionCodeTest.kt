package org.amnezia.awg.mayak.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Диагностика уезжает на сервер с КОДОМ направления, а не только с именем.
 *
 * Зачем сторож: имя приложение кладёт такое, каким показало его человеку, то есть на языке телефона,
 * и в панели одна страна расползалась на «Нидерланды» (26 заливок) и «Netherlands» (3) — замер на
 * бою 20-08. Код у направления один на всех языках. Проверяем ЭФФЕКТ — само тело запроса, тем же
 * Json, каким его пишет MayakBackend (encodeDefaults = true).
 */
class DiagDirectionCodeTest {

    private val json = Json { encodeDefaults = true }

    private fun req(code: String? = null) = DiagLogRequest(
        appVersion = "0.5.39 (201)",
        os = "Android 14 (SDK 34)",
        deviceModel = "Pixel 8",
        networkType = "cellular",
        otherVpn = false,
        direction = "Нидерланды",
        directionCode = code,
        deviceId = 42,
        source = "manual",
        log = "тестовый лог",
    )

    @Test
    fun `код направления уезжает рядом с именем`() {
        val body = json.encodeToString(DiagLogRequest.serializer(), req("nl"))
        assertTrue(body, body.contains("\"direction_code\":\"nl\""))
        assertTrue(body, body.contains("\"direction\":\"Нидерланды\"")) // имя остаётся — оно для человека
    }

    @Test
    fun `кода нет — поля в запросе нет вовсе`() {
        // Лог из настроек или до выбора страны. Ядро пустое поле и так отбросит, но врать в теле
        // запроса незачем: чего мы не знаем, того и не шлём.
        val body = json.encodeToString(DiagLogRequest.serializer(), req(null))
        assertFalse(body, body.contains("direction_code"))
    }
}
