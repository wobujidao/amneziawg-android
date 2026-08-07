package org.amnezia.awg.mayak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PendingDiagLogTest {

    // Файл специально НЕ в корне temp-каталога, а во вложенном — так же, как на устройстве
    // (cacheDir/diag/...): проверяем заодно, что save() сам создаёт родительский каталог.
    private fun tempFile(): File {
        val dir = Files.createTempDirectory("pending-diag-log-test").toFile()
        dir.deleteOnExit()
        return File(dir, "diag/mayak-diag-log.json")
    }

    private fun sampleReq(direction: String = "nl2") = DiagLogRequest(
        appVersion = "0.3.99 (146)",
        os = "Android 14 (SDK 34)",
        deviceModel = "Pixel 8",
        networkType = "cellular",
        otherVpn = false,
        direction = direction,
        deviceId = 42,
        source = "manual",
        meta = mapOf("carrier" to "МегаФон"),
        log = "тестовый лог",
    )

    @Test
    fun `нет файла — read отдаёт null, exists — false`() {
        val f = tempFile()
        assertFalse(PendingDiagLog.exists(f))
        assertNull(PendingDiagLog.read(f))
    }

    @Test
    fun `сохранённый запрос читается обратно как есть`() {
        val f = tempFile()
        val req = sampleReq()
        PendingDiagLog.save(f, req)
        assertTrue(PendingDiagLog.exists(f))
        assertEquals(req, PendingDiagLog.read(f))
    }

    @Test
    fun `новое сохранение затирает старое — файл всегда один`() {
        val f = tempFile()
        PendingDiagLog.save(f, sampleReq(direction = "nl2"))
        PendingDiagLog.save(f, sampleReq(direction = "de1"))
        assertEquals("de1", PendingDiagLog.read(f)?.direction)
        // ровно один файл в каталоге — историю неудачных попыток не копим
        assertEquals(1, f.parentFile?.listFiles()?.size)
    }

    @Test
    fun `clear удаляет файл, повторный clear не падает`() {
        val f = tempFile()
        PendingDiagLog.save(f, sampleReq())
        PendingDiagLog.clear(f)
        assertFalse(PendingDiagLog.exists(f))
        PendingDiagLog.clear(f) // файла уже нет — тихо, без исключений
    }

    @Test
    fun `битый JSON не роняет чтение — read отдаёт null`() {
        val f = tempFile()
        f.parentFile?.mkdirs()
        f.writeText("{не json")
        assertNull(PendingDiagLog.read(f))
    }
}
