// Android-реализация Tunnel (:core) поверх GoBackend форка (движок amneziawg-go).
// up(confText): парсим наш .conf штатным парсером форка (он же знает поля AWG 2.0) и поднимаем
// туннель. Согласие на VPN (GoBackend.VpnService.prepare) запрашивается в Activity ДО up().
package org.amnezia.awg.mayak

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.amnezia.awg.mayak.core.Tunnel as MayakCoreTunnel
import java.io.BufferedReader
import java.io.StringReader

/** Имя туннеля в движке + приёмник смены состояния. На DOWN убираем «Подключено»: туннель может
 *  погаснуть ВНЕ приложения (другое VPN-приложение перехватило VpnService, ревок, система остановила
 *  сервис — `GoBackend.VpnService.onDestroy` шлёт `onStateChange(DOWN)`). Раньше колбэк игнорировался
 *  → уведомление висело «Подключено», хотя VPN уже выключен (баг владельца 2026-07-06). */
private class NamedTunnel(private val name: String) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {
        if (newState == Tunnel.State.DOWN) GoTunnel.handleExternalDown()
    }
}

class GoTunnel(context: Context, tunnelName: String = "mayak") : MayakCoreTunnel {
    // ВАЖНО: backend и tunnel — ОДНИ на процесс (companion), а не по одному на Activity.
    // GoBackend.getState() форка сравнивает currentTunnel ПО ССЫЛКЕ, а состояние держит в полях
    // инстанса. Если создавать новый GoBackend/NamedTunnel на каждый onCreate (смена темы/языка,
    // возврат в приложение после закрытия при живом foreground-VpnService), новый инстанс не знает
    // про уже поднятый туннель → getState=DOWN → приложение врёт «не подключено». Единый на процесс
    // backend это чинит: пересоздание Activity состояние НЕ теряет.
    //
    // Заодно это даёт нужную семантику «следим ТОЛЬКО за нашим коннектом»: getState вернёт UP лишь
    // для туннеля, поднятого ЧЕРЕЗ наш backend. Если VPN включён другим приложением (Happ и т.п.),
    // Android гасит наш VpnService → onDestroy обнуляет currentTunnel → isUp()=false.
    companion object {
        // Тег диагностики конфига (содержит «AmneziaWG» → DiagCollector включает в присланный лог).
        private const val CFG_TAG = "AmneziaWG/mayak-cfg"
        @Volatile private var sharedBackend: Backend? = null
        @Volatile private var sharedTunnel: NamedTunnel? = null

        // Момент (SystemClock.elapsedRealtime), когда МЫ подняли туннель; null = мы его не поднимали
        // (или опустили). Процесс-скоупно → переживает пересоздание Activity, но не смерть процесса
        // (что консистентно: userspace-туннель живёт в процессе VpnService — умер процесс, умер туннель).
        @Volatile var connectedSinceElapsed: Long? = null
            private set

        // Метка НАШЕГО коннекта для уведомления «Подключено» ("🇳🇱 Нидерланды"). Ставит UI при коннекте;
        // процесс-скоупна → переживает пересоздание Activity (на повторном открытии показываем то же
        // направление, а не «🌐»). Сбрасывается в down() вместе с connectedSinceElapsed.
        @Volatile var connectedLabel: String? = null

        // Хост сервера ТЕКУЩЕГО подключения ("IP") для пинга; процесс-скоупно (переживает пересоздание
        // Activity → на повторном открытии продолжаем пинговать тот же сервер). Сбрасывается в down().
        @Volatile var connectedServerHost: String? = null

        // Последний измеренный пинг (мс) до сервера; для показа в уведомлении сразу на реоупене. null =
        // ещё не мерян / недоступен. Обновляет ping-цикл UI; сбрасывается в down().
        @Volatile var connectedPingMs: Int? = null

        // Выходной IPv4-адрес (проба ipify через туннель). Процесс-скоупно → показ IP переживает
        // пересоздание Activity (на реоупене видим тот же IP без повторной пробы). Сбрасывается в down().
        @Volatile var egressIpv4: String? = null

        // Выходной IPv6-адрес, если IPv6 РЕАЛЬНО работает через туннель (успешная проба api6.ipify.org).
        // null = IPv6 не задействован. Ставит UI после коннекта; процесс-скоупно (значок «IPv6» переживёт
        // пересоздание Activity). Сбрасывается в down(). Честный сигнал (SPEC-0014): по факту egress, не по ::/0.
        @Volatile var egressIpv6: String? = null

        // Application-контекст (процесс-скоупный) — чтобы убрать уведомление из onStateChange, когда
        // туннель гаснет ВНЕ приложения и Activity под рукой нет. Ставится при создании GoTunnel.
        @Volatile private var appContext: Context? = null

        /** Туннель ушёл в DOWN (в т.ч. внешне): сбросить процесс-скоупное состояние коннекта и убрать
         *  уведомление «Подключено». Идемпотентно с down() — повторный вызов безвреден. */
        fun handleExternalDown() {
            connectedSinceElapsed = null
            connectedLabel = null
            connectedServerHost = null
            connectedPingMs = null
            egressIpv4 = null
            egressIpv6 = null
            appContext?.let { MayakNotification.clear(it) }
        }

        private fun obtainBackend(ctx: Context): Backend {
            appContext = ctx.applicationContext
            return sharedBackend ?: synchronized(this) {
                sharedBackend ?: GoBackend(ctx.applicationContext).also { sharedBackend = it }
            }
        }

        private fun obtainTunnel(name: String): NamedTunnel =
            sharedTunnel ?: synchronized(this) {
                sharedTunnel ?: NamedTunnel(name).also { sharedTunnel = it }
            }
    }

