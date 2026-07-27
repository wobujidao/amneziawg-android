// WsDatagramClient — клиент запасного транспорта «Маяка» (SPEC-0039): датаграммы AmneziaWG внутри
// обычного WebSocket-соединения к нашему же сайту на :443.
//
// Зачем: клиентское плечо у нас только UDP. Когда оператор душит весь неопознанный UDP (whitelist,
// отдельные соты), продукт ложится целиком. Здесь второй путь — снаружи это HTTPS к сайту, который
// реально существует и отвечает контентом; серверная половина (мост WS⇄UDP за Caddy) уже на ядре.
//
// Почему свой WebSocket, а не библиотека: в проекте сознательно нет okhttp (MayakBackend живёт на
// HttpURLConnection), а нам нужен полный контроль над сокетом — на Android его обязан «защитить»
// VpnService.protect ДО TLS-рукопожатия, иначе собственное соединение завернётся в туннель. Ровно
// поэтому TCP-сокет создаётся не здесь, а через openTcp: платформа отдаёт УЖЕ защищённый сокет.
//
// Кадрирование — RFC 6455, минимально необходимое: бинарные сообщения, клиентские кадры маскируются
// (этого требует стандарт), ping от сервера отвечаем pong. Одна датаграмма = одно сообщение.
package org.amnezia.awg.mayak.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Максимальный размер сообщения — датаграмма AWG заведомо меньше; всё крупнее считаем мусором. */
const val WS_MAX_MESSAGE = 64 * 1024

/**
 * @param url      адрес моста: `wss://<домен>/v1/stream` (в тестах допускается `ws://` на loopback).
 * @param token    общий секрет моста; уходит в `Authorization: Bearer`. Без него мост отвечает
 *                 ровно тем же 404, что и сайт, — по ответу нельзя понять, что путь вообще есть.
 * @param openTcp  как получить ПОДКЛЮЧЁННЫЙ TCP-сокет. На Android сюда передают вариант, который
 *                 создаёт сокет, зовёт VpnService.protect и только потом коннектится.
 */
