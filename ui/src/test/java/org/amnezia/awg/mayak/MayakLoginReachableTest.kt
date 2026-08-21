// Сторожа экрана входа. Все правила ниже ломаются МОЛЧА: приложение собирается, экран открывается,
// вход работает у того, кто проверяет его на широком эмуляторе с выключенной клавиатурой.
//
// 1️⃣ ВОЙТИ МОЖНО НЕ ТОЛЬКО КНОПКОЙ. На коротком экране клавиатура закрывает кнопку «Войти», и
//    человек жмёт туда, где кнопка была секунду назад, — то есть в клавиатуру. Снаружи это выглядит
//    ровно как «нажал и ничего не произошло»: в поле пароля тихо дописывается лишняя буква. Поймано
//    21-08 на Android 9, причём сначала было принято за поломку входа на сервере и стоило половины
//    смены. Лечение — галочка (actionDone) на самой клавиатуре: путь к входу, который клавиатура
//    закрыть не может. Сторож смотрит И разметку (imeOptions у поля пароля), И код (обработчик).
//
// 2️⃣ ОТКАЗ «НЕ ПРО ПАРОЛЬ» ВИДЕН С ОТКРЫТОЙ КЛАВИАТУРОЙ. Ошибки вроде «нет сети» и «сервер не
//    отвечает» писались в строку статуса ПОД карточкой входа — а с клавиатурой её на экране нет
//    вовсе. Человек не видел ни ошибки, ни объяснения. Плашка (Snackbar) при adjustResize садится
//    над клавиатурой. Уберут её — экран снова начнёт молчать в ответ на неудачу.
//
// 3️⃣ ОШИБКА ПАРОЛЯ ТОЖЕ БЫВАЕТ НЕ ВИДНА. Первая версия правки оставила подпись под полем «как
//    есть», рассудив, что уж её-то человек прочитает. Замер на 411×683dp это опроверг: с открытой
//    клавиатурой видимая область кончается ВЫШЕ поля пароля, и «опечатался» выглядело так же немо,
//    как сетевой отказ — кадр через 3 с после нажатия неотличим от кадра до него. Поэтому плашка
//    добавляется и здесь, но ТОЛЬКО когда подписи реально не видно (иначе на высоком экране человек
//    прочитает одно и то же дважды).
//
// 4️⃣ adjustResize ОБЪЯВЛЕН СЛОВОМ. Система выбирала его сама из-за ScrollView в разметке, и на этом
//    молча держались и плашка над клавиатурой, и возможность доскроллить до кнопки.
package org.amnezia.awg.mayak

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MayakLoginReachableTest {

    private companion object {
        const val LAYOUT = "src/main/res/layout/activity_mayak_login.xml"
        const val SCREEN = "src/main/java/org/amnezia/awg/mayak/MayakActivity.kt"
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

    private fun элемент(id: String): Element {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(moduleFile(LAYOUT))
        val все = doc.getElementsByTagName("*")
        for (i in 0 until все.length) {
            val e = все.item(i) as? Element ?: continue
            if (e.getAttribute("android:id") == "@+id/$id") return e
        }
        throw AssertionError("в разметке входа нет элемента $id")
    }

    @Test
    fun `поле пароля даёт войти с клавиатуры`() {
        val поле = элемент("mayak_password")
        assertEquals(
            "у поля пароля пропал imeOptions=actionDone — вход с клавиатуры исчез, " +
                "и на коротком экране кнопка «Войти» снова окажется недостижимой под клавиатурой",
            "actionDone",
            поле.getAttribute("android:imeOptions"),
        )
    }

    @Test
    fun `галочка на клавиатуре запускает вход`() {
        val код = moduleFile(SCREEN).readText()
        assertTrue(
            "у поля пароля нет обработчика setOnEditorActionListener — галочка на клавиатуре " +
                "ничего не делает, и imeOptions в разметке остался украшением",
            код.contains("passField.setOnEditorActionListener"),
        )
        assertTrue(
            "обработчик клавиатуры не проверяет IME_ACTION_DONE",
            код.contains("EditorInfo.IME_ACTION_DONE"),
        )
        assertTrue(
            "обработчик клавиатуры не зовёт вход",
            Regex("""setOnEditorActionListener[\s\S]{0,400}trySignIn\(\)""").containsMatchIn(код),
        )
    }

    @Test
    fun `отказ не про пароль показывается поверх клавиатуры`() {
        val код = moduleFile(SCREEN).readText()
        assertTrue(
            "showLoginError больше не показывает плашку поверх содержимого — отказ «нет сети» " +
                "снова уедет в строку под карточкой, которую закрывает клавиатура",
            Regex("""if \(!blamePassword\)[\s\S]{0,900}показатьПоверхКлавиатуры\(text\)""").containsMatchIn(код),
        )
        assertTrue(
            "плашка перестала быть Snackbar — проверь, что новый способ виден с открытой клавиатурой",
            Regex("""fun показатьПоверхКлавиатуры[\s\S]{0,600}Snackbar\.make""").containsMatchIn(код),
        )
    }

    @Test
    fun `ошибка пароля показывается плашкой, когда подписи не видно`() {
        val код = moduleFile(SCREEN).readText()
        assertTrue(
            "у ветки с подписью под полем пропала подстраховка плашкой — на коротком экране " +
                "«неверный пароль» снова окажется под клавиатурой и человек не увидит ничего",
            Regex("""passwordLayout\.error = text[\s\S]{0,900}показатьПоверхКлавиатуры\(text\)""").containsMatchIn(код),
        )
        assertTrue(
            "плашка у ошибки пароля перестала быть условной — на высоком экране человек прочитает " +
                "одно и то же дважды (подпись под полем + плашка)",
            код.contains("if (!виденНаЭкране(passwordLayout))"),
        )
        assertTrue(
            "виденНаЭкране больше не сверяется с областью, свободной от клавиатуры",
            Regex("""fun виденНаЭкране[\s\S]{0,500}getWindowVisibleDisplayFrame""").containsMatchIn(код),
        )
    }

    @Test
    fun `экран входа объявляет adjustResize словом`() {
        val манифест = moduleFile("src/main/AndroidManifest.xml").readText()
        val блок = Regex("""<activity[^>]*org\.amnezia\.awg\.mayak\.MayakActivity[\s\S]{0,400}?>""")
            .find(манифест)?.value
            ?: throw AssertionError("в манифесте нет записи MayakActivity")
        assertTrue(
            "у MayakActivity нет android:windowSoftInputMode=\"adjustResize\" — режим снова держится " +
                "на том, что система угадает его по ScrollView; плашка над клавиатурой и прокрутка " +
                "до кнопки пропадут молча",
            блок.contains("android:windowSoftInputMode=\"adjustResize\""),
        )
    }
}
