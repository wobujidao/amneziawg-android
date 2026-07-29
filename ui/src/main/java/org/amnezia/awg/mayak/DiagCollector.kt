// Сбор диагностики для отправки на сервер (кнопка «Отправить лог»). Контекст устройства/сети +
// дамп логов движка (logcat) → DiagLogRequest. Цель: инженер по логу видит причину «не работает на
// мобиле» — версия приложения/ОС, модель, Wi-Fi или сотовая, активен ли ДРУГОЙ VPN, и сам лог AWG.
package org.amnezia.awg.mayak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.DiagLogRequest
import java.io.BufferedReader
import java.io.InputStreamReader

object DiagCollector {

    private const val MAX_LOG_BYTES = 256 * 1024 // потолок лога в запросе (сервер тоже режет до 512КиБ)

    /**
     * Собирает диагностику. direction — текущее выбранное направление (может быть пустым),
     * deviceId — id устройства из сессии (0 если неизвестен). source — "manual" (кнопка юзера) или
     * "auto" (авто-заливка при ошибке подключения, 0.3.48). Сеть/логи читаем в IO.
     */
    suspend fun collect(
        context: Context,
        direction: String,
        deviceId: Long,
        source: String = "manual",
        // ПОЧЕМУ пришла авто-заливка. Сервер различает только manual/auto (строгая валидация), поэтому
        // повод кладём в meta: connect-error | no-traffic | ladder-<ступень>. Без этого все авто-логи
        // выглядят одинаково и непонятно, что у человека случилось.
        reason: String = "",
        // Живой туннель — только ради счётчиков трафика (статистика движка доступна на экземпляре).
        // null (например, из настроек) → полей rx/tx просто не будет.
        tunnel: GoTunnel? = null,
    ): DiagLogRequest =
        withContext(Dispatchers.IO) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = networkInfo(cm)
            DiagLogRequest(
                appVersion = appVersion(context),
                os = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                networkType = net.type,
                otherVpn = net.vpnActive,
                direction = direction,
                deviceId = deviceId,
                source = source,
                meta = buildMap {
                    put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
                    put("vpn_transport_present", net.vpnActive.toString())
                    // Источник установки (для статистики Play vs сайт): play | sideload | unknown | <installer>.
                    // Агрегируется на бэкенде через meta->>'install_source' (колонка jsonb, миграция не нужна).
                    put("install_source", installSource(context))
                    if (reason.isNotBlank()) put("auto_reason", reason)
                    // Выходные IP нашего подключения (SPEC-0014) — чтобы инженер по логу видел, под каким
                    // IPv4/IPv6 экзита сидел клиент (диагностика «не работает направление / блок IP»).
                    // Процесс-скоупно в GoTunnel: заполнены, если в момент сбора туннель поднят нами.
                    GoTunnel.egressIpv4?.let { put("egress_ipv4", it) }
                    GoTunnel.egressIpv6?.let { put("egress_ipv6", it) }
                    // Кто у человека оператор и в каком он состоянии. До 0.3.81 в логе было только
                    // «wifi/cellular», и оператора мы угадывали по IP — а вся наша проблематика
                    // («у кого что режут») именно про операторов. Всё ниже читается БЕЗ разрешений.
                    putAll(telephony(context))
                    if (net.downKbps > 0) put("link_down_kbps", net.downKbps.toString())
                    if (net.upKbps > 0) put("link_up_kbps", net.upKbps.toString())
                    putAll(tunnelState(tunnel))
                    putAll(deviceState(context))
                },
                log = captureLog(),
            )
        }

    /** Источник установки приложения (для статистики): «play» (Google Play), «sideload» (прямой APK
     *  с сайта / пакет-инсталлер), «unknown» (adb/не определено) или имя installer-пакета (др. стор). */
    private fun installSource(context: Context): String = try {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        when (installer) {
            null -> "sideload"
            "com.android.vending" -> "play"
            "com.google.android.packageinstaller", "com.android.packageinstaller" -> "sideload"
            else -> installer
        }
    } catch (e: Exception) { "unknown" }

    private data class Net(val type: String, val vpnActive: Boolean, val downKbps: Int = 0, val upKbps: Int = 0)

    /**
     * Тип ФИЗИЧЕСКОЙ сети (wifi/cellular/other) + есть ли активный VPN-транспорт. Физическую сеть
     * ищем перебором всех сетей (а не activeNetwork) — чтобы под поднятым VPN всё равно увидеть,
     * Wi-Fi это или сотовая. vpnActive=true → в момент сбора активен какой-то VPN (возможно чужой).
     */
    private fun networkInfo(cm: ConnectivityManager?): Net {
        if (cm == null) return Net("other", false)
        var wifi = false
        var cellular = false
        var vpn = false
        var down = 0
        var up = 0
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
                // Оценку канала берём с ФИЗИЧЕСКОЙ сети: у VPN-транспорта она про туннель, а нам нужно
                // понимать, что под ним. Это оценка системы, не замер, но порядок величины различает
                // EDGE от LTE — а без разрешения «состояние телефона» тип радиосети нам не отдают.
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    down = maxOf(down, caps.linkDownstreamBandwidthKbps)
                    up = maxOf(up, caps.linkUpstreamBandwidthKbps)
                }
            }
        } catch (_: Exception) { /* без прав/ошибка — отдаём что есть */ }
        val type = when {
            wifi -> "wifi"
            cellular -> "cellular"
            else -> "other"
        }
        return Net(type, vpn, down, up)
    }

    /**
     * Оператор и состояние сотовой сети — то, что Android отдаёт БЕЗ разрешений.
     *
     * Что берём: имя оператора сети и его код (MCC+MNC) — по ним оператор определяется точно, а не
     * гаданием по IP; отдельно код оператора SIM (в роуминге отличается от сетевого); признак
     * роуминга. Тип радиосети (LTE/HSPA/EDGE/NR) и уровень сигнала Android отдаёт только по
     * разрешению «состояние телефона» — его мы не просим, поэтому здесь их нет; косвенно о качестве
     * канала говорит оценка системы link_down_kbps.
     */
    private fun telephony(context: Context): Map<String, String> = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null || tm.simState == TelephonyManager.SIM_STATE_ABSENT) emptyMap()
        else buildMap {
            tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { put("carrier", it) }
            tm.networkOperator?.takeIf { it.length >= 5 }?.let { put("carrier_mccmnc", it) }
            tm.simOperator?.takeIf { it.length >= 5 && it != tm.networkOperator }?.let { put("sim_mccmnc", it) }
            if (tm.isNetworkRoaming) put("roaming", "true")
            // Тип радиосети и уровень сигнала — по разрешению «состояние телефона» (решение владельца
            // 2026-07-29: «делаем, информация нужна»). Именно они отличают «оператор режет» от
            // «человек в EDGE с одной палкой»: без них обе картины выглядят как «всё плохо работает».
            // Разрешения может не быть (пользователь отказал) — тогда просто молчим.
            runCatching { radioName(tm.dataNetworkType) }.getOrNull()?.let { put("radio", it) }
            runCatching { tm.signalStrength?.level }.getOrNull()?.let { put("signal_level", it.toString()) } // 0..4
        }
    } catch (_: Exception) { emptyMap() }

    /**
     * Состояние НАШЕГО подключения на момент сбора. Всё это есть в тексте лога, но текст надо читать
     * глазами, а по структурированным полям видно сразу и можно считать статистику по всем жалобам:
     * какой ступенью человек идёт, к какому адресу, с какой маской, какой пинг и сколько прошло
     * трафика. Без этого «плохо работает» приходится расшифровывать вручную (жалоба 2026-07-29).
     */
    private fun tunnelState(tunnel: GoTunnel?): Map<String, String> = buildMap {
        put("route", GoTunnel.connectedRoute)
        GoTunnel.connectedServerHost?.let { put("server_host", it) }
        GoTunnel.connectedPingMs?.let { put("ping_ms", it.toString()) }
        GoTunnel.connectedSinceElapsed?.let {
            put("session_s", ((android.os.SystemClock.elapsedRealtime() - it) / 1000).toString())
        }
        tunnel?.transfer()?.let { (rx, tx) -> put("rx_bytes", rx.toString()); put("tx_bytes", tx.toString()) }
        // Из применённого конфига берём то, что влияет на проходимость: адрес входа, маску и MTU.
        GoTunnel.lastConfText?.lineSequence()?.forEach { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            when (line.substring(0, eq).trim()) {
                "Endpoint" -> put("endpoint", line.substring(eq + 1).trim())
                "MTU" -> put("mtu", line.substring(eq + 1).trim())
                "I1" -> put("mask_i1", line.substring(eq + 1).trim().take(64))
            }
        }
    }

    /**
     * Состояние устройства, которое объясняет «само отключилось» и «в фоне не работает».
     *
     * Батарейная оптимизация и режим энергосбережения — прямая причина, по которой Android убивает
     * наш процесс в фоне (мы на этом обожглись в 0.3.76/0.3.77: система убивала VpnService в паузе
     * между DOWN и UP). Часовой пояс и время устройства нужны, чтобы поймать сбитые часы: при них
     * рушится TLS, и человек видит «ничего не открывается» без единой ошибки в туннеле.
     */
    private fun deviceState(context: Context): Map<String, String> = buildMap {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                put("battery_unrestricted", pm.isIgnoringBatteryOptimizations(context.packageName).toString())
                put("power_save", pm.isPowerSaveMode.toString())
                put("device_idle", pm.isDeviceIdleMode.toString())
            }
        } catch (_: Exception) { /* необязательное */ }
        put("tz", java.util.TimeZone.getDefault().id)
        put("device_time", java.time.Instant.now().toString()) // сервер сверит со своим — поймаем сбитые часы
        runCatching {
            put("preset_on", MayakPrefs.presetEnabled(context).toString())
            MayakPresets.activePreset(context)?.let { put("preset", it.name + "/" + it.mode) }
        }
    }

    /** Человекочитаемое имя типа радиосети (значения TelephonyManager.NETWORK_TYPE_*). */
    private fun radioName(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "H"
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "?"
        else -> "type$t"
    }

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    } catch (_: Exception) {
        "?"
    }

    /**
     * Одноразовый дамп логов (`logcat -d`), как делает встроенный лог-вьюер, но в строку и с потолком
     * размера (берём ХВОСТ — самые свежие строки, где причина сбоя подключения).
     */
    private fun captureLog(): String {
        return try {
            // M3 (аудит): НЕ '-b all' (тащило радио/события/чужие приложения = PII). Берём дефолтные буферы,
            // оставляем ТОЛЬКО строки нашего приложения (AmneziaWG) и маскируем возможные секреты.
            val proc = ProcessBuilder()
                .command("logcat", "-d", "-v", "threadtime", "*:V")
                .redirectErrorStream(true)
                .start()
            val raw = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()
            val ours = raw.lineSequence().filter { it.contains("AmneziaWG") }.joinToString("\n")
            tail(scrubSecrets(ours), MAX_LOG_BYTES)
        } catch (e: Exception) {
            "не удалось собрать logcat: ${e.message}"
        }
    }

    // Регэкспы возможных секретов в логе (приватные ключи, токены). Движок ключ не логирует (проверено),
    // но маскируем на всякий случай — defense-in-depth перед отправкой лога на сервер.
    private val SECRET_PATTERNS = listOf(
        Regex("(?i)(private[_ ]?key\\s*[=:]\\s*)\\S+"),
        Regex("(?i)(authorization:\\s*bearer\\s+)\\S+"),
        Regex("(?i)(\\b(?:token|secret|password|pass)\\s*[=:]\\s*)\\S+"),
    )

    private fun scrubSecrets(s: String): String {
        var t = s
        for (re in SECRET_PATTERNS) t = re.replace(t) { it.groupValues[1] + "<redacted>" }
        return t
    }

    /** Хвост строки не длиннее limit байт (UTF-8), с пометкой об усечении. */
    private fun tail(s: String, limit: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return s
        val cut = String(bytes, bytes.size - limit, limit, Charsets.UTF_8)
        return "…[лог усечён до ${limit / 1024} КиБ]…\n$cut"
    }
}
