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

/** Имя туннеля в движке + приёмник смены состояния (нам колбэк не нужен — состояние тянем сами). */
private class NamedTunnel(private val name: String) : Tunnel {
    override fun getName(): String = name
    override fun onStateChange(newState: Tunnel.State) {}
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

        private fun obtainBackend(ctx: Context): Backend =
            sharedBackend ?: synchronized(this) {
                sharedBackend ?: GoBackend(ctx.applicationContext).also { sharedBackend = it }
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
        backend.setState(tunnel, Tunnel.State.UP, config)
        connectedSinceElapsed = SystemClock.elapsedRealtime()
        Unit
    }

    override suspend fun down() = withContext(Dispatchers.IO) {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
        connectedSinceElapsed = null
        connectedLabel = null
        Unit
    }

    fun isUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
}
