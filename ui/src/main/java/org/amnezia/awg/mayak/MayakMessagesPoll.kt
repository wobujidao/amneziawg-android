// Частая проверка ящика сообщений, ПОКА ТУННЕЛЬ ПОДНЯТ (пункт 5а разбора 13-08).
//
// 🔴 Зачем, если есть фоновая проверка раз в 6 часов. Потому что шесть часов — это про «когда-нибудь
// узнает», а не про доставку. Быстрее WorkManager не умеет: минимальный период периодической работы —
// 15 минут, и даже он не гарантия, а «выполним, когда система сочтёт возможным» (поверх системного Doze
// каждый производитель — Samsung, Xiaomi, Huawei — добавляет свои убийцы фона).
//
// Но у нас есть окно, которого нет у обычного приложения: пока туннель поднят, ЖИВ НАШ ПРОЦЕСС —
// его держит foreground-VpnService, и App Standby его не трогает. Сеть в этот момент заведомо есть.
// Значит именно тогда можно заходить в ящик минутами, дёшево и без всякого Firebase. Это закрывает
// самый частый реальный случай: человек включил подключение и пользуется телефоном.
//
// Устроено ровно как LeaseKeepalive и SpeedNotifier — процесс-скоупный объект с одной задачей, которая
// сама завершается, увидев опущенный туннель. Заводится и гасится там же, где они (startKeepalive/
// stopKeepalive в MayakActivity и кнопка «Отключить» в шторке).
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MayakMessagesPoll {

    /**
     * Каждые 5 минут. Запрос крошечный (JSON со списком нового), туннель в это время и так поднят —
     * то есть цена вопроса ниже, чем у одной картинки в мессенджере. Меньше ставить нет смысла: ниже
     * лежит пол самого MayakMessages (SyncTrigger.TUNNEL — минута), и он же страхует от двойного
     * захода, если задача почему-то запустится дважды.
     */
    private const val INTERVAL_MS = 5 * 60 * 1000L

    private const val TAG = "AmneziaWG/mayak-messages"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null

    /** Запустить проверку, пока туннель поднят. Идемпотентно (повторный вызов не плодит задачи). */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        Log.i(TAG, "poll start: every ${INTERVAL_MS / 1000}s while tunnel is up")
        job = scope.launch {
            while (isActive) {
                if (!GoTunnel(app).isUp()) break // туннель опущен → это больше не наше окно
                // Ошибку глотаем: сбой доставки не должен ни рвать цикл, ни всплывать на экране.
                runCatching { MayakMessages.sync(app, MayakMessages.SyncTrigger.TUNNEL) }
                delay(INTERVAL_MS)
            }
            Log.i(TAG, "poll stop")
        }
    }

    /** Остановить (на дисконнекте). Не вызвать — цикл сам завершится, увидев isUp() = false. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
