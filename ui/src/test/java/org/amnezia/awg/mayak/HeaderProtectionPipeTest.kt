// Сквозная проверка пути ключа защиты заголовка (AWG 3.0) ВНУТРИ приложения: ответ ядра →
// ConfRenderer (:core) → парсер форка (org.amnezia.awg.config) → UAPI-строка, которую GoBackend
// отдаёт движку. Ровно этим путём ключ едет на устройстве; если любой слой его потеряет или
// переименует — тест красный. Чистый JVM-тест: Android тут не нужен.
package org.amnezia.awg.mayak

import java.io.BufferedReader
import java.io.StringReader
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.KeyPair
import org.amnezia.awg.mayak.core.ClientConfig
import org.amnezia.awg.mayak.core.ConfRenderer
import org.amnezia.awg.mayak.core.Obfuscation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderProtectionPipeTest {

    private val hpk = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val keys = KeyPair()
    private val serverKeys = KeyPair()

    private fun renderConf(key: String): String = ConfRenderer.render(
        ClientConfig(
            address = "10.8.0.2/32",
            obfuscation = Obfuscation(
                jc = 4, jmin = 8, jmax = 80,
                // с ключом сервер поднимает S1–S4 до 12 (требование движка v3)
                s1 = 12, s2 = 12, s3 = 12, s4 = 12,
                headerProtectionKey = key,
            ),
            serverPubkey = serverKeys.publicKey.toBase64(),
            endpoint = "203.0.113.7:51820",
            allowedIps = "0.0.0.0/0",
        ),
        keys.privateKey.toBase64(),
    )

    @Test
    fun `ключ доезжает от рендера через парсер форка до UAPI-строки движка`() {
        val conf = renderConf(hpk)
        val cfg = Config.parse(BufferedReader(StringReader(conf)))
        val uapi = cfg.getInterface().toAwgUserspaceString()
        // UAPI-имя фиксировано движком v3 (device/uapi.go: case "header_protection_key")
        assertTrue(uapi.contains("header_protection_key=$hpk\n"))
        // поднятые S3/S4 тоже доезжают (движок требует ≥12 при заданном ключе)
        assertTrue(uapi.contains("s3=12\n"))
        assertTrue(uapi.contains("s4=12\n"))
    }

    @Test
    fun `без ключа UAPI-строка его не содержит — как до появления поля`() {
        val conf = renderConf("")
        assertFalse(conf.contains("HeaderProtectionKey"))
        val uapi = Config.parse(BufferedReader(StringReader(conf))).getInterface().toAwgUserspaceString()
        assertFalse(uapi.contains("header_protection_key"))
    }

    @Test
    fun `парсер форка отвергает кривой ключ в conf и не выдаёт его в тексте ошибки`() {
        // Кривой ключ может приехать не только из ответа API (там его режет ConfRenderer), но и из
        // .conf на диске (last-good). Парсер — второй рубеж того же fail-closed: конфиг не собирается,
        // туннель не поднимается «наполовину».
        val broken = renderConf(hpk).replace(hpk, hpk.uppercase())
        try {
            Config.parse(BufferedReader(StringReader(broken)))
            throw AssertionError("кривой HeaderProtectionKey прошёл парсер форка")
        } catch (e: BadConfigException) {
            // ключ — секрет: в тексте исключения его быть не должно
            assertFalse(e.message.orEmpty().contains(hpk.uppercase().take(16)))
        }
    }
}
