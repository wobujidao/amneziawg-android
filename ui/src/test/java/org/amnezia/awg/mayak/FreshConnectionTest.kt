// Сторож правила «проба выхода не берёт чужой сокет».
//
// Ломается молча и дорого: сборка идёт, проба «работает», а человек ждёт лишние 4 секунды на каждой
// ступени (разбор диаг-логов владельца 21-08) — либо, что хуже, получает «Защищено» при мёртвом
// туннеле, потому что переиспользованный сокет ответил МИМО него.
//
// Проверяем ровно две вещи, на которых держится разведение пулов OkHttp:
//   1) у двух соединений подряд РАЗНЫЕ экземпляры SSLSocketFactory (ключ пула включает фабрику);
//   2) запрос просит закрыть соединение — свой сокет мы в пуле тоже не оставляем.
// И третью, текстом: сами пробы зовут именно этот помощник, а не голый openConnection().
package org.amnezia.awg.mayak

import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshConnectionTest {

    @Test
    fun `каждое соединение получает свой экземпляр фабрики`() {
        val url = URL("https://example.invalid/v1/egress-check")
        val a = FreshConnection.open(url) as HttpsURLConnection
        val b = FreshConnection.open(url) as HttpsURLConnection
        assertNotSame(
            "фабрика общая → OkHttp достанет соединение из общего пула, и проба спотыкнётся о чужой сокет",
            a.sslSocketFactory,
            b.sslSocketFactory,
        )
    }

    @Test
    fun `соединение просит закрыться и не оставаться в пуле`() {
        val conn = FreshConnection.open(URL("https://example.invalid/v1/egress-check"))
        assertEquals("close", conn.getRequestProperty("Connection"))
    }

    @Test
    fun `пробы ходят через помощник, а не голым openConnection`() {
        listOf(
            "src/main/java/org/amnezia/awg/mayak/HttpEgressProbe.kt",
            "src/main/java/org/amnezia/awg/mayak/MayakLinkProbe.kt",
        ).forEach { rel ->
            val text = найтиФайл(rel)
            assertTrue(
                "$rel больше не зовёт FreshConnection — проба снова будет брать сокет из общего пула",
                text.contains("FreshConnection.open("),
            )
            assertTrue(
                "$rel открывает соединение напрямую — так в пробу опять попадёт сокет, созданный до туннеля",
                !text.contains("openConnection() as HttpURLConnection"),
            )
        }
    }


    @Test
    fun `соединение в обход туннеля фабрику не подменяет`() {
        // Обход держится на сокет-фабрике самой сети (Network.openConnection). Подменишь её здесь —
        // и обход тихо перестанет быть обходом, а он существует ровно для случая «туннель мёртв».
        val url = URL("https://example.invalid/version.json")
        val своё = FreshConnection.open(url) { it.openConnection() as HttpsURLConnection }
        assertSame(
            "фабрику соединения, открытого чужим opener'ом, трогать нельзя",
            HttpsURLConnection.getDefaultSSLSocketFactory(),
            (своё as HttpsURLConnection).sslSocketFactory,
        )
        assertEquals("close", своё.getRequestProperty("Connection"))
    }

    private fun найтиФайл(rel: String): String {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, "ui/$rel").takeIf { it.exists() }?.let { return it.readText() }
            dir = dir?.parentFile
        }
        throw AssertionError("не нашёл $rel — сторож обязан падать, а не молчать")
    }
}
