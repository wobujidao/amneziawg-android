// Тесты клиентской проверки подписанного delivery-документа (F-T8).
//
// Главный якорь — ТЕСТ-ВЕКТОР, произведённый НАСТОЯЩИМ серверным кодом ядра
// (internal/configsign.Sign + internal/delivery.Sign, Go): генератор с фиксированными сидами
// (scratchpad/vecgen, 2026-08-09) сам проверил, что (1) конверт проходит серверный
// configsign.Verify и (2) пересобранный канон сходится с подписью через ed25519.Verify.
// Kotlin обязан воспроизвести канонические signedBytes БАЙТ-В-БАЙТ — это ровно та грабля,
// на которой подпись «не сходится при верном ключе» (порядок полей v,e,k,p, компактный JSON,
// Base64 StdEncoding с паддингом).
package org.amnezia.awg.mayak.core

import com.sun.net.httpserver.HttpServer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Base64

class DeliveryTest {
    companion object {
        // ---- вектор из Go (сиды 0x01..0x20 и 0x41..0x60, время фиксировано) ----
        private const val PUB1 = "ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=" // эпоха 1
        private const val PUB2 = "rcFAEfgtHFbZVqpPnXPYhYNhpgYEhSXg0Ixjjcdd2Mc=" // эпоха 2
        private const val NOW = 1754700060L // подписано в 1754700000, ttl 24ч
        private const val EXPIRES = 1754786400L

        private const val ENVELOPE1 =
            """{"version":7,"expires_at":1754786400,"key_epoch":1,"payload":"eyJjb3JlcyI6WyJodHRwczovL2FwaS5tYXlha25ldHdvcmtzLmNvbSIsImh0dHBzOi8vYXBpLnJlc2VydmUtem9uZS5leGFtcGxlIl0sImRvaCI6W3sidXJsIjoiaHR0cHM6Ly9kbnMuZXhhbXBsZS5uZXQvZG5zLXF1ZXJ5IiwiYm9vdHN0cmFwX2lwcyI6WyI5LjkuOS4xMCIsIjE0OS4xMTIuMTEyLjEwIl19XSwic2VlZHMiOlsiMi4yNi43Ny4yNDMiLCIyYTEyOmJlYzQ6MWRlMDo0MzQ6OjIiXSwidHRsX2hpbnQiOjM2MDB9","sig":"hyZOSW76yuj/V1xwbSotVde8883IQ6nwLNEG7prc3wj+BbxtC0RJQgluVrlP87wl2eJP4KsmNq1s8d0nnL8mAg=="}"""

        // эпоха 2, версия 8, подписан ключом эпохи 2 (ротация)
        private const val ENVELOPE2 =
            """{"version":8,"expires_at":1754786400,"key_epoch":2,"payload":"eyJjb3JlcyI6WyJodHRwczovL2FwaS5tYXlha25ldHdvcmtzLmNvbSIsImh0dHBzOi8vYXBpLnJlc2VydmUtem9uZS5leGFtcGxlIl0sImRvaCI6W3sidXJsIjoiaHR0cHM6Ly9kbnMuZXhhbXBsZS5uZXQvZG5zLXF1ZXJ5IiwiYm9vdHN0cmFwX2lwcyI6WyI5LjkuOS4xMCIsIjE0OS4xMTIuMTEyLjEwIl19XSwic2VlZHMiOlsiMi4yNi43Ny4yNDMiLCIyYTEyOmJlYzQ6MWRlMDo0MzQ6OjIiXSwidHRsX2hpbnQiOjM2MDB9","sig":"u4BLdQv+SkY5n4/eIvZTupnWwRnRfisMsc0yFI56IxJ300/xD7NT3u+3vueuj+Tpcqrs+ZNxNVS1+oBSB2hADA=="}"""

        // помечен эпохой 1, но ПОДПИСАН ключом эпохи 2 — кросс-эпохная подмена
        private const val ENVELOPE3_CROSS =
            """{"version":9,"expires_at":1754786400,"key_epoch":1,"payload":"eyJjb3JlcyI6WyJodHRwczovL2FwaS5tYXlha25ldHdvcmtzLmNvbSJdfQ==","sig":"P+tq8A676DHS8O4GfvcBwo6TQj7L9EVwNfVwc4cyv995/UG+92qTQBK/6NzDKYLU97K7+cjJWCHRX1CzvSJ4CQ=="}"""

        // эпоха 99 — валидная подпись ключом 1, но такого якоря у клиента НЕТ
        private const val ENVELOPE4_EPOCH99 =
            """{"version":10,"expires_at":1754786400,"key_epoch":99,"payload":"eyJjb3JlcyI6WyJodHRwczovL2FwaS5tYXlha25ldHdvcmtzLmNvbSJdfQ==","sig":"7g8WAQZz05YWzMxcrkgDkuPAdpsq2H++djMR9Dh7Morgkn+0fOISUx2igK0FGterkGHzBWvIDAnXo/r6HHzcDg=="}"""

        // payload НЕ кратен 3 байтам → base64 с паддингом «=». Конверт 1 паддинга не требует,
        // и мутация «NO_PADDING» проходила бы на нём незаметно — этот вектор её ловит.
        private const val ENVELOPE5_PADDED =
            """{"version":11,"expires_at":1754786400,"key_epoch":1,"payload":"eyJjb3JlcyI6WyJodHRwczovL2FwaS5tYXlha25ldHdvcmtzLmNvbSJdLCJ0dGxfaGludCI6NX0=","sig":"3IpymAASqucaTw2092xz66OyoWh2d3uwhr+0XV3Cd4reYTbkIypoUZdL9f/wrHiYv6ObCpiPzUnNC5c//8sjDQ=="}"""
        private const val ENV5_SIGNED_HEX =
            "7b2276223a31312c2265223a313735343738363430302c226b223a312c2270223a2265794a6a62334a6c6379493657794a6f64485277637a6f764c3246776153357459586c686132356c64486476636d747a4c6d4e7662534a644c434a306447786661476c75644349364e58303d227d"

        // hex(signedBytes) конверта 1, посчитанный Go (доказан ed25519.Verify в генераторе)
        private const val ENV1_SIGNED_HEX =
            "7b2276223a372c2265223a313735343738363430302c226b223a312c2270223a2265794a6a62334a6c6379493657794a6f64485277637a6f764c3246776153357459586c686132356c64486476636d747a4c6d4e7662534973496d68306448427a4f693876595842704c6e4a6c63325679646d5574656d39755a53356c654746746347786c496c3073496d5276614349365733736964584a73496a6f696148523063484d364c79396b626e4d755a586868625842735a5335755a5851765a47357a4c5846315a584a3549697769596d397664484e30636d467758326c7763794936577949354c6a6b754f5334784d434973496a45304f5334784d5449754d5445794c6a4577496c3139585377696332566c5a484d694f6c73694d6934794e6934334e7934794e444d694c434979595445794f6d4a6c597a51364d57526c4d446f304d7a51364f6a4969585377696448527358326870626e51694f6a4d324d444239227d"

        private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

        private val anchors: Map<Int, ByteArray> = mapOf(1 to b64(PUB1), 2 to b64(PUB2))

        // Сид эпохи 1 — тот же, что в Go-генераторе (0x01..0x20): тесты могут подписывать сами.
        private val seed1 = ByteArray(32) { (it + 1).toByte() }
    }

