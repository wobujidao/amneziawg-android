package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DoH перевели с JSON-расширения на стандартный RFC 8484 (2026-07-25): JSON понимают только Cloudflare
 * и Google — ровно те, кого в РФ блокируют, а Quad9/AdGuard (не блокируемые) отвечали на него HTTP 400.
 * Здесь проверяем чистую часть протокола: сборку запроса и разбор ответа, включая CNAME-цепочку и
 * сжатие имён — на них парсер и ломается, если считать оффсеты «на глаз».
 */
class DohResolverTest {

    @Test
    fun `запрос собирается по RFC 1035`() {
        val q = DohResolver.buildQuery("mayakvpn.ru")!!
        // заголовок: id=0 (обязателен для GET по RFC 8484), RD=1, ровно один вопрос
        assertEquals(0, q[0].toInt())
        assertEquals(0, q[1].toInt())
        assertEquals(0x01, q[2].toInt() and 0xff)
        assertEquals(0x00, q[3].toInt() and 0xff)
        assertEquals(1, ((q[4].toInt() and 0xff) shl 8) or (q[5].toInt() and 0xff))
        // QNAME = 8"mayakvpn" 2"ru" 0, затем QTYPE=A(1), QCLASS=IN(1)
        val expected = byteArrayOf(
            0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            8, 'm'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte(), 'a'.code.toByte(),
            'k'.code.toByte(), 'v'.code.toByte(), 'p'.code.toByte(), 'n'.code.toByte(),
            2, 'r'.code.toByte(), 'u'.code.toByte(), 0,
            0, 1, 0, 1,
        )
        assertEquals(expected.toList(), q.toList())
    }

    @Test
    fun `мусорное имя запроса отбрасываем`() {
        assertNull(DohResolver.buildQuery(""))
        assertNull(DohResolver.buildQuery("."))
        assertNull(DohResolver.buildQuery("a..b"))                 // пустая метка
        assertNull(DohResolver.buildQuery("x".repeat(64) + ".ru")) // метка длиннее 63
    }

    @Test
    fun `A-запись достаётся из ответа`() {
        val resp = response(
            answers = listOf(rr(type = 1, rdata = byteArrayOf(138.toByte(), 16, 128.toByte(), 138.toByte()))),
        )
        assertEquals("138.16.128.138", DohResolver.firstARecord(resp))
    }

    @Test
    fun `CNAME перед A не сбивает разбор`() {
        val cname = byteArrayOf(3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(), 0xC0.toByte(), 12)
        val resp = response(
            answers = listOf(
                rr(type = 5, rdata = cname),                       // CNAME → пропустить
                rr(type = 1, rdata = byteArrayOf(10, 0, 0, 7)),    // A → взять
            ),
        )
        assertEquals("10.0.0.7", DohResolver.firstARecord(resp))
    }

    @Test
    fun `ответ без A-записи даёт null`() {
        assertNull(DohResolver.firstARecord(response(answers = emptyList())))
        // AAAA-only: тип 28, rdata 16 байт — брать нечего (клиент ждёт IPv4)
        assertNull(DohResolver.firstARecord(response(answers = listOf(rr(type = 28, rdata = ByteArray(16))))))
    }

    @Test
    fun `обрезанный ответ не роняет парсер`() {
        val full = response(answers = listOf(rr(type = 1, rdata = byteArrayOf(1, 2, 3, 4))))
        for (cut in intArrayOf(0, 5, 12, 20, full.size - 2)) {
            assertNull("обрезка до $cut байт", DohResolver.firstARecord(full.copyOf(cut)))
        }
    }

    // --- сборка тестовых ответов ---

    /** Ресурсная запись со сжатым именем (указатель на вопрос, оффсет 12) — так отвечают реальные резолверы. */
    private fun rr(type: Int, rdata: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xC0.toByte()); out.add(12)                          // NAME = указатель на QNAME
        out.add((type shr 8).toByte()); out.add(type.toByte())       // TYPE
        out.add(0); out.add(1)                                       // CLASS = IN
        out.addAll(listOf<Byte>(0, 0, 0, 60))                        // TTL
        out.add((rdata.size shr 8).toByte()); out.add(rdata.size.toByte())
        rdata.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun response(answers: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(listOf<Byte>(0, 0, 0x81.toByte(), 0x80.toByte())) // id=0, ответ + RA
        out.addAll(listOf<Byte>(0, 1))                               // QDCOUNT=1
        out.add((answers.size shr 8).toByte()); out.add(answers.size.toByte())
        out.addAll(listOf<Byte>(0, 0, 0, 0))                         // NSCOUNT, ARCOUNT
        DohResolver.buildQuery("mayakvpn.ru")!!.drop(12).forEach { out.add(it) } // вопрос как в запросе
        answers.forEach { a -> a.forEach { out.add(it) } }
        return out.toByteArray()
    }
}

/**
 * Живой резолв через РЕАЛЬНЫЕ DoH-эндпоинты (проверяет, что провайдеры принимают наш wire-format и что
 * парсер справляется с их настоящими ответами — юнит-тесты выше этого не покажут). Гейт по env, чтобы
 * обычный `gradlew test` и CI не зависели от сети: MAYAK_TEST_NET=1 ./gradlew :core:test.
 */
class DohResolverLiveTest {
    @Test
    fun `резолв домена через живые резолверы`() {
        org.junit.Assume.assumeTrue("нет MAYAK_TEST_NET=1 — живой тест пропущен", System.getenv("MAYAK_TEST_NET") == "1")
        val ip = DohResolver.resolve("mayakvpn.ru")
        org.junit.Assert.assertNotNull("ни один DoH-резолвер не ответил", ip)
        org.junit.Assert.assertTrue("ожидали IPv4, получили $ip", Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip!!))
        // второй вызов обязан прийти из кэша (тот же ответ, без сети)
        org.junit.Assert.assertEquals(ip, DohResolver.resolve("mayakvpn.ru"))
    }
}
