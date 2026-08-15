/*
 * Copyright © 2026 Mayak Networks. SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.mayak.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Что нового» в окне обновления — на языке человека.
 *
 * Повод (16-08, живая проверка самообновления на эмуляторе): рамка диалога переводится ресурсами
 * приложения, а список изменений приезжает с сервера ОДНОЙ строкой — и английский телефон получал
 * английский заголовок со стеной русского текста внутри.
 */
class ChangelogLanguageTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String): AppVersionInfo = json.decodeFromString(AppVersionInfo.serializer(), body)

    @Test
    fun `английской корзине достаётся английский текст`() {
        val info = parse(
            """{"latest_version_code":172,"latest_version_name":"0.5.10",
               "changelog":"Русский текст","changelog_en":"English text"}"""
        )
        assertEquals("English text", info.changelogFor("en"))
    }

    @Test
    fun `русской корзине достаётся русский текст`() {
        val info = parse(
            """{"latest_version_code":172,"changelog":"Русский текст","changelog_en":"English text"}"""
        )
        assertEquals("Русский текст", info.changelogFor("ru"))
    }

    @Test
    fun `нет английского перевода — отдаём русский, а не пустоту`() {
        // Старая выкладка: поля changelog_en в файле нет вовсе. Молчание хуже чужого языка.
        val info = parse("""{"latest_version_code":171,"changelog":"Русский текст"}""")
        assertEquals("Русский текст", info.changelogFor("en"))
    }

    @Test
    fun `пустой английский перевод не подменяет русский пустотой`() {
        val info = parse("""{"latest_version_code":171,"changelog":"Русский текст","changelog_en":"   "}""")
        assertEquals("Русский текст", info.changelogFor("en"))
    }

    @Test
    fun `незнакомая корзина языка ведёт себя как английская`() {
        // namesLanguageBucket отдаёт только ru/en, но контракт не должен зависеть от этого частного
        // случая: всё, что не русский, — не русский.
        val info = parse("""{"latest_version_code":172,"changelog":"Русский","changelog_en":"English"}""")
        assertEquals("English", info.changelogFor("de"))
    }
}