    private fun payloadOf(envelopeJson: String): ByteArray {
        val m = Regex("\"payload\":\"([^\"]*)\"").find(envelopeJson)!!
        return b64(m.groupValues[1])
    }

    /** Подпись конверта тем же каноном — ТОЛЬКО для тестов (payload-валидация и края).
     *  Право на жизнь этому помощнику даёт тест `канон байт в байт совпадает с Go`. */
    private fun signEnvelope(seed: ByteArray, version: Int, expiresAt: Long, keyEpoch: Int, payload: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        val msg = Delivery.signedBytes(version, expiresAt, keyEpoch, payload)
        signer.update(msg, 0, msg.size)
        val sig = Base64.getEncoder().encodeToString(signer.generateSignature())
        val p = Base64.getEncoder().encodeToString(payload)
        return """{"version":$version,"expires_at":$expiresAt,"key_epoch":$keyEpoch,"payload":"$p","sig":"$sig"}"""
    }

    private fun ok(env: String, now: Long = NOW, minVersion: Int = 0): DeliveryOutcome.Ok {
        val out = Delivery.verify(env, anchors, now, minVersion)
        assertTrue("ждали Ok, получили $out", out is DeliveryOutcome.Ok)
        return out as DeliveryOutcome.Ok
    }

    private fun rejected(env: String, reason: DeliveryReject, now: Long = NOW, minVersion: Int = 0) {
        val out = Delivery.verify(env, anchors, now, minVersion)
        assertTrue("ждали Rejected($reason), получили $out", out is DeliveryOutcome.Rejected)
        assertEquals(reason, (out as DeliveryOutcome.Rejected).reason)
    }

    // ---- канон ----

