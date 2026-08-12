// Сторож слов в ящике сообщений (SPEC-0047 §5, §7).
//
// Уведомление читают через плечо: оно всплывает в шторке, висит на заблокированном экране и лежит в
// системном списке каналов. При этом приложение умеет прятаться под «Погоду»/«Заметки»
// (MayakDisguise) — то есть человек мог специально позаботиться о том, чтобы про наличие сервиса на
// его телефоне никто не узнал. Одно слово «VPN» в заголовке отменяет всю эту работу разом.
//
// Плюс отдельная причина: реклама VPN в РФ запрещена с 01.09.2025 (ст. 14.3 КоАП), а «Новости и
// предложения» — это рассылка. Тексты обязаны быть про свой сервис, без «обхода блокировок».
//
// Проверять это глазами бессмысленно: строк семь десятков, их будут дописывать, и забудут ровно
// один раз. Поэтому сторож, а не внимательность. Заодно он ловит вторую беду — повод (`kind`), под
// который перевод завести забыли: такой показался бы человеку голым серверным текстом.
package org.amnezia.awg.mayak

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.amnezia.awg.mayak.core.MessageKinds
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MayakMessagesWordsTest {

    private companion object {
        val FILES = listOf("src/main/res/values-ru/strings.xml", "src/main/res/values/strings.xml")

        /** Что не должно попасться НИ В ОДНОМ показываемом тексте ящика (регистр не важен). */
        val FORBIDDEN = listOf("vpn", "туннел", "обход", "обойт", "tunnel", "bypass", "circumvent")

        /**
         * Префиксы имён строк, которые человек видит в уведомлении, в списке и в карточке.
         *
         * `mayak_reg_` добавлен 13-08 вместе с регистрацией в приложении (SPEC-0048, T6): это ПЕРВЫЙ
         * экран, который видит новичок, и объяснять на нём, «зачем это приложение», нельзя ровно по
         * той же причине — карточка Play, скриншот через плечо, реклама VPN в РФ запрещена.
         */
        val PREFIXES = listOf("mayak_messages_", "mayak_msg_", "mayak_settings_notify", "mayak_reg_")
    }

    /** Рабочий каталог юнит-тестов AGP — каталог модуля (ui/), но не полагаемся на это. */
    private fun moduleFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val underUi = File(dir, "ui/$relative")
            if (underUi.isFile) return underUi
            dir = dir?.parentFile
        }
        throw AssertionError("не нашёл $relative — тест запущен не из дерева репозитория")
    }

    private fun strings(relative: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out[e.getAttribute("name")] = e.textContent.orEmpty()
        }
        return out
    }

    @Test
    fun `в текстах ящика и регистрации нет слов, выдающих назначение приложения`() {
        for (file in FILES) {
            for ((name, value) in strings(file)) {
                if (PREFIXES.none { name.startsWith(it) }) continue
                val lower = value.lowercase()
                for (word in FORBIDDEN) {
                    assertTrue(
                        "$file: строка $name содержит «$word» — её увидят в шторке: «$value»",
                        !lower.contains(word),
                    )
                }
            }
        }
    }

    /**
     * У каждого повода из белого списка обязан быть свой заголовок — иначе приложение молча уйдёт на
     * серверный текст, и вся затея с локализацией по (kind, params) перестанет работать именно на
     * том поводе, который забыли. `custom` в списке нет: у него текст всегда серверный.
     */
    @Test
    fun `у каждого известного повода есть заголовок на обоих языках`() {
        val kinds = MessageKinds::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(MessageKinds) as? String }
            .filter { it != MessageKinds.CUSTOM }
        assertTrue("белый список поводов пуст — сторож проверял бы пустоту", kinds.size >= 20)
        for (file in FILES) {
            val names = strings(file).keys
            for (kind in kinds) {
                assertTrue(
                    "$file: у повода «$kind» нет строки mayak_msg_$kind — человек увидит серверный текст",
                    names.contains("mayak_msg_$kind"),
                )
            }
        }
    }

    /** Сторож самого сторожа: сломанная строка обязана его завалить (иначе он зеленеет на чём угодно). */
    @Test
    fun `сторож ловит запрещённое слово`() {
        val sample = "Ваш VPN отключён"
        assertTrue(FORBIDDEN.any { sample.lowercase().contains(it) })
    }
}
