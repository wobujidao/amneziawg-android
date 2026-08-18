// Сторож: в текстах, которые видит человек, слова «VPN» нет НИ НА ОДНОМ языке.
//
// Повод (18-08, прогон на эмуляторе): русские строки не говорили «VPN» ни разу — там «защищённое
// подключение», «туннель», «Маяк». А английские говорили ДВАДЦАТЬ ОДИН раз, включая имя канала
// уведомлений «VPN status». То есть одно и то же приложение рассказывало о себе двум людям
// по-разному, и англоязычному — ровно то, что мы стараемся не говорить.
//
// Почему это не вкусовщина:
//   • приложение умеет прятаться под «Погоду» или «Заметки» (MayakDisguise) — человек мог специально
//     позаботиться, чтобы про сервис на его телефоне никто не узнал. Канал «VPN status» в системном
//     списке каналов и на заблокированном экране отменяет эту работу разом, и язык тут ни при чём;
//   • на карточку Play и на скриншот через плечо это попадает так же, как тексты ящика сообщений,
//     ради которых уже заведён MayakMessagesWordsTest — тот стережёт лишь четыре префикса имён;
//   • реклама VPN в РФ запрещена с 01.09.2025 (ст. 14.3 КоАП), а листинг у нас один на оба языка.
//
// Проверять глазами бессмысленно: строк больше пятисот, и забудут ровно один раз.
package org.amnezia.awg.mayak

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MayakNoVpnWordTest {

    private companion object {
        val FILES = listOf("src/main/res/values/strings.xml", "src/main/res/values-ru/strings.xml")

        /**
         * Что смотрим: все наши строки (`mayak_*`) плюс две апстримные ошибки, которые человек
         * видит при отказе системы. Апстримные строки Amnezia целиком под этот сторож не берём —
         * их сотни, они про экраны конфигов, и переписывать чужой словарь мы не подписывались.
         */
        fun watched(name: String) =
            name.startsWith("mayak_") || name == "vpn_not_authorized_error" || name == "vpn_start_error"
    }

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
        val out = LinkedHashMap<String, String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                out[e.getAttribute("name")] = e.textContent.orEmpty()
            }
        }
        return out
    }

    @Test
    fun `ни имя строки, ни её текст не говорят VPN`() {
        val bad = mutableListOf<String>()
        for (file in FILES) {
            for ((name, text) in strings(file)) {
                if (!watched(name)) continue
                if (text.contains("vpn", ignoreCase = true)) {
                    bad += "$file: $name = \"${text.take(90)}\""
                }
            }
        }
        assertTrue(
            "Слово «VPN» в тексте, который видит человек:\n" + bad.joinToString("\n") { "  $it" } +
                "\n\nСкажи то же самое иначе — как это делает русская пара: «защищённое подключение», " +
                "«туннель», «через Маяк». Приложение умеет прятаться под другое имя, и одно это слово " +
                "на экране настроек или в списке каналов уведомлений отменяет маскировку целиком.",
            bad.isEmpty(),
        )
    }

    @Test
    fun `русская и английская сторона одинаково молчаливы`() {
        val counts = FILES.map { file ->
            file to strings(file).filterKeys(::watched).count { it.value.contains("vpn", ignoreCase = true) }
        }
        assertTrue(
            "Одна языковая сторона говорит «VPN» чаще другой: $counts. Так и появился этот сторож — " +
                "в русском было 0, в английском 21.",
            counts.all { it.second == 0 },
        )
    }
}