    @Test
    fun `канон байт в байт совпадает с Go`() {
        val got = Delivery.signedBytes(7, EXPIRES, 1, payloadOf(ENVELOPE1))
        assertEquals(ENV1_SIGNED_HEX, got.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `канон с паддингом совпадает с Go - паддинг обязателен`() {
        val payload = payloadOf(ENVELOPE5_PADDED)
        assertTrue("вектор обязан требовать паддинг", Base64.getEncoder().encodeToString(payload).endsWith("="))
        val got = Delivery.signedBytes(11, EXPIRES, 1, payload)
        assertEquals(ENV5_SIGNED_HEX, got.joinToString("") { "%02x".format(it) })
        val out = ok(ENVELOPE5_PADDED)
        assertEquals(listOf("https://api.mayaknetworks.com"), out.doc.cores)
        assertEquals(5, out.doc.ttlHintSec)
    }

    @Test
    fun `BouncyCastle из того же сида даёт тот же публичный ключ что Go`() {
        val pub = Ed25519PrivateKeyParameters(seed1, 0).generatePublicKey().encoded
        assertEquals(PUB1, Base64.getEncoder().encodeToString(pub))
    }

    // ---- happy path ----

    @Test
    fun `конверт от настоящего Go-ядра принимается и разбирается`() {
        val out = ok(ENVELOPE1, minVersion = 7) // равная версия проходит (повторное скачивание)
        assertEquals(7, out.version)
        assertEquals(1, out.keyEpoch)
        assertEquals(EXPIRES, out.expiresAt)
        assertEquals(
            listOf("https://api.mayaknetworks.com", "https://api.reserve-zone.example"),
            out.doc.cores,
        )
        assertEquals(1, out.doc.doh.size)
        assertEquals("https://dns.example.net/dns-query", out.doc.doh[0].url)
        assertEquals(listOf("9.9.9.10", "149.112.112.10"), out.doc.doh[0].bootstrapIps)
        assertEquals(listOf("2.26.77.243", "2a12:bec4:1de0:434::2"), out.doc.seeds)
        assertEquals(3600, out.doc.ttlHintSec)
    }

    @Test
    fun `ротация - эпоха 2 выбирает второй якорь`() {
        val out = ok(ENVELOPE2)
        assertEquals(2, out.keyEpoch)
        assertEquals(8, out.version)
    }

    // ---- fail-closed: подпись ----

    @Test
    fun `подделанный байт подписи отвергается`() {
        val sig = b64(Regex("\"sig\":\"([^\"]*)\"").find(ENVELOPE1)!!.groupValues[1])
        sig[10] = (sig[10].toInt() xor 0xFF).toByte()
        val tampered = ENVELOPE1.replace(
            Regex("\"sig\":\"[^\"]*\""),
            "\"sig\":\"${Base64.getEncoder().encodeToString(sig)}\"",
        )
        rejected(tampered, DeliveryReject.BAD_SIGNATURE)
    }

    @Test
    fun `подмена version при нетронутой подписи отвергается`() {
        // подпись покрывает version через канон — атака «подкрутить версию» не проходит
        rejected(ENVELOPE1.replace("\"version\":7", "\"version\":11"), DeliveryReject.BAD_SIGNATURE)
    }

    @Test
    fun `подмена payload при нетронутой подписи отвергается`() {
        val evil = Base64.getEncoder()
            .encodeToString("""{"cores":["https://evil.example"]}""".toByteArray())
        val tampered = ENVELOPE1.replace(Regex("\"payload\":\"[^\"]*\""), "\"payload\":\"$evil\"")
        rejected(tampered, DeliveryReject.BAD_SIGNATURE)
    }

    @Test
    fun `конверт подписанный ключом другой эпохи отвергается`() {
        // помечен эпохой 1, подписан ключом эпохи 2: якорь 1 подпись не подтверждает
        rejected(ENVELOPE3_CROSS, DeliveryReject.BAD_SIGNATURE)
    }

    // ---- fail-closed: эпоха ----

    @Test
    fun `неизвестная эпоха отвергается даже с валидной подписью`() {
        rejected(ENVELOPE4_EPOCH99, DeliveryReject.UNKNOWN_EPOCH)
    }

    @Test
    fun `пустой набор якорей отвергает всё`() {
        val out = Delivery.verify(ENVELOPE1, emptyMap(), NOW, 0)
        assertTrue(out is DeliveryOutcome.Rejected)
        assertEquals(DeliveryReject.UNKNOWN_EPOCH, (out as DeliveryOutcome.Rejected).reason)
    }

    // ---- fail-closed: срок и откат ----

    @Test
    fun `протухший документ отвергается - граница включительно как у ядра`() {
        rejected(ENVELOPE1, DeliveryReject.EXPIRED, now = EXPIRES) // now == expires → протух
        rejected(ENVELOPE1, DeliveryReject.EXPIRED, now = EXPIRES + 1)
    }

    @Test
    fun `нулевое время отвергается - анти-freeze обязателен`() {
        rejected(ENVELOPE1, DeliveryReject.MALFORMED, now = 0)
        rejected(ENVELOPE1, DeliveryReject.MALFORMED, now = -5)
    }

    @Test
    fun `версия ниже уже принятой отвергается`() {
        rejected(ENVELOPE1, DeliveryReject.ROLLED_BACK, minVersion = 8) // v7 < 8
    }

    // ---- fail-closed: мусор ----

    @Test
    fun `мусор вместо конверта отвергается`() {
        rejected("", DeliveryReject.MALFORMED)
        rejected("не json", DeliveryReject.MALFORMED)
        rejected("{}", DeliveryReject.MALFORMED) // нет обязательных полей
        rejected("""{"version":1,"expires_at":1,"key_epoch":1,"payload":"@@","sig":"@@"}""", DeliveryReject.MALFORMED)
    }

    @Test
    fun `подпись не 64 байта отвергается до крипто`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(10))
        rejected(
            ENVELOPE1.replace(Regex("\"sig\":\"[^\"]*\""), "\"sig\":\"$short\""),
            DeliveryReject.MALFORMED,
        )
    }

