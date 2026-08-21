// MayakLinkProbe — диагностика связи НА ТЕКУЩЕЙ ЛИНИИ, без единого переподключения.
//
// ⚠️ Замечание владельца 21-08, дословно: «у меня подключение работает, но связи нет. И если я
// нажимаю проверить, получается что проверка уже обрывает линию и начинает подключаться вновь. А
// нужно было проверять именно на этой линии, не переподключая её».
//
// Старая проверка (doLinkCheck) проходит лестницу целиком: гасит туннель и поднимает каждую ступень
// заново. Она отвечает на вопрос «работают ли ступени у этого оператора» — и ради этого уничтожает
// состояние «подключено, а трафика нет», то есть ровно то, которое человек и хотел показать.
//
// Здесь — другой инструмент: ничего не гасим, только смотрим. Отсюда и ограничения, они честные:
// про НЕПОДНЯТЫЕ ступени эта проверка не скажет ничего, для них есть второй шаг (прогон лестницы) —
// и он спрашивает разрешения, потому что рвёт линию.
//
// Решение «что означают эти факты» живёт в :core (LinkDiagnosis) и покрыто тестами на JVM. Здесь —
// только измерения.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.LinkFacts
import org.amnezia.awg.mayak.core.Probe
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.math.abs

object MayakLinkProbe {

    private const val TAG = "AmneziaWG/mayak-diag"

    /** Таймаут одной пробы. Короткий намеренно: десять проверок подряд не должны стоить человеку минуту. */
    private const val PROBE_MS = 4_000

    /** Свежесть рукопожатия, при которой считаем, что выход отвечает. Движок перевыпускает сессию
     *  примерно раз в две минуты, поэтому три — с запасом на медленную соту. */
    private const val HANDSHAKE_FRESH_MS = 3 * 60 * 1000L

    /** Допустимое расхождение часов. Меньше пяти минут TLS переживает; больше — уже ломает. */
    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    /** Размер «крупного» ответа. 64 КБ гарантированно разложатся на полноразмерные пакеты — то есть
     *  проверят ровно то, что мелкая проба пройти не может: проходят ли большие пакеты. */
    private const val BIG_BYTES = 65_536

    private const val OUTSIDE_URL = "https://api.ipify.org?format=json"
    private const val BIG_URL = "https://speed.cloudflare.com/__down?bytes=$BIG_BYTES"
    private const val DNS_NAME = "api.ipify.org"

