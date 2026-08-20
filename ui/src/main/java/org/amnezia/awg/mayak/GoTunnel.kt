// Android-реализация Tunnel (:core) поверх GoBackend форка (движок amneziawg-go, линия 3.0).
// up(confText): парсим наш .conf штатным парсером форка — он знает все поля обфускации AWG:
// Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5 достались от линии 2.0 без изменений, а HeaderProtectionKey
// добавился в 3.0 (парсер форка его умеет, см. config/Interface.java) — и поднимаем туннель.
// Согласие на VPN (GoBackend.VpnService.prepare) запрашивается в Activity ДО up().
package org.amnezia.awg.mayak

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        // Пауза между DOWN и UP: VpnService умирает асинхронно (в логе #67 — через 63 мс), и без
        // паузы его onDestroy убивает уже поднятый нами туннель. Секунда с запасом.
        private const val REBIND_SETTLE_MS = 1_200L

        // Сколько ждём, прежде чем ПРОВЕРИТЬ, что туннель действительно стоит. Проверяем, а не верим:
        // ровно на «поверил» и погорела первая версия.
        private const val REBIND_VERIFY_MS = 1_500L

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

        // id направления ТЕКУЩЕГО подключения; процесс-скоупно (переживает пересоздание Activity И
        // пересортировку/рефетч списка). Надёжный источник «какая страна подключена» для показа ЕЁ пинга
        // из живого замера туннеля — в отличие от Activity-поля connectedDir, которое обнуляется при
        // пересоздании Activity и эвристически восстанавливается (мог указать не на ту страну → активная
        // страна пинговалась через свой же туннель, hairpin, «•••»). Ставится при коннекте; сброс в down().
        @Volatile var connectedDirectionId: Long? = null

        // Выходной IPv4-адрес (проба ipify через туннель). Процесс-скоупно → показ IP переживает
        // пересоздание Activity (на реоупене видим тот же IP без повторной пробы). Сбрасывается в down().
        @Volatile var egressIpv4: String? = null

        // Выходной IPv6-адрес, если IPv6 РЕАЛЬНО работает через туннель (успешная проба api6.ipify.org).
        // null = IPv6 не задействован. Ставит UI после коннекта; процесс-скоупно (значок «IPv6» переживёт
        // пересоздание Activity). Сбрасывается в down(). Честный сигнал (SPEC-0014): по факту egress, не по ::/0.
        @Volatile var egressIpv6: String? = null

        // Подключены ЧЕРЕЗ запасной канал (SPEC-0039): AWG идёт внутри обычного HTTPS к нашему сайту,
        // а не по UDP. Процесс-скоупно → пометка «Резерв» на главном и в уведомлении переживает
        // пересоздание Activity. Сбрасывается вместе с остальным состоянием коннекта в down().
        @Volatile var connectedViaFallback: Boolean = false

        // КАКОЙ ступенью лестницы мы подключены: ROUTE_DIRECT / ROUTE_RELAY / ROUTE_FALLBACK.
        //
        // Раньше различался только запасной канал (connectedViaFallback), а транзит через Россию был
        // неотличим от прямого пути. Владелец 2026-07-28 поймал это на себе: приложение молча увело
        // его на транзит, и узнал он об этом только из разбора диаг-лога. Путь надо показывать —
        // у транзита свои задержки и свои помехи, человек вправе понимать, чем он идёт.
        // Значения — из :core (LadderTelemetry): те же строки уходят в недельный бикон исходом
        // лестницы, и алиас не даёт им разъехаться со строками состояния туннеля молча.
        const val ROUTE_DIRECT = org.amnezia.awg.mayak.core.LadderTelemetry.ROUTE_DIRECT
        const val ROUTE_RELAY = org.amnezia.awg.mayak.core.LadderTelemetry.ROUTE_RELAY
        const val ROUTE_FALLBACK = org.amnezia.awg.mayak.core.LadderTelemetry.ROUTE_FALLBACK

        @Volatile var connectedRoute: String = ROUTE_DIRECT

        // --- ЕДИНСТВЕННЫЙ источник права сказать «Защищено» (аудит 2026-07-31) ---
        //
        // Раньше «Защищено» было синонимом «туннель поднят», и приложение врало в трёх сценариях сразу:
        // подключение провалилось, а уведомление висит; пропала сеть — «Защищено» ещё почти минуту;
        // туннель есть, а трафика нет. Поднятый туннель НЕ доказывает, что трафик идёт, — доказывает
        // только свежее подтверждение (проба выхода, ответ пинга, рост rx, свежее рукопожатие).
        //
        // Поэтому состояние живости держим ЗДЕСЬ, рядом с туннелем, а не в Activity: уведомление
        // обновляется из полудюжины мест (пинг-цикл, скорость, реоупен, автоподключение) — по той же
        // причине, по которой здесь же живёт connectedRoute. Кто бы ни обновлял, слово будет одно.
        // Сами числа и правило, по которому из признаков получается вердикт, живут в :core
        // (LivenessDecision) — там их проверяет тест на JVM. Здесь только доступ по привычному имени.
        const val LIVE_UNKNOWN = org.amnezia.awg.mayak.core.LivenessDecision.LIVE_UNKNOWN
        const val LIVE_OK = org.amnezia.awg.mayak.core.LivenessDecision.LIVE_OK
        const val LIVE_NO_TRAFFIC = org.amnezia.awg.mayak.core.LivenessDecision.LIVE_NO_TRAFFIC
        const val LIVE_NO_NETWORK = org.amnezia.awg.mayak.core.LivenessDecision.LIVE_NO_NETWORK

        @Volatile var liveness: Int = LIVE_UNKNOWN

        // Последний ПРИМЕНЁННЫЙ конфиг туннеля. Нужен, чтобы переподнять туннель после смены сети,
        // не ходя в /connect: сети в этот момент может не быть вовсе, да и новый конфиг не нужен —
        // ключи и пир те же. Секрет: держим только в памяти процесса, на диск не пишем.
        @Volatile var lastConfText: String? = null

        // Идёт НАШ намеренный переподъём после смены сети. Нужен, потому что DOWN внутри переподъёма
        // приходит в onStateChange тем же путём, что и внешний обрыв, и без этого флага handleExternalDown
        // стёр бы всё состояние коннекта, погасил уведомление и убил шим запасного канала — то есть
        // починка ломала бы ровно то, что чинит.
        @Volatile private var rebinding = false

        // Application-контекст (процесс-скоупный) — чтобы убрать уведомление из onStateChange, когда
        // туннель гаснет ВНЕ приложения и Activity под рукой нет. Ставится при создании GoTunnel.
        @Volatile private var appContext: Context? = null

        /** Туннель ушёл в DOWN (в т.ч. внешне): сбросить процесс-скоупное состояние коннекта и убрать
         *  уведомление «Подключено». Идемпотентно с down() — повторный вызов безвреден. */
        fun handleExternalDown() {
            if (rebinding) return // это наш собственный DOWN внутри переподъёма, а не обрыв
            connectedSinceElapsed = null
            connectedLabel = null
            connectedServerHost = null
            connectedPingMs = null
            connectedDirectionId = null
            egressIpv4 = null
            egressIpv6 = null
            // Туннеля нет — держать WSS-соединение к мосту незачем (и светить его тоже незачем).
            connectedViaFallback = false
            connectedRoute = ROUTE_DIRECT
            liveness = LIVE_UNKNOWN
            lastConfText = null
            MayakFallbackTransport.stop()
            MayakLiveness.stop()
            appContext?.let { MayakNotification.clear(it) }
        }


        /**
         * Переподнять туннель ПОСЛЕ СМЕНЫ СЕТИ, тем же конфигом и не спрашивая ядро.
         *
         * ЗАЧЕМ. С 2026-07-06 обработчик смены сети у нас ничего не делал: тогда апстримный вариант рвал
         * туннель на каждом хендовере и не поднимал обратно, и его отключили с формулировкой «WireGuard
         * роумит сам». Диаг-лог владельца #66 (28-07) опроверг это на живом случае «зашёл в лифт»:
         *
         *   15:16:44  сеть ПОТЕРЯНА (onLost)
         *   15:16:45  появилась ДРУГАЯ сеть (onAvailable 176 — новый id, не та же самая)
         *   15:16:59…15:17:40  девять рукопожатий подряд, каждые ~5 с — НИ ОДНОГО ответа
         *   15:17:45  человек вручную переподключился → ответ пришёл за 90 мс
         *
         * То есть сокет остался привязан к УМЕРШЕЙ сети и сам не воскресает: 45 секунд «интернета нет»,
         * пока человек не догадается нажать кнопку. Мягкий хендовер движок переживает штатно — полную
         * потерю с появлением НОВОЙ сети нет.
         *
         * ПОЧЕМУ ЭТО НЕ ПОВТОРЕНИЕ ОШИБКИ 06-07. Тогда DOWN делал TunnelManager, у которого нашего
         * конфига нет вовсе (он приходит из /connect), поэтому UP не мог случиться в принципе. Теперь
         * конфиг лежит рядом с туннелем (lastConfText), и переподъём — это DOWN+UP тем же конфигом,
         * без сети и без ядра. Живёт в companion намеренно: backend и туннель процесс-скоупные, значит
         * работает и в фоне, когда Activity нет.
         *
         * Возвращает true, если реально переподняли.
         */
        suspend fun rebindAfterNetworkChange(sinceEpochMs: Long): Boolean = withContext(Dispatchers.IO) {
            val b = sharedBackend ?: return@withContext false
            val t = sharedTunnel ?: return@withContext false
            val conf = lastConfText ?: return@withContext false
            val up = runCatching { b.getState(t) == Tunnel.State.UP }.getOrDefault(false)
            if (!up) return@withContext false // туннель не наш или его нет — не трогаем

            // Переподнимаем ТОЛЬКО если туннель реально не ожил на новой сети. Смена сети часто
            // безобидна (мягкий хендовер, Wi-Fi→сота с рабочим роумингом), и дёргать в этих случаях
            // DOWN→UP вредно: tun на миг исчезает — лишний разрыв и щель, в которую успевает уйти
            // трафик мимо туннеля.
            //
            // Критерий именно «НЕТ рукопожатия ПОСЛЕ смены сети», а не «рукопожатие старое». Разница
            // принципиальная: в момент смены сети последнее рукопожатие ещё свежее (сеть работала
            // секунду назад), и проверка по возрасту молчала бы ровно тогда, когда нужна. Вызывающий
            // даёт движку фору (см. REBIND_GRACE_MS) и только потом зовёт нас.
            val last = runCatching {
                val st = b.getStatistics(t)
                st.peers().maxOfOrNull { st.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
            }.getOrDefault(0L)
            if (last > sinceEpochMs) {
                android.util.Log.i(CFG_TAG, "смена сети: туннель ожил сам (рукопожатие после смены) — не трогаю")
                return@withContext false
            }
            val since = connectedSinceElapsed // сессию НЕ обнуляем: для человека это то же подключение
            android.util.Log.i(CFG_TAG, "смена сети: переподнимаю туннель тем же конфигом")
            // ⚠️ ГОНКА, на которой первая версия этой починки провалилась (диаг-лог #67, 28-07).
            // GoBackend.setState(DOWN) зовёт vpnService.stopSelf() — это АСИНХРОННО. Если сразу поднять
            // туннель, приходит запоздалый VpnService.onDestroy, видит УЖЕ НОВЫЙ currentTunnel и глушит
            // его: в логе туннель поднялся в 51.786 и был убит в 51.793, семь миллисекунд спустя.
            // Итог был хуже, чем без починки: вместо мёртвого туннеля — отсутствующий.
            // Поэтому ждём, пока служба реально доумрёт, и ПРОВЕРЯЕМ результат, а не верим ему.
            val config = Config.parse(BufferedReader(StringReader(conf)))
            rebinding = true
            try {
                runCatching { b.setState(t, Tunnel.State.DOWN, null) }
                delay(REBIND_SETTLE_MS)
                runCatching { b.setState(t, Tunnel.State.UP, config) }
                delay(REBIND_VERIFY_MS)
                if (runCatching { b.getState(t) != Tunnel.State.UP }.getOrDefault(true)) {
                    android.util.Log.w(CFG_TAG, "переподъём: туннель не удержался — пробую ещё раз")
                    runCatching { b.setState(t, Tunnel.State.UP, config) }
                    delay(REBIND_VERIFY_MS)
                }
            } finally {
                rebinding = false
            }
            val ok = runCatching { b.getState(t) == Tunnel.State.UP }.getOrDefault(false)
            if (!ok) {
                // Не получилось — НЕ делаем вид, что всё хорошо. Пусть приложение честно покажет
                // «отключено»: один тап человека лучше, чем значок «защищено» без интернета.
                android.util.Log.w(CFG_TAG, "переподъём не удался — показываю честное «отключено»")
                handleExternalDown()
                return@withContext false
            }
            connectedSinceElapsed = since ?: SystemClock.elapsedRealtime()
            android.util.Log.i(CFG_TAG, "переподъём удался")
            true
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
        lastConfText = confText // чтобы можно было переподнять туннель без похода в /connect (см. rebindAfterNetworkChange)
        connectedSinceElapsed = SystemClock.elapsedRealtime()
        // Новый туннель = подтверждения ещё нет. Пока проба выхода не сказала «работает», человек
        // видит «Проверяем соединение…», а не «Защищено» (аудит 2026-07-31).
        liveness = LIVE_UNKNOWN
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
        android.util.Log.i(CFG_TAG, "конфиг туннеля: $summary | маска=${maskKind(confText)}")
    }

    /**
     * Какая маска мимикрии пришла в конфиге — по «шапке» I1, без самой строки.
     *
     * Нужно для разбора жалоб: с 2026-07-28 ядро выдаёт маску СЛУЧАЙНО из набора на каждую выдачу, и
     * без этой пометки нельзя сказать, с какой именно человек сидел. А это ровно тот вопрос, который
     * встал сразу же: скорость на одном и том же выходе гуляет от 2,9 до 97 Мбит/с, и надо развести
     * «плохая сота» и «эту маску оператор придушил». Логируем ВИД, а не строку: строка длинная и
     * содержит случайные байты, в логе от неё пользы нет.
     */
    private fun maskKind(confText: String): String {
        val i1 = confText.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("I1 ") || it.startsWith("I1=") }
            ?.substringAfter('=')?.trim() ?: return "нет"
        return when {
            i1.startsWith("<b 0xc30000000108>") -> "quic"
            i1.startsWith("<b 0x000100002112a442>") -> "stun"
            i1.startsWith("<b 0x4f5054494f4e53") -> "sip" // "OPTIONS" в hex
            Regex("^<r \\d+>$").matches(i1) -> "шум"
            else -> "иная(${i1.take(14)})"
        }
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
        connectedDirectionId = null
        egressIpv4 = null
        egressIpv6 = null
        // Запасной канал живёт ровно столько, сколько туннель: гасим шим здесь, в ЕДИНОЙ точке, —
        // так он закрывается при любом способе отключения (кнопка, отмена, смена страны, внешний DOWN).
        connectedViaFallback = false
        connectedRoute = ROUTE_DIRECT
        liveness = LIVE_UNKNOWN
        lastConfText = null
        MayakFallbackTransport.stop()
        MayakLiveness.stop()
        Unit
    }

    fun isUp(): Boolean = runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)

    /** Был ли хоть один успешный хендшейк с сервером — сигнал для сторожа запасного канала (SPEC-0039 T5):
     *  хендшейка нет вовсе = наши UDP-пакеты не доходят, ждать полный набор egress-проб незачем.
     *  Ошибку статистики считаем «хендшейка нет»: сторож в этом случае лишь уйдёт на запасной канал
     *  раньше, а не зависнет. */
    fun hasHandshake(): Boolean = runCatching {
        val st = backend.getStatistics(tunnel)
        st.peers().any { (st.peer(it)?.latestHandshakeEpochMillis() ?: 0L) > 0L }
    }.getOrDefault(false)

    /**
     * Сколько миллисекунд прошло с ПОСЛЕДНЕГО удачного рукопожатия. null — рукопожатий не было вовсе
     * или статистика недоступна.
     *
     * Зачем: это единственное доказательство живости, которое ничего не стоит и работает в фоне.
     * Рукопожатие двустороннее — сервер на него ОТВЕТИЛ, значит путь был жив. При keepalive 10 с
     * клиент шлёт пакеты постоянно, поэтому на живом туннеле движок перевыпускает сессию примерно
     * раз в две минуты и возраст рукопожатия не растёт бесконечно. Перестало обновляться — сервер
     * не отвечает, сколько бы «Защищено» ни было написано на экране.
     */
    fun handshakeAgeMs(): Long? = runCatching {
        val st = backend.getStatistics(tunnel)
        val last = st.peers().maxOfOrNull { st.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
        if (last <= 0L) null else (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }.getOrNull()

    /** Суммарные rx/tx байты туннеля (для отображения скорости передачи). null — статистика недоступна. */
    fun transfer(): Pair<Long, Long>? = runCatching {
        val st = backend.getStatistics(tunnel)
        st.totalRx() to st.totalTx()
    }.getOrNull()
}
