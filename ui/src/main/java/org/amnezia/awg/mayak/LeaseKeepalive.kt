// Процесс-скоупный keepalive аренды overlay-IP (SPEC-0015). В отличие от Activity-scoped варианта —
// ПЕРЕЖИВАЕТ уничтожение Activity (Android выгружает экран под нагрузкой памяти, но foreground-VpnService
// и наш userspace-туннель остаются в процессе). Пока туннель поднят НАМИ — продлеваем аренду. Как только
// туннель опущен (или процесс убит) — продление прекращается, аренда со временем истекает и освобождается
// жнецом. Так «активная сессия» = «процесс+туннель живы» — точный сигнал живости (туннель живёт В процессе).
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.MayakApiException

object LeaseKeepalive {
    // 30 мин при TTL 3ч → 6 продлений на срок, с запасом на пропуски. Первый прогон — сразу при start().
    private const val INTERVAL_MS = 30 * 60 * 1000L

    // Тег из семейства, которое DiagCollector кладёт в присланный человеком лог. Без него отказ
    // продления аренды не виден НИГДЕ: аренда просто истекает, туннель у человека гаснет через
    // несколько часов, и поддержка не может назвать причину.
    private const val TAG = "AmneziaWG/mayak-lease"

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
                delay(attemptOnce(app)) // сама попытка цикл не рвёт: молчание хуже неудачи
            }
        }
    }

    /** Остановить продление (на дисконнекте). Если не вызвать — цикл сам завершится, увидев isUp()=false. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Одна попытка продления. Возвращает, сколько ждать до следующей.
     *
     * Раньше здесь стоял голый `runCatching { }` — цикл не рвался, но и НАЗВАТЬ причину было нечем:
     * отказ ядра («срок доступа кончился», «устройство удалено», «слишком часто») выглядел точно так
     * же, как пропавший Wi-Fi. Человек в это время видит работающий туннель, а через пару часов —
     * погасший, и поддержке нечего сказать.
     *
     * Цикл по-прежнему НЕ РВЁМ ни на каком отказе, и это осознанно: остановиться молча — хуже, чем
     * впустую сходить на сервер ещё раз. Единственное, что меняет поведение, — «слишком часто»
     * (Retry-After): ждём столько, сколько просит ядро.
     */
    private suspend fun attemptOnce(app: Context): Long =
        try {
            keepaliveOnce(app)
            INTERVAL_MS
        } catch (e: CancellationException) {
            throw e // отмена задачи (stop / смерть процесса) — не наша ошибка, пробрасываем
        } catch (e: MayakApiException) {
            Log.w(TAG, "аренда не продлена: ядро отказало ${e.status} ${e.code.ifEmpty { "(без кода)" }}")
            if (e.retryAfterSec > 0) e.retryAfterSec * 1000L else INTERVAL_MS
        } catch (e: Exception) {
            // Сеть, TLS, разбор ответа. Тело сообщения не пишем: в нём бывает адрес хоста, а лог
            // человек отправляет нам целиком.
            Log.i(TAG, "аренда не продлена: ${e.javaClass.simpleName}")
            INTERVAL_MS
        }

    // Самодостаточный вызов keepalive: строим сессию/бэкенд из appContext (тот же HostProvider, что в
    // MayakActivity.hostProvider() — сохранённый сервер + IP-фолбэки). Не зависит от живой Activity.
    private suspend fun keepaliveOnce(app: Context) {
        val store = KeystoreSecureStore(app)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
        val hosts = MayakHostList.effective(app, store.get(MayakActivity.KEY_SERVER))
        session.keepalive(MayakBackend(HostProvider(hosts), bypassTunnel = OutsideTunnel.opener(app)))
    }
}