    /**
     * Снять факты о текущем состоянии связи.
     *
     * @param tunnelUp поднят ли НАШ туннель прямо сейчас
     * @param viaFallback идёт ли текущее подключение через мост поверх :443
     * @param apiHosts адреса ядра из [MayakHostList] — первый обычно домен, второй запасной по IP
     */
    suspend fun collect(
        context: Context,
        tunnel: GoTunnel?,
        tunnelUp: Boolean,
        viaFallback: Boolean,
        apiHosts: List<String>,
    ): LinkFacts = withContext(Dispatchers.IO) {
        val снаружи = OutsideTunnel.opener(context)

        // 1. Наш адрес МИМО туннеля — отдельно по имени и отдельно по запасному IP. Это различает
        //    «домен режут» и «до нас не достучаться», а лечится это по-разному.
        val поИмени = apiHosts.firstOrNull { !it.contains(Regex("""\d+\.\d+\.\d+\.\d+""")) }
        val поIP = apiHosts.firstOrNull { it.contains(Regex("""\d+\.\d+\.\d+\.\d+""")) }
        val apiByName = поИмени?.let { проба("наш адрес по имени") { httpOk(адрес(it, "/version.json"), снаружи) } } ?: Probe.UNKNOWN
        val apiByIp = поIP?.let { проба("наш адрес по IP") { httpOk(адрес(it, "/version.json"), снаружи) } } ?: Probe.UNKNOWN

        // 2. Интернет ВООБЩЕ. Спрашиваем чужой адрес мимо туннеля — но если хоть один наш ответил,
        //    интернет заведомо есть, и лишний запрос к постороннему сервису не нужен.
        val internetOutside = if (apiByName == Probe.OK || apiByIp == Probe.OK) {
            Probe.OK
        } else {
            проба("интернет мимо туннеля") { httpOk(OUTSIDE_URL, снаружи) }
        }

        // 3. Отвечает ли ВЫХОД. Отдельной пробы не нужно: свежее рукопожатие и есть доказательство —
        //    оно двустороннее, сервер на него ОТВЕТИЛ. Пинговать адрес ноды бессмысленно: при поднятом
        //    туннеле пинг уйдёт В ТУННЕЛЬ и померит совсем другое.
        val возрастРукопожатия = tunnel?.handshakeAgeMs()
        val exitReachable = when {
            !tunnelUp -> Probe.UNKNOWN // туннель не поднят — про выход сказать нечего, и врать не будем
            возрастРукопожатия == null -> Probe.FAIL
            возрастРукопожатия <= HANDSHAKE_FRESH_MS -> Probe.OK
            else -> Probe.FAIL
        }

        // 4. Идёт ли трафик ЧЕРЕЗ туннель. Обычное соединение (без обхода) — оно и уходит в туннель.
        val throughTunnel = if (!tunnelUp) Probe.UNKNOWN else проба("через туннель") { httpOk(OUTSIDE_URL, null) }

        // 5. Разрешаются ли имена. Отдельно от предыдущей: «интернет есть, сайты не открываются» —
        //    самая частая жалоба, и почти всегда это имена, а не связь.
        val dns = if (!tunnelUp) Probe.UNKNOWN else проба("DNS через туннель") {
            InetAddress.getAllByName(DNS_NAME).isNotEmpty()
        }

        // 6. Проходит ли КРУПНЫЙ пакет. Мелкие проходят почти всегда — именно поэтому «рукопожатие
        //    есть, страницы висят» и выглядит загадкой. Проверяем, только если мелкая проба прошла:
        //    иначе провал большой ничего не добавит к уже известному.
        val bigPacket = if (throughTunnel != Probe.OK) Probe.UNKNOWN else проба("крупный пакет") {
            прочитатьХотяБы(BIG_URL, BIG_BYTES / 2)
        }

        // 7. Живость самого туннеля: растут ли принятые байты. «Интерфейс поднят» и «туннель
        //    работает» — разные вещи, и на этом уже обжигались («Защищено» на мёртвом туннеле).
        val tunnelAlive = if (!tunnelUp || tunnel == null) Probe.UNKNOWN else вырослиЛиБайты(tunnel)

        // 8. Часы телефона. Расхождение ломает защищённое соединение раньше всего остального, а
        //    диагноз без этой проверки не ставится вообще никак.
        val clockOk = поИмени?.let { сверитьЧасы(it, снаружи) } ?: Probe.UNKNOWN

        // 9. Чужой VPN. Пока НАШ туннель поднят, отличить его от чужого нельзя — система показывает
        //    один и тот же признак. Поэтому судим только когда наш опущен: транспорт VPN есть, а он
        //    не наш — значит чужой.
        val otherVpn = when {
            tunnelUp -> Probe.UNKNOWN
            MayakNet.vpnActive(context) -> Probe.FAIL
            else -> Probe.OK
        }

        // 10. Проходит ли UDP. Тоже без отдельной пробы: если мы сейчас живём через мост поверх :443,
        //     UDP у этого человека задавлен — это и есть ответ. Прямая ступень с рукопожатием —
        //     обратное доказательство.
        val udpPasses = when {
            !tunnelUp -> Probe.UNKNOWN
            viaFallback -> Probe.FAIL
            exitReachable == Probe.OK -> Probe.OK
            else -> Probe.UNKNOWN
        }

        LinkFacts(
            internetOutside = internetOutside,
            exitReachable = exitReachable,
            throughTunnel = throughTunnel,
            dnsThroughTunnel = dns,
            bigPacket = bigPacket,
            tunnelAlive = tunnelAlive,
            clockOk = clockOk,
            otherVpn = otherVpn,
            udpPasses = udpPasses,
            apiByName = apiByName,
            apiByIp = apiByIp,
            batteryRestricted = фонОграничен(context),
        )
    }