class WsDatagramClient(
    private val url: String,
    private val token: String,
    // 3 с, а не 8. Это не микрооптимизация: запасной канал поднимается ТОЛЬКО когда человек уже
    // сидит на экране «Подключаюсь…», а движок продолжает переспрашивать хендшейк — то есть каждая
    // неудачная попытка ставится в очередь и складывается. Живой разбор 2026-07-27: мост был
    // недостижим (ядро выдавало не тот адрес), и по 8 с на попытку набегала минута молчания вместо
    // внятного отказа. До нашего же :443 из сотовой сети коннект укладывается в доли секунды —
    // всё, что дольше трёх, это не «медленно», а «не туда».
    private val connectTimeoutMs: Int = 3_000,
    private val openTcp: (host: String, port: Int, timeoutMs: Int) -> Socket = ::defaultOpenTcp,
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : Closeable {

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val rnd = SecureRandom()
    private val sendLock = Any()
    @Volatile private var closed = false

    /** Открывает соединение и делает WebSocket-апгрейд. Бросает IOException, если мост не пустил. */
    fun connect() {
        val uri = URI(url)
        val secure = when (uri.scheme?.lowercase()) {
            "wss" -> true
            // Открытый ws:// разрешаем ТОЛЬКО на loopback (тесты/локальный шим). Иначе это была бы
            // тихая деградация до незашифрованного канала — ровно то, от чего мы защищаемся.
            "ws" -> if (isLoopback(uri.host)) false else throw IOException("ws:// разрешён только на loopback")
            else -> throw IOException("неподдерживаемая схема: ${uri.scheme}")
        }
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val host = uri.host ?: throw IOException("в адресе нет хоста: $url")
        val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")

        var s = openTcp(host, port, connectTimeoutMs)
        try {
            s.tcpNoDelay = true // датаграммы мелкие и латентно-чувствительные: Nagle тут только вредит
            if (secure) s = handshakeTls(s, host, port)
            socket = s
            input = DataInputStream(s.getInputStream().buffered())
            output = s.getOutputStream()
            upgrade(host, path)
        } catch (e: Throwable) {
            runCatching { s.close() }
            throw e
        }
    }

    private fun handshakeTls(raw: Socket, host: String, port: Int): SSLSocket {
        val tls = sslSocketFactory.createSocket(raw, host, port, true) as SSLSocket
        // ⚠️ SSLSocket сам по себе имя хоста НЕ проверяет — надо попросить явно, иначе любой валидный
        // серт подойдёт и MITM пройдёт незамеченным. (Правило проекта: TLS проверяем строго.)
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        return tls
    }

    private fun upgrade(host: String, path: String) {
        // Значения заголовков HTTP — только ASCII. Раньше строка запроса кодировалась в ISO-8859-1 и
        // не-ASCII токен МОЛЧА превращался в «?», а мост отвечал обычной 404 — то есть отладка выглядела
        // бы как «сервер не пускает», хотя виноват клиент. Поймали на тесте с кириллическим токеном.
        require(token.all { it.code in 0x21..0x7E }) {
            "токен моста должен быть печатным ASCII (у нас это base64) — иначе заголовок не закодировать"
        }
        val keyBytes = ByteArray(16).also { rnd.nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Authorization: Bearer ").append(token).append("\r\n")
            append("\r\n")
        }
        output.write(req.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val head = readHeaders()
        val status = head.firstOrNull().orEmpty()
        if (!status.contains(" 101")) {
            // Мост маскируется под сайт: отказ приходит как обычная 404. Наверх отдаём это как
            // «не пустили», не пытаясь угадать причину — по ответу её и не отличить, так задумано.
            throw IOException("мост не принял соединение: $status")
        }
        val accept = head.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != acceptFor(key)) throw IOException("неверный Sec-WebSocket-Accept")
    }

    private fun readHeaders(): List<String> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("соединение закрыто на апгрейде")
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) return lines
                lines.add(line)
                if (lines.size > 100) throw IOException("слишком много заголовков")
            } else {
                sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("слишком длинный заголовок")
            }
        }
    }

    /** Отправляет одну датаграмму бинарным кадром. Потокобезопасно относительно [receive]. */
    fun send(datagram: ByteArray) {
        require(datagram.size <= WS_MAX_MESSAGE) { "датаграмма больше $WS_MAX_MESSAGE" }
        synchronized(sendLock) {
            writeFrame(OP_BINARY, datagram)
        }
    }

    /**
     * Блокирующе читает одну датаграмму. null — соединение закрыто штатно.
     * Служебные кадры (ping/pong/close) обрабатываются внутри и наверх не всплывают.
     */
    fun receive(): ByteArray? {
        while (true) {
            val (opcode, payload) = readFrame() ?: return null
            when (opcode) {
                OP_BINARY, OP_TEXT, OP_CONTINUATION -> return payload
                OP_PING -> synchronized(sendLock) { writeFrame(OP_PONG, payload) }
                OP_PONG -> Unit
                OP_CLOSE -> {
                    runCatching { synchronized(sendLock) { writeFrame(OP_CLOSE, ByteArray(0)) } }
                    return null
                }
                else -> throw IOException("неизвестный opcode $opcode")
            }
        }
    }

    /** Собирает сообщение целиком (сервер вправе прислать его фрагментами). null — конец потока. */
    private fun readFrame(): Pair<Int, ByteArray>? {
        var firstOpcode = -1
        var acc: ByteArray? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) return null
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            if ((b1 and 0x80) != 0) throw IOException("сервер прислал маскированный кадр (нарушение RFC)")
            var len = (b1 and 0x7F).toLong()
            when (len) {
                126L -> len = ((input.read() shl 8) or input.read()).toLong()
                127L -> {
                    len = 0
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
            }
            if (len < 0 || len > WS_MAX_MESSAGE) throw IOException("кадр длиной $len — больше допустимого")
            val payload = ByteArray(len.toInt())
            input.readFully(payload)

            // Служебные кадры не фрагментируются — отдаём сразу, не смешивая с накопленным сообщением.
            if (opcode == OP_PING || opcode == OP_PONG || opcode == OP_CLOSE) return opcode to payload

            if (firstOpcode < 0) firstOpcode = opcode
            acc = if (acc == null) payload else acc + payload
            if (fin) return firstOpcode to (acc ?: ByteArray(0))
            if ((acc?.size ?: 0) > WS_MAX_MESSAGE) throw IOException("сообщение из фрагментов превысило лимит")
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        if (closed) throw IOException("клиент закрыт")
        val out = output
        val n = payload.size
        val header = ArrayList<Byte>(14)
        header.add((0x80 or opcode).toByte()) // FIN + opcode
        when {
            n < 126 -> header.add((0x80 or n).toByte()) // 0x80 — бит маски: клиент ОБЯЗАН маскировать
            n < 65536 -> {
                header.add((0x80 or 126).toByte())
                header.add((n shr 8).toByte()); header.add(n.toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (i in 7 downTo 0) header.add((n ushr (8 * i)).toByte())
            }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        val masked = ByteArray(n) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        out.write(header.toByteArray())
        out.write(mask)
        out.write(masked)
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        /** Константа RFC 6455 §4.2.2. Переставить в ней символы — классическая ошибка, сверяться с тест-вектором. */
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun acceptFor(key: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1") // так велит RFC 6455, это не криптозащита
            return Base64.getEncoder().encodeToString(sha1.digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)))
        }

        fun isLoopback(host: String?): Boolean =
            host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)

        fun defaultOpenTcp(host: String, port: Int, timeoutMs: Int): Socket =
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
    }
}
