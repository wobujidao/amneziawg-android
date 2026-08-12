package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт ящика сообщений (SPEC-0047 §3.1) со стороны приложения. Проверяем ровно то, что ломается
 * молча: имена JSON-полей, устойчивость разбора к незнакомому и к числу вместо строки в `params`,
 * и границу тихих часов, которая проходит ЧЕРЕЗ полночь.
 *
 * Серверная половина пишется параллельно тремя другими исполнителями — эти тесты и есть то место,
 * где расхождение с контрактом станет видно раньше, чем на телефоне живого человека.
 */
class MessagesTest {

    private val json = MayakBackend.defaultJson

    @Test
    fun разбираетОтветЯщика() {
        val r = json.decodeFromString(
            MessagesResponse.serializer(),
            """
            {"messages":[{
              "id":42,"category":"account","kind":"subscription_expiring",
              "title":"Доступ скоро закончится","body":"Осталось 3 дня",
              "params":{"days":"3"},"action":"billing","action_param":"",
              "critical":true,"created_at":"2026-08-12T10:00:00Z","read":false
            }],"unread":3,"next_check_after_sec":21600}
            """.trimIndent(),
        )
        assertEquals(3, r.unread)
        assertEquals(21600, r.nextCheckAfterSec)
        val m = r.messages.single()
        assertEquals(42L, m.id)
        assertEquals(MessageCategories.ACCOUNT, m.category)
        assertEquals(MessageKinds.SUBSCRIPTION_EXPIRING, m.kind)
        assertEquals(MessageActions.BILLING, m.action)
        assertEquals("", m.actionParam)
        assertTrue(m.critical)
        assertFalse(m.read)
        assertEquals("3", m.param("days"))
    }

    /** Пустой ящик — это 200 и пустой список, а не ошибка: экран обязан сказать «сообщений нет». */
    @Test
    fun пустойЯщикРазбираетсяКакПустой() {
        val r = json.decodeFromString(MessagesResponse.serializer(), """{"messages":[],"unread":0}""")
        assertTrue(r.messages.isEmpty())
        assertEquals(0, r.unread)
        assertEquals(0, r.nextCheckAfterSec)
    }

    /** Правило проекта: незнакомый ключ в ОТВЕТЕ не роняет разбор (серверная половина ещё растёт). */
    @Test
    fun незнакомыеКлючиНеЛомаютРазбор() {
        val r = json.decodeFromString(
            MessagesResponse.serializer(),
            """{"messages":[{"id":7,"kind":"maintenance","expires_at":"2026-09-01T00:00:00Z"}],
                "unread":1,"next_check_after_sec":600,"server_time":"2026-08-12T10:00:00Z"}""",
        )
        assertEquals(7L, r.messages.single().id)
    }

    /**
     * `params` — это `jsonb`: «3» законно приезжает и строкой, и числом. Строгая Map<String,String>
     * уронила бы на числе разбор ВСЕГО ящика, то есть человек остался бы вообще без сообщений.
     */
    @Test
    fun параметрЧисломЧитаетсяТакЖеКакСтрокой() {
        val m = json.decodeFromString(
            UserMessage.serializer(),
            """{"id":1,"kind":"subscription_expiring","params":{"days":3,"plan":"Год"}}""",
        )
        assertEquals("3", m.param("days"))
        assertEquals("Год", m.param("plan"))
    }

    /** Нет ключа, пусто или сложное значение — null: подставлять в текст нечего, нужен серверный. */
    @Test
    fun отсутствующийИНегодныйПараметрДаютNull() {
        val m = json.decodeFromString(
            UserMessage.serializer(),
            """{"id":1,"params":{"empty":"","nested":{"a":1},"list":[1,2],"nothing":null}}""",
        )
        assertNull(m.param("days"))
        assertNull(m.param("empty"))
        assertNull(m.param("nested"))
        assertNull(m.param("list"))
        assertNull(m.param("nothing"))
    }

    /** Полей может не быть вовсе (старое/иное ядро) — дефолты обязаны быть безопасными. */
    @Test
    fun сообщениеБезПолейИмеетБезопасныеУмолчания() {
        val m = json.decodeFromString(UserMessage.serializer(), """{"id":5}""")
        assertEquals(MessageActions.NONE, m.action)
        assertFalse(m.critical)
        assertFalse(m.read)
        assertNull(m.createdMs())
    }

    /** Умолчания выключателей обязаны совпадать с таблицей: новости ВЫКЛЮЧЕНЫ (38-ФЗ ст. 18). */
    @Test
    fun умолчанияВыключателей() {
        val p = json.decodeFromString(NotificationPrefs.serializer(), "{}")
        assertTrue(p.service)
        assertFalse(p.news)
        assertTrue(p.quietHours)
    }

    /** В PUT уходит РОВНО три ключа: ядро читает тело строго, лишний ключ = 400 на весь запрос. */
    @Test
    fun вЗапросеРовноТриКлюча() {
        val body = json.encodeToString(NotificationPrefs.serializer(), NotificationPrefs(service = false, news = true, quietHours = false))
        assertEquals("""{"service":false,"news":true,"quiet_hours":false}""", body)
    }

    /**
     * Граница тихих часов проходит через полночь. Наивная проверка «час в промежутке 23..9» не
     * истинна НИКОГДА — с ней уведомления звенели бы ночью при включённой настройке.
     */
    @Test
    fun тихиеЧасыПересекаютПолночь() {
        assertTrue(quietHourNow(23))
        assertTrue(quietHourNow(0))
        assertTrue(quietHourNow(3))
        assertTrue(quietHourNow(8))
        assertFalse(quietHourNow(9)) // ровно 9:00 — уже день
        assertFalse(quietHourNow(12))
        assertFalse(quietHourNow(22)) // 22:59 — ещё день
    }
}