    /**
     * Ограничен ли нашему приложению фон. Это не проба сети, а факт системы — и одна из главных
     * причин «отваливается, когда экран гаснет», о которой человек не догадывается сам.
     * Считаем ограниченным, если приложение НЕ исключено из оптимизации батареи ИЛИ включён режим
     * энергосбережения.
     */
    private fun фонОграничен(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm == null) false else !pm.isIgnoringBatteryOptimizations(context.packageName) || pm.isPowerSaveMode
    } catch (_: Exception) {
        false // не смогли узнать — не выдумываем ограничение, которого можем не быть
    }

    /** Обёртка: любая ошибка пробы — это FAIL, а не падение диагностики. Заодно строка в диаг-лог. */
    private inline fun проба(имя: String, body: () -> Boolean): Probe = try {
        val ok = body()
        Log.i(TAG, "проба «$имя»: ${if (ok) "ок" else "не прошла"}")
        if (ok) Probe.OK else Probe.FAIL
    } catch (e: Exception) {
        Log.i(TAG, "проба «$имя»: не прошла — ${e.javaClass.simpleName}: ${e.message}")
        Probe.FAIL
    }

    /** GET с кодом 200. `opener` = null → обычное соединение (уйдёт в туннель). */
    /** Собрать адрес из хоста и пути: у хостов из MayakHostList схема уже есть, у «голых» — нет. */
    private fun адрес(host: String, path: String = ""): String =
        (if (host.startsWith("http")) host else "https://$host").trimEnd('/') + path

    private fun httpOk(url: String, opener: ((URL) -> HttpURLConnection)?): Boolean {
        val u = URL(адрес(url))
        val conn = opener?.invoke(u) ?: (u.openConnection() as HttpURLConnection)
        return try {
            conn.connectTimeout = PROBE_MS
            conn.readTimeout = PROBE_MS
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    /** Прочитать из ответа хотя бы [minBytes] — так проверяется, что через туннель проходит крупное. */
    private fun прочитатьХотяБы(url: String, minBytes: Int): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = PROBE_MS
            conn.readTimeout = PROBE_MS
            if (conn.responseCode !in 200..299) return false
            var прочитано = 0
            val buf = ByteArray(8192)
            conn.inputStream.use { s ->
                while (прочитано < minBytes) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    прочитано += n
                }
            }
            прочитано >= minBytes
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Растут ли принятые байты. Смотрим ДО и ПОСЛЕ короткой паузы, а между ними дёргаем сеть —
     * иначе на молчащем туннеле счётчик не сдвинется и живой канал выглядел бы мёртвым.
     */
    private suspend fun вырослиЛиБайты(tunnel: GoTunnel): Probe {
        val было = tunnel.transfer()?.first ?: return Probe.UNKNOWN
        runCatching { httpOk(OUTSIDE_URL, null) } // трафик, который обязан отразиться в счётчике
        delay(700)
        val стало = tunnel.transfer()?.first ?: return Probe.UNKNOWN
        val вырос = стало > было
        Log.i(TAG, "живость туннеля: принято $было → $стало (${if (вырос) "растёт" else "не растёт"})")
        return if (вырос) Probe.OK else Probe.FAIL
    }

    /**
     * Сверить часы телефона с нашим сервером по заголовку Date. Идём МИМО туннеля: расхождение часов
     * ломает и сам туннель, поэтому мерить его через туннель — значит зависеть от того, что проверяешь.
     */
    private fun сверитьЧасы(host: String, opener: (URL) -> HttpURLConnection): Probe = try {
        // ⚠️ В host из MayakHostList СХЕМА УЖЕ ЕСТЬ («https://api…»). Первая версия клеила «https://»
        // ещё раз, URL получался битым, проба падала — и вердикт честно говорил «не проверяли»
        // вместо часов. Поймано первым же живым отчётом (диаг #70, 21-08): в следе не было ни
        // «часы ✓», ни «часы ✗». Тот же приём защиты, что в httpOk ниже.
        val conn = opener(URL(адрес(host, "/version.json")))
        try {
            conn.connectTimeout = PROBE_MS
            conn.readTimeout = PROBE_MS
            conn.responseCode
            val серверное = conn.date // 0 — заголовка нет
            if (серверное <= 0L) {
                Probe.UNKNOWN
            } else {
                val разница = abs(System.currentTimeMillis() - серверное)
                Log.i(TAG, "часы: расхождение с сервером ${разница / 1000} с")
                if (разница <= CLOCK_SKEW_MS) Probe.OK else Probe.FAIL
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.i(TAG, "часы: сверить не удалось — ${e.javaClass.simpleName}")
        Probe.UNKNOWN
    }
}
