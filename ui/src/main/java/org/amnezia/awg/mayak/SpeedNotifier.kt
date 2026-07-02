// Процесс-скоупное обновление ПОСТОЯННОГО уведомления скоростью передачи (↓/↑). В отличие от
// Activity-scoped speedJob (обновляет вид на главном экране) — ПЕРЕЖИВАЕТ сворачивание/уничтожение
// Activity, чтобы скорость была видна в шторке, когда приложение свёрнуто (напр. смотришь YouTube, а
// трафик виден). Работает ТОЛЬКО пока туннель поднят НАМИ И включена опция «Показывать скорость».
// Обновляет уведомление раз в 2с дельтой rx/tx (getStatistics через процесс-скоупный backend).
package org.amnezia.awg.mayak

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object SpeedNotifier {
    private const val INTERVAL_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /** Запустить обновление скорости в уведомлении, пока туннель поднят. Идемпотентно. Если опция
     *  «Показывать скорость» выключена — не запускается (обычное уведомление ведёт ping-цикл Activity). */
    fun start(context: Context) {
        stop()
        val app = context.applicationContext
        if (!MayakPrefs.showSpeed(app)) return
        val tun = GoTunnel(app)
        job = scope.launch {
            var lastRx = -1L
            var lastTx = -1L
            while (isActive) {
                if (!tun.isUp()) break // туннель опущен → перестаём обновлять
                val tr = tun.transfer()
                if (tr != null) {
                    val (rx, tx) = tr
                    if (lastRx >= 0) {
                        val down = (rx - lastRx).coerceAtLeast(0) * 1000 / INTERVAL_MS // байт/с
                        val up = (tx - lastTx).coerceAtLeast(0) * 1000 / INTERVAL_MS
                        val speed = "↓ ${MayakNotification.formatSpeed(down)}  ↑ ${MayakNotification.formatSpeed(up)}"
                        MayakNotification.show(
                            app, GoTunnel.connectedLabel, GoTunnel.connectedPingMs,
                            ipv6 = GoTunnel.egressIpv6 != null, speed = speed, // ↓/↑ в тексте уведомления
                        )
                    }
                    lastRx = rx; lastTx = tx
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