    private val backend: Backend = obtainBackend(context)
    private val tunnel: NamedTunnel = obtainTunnel(tunnelName)

    override suspend fun up(confText: String) = withContext(Dispatchers.IO) {
        val config = Config.parse(BufferedReader(StringReader(confText)))
        logConfigSummary(confText) // диагностика: ЧТО применяем (без ключа/обфускации) — виден ли IPv6 в конфиге
        backend.setState(tunnel, Tunnel.State.UP, config)
        connectedSinceElapsed = SystemClock.elapsedRealtime()
        logTunAddresses() // диагностика: какие адреса РЕАЛЬНО встали на tun (Android применил v4/v6?)
        Unit
    }

    // Диагностика (тег с «AmneziaWG» → в присланный диаг-лог): сводка применяемого конфига. ТОЛЬКО
    // Address/DNS/MTU/AllowedIPs/Endpoint — БЕЗ приватного ключа и обфускации. Сразу видно, дали ли клиенту
    // IPv6 (dual-stack Address + ::/0 в AllowedIPs + IPv6-DNS). Оставлено намеренно (решение владельца 2026-07-07).
    private fun logConfigSummary(confText: String) {
        val keys = listOf("Address", "DNS", "MTU", "AllowedIPs", "Endpoint")
        val summary = confText.lineSequence()
            .map { it.trim() }
            .filter { line -> keys.any { line.startsWith("$it ") || line.startsWith("$it=") } }
            .joinToString(" | ")
        android.util.Log.i(CFG_TAG, "конфиг туннеля: $summary")
    }

    // Диагностика: адреса, реально вставшие на tun-интерфейс. Дельта с logConfigSummary («в конфиге v6 есть,
    // а на tun не встал») = Android не применил IPv6. Best-effort (VPN-интерфейс может назваться tun0/tun1).
    private fun logTunAddresses() {
        val addrs = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.startsWith("tun") }
                .flatMap { nif -> nif.inetAddresses.toList().map { "${nif.name}:${it.hostAddress}" } }
                .joinToString(", ").ifBlank { "tun-интерфейс не найден/без адресов" }
        }.getOrElse { "н/д: ${it.javaClass.simpleName}" }
        android.util.Log.i(CFG_TAG, "tun-адреса: $addrs")
    }

    override suspend fun down() = withContext(Dispatchers.IO) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        connectedSinceElapsed = null
        connectedLabel = null
        connectedServerHost = null
        connectedPingMs = null
        egressIpv4 = null
        egressIpv6 = null
        Unit
    }

    fun isUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /** Суммарные rx/tx байты туннеля (для отображения скорости передачи). null — статистика недоступна. */
    fun transfer(): Pair<Long, Long>? = runCatching {
        val st = backend.getStatistics(tunnel)
        st.totalRx() to st.totalTx()
    }.getOrNull()
}
