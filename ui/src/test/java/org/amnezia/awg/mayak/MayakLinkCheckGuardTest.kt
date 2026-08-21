// Сторожа кнопки «Проверить связь». Оба правила ломаются МОЛЧА: приложение собирается, диалог
// открывается, проверка проходит — а человек остаётся не там, где был.
//
// 1️⃣ ПРОВЕРКА НА ТЕКУЩЕЙ ЛИНИИ ИДЁТ ПЕРВОЙ. Замечание владельца 21-08: «проверка уже обрывает линию
//    и начинает подключаться вновь, а нужно было проверять именно на этой линии». Вернут прежний
//    порядок — и кнопка снова будет уничтожать состояние, ради которого её жмут.
//
// 2️⃣ ПОСЛЕ ПЕРЕБОРА ПУТЕЙ СВЯЗЬ ВОЗВРАЩАЕТСЯ. Диалог обещает выключить связь НА ВРЕМЯ проверки, а
//    не насовсем. До 21-08 перебор заканчивал в опущенном состоянии: человек нажимал «проверить» и
//    оставался без связи, ничего об этом не прочитав.
package org.amnezia.awg.mayak

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MayakLinkCheckGuardTest {

    private fun screen(): String {
        var dir: File? = File("").absoluteFile
        val rel = "src/main/java/org/amnezia/awg/mayak/MayakActivity.kt"
        repeat(6) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, "ui/$rel").takeIf { it.exists() }?.let { return it.readText() }
            dir = dir?.parentFile
        }
        throw AssertionError("не нашёл MayakActivity.kt — тест запущен не из дерева репозитория")
    }

    @Test
    fun `кнопка сперва диагностирует текущую линию, а не гасит её`() {
        val код = screen()
        assertTrue(
            "offerLinkCheck больше не зовёт MayakLinkProbe — значит кнопка снова начинает с разрыва линии",
            Regex("""fun offerLinkCheck[\s\S]{0,2000}MayakLinkProbe\.collect""").containsMatchIn(код),
        )
        assertTrue(
            "перебор ступеней перестал быть отдельным шагом с предупреждением",
            код.contains("fun offerDeepLinkCheck") && код.contains("mayak_diag_deep_warning"),
        )
    }

    @Test
    fun `после перебора путей связь возвращается туда, где была`() {
        val код = screen()
        assertTrue(
            "doLinkCheck больше не запоминает, была ли связь до проверки",
            Regex("""fun doLinkCheck[\s\S]{0,1200}val былаСвязь = tunnel\.isUp\(\)""").containsMatchIn(код),
        )
        assertTrue(
            "связь не восстанавливается после проверки — человек останется в «Не защищено», " +
                "хотя диалог обещал выключить её лишь НА ВРЕМЯ проверки",
            Regex("""finally \{[\s\S]{0,600}if \(былаСвязь\) connectTo\(d\)""").containsMatchIn(код),
        )
    }
}
