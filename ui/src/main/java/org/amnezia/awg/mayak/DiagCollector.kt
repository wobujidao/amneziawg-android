// Сбор диагностики для отправки на сервер (кнопка «Отправить лог»). Контекст устройства/сети +
// дамп логов движка (logcat) → DiagLogRequest. Цель: инженер по логу видит причину «не работает на
// мобиле» — версия приложения/ОС, модель, Wi-Fi или сотовая, активен ли ДРУГОЙ VPN, и сам лог AWG.
package org.amnezia.awg.mayak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.mayak.core.DiagLogRequest
import java.io.BufferedReader
import java.io.InputStreamReader

object DiagCollector {

    private const val MAX_LOG_BYTES = 256 * 1024 // потолок лога в запросе (сервер тоже режет до 512КиБ)

    /**
     * Собирает диагностику. direction — текущее выбранное направление (может быть пустым),
     * deviceId — id устройства из сессии (0 если неизвестен). Сеть/логи читаем в IO.
     */
    suspend fun collect(context: Context, direction: String, deviceId: Long): DiagLogRequest =
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
                meta = buildMap {
                    put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
                    put("vpn_transport_present", net.vpnActive.toString())
                },
                log = captureLog(),
            )
        }

    private data class Net(val type: String, val vpnActive: Boolean)

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
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
            }
        } catch (_: Exception) { /* без прав/ошибка — отдаём что есть */ }
        val type = when {
            wifi -> "wifi"
            cellular -> "cellular"
            else -> "other"
        }
        return Net(type, vpn)
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
            val proc = ProcessBuilder()
                .command("logcat", "-d", "-b", "all", "-v", "threadtime", "*:V")
                .redirectErrorStream(true)
                .start()
            val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()
            tail(text, MAX_LOG_BYTES)
        } catch (e: Exception) {
            "не удалось собрать logcat: ${e.message}"
        }
    }

    /** Хвост строки не длиннее limit байт (UTF-8), с пометкой об усечении. */
    private fun tail(s: String, limit: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return s
        val cut = String(bytes, bytes.size - limit, limit, Charsets.UTF_8)
        return "…[лог усечён до ${limit / 1024} КиБ]…\n$cut"
    }
}
