package org.amnezia.awg.mayak.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шим глазами движка AWG: он просто шлёт датаграммы на 127.0.0.1:<порт шима> и ждёт ответы оттуда же.
 * Игрушечный WS-сервер на другом конце возвращает то, что получил, — так проверяется весь путь
 * «движок → шим → WebSocket → и обратно», только без TLS и Android.
 */
class WsUdpShimTest {

    private val token = "LetuNRcdPCaFBeEbsUT0Idc6EhAGw9HDlWWS3kt39s0="

    @Test
    fun `датаграммы движка доходят до моста и возвращаются`() {
        val srv = EchoWsServer(token).also { it.start() }
        val shim = WsUdpShim({ WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", token).also { it.connect() } })
        try {
            shim.start()
            val engine = DatagramSocket() // «движок AWG»
            engine.soTimeout = 5_000
            val target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), shim.localPort)

            for (i in 1..3) {
                val msg = "хендшейк-$i".toByteArray()
                engine.send(DatagramPacket(msg, msg.size, target))
                val back = DatagramPacket(ByteArray(2048), 2048)
                engine.receive(back)
                assertArrayEquals("ответ #$i", msg, back.data.copyOf(back.length))
            }
            assertEquals(3, shim.sent.get())
            assertEquals(3, shim.received.get())
            engine.close()
        } finally {
            shim.close(); srv.close()
        }
    }

    /**
     * Обрыв WS-соединения не должен ронять шим: следующая датаграмма движка обязана поднять новое
     * соединение. Это главный сценарий на мобильной сети — TCP рвётся постоянно.
     */
    @Test
    fun `после обрыва соединение поднимается заново`() {
        val srv = EchoWsServer(token, dropAfter = 1).also { it.start() }
        val states = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        val shim = WsUdpShim(
            { WsDatagramClient("ws://127.0.0.1:${srv.port}/v1/stream", token).also { it.connect() } },
            onUp = { states.add(it) },
            reconnectDelaysMs = listOf(0, 50, 50),
        )
        try {
            shim.start()
            val engine = DatagramSocket()
            engine.soTimeout = 5_000
            val target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), shim.localPort)
            val msg = "keepalive".toByteArray()

            engine.send(DatagramPacket(msg, msg.size, target))
            val back = DatagramPacket(ByteArray(2048), 2048)
            engine.receive(back)
            assertArrayEquals(msg, back.data.copyOf(back.length))

            // сервер закрыл соединение; шлём ещё раз — должен подняться новый сокет и снова ответить
            var ok = false
            for (attempt in 1..6) {
                engine.send(DatagramPacket(msg, msg.size, target))
                try {
                    engine.soTimeout = 1_500
                    engine.receive(back)
                    ok = true
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    // датаграмма пришлась на момент разрыва — теряется, как и положено UDP; шлём снова
                }
            }
            assertTrue("после обрыва канал должен восстановиться", ok)
            assertTrue("должен был отметиться разрыв", states.contains(false))
            assertTrue("и восстановление", states.count { it } >= 2)
            engine.close()
        } finally {
            shim.close(); srv.close()
        }
    }
}

/** WS-сервер-эхо: тот же протокол, что у боевого моста, но без TLS. dropAfter>0 — рвать после N сообщений. */
private class EchoWsServer(private val token: String, private val dropAfter: Int = 0) {
    private val ss = java.net.ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = ss.localPort
    @Volatile private var stopped = false

    fun start() = kotlin.concurrent.thread(isDaemon = true) {
        while (!stopped) {
            val s = try { ss.accept() } catch (e: Exception) { return@thread }
            kotlin.concurrent.thread(isDaemon = true) { runCatching { serve(s) } }
        }
    }

    fun close() { stopped = true; runCatching { ss.close() } }

    private fun serve(s: java.net.Socket) {
        val input = java.io.DataInputStream(s.getInputStream().buffered())
        val out = s.getOutputStream()
        val headers = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read(); if (b < 0) return
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r'); sb.setLength(0)
                if (line.isEmpty()) break
                headers.add(line)
            } else sb.append(b.toChar())
        }
        val auth = headers.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter("Bearer ")?.trim()
        if (auth != token) { s.close(); return }
        val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${WsDatagramClient.acceptFor(key)}\r\n\r\n").toByteArray())
        out.flush()

        var n = 0
        while (true) {
            val b0 = input.read(); if (b0 < 0) return
            val b1 = input.read(); if (b1 < 0) return
            var len = (b1 and 0x7F).toLong()
            when (len) {
                126L -> len = ((input.read() shl 8) or input.read()).toLong()
                127L -> { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            }
            val mask = ByteArray(4); input.readFully(mask)
            val payload = ByteArray(len.toInt()); input.readFully(payload)
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            if ((b0 and 0x0F) != 0x2) continue

            val head = ArrayList<Byte>()
            head.add((0x80 or 0x2).toByte())
            if (payload.size < 126) head.add(payload.size.toByte())
            else { head.add(126.toByte()); head.add((payload.size shr 8).toByte()); head.add(payload.size.toByte()) }
            out.write(head.toByteArray()); out.write(payload); out.flush()

            if (dropAfter > 0 && ++n >= dropAfter) { s.close(); return }
        }
    }
}