    // ---- payload-валидация (конверты подписываем сами тем же каноном) ----

    private fun signedPayload(payload: String): String =
        signEnvelope(seed1, 20, EXPIRES, 1, payload.toByteArray())

    @Test
    fun `самоподписанный минимальный документ проходит - помощник честен`() {
        val out = ok(signedPayload("""{"cores":["https://a.example"]}"""))
        assertEquals(listOf("https://a.example"), out.doc.cores)
        assertTrue(out.doc.doh.isEmpty() && out.doc.seeds.isEmpty())
    }

    @Test
    fun `неизвестные поля payload игнорируются - additive совместимость`() {
        ok(signedPayload("""{"cores":["https://a.example"],"future_channel":{"x":1}}"""))
    }

    @Test
    fun `плохой payload отвергается по каждому правилу`() {
        val bad = listOf(
            """{"cores":[]}""", // пусто
            """{"cores":["http://a.example"]}""", // не https
            """{"cores":["https://a.example/v1"]}""", // путь у базы
            """{"cores":["https://a.example?x=1"]}""", // query
            """{"cores":["https://user@a.example"]}""", // userinfo
            """{"cores":["https://a.example","https://a.example"]}""", // дубль
            """{"cores":["https://a.example:99999"]}""", // порт за пределами
            """{"cores":["https://пример.рф"]}""", // не-ASCII host (homograph)
            """{"cores":["https://a.example"],"seeds":["not-an-ip"]}""",
            """{"cores":["https://a.example"],"seeds":["1.2.3.4","1.2.3.4"]}""", // дубль seed
            """{"cores":["https://a.example"],"doh":[{"url":"https://d.example/q","bootstrap_ips":[]}]}""",
            """{"cores":["https://a.example"],"doh":[{"url":"http://d.example/q","bootstrap_ips":["1.2.3.4"]}]}""",
            """{"cores":["https://a.example"],"doh":[{"url":"https://d.example/q","bootstrap_ips":["x"]}]}""",
            """{"cores":["https://a.example"],"ttl_hint":-1}""",
            "{" + (1..17).joinToString(",", "\"cores\":[", "]") { "\"https://a$it.example\"" } + "}", // >16
            """не json вовсе""",
        )
        for (p in bad) {
            rejected(signedPayload(p), DeliveryReject.BAD_PAYLOAD)
        }
    }

    // ---- маппинги ----

    @Test
    fun `seed-IP превращается в базовый URL client-api`() {
        assertEquals("https://2.26.77.243:8443", Delivery.seedUrl("2.26.77.243"))
        assertEquals("https://[2a12:bec4:1de0:434::2]:8443", Delivery.seedUrl("2a12:bec4:1de0:434::2"))
    }

    @Test
    fun `DoH-эндпоинты собираются из bootstrap-IP и пути резолвера`() {
        val out = ok(ENVELOPE1)
        assertEquals(
            listOf("https://9.9.9.10/dns-query", "https://149.112.112.10/dns-query"),
            Delivery.dohEndpoints(out.doc),
        )
    }

    // ---- сквозной путь на заглушке: настоящий конверт от configsign через живой HTTP ----

    @Test
    fun `сквозной путь - конверт с локального HTTP проходит проверку и отдаёт адреса`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/client/delivery") { ex ->
            val body = ENVELOPE1.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        // всё остальное — 404, как боевое ядро без заведённого документа
        server.createContext("/") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        try {
            val port = server.address.port
            // документ есть → скачали → проверили → адреса на месте
            val raw = httpGet("http://127.0.0.1:$port/v1/client/delivery")!!
            val out = ok(raw, minVersion = 0)
            assertEquals("https://api.mayaknetworks.com", out.doc.cores.first())
            // документа нет (404, как сейчас на бою) → null → приложение остаётся на прежнем списке
            assertNull(httpGet("http://127.0.0.1:$port/v1/client/other"))
        } finally {
            server.stop(0)
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
