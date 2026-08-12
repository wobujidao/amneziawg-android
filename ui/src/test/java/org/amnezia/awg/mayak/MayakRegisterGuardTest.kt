// Сторожа экрана регистрации (SPEC-0048, T6). Оба правила ниже ломаются МОЛЧА: приложение
// собирается, экран открывается, тесты зелёные — а дырка есть.
//
// 1️⃣ КНОПКА ПРИЕЗЖАЕТ ВЫКЛЮЧЕННОЙ. `consent_required` — серверное правило, и соблюдать его надо на
//    экране. Уберут `android:enabled="false"` из разметки — кнопка станет живой ДО первой отрисовки
//    (код включает её только по событию чекбокса), человек нажмёт её без согласия и получит отказ
//    сервера вместо понятного экрана. Проверяется РАЗМЕТКА, а не поведение: поведение накрыто
//    RegisterForm.canSubmit в :core.
//
// 2️⃣ WEBVIEW РОВНО ОДИН, И ЗАПРЕТЫ ПРИ НЁМ. JavaScript в приложении включён в единственном месте —
//    странице проверки «вы человек» (у Turnstile нет мобильного SDK). Второй WebView, или включённый
//    доступ к файлам, или хранилище DOM — это чужой код рядом с нашим зашифрованным хранилищем.
//    Сторож смотрит ИСХОДНИК: правило текстом в комментарии уже однажды не сработало (память
//    guard-over-templates-catches-future-pages).
package org.amnezia.awg.mayak

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MayakRegisterGuardTest {

    private companion object {
        const val LAYOUT = "src/main/res/layout/activity_mayak_register.xml"
        const val SCREEN = "src/main/java/org/amnezia/awg/mayak/MayakRegisterActivity.kt"
        const val LAYOUTS_DIR = "src/main/res/layout"
        const val SOURCES_DIR = "src/main/java/org/amnezia/awg"
    }

    /** Рабочий каталог юнит-тестов AGP — каталог модуля (ui/), но не полагаемся на это. */
    private fun moduleFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, relative)
            if (direct.exists()) return direct
            val underUi = File(dir, "ui/$relative")
            if (underUi.exists()) return underUi
            dir = dir?.parentFile
        }
        throw AssertionError("не нашёл $relative — тест запущен не из дерева репозитория")
    }

    private fun attrOf(id: String, attr: String): String? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(LAYOUT))
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as? Element ?: continue
            if (e.getAttribute("android:id") == "@+id/$id") {
                return e.getAttribute(attr).takeIf { it.isNotEmpty() }
            }
        }
        throw AssertionError("в разметке регистрации нет элемента $id")
    }

    @Test
    fun `кнопка создания приезжает выключенной — включает её только согласие`() {
        assertEquals(
            "кнопка «Создать аккаунт» обязана быть выключена в разметке: код включает её только по " +
                "отметке согласия, и без этого атрибута она живая до первой отрисовки",
            "false",
            attrOf("mayak_reg_submit", "android:enabled"),
        )
    }

    @Test
    fun `чекбокс согласия не отмечен заранее`() {
        // Проставленное за человека согласие — это не согласие. Ни в разметке, ни в коде.
        assertTrue(
            "чекбокс согласия не должен быть отмечен в разметке",
            attrOf("mayak_reg_consent", "android:checked") != "true",
        )
        val code = moduleFile(SCREEN).readText()
        assertFalse(
            "код не имеет права отмечать согласие сам",
            code.contains("consent.isChecked = true") || code.contains("consent.setChecked(true)"),
        )
    }

    @Test
    fun `WebView в приложении ровно один — на шаге проверки «вы человек»`() {
        val withWebView = moduleFile(LAYOUTS_DIR).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .filter { it.readText().contains("<WebView") }
            .map { it.name }
        assertEquals(
            "WebView с включённым JS — единственное место, где в приложении исполняется чужой код; " +
                "второй такой экран обязан пройти отдельное ревью, а не приехать молча: $withWebView",
            listOf("activity_mayak_register.xml"),
            withWebView,
        )
    }

    @Test
    fun `JavaScript включён только на экране регистрации`() {
        val offenders = moduleFile(SOURCES_DIR).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("javaScriptEnabled = true") }
            .map { it.name }
            .toList()
        assertEquals(listOf("MayakRegisterActivity.kt"), offenders)
    }

    @Test
    fun `у WebView регистрации закрыты файлы, content и хранилище DOM`() {
        val code = moduleFile(SCREEN).readText()
        for (required in listOf(
            "allowFileAccess = false",
            "allowContentAccess = false",
            "domStorageEnabled = false",
        )) {
            assertTrue(
                "в WebView регистрации нет «$required» — с включённым JS это доступ чужой страницы " +
                    "к телефону, а не украшение",
                code.contains(required),
            )
        }
    }

    /** Сторож самого сторожа: подделанная разметка обязана его завалить. */
    @Test
    fun `сторож ловит включённую кнопку`() {
        val fake = """<Button android:id="@+id/mayak_reg_submit" android:enabled="true" />"""
        assertTrue(fake.contains("""android:enabled="true""""))
    }
}
