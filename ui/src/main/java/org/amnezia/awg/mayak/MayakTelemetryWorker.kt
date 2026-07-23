// Тихий еженедельный телеметри-бикон «Маяк». Раз в 7 дней (WorkManager) шлём на ядро набор НЕ-ПДн:
// версия приложения/сборки, модель устройства, версия ОС, локаль, источник установки + агрегированные
// счётчики использования (число подключений / активных дней). БЕЗ UI, уведомлений и любого видимого
// эффекта. Если пользователь не вошёл (нет токена) — тихо ничего не шлём. Любой сбой глотаем (Result
// .success), чтобы бикон не устраивал retry-шторм: не доехал — доедет через штатный 7-дневный интервал.
// Аутентификация и фейловер доменов — те же, что у /connect (MayakBackend + HostProvider, как в
// LeaseKeepalive). user_id и ip ядро проставляет само по Bearer-токену — их НЕ шлём.
package org.amnezia.awg.mayak

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.TelemetryRequest

class MayakTelemetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching {
            val app = applicationContext
            val store = KeystoreSecureStore(app)
            val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(app, store))
            // Не вошёл → тихо ничего не шлём (не крашимся, не ретраим).
            if (session.hasToken()) {
                session.sendTelemetry(MayakBackend(HostProvider(hostsFor(store))), buildBeacon(app))
            }
        }
        // Любой сбой (нет сети / ядро недоступно / 4xx-5xx) глотаем: бикон не критичен и не должен
        // устраивать retry-шторм. Всегда success → следующая попытка через штатный 7-дневный интервал.
        return Result.success()
    }

    // Те же адреса ядра, что в MayakActivity.hostProvider()/LeaseKeepalive: сохранённый сервер (если задан
    // рег-ссылкой/QR) первым, затем публичный домен + IP-фолбэк. :core делает фейловер по сетевым ошибкам.
    private fun hostsFor(store: KeystoreSecureStore): List<String> {
        val saved = store.get(MayakActivity.KEY_SERVER)?.trimEnd('/')
        return if (saved != null && saved !in MayakActivity.DEFAULT_HOSTS)
            listOf(saved) + MayakActivity.DEFAULT_HOSTS
        else MayakActivity.DEFAULT_HOSTS
    }

    private fun buildBeacon(context: Context): TelemetryRequest = TelemetryRequest(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        deviceModel = "${Build.MANUFACTURER ?: ""} ${Build.MODEL ?: ""}".trim(),
        osVersion = "android ${Build.VERSION.RELEASE ?: ""}".trim(),
        locale = localeTag(),
        installSource = installSource(context),
        connectCount = MayakPrefs.connectCount(context),
        activeDays = MayakPrefs.activeDays(context),
    )

    // BCP-47 тег текущей локали, напр. "ru-RU". "und"/пусто → "" (не шлём мусор).
    private fun localeTag(): String =
        runCatching { Locale.getDefault().toLanguageTag() }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "und" } ?: ""

    // Пакет-установщик (магазин), если доступен: Play → "Play", иначе имя пакета установщика, иначе ""
    // (сайдлоад/неизвестно). Best-effort, ошибки → "".
    private fun installSource(context: Context): String = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
        when (installer) {
            null, "" -> ""
            "com.android.vending", "com.google.android.feedback" -> "Play"
            else -> installer
        }
    }.getOrDefault("")

    companion object {
        private const val UNIQUE_WORK = "mayak-telemetry-weekly"

        /** Поставить в очередь еженедельный бикон (идемпотентно: KEEP не пересоздаёт уже стоящую работу,
         *  поэтому безопасно звать на каждом старте приложения). Интервал 7 дней, только при наличии сети. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<MayakTelemetryWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
