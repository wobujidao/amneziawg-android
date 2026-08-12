// Сторож ДОСТАВКИ сообщений (SPEC-0047, разбор 13-08).
//
// 🔴 Что именно он охраняет. 12-08 ящик был написан целиком и проверен на эмуляторе — а на живом
// телефоне 13-08 не доставил ни одного уведомления. Не из-за Android, не из-за «Не беспокоить» и не
// из-за отсутствия Firebase: сложились два наших решения, каждое по отдельности разумное.
//   1) Проверка на переднем плане была зажата анти-дребезгом в ЧАС.
//   2) Экран «Сообщения» забирал ящик САМ, своим запросом, и намеренно не показывал уведомлений.
// Вместе: единственный путь, которым сообщение попадало в телефон, совпал с тем, где показ запрещён.
//
// Такую беду не видно ни в одном тесте на отдельную функцию — каждая половина работала правильно.
// Поэтому сторож проверяет СВЯЗКУ: что открытие приложения реально ходит на сервер, что забор идёт
// через одну дверь и что среди поводов есть показывающие. Глазами это не удержать: следующий, кто
// станет «экономить запросы», поднимет минуту до часа и снова всё выключит, ничего не сломав по виду.
package org.amnezia.awg.mayak

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayakMessagesDeliveryTest {

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

    private fun source(name: String): String =
        moduleFile("src/main/java/org/amnezia/awg/mayak/$name").readText()

    // ===== Частота: пол в секундах, а не в часах =====

    @Test
    fun `открытие приложения не зажато часом`() {
        val gap = MayakMessages.SyncTrigger.OPEN.minGapMs
        // Верхняя граница — минута: человек в этот момент стоит и смотрит на экран, а запрос крошечный.
        // Ровно здесь 13-08 стоял час, и приложение молча не пошло на сервер через 4 минуты после
        // сообщения.
        assertTrue("пол проверки при открытии приложения = $gap мс, это слишком много", gap <= 60_000L)
        // Ноль тоже неправильно: пересоздание Activity (поворот, смена темы) — это не «человек открыл
        // приложение» второй раз, и три запроса подряд на одно движение нам не нужны.
        assertTrue("пол в 0 мс — дребезг на пересоздании экрана", gap > 0L)
    }

    @Test
    fun `частая проверка при поднятом туннеле не реже, чем раз в несколько минут`() {
        val gap = MayakMessages.SyncTrigger.TUNNEL.minGapMs
        assertTrue("пол проверки при поднятом туннеле = $gap мс", gap in 1L..5 * 60_000L)
    }

    @Test
    fun `пора ли идти — считается честно`() {
        val now = 1_000_000L
        assertTrue("ни разу не ходили — надо идти", MayakMessages.due(0L, now, 10_000L))
        assertFalse("зазор не прошёл — идти рано", MayakMessages.due(now - 5_000L, now, 10_000L))
        assertTrue("зазор прошёл — пора", MayakMessages.due(now - 20_000L, now, 10_000L))
        assertTrue("зазор ровно вышел — пора", MayakMessages.due(now - 10_000L, now, 10_000L))
        assertTrue("часы уехали назад — считаем, что пора", MayakMessages.due(now + 60_000L, now, 10_000L))
    }

    // ===== Связка: забор без показа не должен остаться единственным путём =====

    @Test
    fun `каждый повод тихой проверки умеет показать найденное`() {
        // Забор без показа у нас ровно один и он не повод, а отдельная дверь для экрана «Сообщения»
        // (loadForScreen). Появится такой среди поводов — значит кто-то снова строит путь, на котором
        // сообщение попадает в телефон молча; тогда сюда нужно прийти и подумать, а не править тест.
        val mute = MayakMessages.SyncTrigger.entries.filter { !it.notify }
        assertTrue("повод(ы) забирают ящик, но не показывают: $mute", mute.isEmpty())
    }

    @Test
    fun `возврат в приложение проверяет ящик`() {
        val body = functionBody(source("MayakActivity.kt"), "override fun onResume()")
        assertTrue(
            "onResume не зовёт syncMessages() — человек, открывший приложение из недавних, " +
                "снова не будет ходить на сервер (ровно дефект 13-08)",
            body.contains("syncMessages()"),
        )
    }

    @Test
    fun `экран Сообщения забирает ящик через общую дверь, а не своим запросом`() {
        val screen = source("MayakMessagesActivity.kt")
        assertFalse(
            "экран ходит в ящик сам (session.messages) — тогда он снова становится путём доставки, " +
                "на котором уведомление показывать нельзя",
            screen.contains("session.messages("),
        )
        assertTrue(
            "экран обязан забирать ящик через MayakMessages.loadForScreen",
            screen.contains("MayakMessages.loadForScreen("),
        )
    }

    /** Тело функции по её объявлению: от `{` до парной закрывающей. */
    private fun functionBody(src: String, decl: String): String {
        val start = src.indexOf(decl)
        assertTrue("не нашёл в исходнике: $decl", start >= 0)
        var i = src.indexOf('{', start)
        assertTrue("не нашёл тело функции: $decl", i >= 0)
        var depth = 0
        val from = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(from, i + 1)
                }
            }
            i++
        }
        throw AssertionError("не сошлись фигурные скобки у $decl")
    }
}
