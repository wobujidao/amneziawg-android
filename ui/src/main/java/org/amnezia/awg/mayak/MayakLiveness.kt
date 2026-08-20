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
//   3. рукопожатие свежее (LivenessDecision.HANDSHAKE_FRESH_MS) И не из ПРОШЛОЙ сети → сервер
//      отвечал недавно;
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
import org.amnezia.awg.mayak.core.LivenessDecision

object MayakLiveness {
    private const val TAG = "Mayak/Liveness"
    private const val TICK_MS = 3_000L

    // Фора только что поднятому туннелю. Первое рукопожатие приходит за секунды, а пир на выходе
    // заводится до ~15 с (sync-таймер ноды), поэтому сразу после подъёма «трафика нет» — не диагноз,
    // а нетерпение: на живом эмуляторе 2026-08-01 сторож успел мигнуть «трафик не идёт» за 3 с до
    // первого же пакета. В эту фору честный статус — «Проверяем соединение…».
    private const val WARMUP_MS = 20_000L

    // Пауза перед проверкой после смены сети — дать стеку встать на новую сеть.
    private const val NETCHANGE_SETTLE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var proofJob: Job? = null

    // ===== Режим «сеть только что сменилась» =====
    //
    // Рукопожатие, случившееся на СТАРОЙ сети, ничего не говорит о новой: телефон уехал с Wi-Fi на
    // мобильную, сокет движка остался на прежней — а правило №3 (рукопожатие свежее 150 с) уверенно
    // пишет «Защищено» ещё две с половиной минуты. Поэтому с момента смены сети рукопожатие
    // засчитывается, ТОЛЬКО если оно моложе самой смены; пока такого нет — судим по росту rx и по
    // ответу сервера (проверка ниже).
    //
    // Режим закрывается сам, как только жизнь доказана (`netChangeAt = null`), — по времени он не
    // истекает НАМЕРЕННО: «трафика нет» после мёртвой смены сети должно держаться, пока трафик не
    // пойдёт. Первая версия правки гасила режим по таймеру, и вердикт «трафика нет» жил полсекунды:
    // следующий такт снова верил старому рукопожатию и возвращал «Защищено» (поймано на эмуляторе
    // 21-08 живым туннелем с удалённым на ноде пиром).
    @Volatile private var netChangeAt: Long? = null
    /** Проверка после смены сети ещё идёт — вердикта нет, честное слово «Проверяем соединение…». */
    @Volatile private var proofPending = false
    /** Номер текущей проверки: отменённая не должна снимать флаг, который поставила следующая
     *  (`cancel()` асинхронен, её `finally` выполняется уже ПОСЛЕ старта новой). */
    @Volatile private var proofGeneration = 0

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
        netChangeAt = null // новый туннель — прежние подозрения к делу не относятся
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
                val now = SystemClock.elapsedRealtime()
                val warmingUp = since != null && now - since < WARMUP_MS
                val state = LivenessDecision.verdict(
                    hasNetwork = MayakNet.hasNetwork(app),
                    rxGrew = grew,
                    handshakeAgeMs = tun.handshakeAgeMs() ?: Long.MAX_VALUE,
                    msSinceNetworkChange = netChangeAt?.let { now - it },
                    proofPending = proofPending,
                    warmingUp = warmingUp,
                )
                // Жизнь доказана — режим «сеть только что сменилась» закрыт.
                if (state == GoTunnel.LIVE_OK) netChangeAt = null
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
        proofJob?.cancel()
        proofJob = null
        proofPending = false
        netChangeAt = null
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
        // прежней сети, и трафик может не пойти вовсе. Честное слово здесь — «проверяем»; вердикт
        // ставит проверка ниже.
        if (GoTunnel.liveness == GoTunnel.LIVE_NO_NETWORK) apply(app, GoTunnel.LIVE_UNKNOWN)
        verifyAfterNetworkChange(app)
    }

    /**
     * Проверить ПОСЛЕ смены сети, что туннель на новой сети действительно жив, — не дожидаясь, пока
     * состарится рукопожатие.
     *
     * Зачем. Правило «рукопожатие свежее 150 с → Защищено» написано для покоя: на живом туннеле
     * движок перевыпускает сессию раз в ~120 с, и возраст рукопожатия — хорошее доказательство. Но
     * ровно в момент смены сети оно превращается в ложь: рукопожатие состоялось на СТАРОЙ сети,
     * сокет движка остался там же, а человеку продолжают писать «Защищено». В присланных логах это
     * самый частый сюжет жалобы «подключено, а ничего не открывается».
     *
     * Как. Пара секунд на то, чтобы стек встал на новую сеть; потом смотрим рост rx (бесплатно), и
     * только если его нет — спрашиваем сервер ICMP-эхом ЧЕРЕЗ туннель (тем же способом, что
     * пинг-цикл открытого экрана: подпроцесс `ping` мимо туннеля увести нельзя, поэтому ответ на
     * него и есть доказательство, что туннель проводит трафик). Ответил — «Защищено», не ответил —
     * «трафика нет», и человек видит это через ~15 с, а не через две с половиной минуты.
     *
     * ⛔ Туннель здесь по-прежнему НЕ трогаем: авто-переподъём по смене сети выкатывали дважды и
     * дважды делали хуже (Application.onNetworkChange). Это измерение, а не лечение.
     */
    private fun verifyAfterNetworkChange(app: Context) {
        val host = GoTunnel.connectedServerHost
        proofJob?.cancel()
        val generation = ++proofGeneration
        netChangeAt = SystemClock.elapsedRealtime()
        proofPending = true
        proofJob = scope.launch {
            try {
                val tun = GoTunnel(app)
                if (!tun.isUp()) return@launch
                val rxBefore = tun.transfer()?.first ?: -1L
                delay(NETCHANGE_SETTLE_MS)
                if (!tun.isUp()) return@launch
                val rxAfter = tun.transfer()?.first ?: -1L
                if (rxBefore >= 0 && rxAfter > rxBefore) {
                    Log.i(TAG, "после смены сети: rx вырос ($rxBefore → $rxAfter) → живой")
                    netChangeAt = null
                    apply(app, GoTunnel.LIVE_OK)
                    return@launch
                }
                if (host == null) return@launch // некого спрашивать — оставляем «проверяем»
                val ms = MayakPing.ping(host)
                if (!tun.isUp()) return@launch  // пока пинговали, туннель опустили
                if (ms != null) {
                    Log.i(TAG, "после смены сети: сервер ответил за ${ms}мс → живой")
                    netChangeAt = null
                    apply(app, GoTunnel.LIVE_OK)
                } else {
                    Log.i(TAG, "после смены сети: сервер не ответил, rx не вырос → трафика нет")
                    // Это независимый вердикт сторожа — пинг-цикл открытого экрана снижает по нему
                    // свой порог промахов (LivenessDecision.missesBeforeSelfHeal).
                    watchdogSaysNoTraffic = true
                    apply(app, GoTunnel.LIVE_NO_TRAFFIC)
                }
            } finally {
                // Флаг снимает только ТА проверка, которая его поставила.
                if (generation == proofGeneration) proofPending = false
            }
        }
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
