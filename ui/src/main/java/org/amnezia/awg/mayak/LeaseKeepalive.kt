// Процесс-скоупный keepalive аренды overlay-IP (SPEC-0015). В отличие от Activity-scoped варианта —
// ПЕРЕЖИВАЕТ уничтожение Activity (Android выгружает экран под нагрузкой памяти, но foreground-VpnService
// и наш userspace-туннель остаются в процессе). Пока туннель поднят НАМИ — продлеваем аренду. Как только
// туннель опущен (или процесс убит) — продление прекращается, аренда со временем истекает и освобождается
// жнецом. Так «активная сессия» = «процесс+туннель живы» — точный сигнал живости (туннель живёт В процессе).
package org.amnezia.awg.mayak

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend

object LeaseKeepalive {
    // 30 мин при TTL 3ч → 6 продлений на срок, с запасом на пропуски. Первый прогон — сразу при start().
    private const val INTERVAL_MS = 30 * 60 * 1000L

    // Процесс-скоупный scope (живёт всё время процесса); активна максимум одна задача keepalive.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /** Запустить продление, пока туннель поднят. Идемпотентно (повторный вызов не плодит задачи). */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            while (isActive) {
                if (!GoTunnel(app).isUp()) break // туннель опущен → аренду больше не продлеваем
                runCatching { keepaliveOnce(app) } // best-effort: ошибка сети/ядра не рвёт цикл
                delay(INTERVAL_MS)
            }
        }
    }

    /** Остановить продление (на дисконнекте). Если не вызвать — цикл сам завершится, увидев isUp()=false. */
    fun stop() {
        job?.cancel()
        job = null
    }

    // Самодостаточный вызов keepalive: строим сессию/бэкенд из appContext (тот же HostProvider, что в
    // MayakActivity.hostProvider() — сохранённый сервер + IP-фолбэки). Не зависит от живой Activity.
    private suspend fun keepaliveOnce(app: Context) {
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        val saved = store.get(MayakActivity.KEY_SERVER)?.trimEnd('/')
        val hosts = if (saved != null && saved !in MayakActivity.DEFAULT_HOSTS)
            listOf(saved) + MayakActivity.DEFAULT_HOSTS
        else MayakActivity.DEFAULT_HOSTS
        session.keepalive(MayakBackend(HostProvider(hosts)))
    }
}
