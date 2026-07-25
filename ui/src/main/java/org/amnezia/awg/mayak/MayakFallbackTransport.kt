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

    // Адрес моста, разрешённый ЗАРАНЕЕ — пока туннель ещё не поднят (см. start). Держится на всё время
    // жизни канала: WS-соединение переустанавливается при обрывах, и каждый реконнект иначе снова
    // упирался бы в резолвинг через мёртвый туннель.
    @Volatile private var pinnedIp: String? = null

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
    /**
     * @param resolvedIp адрес хоста моста, разрешённый ВЫЗЫВАЮЩИМ до подъёма туннеля (null — резолвить
     * на месте). Это не оптимизация: имя моста разрешается системным резолвером, а тот при поднятом
     * VPN ходит через туннель — то есть ровно через тот путь, который не работает (иначе запасной
     * канал не понадобился бы). Соединяемся по IP, а имя остаётся для SNI и проверки сертификата.
     */
    @Synchronized
    fun start(fb: Fallback, resolvedIp: String? = null): String? {
        if (!fb.usable()) {
            Log.i(TAG, "запасной канал не пригоден (kind=${fb.kind}) — остаёмся на UDP")
            return null
        }
        stop()
        pinnedIp = resolvedIp
        Log.i(TAG, "адрес моста: ${resolvedIp ?: "НЕ разрешён заранее (будет резолвиться через туннель — почти наверняка не выйдет)"}")
        return try {
            val s = WsUdpShim(
                connectClient = {
                    WsDatagramClient(url = fb.url, token = fb.token, openTcp = ::openProtectedTcp).also { it.connect() }
                },
                onUp = { up ->
                    isUp = up
                    Log.i(TAG, if (up) "запасной канал поднят" else "запасной канал оборван, переподключаюсь")
                },
                // Причина обязана быть в логе: без неё «оборван, переподключаюсь» каждые полсекунды
                // не отличить от «нет сети», «не пустил мост», «не смогли защитить сокет» и «имя не
                // разрешилось» — а лечатся они по-разному.
                onError = { e -> Log.w(TAG, "попытка подключения к мосту не удалась: ${e.javaClass.simpleName}: ${e.message}") },
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
        pinnedIp = null
    }

    /** Диагностика: сколько датаграмм ушло/пришло по запасному каналу (0/0 — им не пользовались). */
    fun counters(): Pair<Long, Long> = shim?.let { it.sent.get() to it.received.get() } ?: (0L to 0L)

    private fun openProtectedTcp(host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        // ⚠️ Android-грабля, на которую проект уже наступал с пингом (0.3.36): свежий Socket() НЕ создаёт
        // нативный fd до connect/bind, а VpnService.protect() применяется именно к fd — на сокете без fd
        // он просто возвращает false. Здесь это выглядело как «не удалось защитить сокет» на каждой
        // попытке (живой тест 2026-07-25). bind() на эфемерный порт создаёт fd, и protect работает.
        s.bind(InetSocketAddress(0))
        // Порядок важен: protect ДО connect. Иначе первый SYN уйдёт в туннель, а туннель как раз не работает.
        if (!GoBackend.protectSocket(s)) {
            runCatching { s.close() }
            throw IOException("не удалось защитить сокет от туннеля — запасной канал не поднимаю (иначе петля)")
        }
        // Литеральный IP в InetSocketAddress резолвинга НЕ требует — а он при поднятом туннеле пошёл
        // бы через сам туннель (защитить системный резолвер мы не можем).
        val target = pinnedIp ?: host
        if (target != host) Log.i(TAG, "мост $host → $target (адрес разрешён до подъёма туннеля)")
        s.connect(InetSocketAddress(target, port), timeoutMs)
        return s
    }
}
