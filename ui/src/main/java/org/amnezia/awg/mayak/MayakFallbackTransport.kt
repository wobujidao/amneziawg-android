// MayakFallbackTransport — андроидная обвязка запасного канала (SPEC-0039 T4).
//
// Логика транспорта живёт в :core (WsDatagramClient + WsUdpShim) и тестируется на JVM. Здесь только
// то, что без Android не сделать: СОКЕТ НАДО ЗАЩИТИТЬ от заворачивания в собственный туннель.
//
// Если этого не сделать, получается петля: пакеты нашего же WSS-соединения уходят в tun-интерфейс,
// оттуда в AWG, который пытается отправить их… через то же соединение. Наружу не выходит ничего, а
// выглядит это как «просто не подключается» — самый неприятный вид поломки, без единой ошибки в логе.
// Поэтому protect зовётся ДО connect (первый SYN уже должен идти мимо туннеля), и если он не удался —
// мы НЕ подключаемся вовсе. Лучше честно остаться без запасного канала, чем молча висеть.
package org.amnezia.awg.mayak

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.mayak.core.Fallback
import org.amnezia.awg.mayak.core.WsDatagramClient
import org.amnezia.awg.mayak.core.WsUdpShim

object MayakFallbackTransport {
    // Тег ОБЯЗАН содержать «AmneziaWG»: DiagCollector собирает в присланный лог только такие теги.
    // Первый же живой разбор (2026-07-25) уткнулся ровно в это — в логе не было НИ ОДНОЙ строки
    // запасного канала, и понять, пробовал он его вообще, было нельзя.
    private const val TAG = "AmneziaWG/mayak-fallback"

    private var shim: WsUdpShim? = null

    /** Поднят ли канал прямо сейчас (для пометки «резервный канал» в интерфейсе). */
    @Volatile
    var isUp: Boolean = false
        private set

    /**
     * Поднимает запасной канал и возвращает `127.0.0.1:<порт>` — это надо подставить в конфиг вместо
     * настоящего endpoint'а сервера. null — фолбэк непригоден или не поднялся, работаем как обычно.
     *
     * Соединение до моста устанавливается ЛЕНИВО, на первой датаграмме от движка: пока туннель молчит,
     * лишнюю TCP-сессию к нашему домену не держим и лишний раз её не светим.
     */
    @Synchronized
    fun start(fb: Fallback): String? {
        if (!fb.usable()) {
            Log.i(TAG, "запасной канал не пригоден (kind=${fb.kind}) — остаёмся на UDP")
            return null
        }
        stop()
        return try {
            val s = WsUdpShim(
                connectClient = {
                    WsDatagramClient(url = fb.url, token = fb.token, openTcp = ::openProtectedTcp).also { it.connect() }
                },
                onUp = { up ->
                    isUp = up
                    Log.i(TAG, if (up) "запасной канал поднят" else "запасной канал оборван, переподключаюсь")
                },
            )
            s.start()
            shim = s
            Log.i(TAG, "шим слушает 127.0.0.1:${s.localPort}, мост ${fb.url}")
            "127.0.0.1:${s.localPort}"
        } catch (e: Exception) {
            Log.w(TAG, "не удалось поднять запасной канал: ${e.message}")
            null
        }
    }

    @Synchronized
    fun stop() {
        shim?.let { runCatching { it.close() } }
        shim = null
        isUp = false
    }

    /** Диагностика: сколько датаграмм ушло/пришло по запасному каналу (0/0 — им не пользовались). */
    fun counters(): Pair<Long, Long> = shim?.let { it.sent.get() to it.received.get() } ?: (0L to 0L)

    private fun openProtectedTcp(host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        // ⚠️ Порядок важен: protect ДО connect. Иначе первый SYN уйдёт в туннель, а туннеля ещё нет.
        if (!GoBackend.protectSocket(s)) {
            runCatching { s.close() }
            throw IOException("не удалось защитить сокет от туннеля — запасной канал не поднимаю (иначе петля)")
        }
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }
}
