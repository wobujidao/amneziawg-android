package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Показ номера аккаунта. Проверяем ровно то, что молча ломается: ведущие нули (номер обязан
 * оставаться строкой), разметка тремя блоками и молчание вместо мусора.
 *
 * Плюс контракт с ядром: имя поля в ответе GET /v1/client/account и то, что ОТСУТСТВИЕ поля —
 * штатный случай (номер добавляют в ответ отдельной правкой; старое ядро его не пришлёт, и
 * приложение обязано это переживать, а не падать).
 */
class AccountNumberTest {

    private val json = MayakBackend.defaultJson

    @Test
    fun девятьЦифр_размечаютсяТремяБлоками() {
        assertEquals("472-918-563", AccountNumber.format("472918563"))
    }

    @Test
    fun ведущиеНули_неТеряются() {
        // Самая дорогая ошибка в этом коде: где-то по пути номер стал числом. Тогда «007891234»
        // превратится в «7891234», человек продиктует его поддержке — и она не найдёт учётку.
        assertEquals("007-891-234", AccountNumber.format("007891234"))
        assertEquals("000-000-000", AccountNumber.format("000000000"))
    }

    @Test
    fun ужеРазмеченный_показываетсяТакЖе() {
        // Если серверная сторона однажды пришлёт номер с дефисами, показ не должен ни спрятать его,
        // ни удвоить разметку.
        assertEquals("472-918-563", AccountNumber.format("472-918-563"))
        assertEquals("472-918-563", AccountNumber.format(" 472 918 563 "))
    }

    @Test
    fun мусорПоказываемКакПришло_аНеРазмеченным() {
        // Девять цифр внутри строки — ещё не номер. Разметить это значило бы соврать.
        assertEquals("CARD 472918563", AccountNumber.format("CARD 472918563"))
        assertEquals("нет", AccountNumber.format("нет"))
    }

    @Test
    fun номерДругойДлины_показываетсяБезРазметки() {
        // Ёмкость 10^8 конечна: когда номер удлинят миграцией, старое приложение обязано показать
        // его как есть, а не спрятать (спрятанный номер = поддержка снова спрашивает почту).
        assertEquals("4729185631", AccountNumber.format("4729185631"))
        assertTrue(AccountNumber.isShowable("4729185631"))
    }

    @Test
    fun показываемТолькоТоЧтоПохожеНаНомер() {
        assertTrue(AccountNumber.isShowable("472918563"))
        assertTrue(AccountNumber.isShowable("472-918-563"))
        assertFalse("пусто — показывать нечего", AccountNumber.isShowable(""))
        assertFalse("пробелы — показывать нечего", AccountNumber.isShowable("   "))
        assertFalse("поля нет вовсе", AccountNumber.isShowable(null))
        assertFalse("одни разделители без цифр", AccountNumber.isShowable("---"))
        assertFalse("буквы", AccountNumber.isShowable("tg:12345678"))
    }

    @Test
    fun ответЯдра_имяПоляAccountNumber() {
        val a = json.decodeFromString(
            AccountInfo.serializer(),
            """{"login":"a@b.ru","email":"a@b.ru","account_number":"472918563","devices_used":1}""",
        )
        assertEquals("472918563", a.accountNumber)
    }

    @Test
    fun ответЯдраБезНомера_разбираетсяИНичегоНеПоказывает() {
        // Ядро без миграции 0110 (или до правки хендлера) поля не пришлёт. Это НЕ ошибка: экран
        // просто ничего не покажет. Падать на разборе тут — значит уронить весь блок аккаунта.
        val a = json.decodeFromString(AccountInfo.serializer(), """{"login":"a@b.ru"}""")
        assertNull(a.accountNumber)
        assertFalse(AccountNumber.isShowable(a.accountNumber))
        assertEquals("", AccountNumber.format(a.accountNumber))
    }

    @Test
    fun ответЯдраСNull_разбираетсяИНичегоНеПоказывает() {
        // Колонка nullable: у учёток, заведённых до миграции и не добравших номер, придёт null.
        // Не-nullable поле с дефолтом здесь БЫ УПАЛО (coerceInputValues в defaultJson не включён).
        val a = json.decodeFromString(AccountInfo.serializer(), """{"account_number":null}""")
        assertNull(a.accountNumber)
        assertFalse(AccountNumber.isShowable(a.accountNumber))
    }
}
