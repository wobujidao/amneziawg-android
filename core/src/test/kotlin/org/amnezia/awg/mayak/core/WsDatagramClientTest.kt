package org.amnezia.awg.mayak.core

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Тесты запасного транспорта (SPEC-0039). Поднимаем игрушечный WS-сервер на loopback и гоняем через
 * него датаграммы — так проверяется ровно то, что поедет на устройстве, только без TLS и Android.
 */
class WsDatagramClientTest {

    /** Боевой токен моста — base64, то есть ASCII. Тест держит ту же форму. */
    private val TOKEN = "LetuNRcdPCaFBeEbsUT0Idc6EhAGw9HDlWWS3kt39s0="

    private var server: FakeWsServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    /** Тест-вектор RFC 6455 §1.3. Ловит опечатку в GUID — на ней я уже один раз потерял время. */
    @Test
    fun `accept считается по вектору из RFC`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsDatagramClient.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun `датаграммы ходят в обе стороны`() {
        val srv = FakeWsServer(token = TOKEN).also { server = it; it.start() }
        val c = WsDatagramClient(url = "ws://127.0.0.1:${srv.port}/v1/stream", token = TOKEN)
        c.use {
            it.connect()
            for (i in 1..3) {
                val msg = "датаграмма-$i".toByteArray()
                it.send(msg)
                assertArrayEquals("ответ #$i", msg, it.receive())
            }
        }
    }

    /** Крупная датаграмма: длина кадра уезжает в расширенное поле (126) — частое место ошибок. */
    @Test
    fun `кадр длиннее 125 байт кодируется верно`() {
        val srv = FakeWsServer(token = TOKEN).also { server = it; it.start() }
        val big = ByteArray(1400) { (it % 251).toByte() }
        WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", TOKEN).use {
            it.connect()
            it.send(big)
            assertArrayEquals(big, it.receive())
        }
    }

    /** Сервер вправе прислать сообщение частями — клиент обязан склеить. */
    @Test
    fun `фрагментированное сообщение склеивается`() {
        val srv = FakeWsServer(token = TOKEN, fragmentReplies = true).also { server = it; it.start() }
        WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", TOKEN).use {
            it.connect()
            val msg = "склей-меня-целиком".toByteArray()
            it.send(msg)
            assertArrayEquals(msg, it.receive())
        }
    }

    /** Ping от сервера отвечаем pong и продолжаем работать — иначе мост закроет нас по простою. */
    @Test
    fun `ping от сервера не ломает поток данных`() {
        val srv = FakeWsServer(token = TOKEN, pingBeforeReply = true).also { server = it; it.start() }
        WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", TOKEN).use {
            it.connect()
            val msg = "после-пинга".toByteArray()
            it.send(msg)
            assertArrayEquals(msg, it.receive())
            assertTrue("сервер должен был получить pong", srv.awaitPong())
        }
    }

    /** Мост маскируется под сайт: без токена приходит обычная 404, и это должно быть внятной ошибкой. */
    @Test
    fun `неверный токен = отказ, а не молчание`() {
        val srv = FakeWsServer(token = TOKEN).also { server = it; it.start() }
        try {
            WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", "ne-tot-token").use { it.connect() }
            fail("ожидали IOException на отказе моста")
        } catch (e: IOException) {
            assertTrue("текст ошибки: ${e.message}", e.message!!.contains("404"))
        }
    }

    /** Закрытие с той стороны — это null, а не исключение: вызывающий переподключится. */
    @Test
    fun `закрытие сервером даёт null`() {
        val srv = FakeWsServer(token = TOKEN, closeAfterFirst = true).also { server = it; it.start() }
        WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", TOKEN).use {
            it.connect()
            it.send("раз".toByteArray())
            assertArrayEquals("раз".toByteArray(), it.receive())
            assertNull("после close сервера ждём null", it.receive())
        }
    }

    /**
     * Не-ASCII токен обязан падать ВНЯТНО. Раньше он молча кодировался в «?», мост отвечал обычной
     * 404, и выглядело это как «сервер не пускает» — искать пришлось бы не там.
     */
    @Test
    fun `не-ASCII токен отвергается внятно`() {
        val srv = FakeWsServer(token = TOKEN).also { server = it; it.start() }
        try {
            WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", "секретный-токен").use { it.connect() }
            fail("ожидали отказ на не-ASCII токене")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ASCII"))
        }
    }

    /**
     * Открытый ws:// наружу запрещён. Иначе при опечатке в конфиге транспорт молча деградировал бы
     * до незашифрованного — при том что вся затея ради маскировки под обычный HTTPS.
     */
    @Test
    fun `открытый ws запрещён вне loopback`() {
        try {
            WsDatagramClient("ws://mayakvpn.ru/v1/stream", TOKEN).use { it.connect() }
            fail("ожидали отказ на ws:// к внешнему хосту")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("loopback"))
        }
    }
}

