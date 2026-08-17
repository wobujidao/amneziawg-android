// Сторож живости туннеля: кто имеет право писать в шторке «Защищено».
//
// ЗАЧЕМ (аудит 2026-07-31). Раньше единственным сторожем был ICMP-пинг из MayakActivity, и у него
// два врождённых изъяна: (1) он живёт в Activity — свернул приложение, и в шторке навсегда застыло
// последнее слово; (2) четыре промаха по 5 с — это от 20 до 50 секунд, в течение которых человеку
// уверенно писали «Защищено» при выключенной сети (замерено: 42 с подряд).
//
// Поэтому сторож процесс-скоупный, как LeaseKeepalive/SpeedNotifier: живёт ровно столько, сколько
// туннель, и переживает уничтожение Activity. Запускается и гасится вместе с ними (startKeepalive/
// stopKeepalive в MayakActivity + автоподключение), плюс сам останавливается, когда туннель опущен.
//
// ⛔ ЧЕГО ОН НЕ ДЕЛАЕТ: не переподнимает туннель, не переключает пути, не дёргает сеть. Только
// СТАТУС. Авто-переподъём мы выкатывали дважды и дважды делали хуже (гонка с onDestroy, смерть
// процесса в паузе — откат 0.3.78, см. Application.onNetworkChange). Задача сторожа — честное слово,
// а не самолечение.
//
// ПО ЧЕМУ СУДИТ (в порядке убывания надёжности, всё локально и бесплатно):
//   1. нет ни одной физической сети → защищать нечего, это видно мгновенно;
//   2. rx туннеля вырос с прошлого такта → трафик РЕАЛЬНО идёт, свежее доказательство;
//   3. рукопожатие свежее (моложе HANDSHAKE_FRESH_MS) → сервер отвечал недавно;
//   4. иначе — трафика нет.
package org.amnezia.awg.mayak

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MayakLiveness {
    private const val TAG = "Mayak/Liveness"
    private const val TICK_MS = 3_000L

    // Порог свежести рукопожатия. На живом туннеле с keepalive 10 с движок перевыпускает сессию
    // примерно раз в 120 с (REKEY_AFTER_TIME), поэтому возраст рукопожатия на здоровом соединении
    // не превышает ~130 с. Берём 150 с с запасом: больше — сервер не ответил на полный цикл
    // перевыпуска, то есть путь мёртв. Меньше ставить нельзя — начнём пугать здоровых.
    private const val HANDSHAKE_FRESH_MS = 150_000L

    // Фора только что поднятому туннелю. Первое рукопожатие приходит за секунды, а пир на выходе
    // заводится до ~15 с (sync-таймер ноды), поэтому сразу после подъёма «трафика нет» — не диагноз,
    // а нетерпение: на живом эмуляторе 2026-08-01 сторож успел мигнуть «трафик не идёт» за 3 с до
    // первого же пакета. В эту фору честный статус — «Проверяем соединение…».
    private const val WARMUP_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /** Кого будить при СМЕНЕ состояния живости (главный экран, пока он есть). Ставится/снимается
     *  Activity; сторож работает и без слушателя — уведомление он обновляет сам. */
    @Volatile var onChange: ((Int) -> Unit)? = null

    /**
     * Этот сторож САМ, по росту rx и возрасту рукопожатия, видит, что трафика нет.
     *
     * Отдельно от [GoTunnel.liveness] потому, что в общее состояние пишет и пинг-цикл главного
     * экрана — читая его, экран читал бы собственный вывод. Здесь ровно вердикт СТОРОЖА, и нужен он
     * ровно затем, чтобы экран не пересчитывал с нуля то, что уже известно: в живом логе (диаг #28)
     * между «сторож знает» и «начали чинить» прошло 36 секунд, и всё это время человек смотрел на
     * «Защищено» и на страницу, которая не грузится. Подробнее — [LivenessDecision].
     */
    @Volatile var watchdogSaysNoTraffic: Boolean = false
        private set

    /** Запустить сторожа на время жизни туннеля. Идемпотентно. */
    fun start(context: Context) {
        stop()
        val app = context.applicationContext
        val tun = GoTunnel(app)
        job = scope.launch {
            var lastRx = -1L
            while (isActive) {
                if (!tun.isUp()) break // туннель опущен — сторожить нечего
                val rx = tun.transfer()?.first ?: -1L
                val grew = lastRx >= 0 && rx > lastRx
                lastRx = rx
                val since = GoTunnel.connectedSinceElapsed
                val warmingUp = since != null && SystemClock.elapsedRealtime() - since < WARMUP_MS
                val state = when {
                    !MayakNet.hasNetwork(app) -> GoTunnel.LIVE_NO_NETWORK
                    grew -> GoTunnel.LIVE_OK
                    (tun.handshakeAgeMs() ?: Long.MAX_VALUE) <= HANDSHAKE_FRESH_MS -> GoTunnel.LIVE_OK
                    warmingUp -> GoTunnel.LIVE_UNKNOWN // туннель только встал — ещё не «нет трафика»
                    else -> GoTunnel.LIVE_NO_TRAFFIC
                }
                // Вердикт сторожа записываем КАЖДЫЙ такт, а не только на смене состояния: пинг-цикл
                // главного экрана мог уже поставить в общее состояние «трафика нет» сам, и тогда
                // `state != GoTunnel.liveness` не сработает — а экрану нужно знать, что сторож
                // пришёл к тому же выводу НЕЗАВИСИМО.
                watchdogSaysNoTraffic = state == GoTunnel.LIVE_NO_TRAFFIC
                if (state != GoTunnel.liveness) {
                    Log.i(TAG, "живость: ${GoTunnel.liveness} → $state (rx вырос=$grew)")
                    apply(app, state)
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watchdogSaysNoTraffic = false // сторожа нет — и вердикта его нет; экран считает по-своему
    }

    /**
     * Сказать правду о живости СРАЗУ по системному сигналу о смене сети — не дожидаясь такта.
     *
     * Зачем: такт сторожа — `delay(3с)`, а спящий Android эти задержки замораживает. В живом логе
     * (диаг #37, 16-08) телефон потерял Wi-Fi в 23:52:42, а сторож заметил это в 23:54:11 — полторы
     * минуты человеку уверенно писали «Защищено» при отсутствующей сети. Системный колбэк о смене
     * сети при этом приходит мгновенно и в Doze — мы его получали и не использовали.
     *
     * ⛔ Это ТОЛЬКО СТАТУС. Туннель здесь не трогаем и не переподнимаем: авто-переподъём по смене
     * сети выкатывали дважды и дважды делали хуже (см. Application.onNetworkChange).
     */
    fun onNetworkChanged(context: Context, hasNetwork: Boolean) {
        val app = context.applicationContext
        if (GoTunnel.connectedSinceElapsed == null) return // туннеля нет — сторожить нечего
        if (!hasNetwork) {
            // Пишем в лог ИМЕННО отсюда: в присланных людьми логах видно только строку «живость: X → Y»,
            // и по ней не отличить «сторож дотикал» от «пришёл системный сигнал». А разница — вся суть
            // правки: в Doze сторож не тикает вовсе.
            Log.i(TAG, "сети нет (системный сигнал, не такт сторожа) → ${GoTunnel.LIVE_NO_NETWORK}")
            apply(app, GoTunnel.LIVE_NO_NETWORK)
            return
        }
        // Сеть появилась/сменилась. Утверждать «всё хорошо» права нет: сокет движка остался на
        // прежней сети, и трафик может не пойти вовсе. Честное слово здесь — «проверяем»; настоящий
        // вердикт поставит ближайший такт сторожа по росту rx.
        if (GoTunnel.liveness == GoTunnel.LIVE_NO_NETWORK) apply(app, GoTunnel.LIVE_UNKNOWN)
    }

    /**
     * Записать состояние живости и СРАЗУ показать его человеку (шторка + экран, если он открыт).
     * Единая точка: и сторож, и пинг-цикл главного экрана меняют слово только через неё — иначе
     * половина мест снова забыла бы обновить уведомление, как было до аудита.
     */
    fun apply(context: Context, state: Int) {
        if (GoTunnel.liveness == state) return
        GoTunnel.liveness = state
        runCatching {
            MayakNotification.show(context.applicationContext, GoTunnel.connectedLabel, GoTunnel.connectedPingMs)
        }
        onChange?.invoke(state)
    }
}
