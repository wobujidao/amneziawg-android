// WsUdpShim — локальный UDP-шим запасного транспорта (SPEC-0039 T4).
//
// Идея простая: движок AmneziaWG остаётся нетронутым и шлёт свои датаграммы на 127.0.0.1, а шим
// перекладывает их в WebSocket-соединение до нашего моста и обратно. Loopback в туннель не
// маршрутизируется, поэтому петли нет и «защищать» этот сокет не нужно — защищать нужно только
// TCP-сокет самого WS-соединения, и это делает платформа (см. openTcp у WsDatagramClient).
//
// Почему так, а не своя реализация conn.Bind внутри Go: WS-соединение переустанавливается (обрыв,
// смена сети), и КАЖДЫЙ новый сокет надо заново отдавать в VpnService.protect. Из Go это означало бы
// JNI-колбэк на каждый реконнект в самом чувствительном месте; здесь реконнект живёт на Kotlin рядом
// с остальной логикой подключения. Разбор — SPEC-0039 §2а.
//
// Датаграммы при обрыве ТЕРЯЮТСЯ, и это правильно: UDP и так ненадёжен, WireGuard переспросит сам.
package org.amnezia.awg.mayak.core

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * @param connectClient создаёт НОВОГО подключённого клиента (вызывается заново на каждый реконнект).
 * @param onUp          колбэк смены состояния канала: true — соединение живо, false — переподключаемся.
 *                      Нужен для UI-пометки «резервный канал» и телеметрии (T5).
 */
class WsUdpShim(
    private val connectClient: () -> WsDatagramClient,
    private val onUp: (Boolean) -> Unit = {},
    private val reconnectDelaysMs: List<Long> = listOf(0, 500, 1_000, 2_000, 5_000),
) : Closeable {

    private val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    /** Порт на 127.0.0.1, который надо подставить движку как Endpoint вместо адреса сервера. */
    val localPort: Int get() = udp.localPort

    /** Счётчики для диагностики: сколько датаграмм ушло/пришло по запасному каналу. */
    val sent = AtomicLong()
    val received = AtomicLong()

    @Volatile private var closed = false
    @Volatile private var client: WsDatagramClient? = null
    @Volatile private var enginePeer: SocketAddress? = null
    private var upPump: Thread? = null
    private var downPump: Thread? = null

    fun start() {
        upPump = thread(isDaemon = true, name = "mayak-ws-up") { pumpEngineToWs() }
    }

    /** AWG → мост. Заодно поднимает соединение и рулит реконнектом: данные есть только здесь. */
    private fun pumpEngineToWs() {
        val buf = ByteArray(WS_MAX_MESSAGE)
        var attempt = 0
        while (!closed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                udp.receive(packet)
            } catch (e: IOException) {
                return // сокет закрыт — уходим
            }
            enginePeer = packet.socketAddress

            var c = client
            if (c == null) {
                // Подключаемся ЛЕНИВО, на первой датаграмме: пока движок молчит, держать TCP-сессию
                // к мосту незачем (и не светить её лишний раз).
                val delay = reconnectDelaysMs[minOf(attempt, reconnectDelaysMs.size - 1)]
                if (delay > 0) Thread.sleep(delay)
                if (closed) return
                try {
                    val fresh = connectClient()
                    client = fresh
                    attempt = 0
                    onUp(true)
                    downPump = thread(isDaemon = true, name = "mayak-ws-down") { pumpWsToEngine(fresh) }
                    c = fresh
                } catch (e: Exception) {
                    attempt++
                    onUp(false)
                    continue // датаграмму теряем — WireGuard переспросит
                }
            }
            try {
                c.send(packet.data.copyOf(packet.length))
                sent.incrementAndGet()
            } catch (e: Exception) {
                dropClient(c)
                attempt++
            }
        }
    }

    /** Мост → AWG. Живёт ровно столько, сколько живёт соединение [c]. */
    private fun pumpWsToEngine(c: WsDatagramClient) {
        try {
            while (!closed) {
                val data = c.receive() ?: break
                val peer = enginePeer ?: continue
                udp.send(DatagramPacket(data, data.size, peer as InetSocketAddress))
                received.incrementAndGet()
            }
        } catch (e: Exception) {
            // штатно: обрыв соединения — реконнектом займётся отправляющая сторона
        } finally {
            dropClient(c)
        }
    }

    private fun dropClient(c: WsDatagramClient) {
        if (client === c) {
            client = null
            onUp(false)
        }
        runCatching { c.close() }
    }

    override fun close() {
        closed = true
        client?.let { runCatching { it.close() } }
        runCatching { udp.close() }
    }
}