/** Игрушечный сервер WebSocket на loopback: апгрейд + эхо бинарных кадров. */
private class FakeWsServer(
    private val token: String,
    private val fragmentReplies: Boolean = false,
    private val pingBeforeReply: Boolean = false,
    private val closeAfterFirst: Boolean = false,
) {
    private val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = ss.localPort
    @Volatile private var gotPong = false
    @Volatile private var stopped = false

    fun start() {
        thread(isDaemon = true, name = "fake-ws") {
            while (!stopped) {
                val s = try { ss.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) { runCatching { serve(s) } }
            }
        }
    }

    fun awaitPong(): Boolean {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (gotPong) return true
            Thread.sleep(10)
        }
        return false
    }

    fun close() {
        stopped = true
        runCatching { ss.close() }
    }

    private fun serve(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered())
        val out = s.getOutputStream()
        val headers = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r'); sb.setLength(0)
                if (line.isEmpty()) break
                headers.add(line)
            } else sb.append(b.toChar())
        }
        val auth = headers.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter("Bearer ")?.trim()
        if (auth != token) {
            // Ровно как боевой мост: отказ выглядит обычной 404-страницей сайта.
            val body = "<!doctype html>404"
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n\r\n$body")
                .toByteArray())
            out.flush(); s.close(); return
        }
        val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${WsDatagramClient.acceptFor(key)}\r\n\r\n").toByteArray())
        out.flush()

        while (true) {
            val msg = readClientFrame(input) ?: return
            if (pingBeforeReply) writeFrame(out, 0x9, "пинг".toByteArray())
            if (fragmentReplies && msg.size > 1) {
                val half = msg.size / 2
                writeFrame(out, 0x2, msg.copyOfRange(0, half), fin = false)
                writeFrame(out, 0x0, msg.copyOfRange(half, msg.size), fin = true)
            } else {
                writeFrame(out, 0x2, msg)
            }
            if (closeAfterFirst) {
                writeFrame(out, 0x8, ByteArray(0))
                out.flush(); s.close(); return
            }
        }
    }

    /** Читает кадр клиента (обязан быть маскированным); pong учитывает и ждёт следующий кадр данных. */
    private fun readClientFrame(input: DataInputStream): ByteArray? {
        while (true) {
            val b0 = input.read(); if (b0 < 0) return null
            val b1 = input.read(); if (b1 < 0) return null
            val opcode = b0 and 0x0F
            if ((b1 and 0x80) == 0) throw IOException("клиент прислал НЕмаскированный кадр")
            var len = (b1 and 0x7F).toLong()
            when (len) {
                126L -> len = ((input.read() shl 8) or input.read()).toLong()
                127L -> { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            }
            val mask = ByteArray(4); input.readFully(mask)
            val payload = ByteArray(len.toInt()); input.readFully(payload)
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0xA -> gotPong = true
                0x8 -> return null
                else -> return payload
            }
        }
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val head = ArrayList<Byte>()
        head.add(((if (fin) 0x80 else 0x00) or opcode).toByte())
        when {
            payload.size < 126 -> head.add(payload.size.toByte())
            payload.size < 65536 -> {
                head.add(126.toByte()); head.add((payload.size shr 8).toByte()); head.add(payload.size.toByte())
            }
            else -> {
                head.add(127.toByte()); for (i in 7 downTo 0) head.add((payload.size ushr (8 * i)).toByte())
            }
        }
        synchronized(out) {
            out.write(head.toByteArray()); out.write(payload); out.flush()
        }
    }
}
