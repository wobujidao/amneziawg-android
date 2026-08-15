// Тихий замер близости выходов — Android-обвязка над :core.LatencyProbe (там же — почему замер
// устроен именно так; коротко: TCP-время до легенды узла host:443, ТОЛЬКО когда не активен НИ ОДИН
// VPN — ни наш, ни чужой, признак у системы; цифр на экране НЕТ, замер кормит только порядок «Авто»).
//
// Здесь живёт то, что не переносимо на JVM:
//  • признаки сети (MayakNet.vpnActive / hasNetwork) — у системы, перед КАЖДОЙ попыткой;
//  • хранение: SharedPreferences `mayak_latency`, у каждого направления RTT + отметка времени
//    (стеночные часы). Замер живёт сутки (LatencyProbe.TTL_MS), переживает перезапуск процесса и
//    перемеряется на холодном старте, когда протух. Никаких будильников — только по факту показа
//    списка (measureIfNeeded идемпотентен: всё свежо → выходит сразу);
//  • DoH-резолв pool_host ДО замера (мимо DNS оператора и вне секундомера) — как делал снятый
//    ICMP-замер и как делает endpoint туннеля;
//  • провал замера («узел не ответил») помним ТОЛЬКО в памяти процесса, на диск не пишем: сеть
//    могла мигнуть, холодный старт попробует снова. Прерванный VPN'ом замер не помним вовсе —
//    следующая возможность без VPN перемерит.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.DohResolver
import org.amnezia.awg.mayak.core.LatencyProbe

object MayakLatency {
    private const val TAG = "MayakLatency"
    private const val PREFS = "mayak_latency"

    /** Направления, чей узел в ЭТОМ процессе уже не ответил, — не долбим их на каждой перерисовке. */
    private val failedThisProcess: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Серия уже идёт — вторую параллельно не заводим (перерисовка списка зовёт нас часто). */
    @Volatile private var running = false

    /** Свежий (в пределах суток) замер RTT направления, мс; нет/протух → null. */
    fun freshRtt(context: Context, directionId: Long): Int? {
        val p = prefs(context)
        if (!LatencyProbe.isFresh(p.getLong(keyAt(directionId), 0L), System.currentTimeMillis())) return null
        val v = p.getInt(keyRtt(directionId), -1)
        return if (v >= 0) v else null
    }

    /**
     * Замерить направления, у которых нет свежего замера, — если это сейчас ВООБЩЕ уместно:
     * ни одного активного VPN (признак системы, не наше знание о своём туннеле) и есть живая
     * не-VPN-сеть. Неуместно → молча выходим, никакой очереди «померить позже» нет: следующий
     * показ списка спросит заново. [onUpdated] зовётся на главном потоке, только когда появились
     * НОВЫЕ результаты — перерисовать «Авто» по свежим замерам.
     */
    fun measureIfNeeded(context: Context, dirs: List<Direction>, scope: CoroutineScope, onUpdated: () -> Unit) {
        val app = context.applicationContext
        if (running) return
        val need = dirs.filter {
            it.poolHost.isNotBlank() && it.id !in failedThisProcess && freshRtt(app, it.id) == null
        }
        val vpn = MayakNet.vpnActive(app)
        if (!LatencyProbe.shouldMeasure(vpn, MayakNet.hasNetwork(app), need.size)) {
            // Лог только когда мерить БЫЛО что: «всё свежо» — норма на каждой перерисовке, не шумим.
            if (need.isNotEmpty()) {
                Log.i(TAG, if (vpn) "замер отложен: активен VPN (наш или чужой)" else "замер отложен: нет сети")
            }
            return
        }
        running = true
        scope.launch(Dispatchers.IO) {
            var updated = false
            try {
                Log.i(TAG, "VPN не активен — меряем TCP:${LatencyProbe.PORT}, направлений: ${need.size}")
                val results = need.map { d ->
                    async {
                        // DoH заранее и вне секундомера: DNS оператора мимо, время резолва в RTT не въезжает.
                        val ip = DohResolver.resolveHost(d.poolHost)
                        d to LatencyProbe.measure(ip, vpnActive = { MayakNet.vpnActive(app) })
                    }
                }.awaitAll()
                val e = prefs(app).edit()
                val now = System.currentTimeMillis()
                for ((d, r) in results) {
                    val rtt = r.rttMs
                    when {
                        rtt != null -> {
                            e.putInt(keyRtt(d.id), rtt).putLong(keyAt(d.id), now)
                            updated = true
                            Log.i(TAG, "${d.code}: rtt=$rtt мс (${d.poolHost}:${LatencyProbe.PORT}, медиана ${LatencyProbe.ATTEMPTS} попыток)")
                        }
                        r.aborted -> Log.i(TAG, "${d.code}: замер прерван — VPN поднялся посреди серии")
                        else -> {
                            failedThisProcess.add(d.id)
                            Log.i(TAG, "${d.code}: узел не ответил (${d.poolHost}:${LatencyProbe.PORT})")
                        }
                    }
                }
                if (updated) e.apply()
            } finally {
                running = false
            }
            if (updated) withContext(Dispatchers.Main) { onUpdated() }
        }
    }

    private fun keyRtt(id: Long) = "rtt_$id"
    private fun keyAt(id: Long) = "at_$id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
