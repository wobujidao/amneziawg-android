// Сквозная проверка пути парного флага 3.1 (случайная длина пакетов рукопожатия) ВНУТРИ приложения:
// ответ ядра → ConfRenderer (:core) → парсер форка (org.amnezia.awg.config) → UAPI-строка, которую
// GoBackend отдаёт движку. Тем же путём едет ключ защиты заголовка (HeaderProtectionPipeTest).
//
// Почему это стоит отдельного теста. Флаг ПАРНЫЙ: сервер, включивший его, шлёт пакеты рукопожатия
// случайной длины, и клиент без флага отбросит их как чужие — связи не будет ВООБЩЕ. Значит потеря
// флага любым слоем (не отрендерили, не разобрали, не назвали как ждёт движок) выглядит у человека
// не как «мелкий регресс», а как «страна перестала работать».
//
// Имена намеренно РАЗНЫЕ на разных концах: в .conf у awg-tools ключ зовётся RandomTrailers, в UAPI
// движка — random_trailers. Тест фиксирует оба.
package org.amnezia.awg.mayak

import java.io.BufferedReader
import java.io.StringReader
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.KeyPair
import org.amnezia.awg.mayak.core.ClientConfig
import org.amnezia.awg.mayak.core.ConfRenderer
import org.amnezia.awg.mayak.core.Obfuscation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomTrailersPipeTest {

    private val keys = KeyPair()
    private val serverKeys = KeyPair()

    private fun renderConf(randomTrailers: Boolean): String = ConfRenderer.render(
        ClientConfig(
            address = "10.8.0.2/32",
            obfuscation = Obfuscation(
                jc = 4, jmin = 8, jmax = 80,
                s1 = 12, s2 = 12, s3 = 12, s4 = 0,
                randomTrailers = randomTrailers,
            ),
            serverPubkey = serverKeys.publicKey.toBase64(),
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0",
        ),
        keys.privateKey.toBase64(),
    )

    @Test
    fun `флаг доезжает от рендера через парсер форка до UAPI-строки движка`() {
        val conf = renderConf(true)
        assertTrue("в .conf нет директивы awg-tools", conf.contains("RandomTrailers = true"))
        val uapi = Config.parse(BufferedReader(StringReader(conf))).getInterface().toAwgUserspaceString()
        // UAPI-имя фиксировано движком v3.1 (device/uapi.go: case "random_trailers")
        assertTrue("флаг не доехал до движка: $uapi", uapi.contains("random_trailers=true\n"))
    }

    @Test
    fun `без флага ни конфиг, ни UAPI его не содержат — байт-в-байт как раньше`() {
        val conf = renderConf(false)
        assertFalse(conf.contains("RandomTrailers"))
        val uapi = Config.parse(BufferedReader(StringReader(conf))).getInterface().toAwgUserspaceString()
        assertFalse(uapi.contains("random_trailers"))
    }
}
