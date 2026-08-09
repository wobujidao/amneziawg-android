/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.os.SystemClock
import org.amnezia.awg.mayak.GoTunnel
import org.amnezia.awg.mayak.MayakPrefs
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.android.material.color.DynamicColors
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.AwgQuickBackend
import org.amnezia.awg.configStore.FileConfigStore
import org.amnezia.awg.model.TunnelManager
import org.amnezia.awg.util.NetworkState
import org.amnezia.awg.util.NetworkType
import org.amnezia.awg.util.RootShell
import org.amnezia.awg.util.ToolsInstaller
import org.amnezia.awg.util.UserKnobs
import org.amnezia.awg.util.applicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

class Application : android.app.Application() {
    private val futureBackend = CompletableDeferred<Backend>()
    private val coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private lateinit var rootShell: RootShell
    private lateinit var preferencesDataStore: DataStore<Preferences>
    private lateinit var toolsInstaller: ToolsInstaller
    private lateinit var tunnelManager: TunnelManager
    private lateinit var networkState: NetworkState

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
        if (BuildConfig.MIN_SDK_VERSION > Build.VERSION.SDK_INT) {
            @Suppress("UnsafeImplicitIntentLaunch")
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            System.exit(0)
        }
    }

    private suspend fun determineBackend(): Backend {
        var backend: Backend? = null
        if (UserKnobs.enableKernelModule.first() && AwgQuickBackend.hasKernelSupport()) {
            try {
                rootShell.start()
                val awgQuickBackend = AwgQuickBackend(applicationContext, rootShell, toolsInstaller)
                awgQuickBackend.setMultipleTunnels(UserKnobs.multipleTunnels.first())
                backend = awgQuickBackend
                UserKnobs.multipleTunnels.onEach {
                    awgQuickBackend.setMultipleTunnels(it)
                }.launchIn(coroutineScope)
            } catch (ignored: Exception) {
            }
        }
        if (backend == null) {
            backend = GoBackend(applicationContext)
            // Always-On VPN: система стартует наш VpnService (в т.ч. на буте) → поднимаем туннель «Маяка».
            // Апстримный restoreState(true) для нас no-op (конфиги в /connect, не в FileConfigStore) — вместо
            // него F3-автоподключение из сохранённого на диске конфига (без сети). Гейт — MayakPrefs.autoConnect.
            GoBackend.setAlwaysOnCallback {
                get().applicationScope.launch {
                    org.amnezia.awg.mayak.MayakAutoConnect.bringUpIfEnabled(get())
                }
            }
        }
        return backend
    }

    override fun onCreate() {
        Log.i(TAG, USER_AGENT)
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        rootShell = RootShell(applicationContext)
        toolsInstaller = ToolsInstaller(applicationContext, rootShell)
        preferencesDataStore = PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile("settings") }
        // Тема — ОДИН источник правды: настройка Маяка (умолчание тёмная, решение владельца 09-08).
        // Раньше здесь была развилка по версии Android: на 7–9 тему брали из апстримного
        // UserKnobs.darkTheme, на 10+ отдавали системе. Обе ветки шли мимо выбора человека в наших
        // «Настройках» — на старых телефонах он бы вообще не применился, а тумблера, который писал
        // бы darkTheme, в нашем интерфейсе нет вовсе.
        MayakPrefs.applyTheme(this)
        tunnelManager = TunnelManager(FileConfigStore(applicationContext))
        tunnelManager.onCreate()

        // Initialize network state monitor for auto-reconnection
        networkState = NetworkState(applicationContext) { oldType, newType ->
            Log.i(TAG, "NetworkState callback: Network changed: $oldType -> $newType")
            onNetworkChange(oldType, newType)
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                backend = determineBackend()
                futureBackend.complete(backend!!)
                networkState.bindNetworkListener()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(VmPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }

        // Тихий еженедельный телеметри-бикон (не-ПДн: версия/модель/ОС/локаль/источник + счётчики).
        // Идемпотентно (ExistingPeriodicWorkPolicy.KEEP) → безопасно на каждом старте. Без UI/эффекта;
        // если не вошёл или сбой — тихо no-op. См. MayakTelemetryWorker / TELEMETRY-DISCLOSURE.md.
        runCatching { org.amnezia.awg.mayak.MayakTelemetryWorker.enqueue(this) }
    }

    override fun onTerminate() {
        networkState.unbindNetworkListener()
        coroutineScope.cancel()
        super.onTerminate()
    }

    /**
     * Called when network changes (e.g., WiFi to Mobile or vice versa).
     * Reconnects active tunnels to ensure VPN connection works on new network.
     */
    private fun onNetworkChange(oldType: NetworkType, newType: NetworkType) {
        Log.i(TAG, "onNetworkChange called: $oldType -> $newType")
        
        if (newType == NetworkType.NONE) {
            Log.i(TAG, "Network lost, waiting for new connection...")
            return
        }

        // ⛔ АВТО-ПЕРЕПОДЪЁМ ОТКЛЮЧЁН. Дважды пробовали, дважды сделали ХУЖЕ — оба раза видно в логах.
        //
        // Зачем пробовали: диаг-лог #66 («зашёл в лифт») показал, что после полной потери сети с
        // появлением НОВОЙ движок не оживает сам — девять рукопожатий за 45 с без ответа, сокет остался
        // на умершей сети. Хотелось починить это без участия человека.
        //
        // Попытка 1 (0.3.76): DOWN→UP сразу. Диаг #67: туннель встал в 51.786 и был убит в 51.793 —
        // vpnService.stopSelf() асинхронный, запоздалый onDestroy глушит УЖЕ НОВЫЙ туннель.
        //
        // Попытка 2 (0.3.77): DOWN → пауза 1,2 с → UP → проверка. Диаг #68: за эту паузу Android
        // УБИВАЕТ ПРОЦЕСС — VpnService остановлен, приложение ушло в фон, процесс больше ничем не
        // держится. В логе pid меняется 12557 → 15523 → 22439, и UP делать уже некому. Для человека
        // это выглядит как «приложение само отключается» — ровно так владелец и написал.
        //
        // ВЫВОД: пока единственный доступный способ пере-привязать сокет — turnOff+turnOn (нативного
        // bindUpdate в форке нет), любая авто-починка обязана пережить остановку VpnService. В фоне
        // это не гарантировано ничем. Следующая попытка — только с foreground-гарантией (например,
        // чинить лишь когда экран приложения открыт) либо после появления bindUpdate в движке.
        //
        // Код переподъёма оставлен (GoTunnel.rebindAfterNetworkChange) — он рабочий, когда процесс
        // выживает (в диаге #68 два раза подряд «переподъём удался»). Не вызывается намеренно.
        Log.i(TAG, "Network change ($oldType -> $newType): авто-переподъём отключён (см. комментарий)")
    }

    companion object {
        val USER_AGENT = String.format(Locale.ENGLISH, "AmneziaWG/%s (Android %d; %s; %s; %s %s; %s)", BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown ABI", Build.BOARD, Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT)
        private const val TAG = "AmneziaWG/Application"
        private lateinit var weakSelf: WeakReference<Application>

        fun get(): Application {
            return weakSelf.get()!!
        }

        suspend fun getBackend() = get().futureBackend.await()

        fun getRootShell() = get().rootShell

        fun getPreferencesDataStore() = get().preferencesDataStore

        fun getToolsInstaller() = get().toolsInstaller

        fun getTunnelManager() = get().tunnelManager

        fun getCoroutineScope() = get().coroutineScope

        fun getNetworkState() = get().networkState
    }

    init {
        weakSelf = WeakReference(this)
    }
}
